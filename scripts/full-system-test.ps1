$Server = "localhost"
$Port = 6379
$Results = New-Object System.Collections.Generic.List[object]

function Send-Command {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Command
    )

    $client = New-Object System.Net.Sockets.TcpClient($Server, $Port)
    try {
        $stream = $client.GetStream()
        $writer = New-Object System.IO.StreamWriter($stream)
        $reader = New-Object System.IO.StreamReader($stream)

        $writer.AutoFlush = $true
        $writer.WriteLine($Command)

        return $reader.ReadLine()
    } finally {
        if ($reader) { $reader.Close() }
        if ($writer) { $writer.Close() }
        $client.Close()
    }
}

function Section {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Name
    )

    Write-Host ""
    Write-Host "==================== $Name ===================="
}

function Wait-For-Restart {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Message
    )

    Write-Host ">>> $Message"
    Read-Host "Press ENTER after restart"
}

function Add-Result {
    param(
        [string]$Section,
        [string]$Test,
        [string]$Status,
        [string]$Detail
    )

    $Results.Add([pscustomobject]@{
        Section = $Section
        Test    = $Test
        Status  = $Status
        Detail  = $Detail
    })
}

function Expect-Equal {
    param(
        [string]$Section,
        [string]$Test,
        [string]$Command,
        [string]$Expected
    )

    try {
        $actual = Send-Command $Command
        if ($actual -ne $Expected) {
            throw "Expected '$Expected' but got '$actual'"
        }
        Add-Result $Section $Test "PASS" $actual
    } catch {
        Add-Result $Section $Test "FAIL" $_.Exception.Message
    }
}

function Expect-TtlRange {
    param(
        [string]$Section,
        [string]$Test,
        [string]$Command,
        [int]$Min,
        [int]$Max
    )

    try {
        $actual = Send-Command $Command
        if ($actual -notmatch '^INT -?\d+$') {
            throw "Expected TTL response but got '$actual'"
        }

        $ttl = [int]($actual -replace '^INT ', '')
        if ($ttl -lt $Min -or $ttl -gt $Max) {
            throw "Expected TTL between $Min and $Max but got $ttl"
        }

        Add-Result $Section $Test "PASS" $actual
    } catch {
        Add-Result $Section $Test "FAIL" $_.Exception.Message
    }
}

function Expect-Match {
    param(
        [string]$Section,
        [string]$Test,
        [string]$Command,
        [string]$Pattern
    )

    try {
        $actual = Send-Command $Command
        if ($actual -notmatch $Pattern) {
            throw "Response '$actual' did not match pattern '$Pattern'"
        }
        Add-Result $Section $Test "PASS" $actual
    } catch {
        Add-Result $Section $Test "FAIL" $_.Exception.Message
    }
}

function Invoke-ConcurrentSetJobs {
    param(
        [int]$Count = 20
    )

    $jobs = 1..$Count | ForEach-Object {
        Start-Job -ArgumentList $_, $Server, $Port -ScriptBlock {
            param($Index, $JobServer, $JobPort)

            $client = New-Object System.Net.Sockets.TcpClient($JobServer, $JobPort)
            try {
                $stream = $client.GetStream()
                $writer = New-Object System.IO.StreamWriter($stream)
                $reader = New-Object System.IO.StreamReader($stream)

                $writer.AutoFlush = $true
                $writer.WriteLine("SET race value$Index")

                $reader.ReadLine()
            } finally {
                if ($reader) { $reader.Close() }
                if ($writer) { $writer.Close() }
                $client.Close()
            }
        }
    }

    try {
        $jobs | Wait-Job | Out-Null
        return $jobs | Receive-Job
    } finally {
        $jobs | Remove-Job -Force
    }
}

function Expect-ConcurrentResults {
    param(
        [string]$Section,
        [string[]]$Responses
    )

    try {
        $failures = $Responses | Where-Object { $_ -ne "OK" }
        if ($failures.Count -gt 0) {
            throw "Unexpected responses: $($Responses -join ', ')"
        }

        Add-Result $Section "Concurrent SET jobs" "PASS" "$($Responses.Count) OK responses"
    } catch {
        Add-Result $Section "Concurrent SET jobs" "FAIL" $_.Exception.Message
    }
}

function Print-Summary {
    Write-Host ""
    Write-Host "==================== Summary ===================="
    $Results | Format-Table -AutoSize

    $failed = @($Results | Where-Object Status -eq "FAIL")
    Write-Host ""
    Write-Host "FAILED_COUNT=$($failed.Count)"

    if ($failed.Count -gt 0) {
        exit 1
    }
}

# -------------------------------------------------------
$section = "1) Basic SET / GET / DEL"
Section $section
Expect-Equal $section "SET a 1" "SET a 1" "OK"
Expect-Equal $section "GET a" "GET a" "VAL 1"
Expect-Equal $section "DEL a" "DEL a" "INT 1"
Expect-Equal $section "GET a after DEL" "GET a" "NIL"

# -------------------------------------------------------
$section = "2) Expiry aliases"
Section $section
Expect-Equal $section "SET e1 EX 5" "SET e1 v EX 5" "OK"
Expect-TtlRange $section "TTL e1" "TTL e1" 4 5
Expect-Equal $section "SET e2 EXP 5" "SET e2 v EXP 5" "OK"
Expect-TtlRange $section "TTL e2" "TTL e2" 4 5
Expect-Equal $section "SET e3 EXPIRE 5" "SET e3 v EXPIRE 5" "OK"
Expect-TtlRange $section "TTL e3" "TTL e3" 4 5

# -------------------------------------------------------
$section = "3) TTL ceil behavior"
Section $section
Start-Sleep -Seconds 2
Expect-TtlRange $section "TTL e1 after 2s" "TTL e1" 2 3

# -------------------------------------------------------
$section = "4) Passive expiry"
Section $section
Start-Sleep -Seconds 4
Expect-Equal $section "GET e1 expired" "GET e1" "NIL"

# -------------------------------------------------------
$section = "5) TTL special values"
Section $section
Expect-Equal $section "SET t1" "SET t1 v" "OK"
Expect-Equal $section "TTL t1 persistent" "TTL t1" "INT -1"
Expect-Equal $section "TTL no_key" "TTL no_key" "INT -2"

# -------------------------------------------------------
$section = "6) SET resets TTL"
Section $section
Expect-Equal $section "SET r1 EX 5" "SET r1 v EX 5" "OK"
Start-Sleep -Seconds 2
Expect-Equal $section "SET r1 newv" "SET r1 newv" "OK"
Expect-Equal $section "TTL r1 reset" "TTL r1" "INT -1"

# -------------------------------------------------------
$section = "7) Concurrent writes (race test)"
Section $section
$jobResponses = Invoke-ConcurrentSetJobs -Count 20
Expect-ConcurrentResults $section $jobResponses
Expect-Match $section "GET race" "GET race" '^VAL value\d+$'

# -------------------------------------------------------
$section = "8) Snapshot during writes"
Section $section
1..50 | ForEach-Object {
    [void](Send-Command "SET snap$_ data$_")
}
Wait-For-Restart "Restart server now to trigger snapshot load"
Expect-Equal $section "GET snap25 after restart" "GET snap25" "VAL data25"
Expect-Equal $section "GET snap50 after restart" "GET snap50" "VAL data50"

# -------------------------------------------------------
$section = "9) WAL durability after graceful shutdown"
Section $section
Expect-Equal $section "SET walkey" "SET walkey 999" "OK"
Expect-Equal $section "SET walttl EX 20" "SET walttl 888 EX 20" "OK"
Wait-For-Restart "Gracefully stop the server and restart it"
Expect-Equal $section "GET walkey after restart" "GET walkey" "VAL 999"
Expect-TtlRange $section "TTL walttl after restart" "TTL walttl" 15 20

# -------------------------------------------------------
$section = "10) Scheduler cleanup"
Section $section
Expect-Equal $section "SET sched EX 3" "SET sched temp EX 3" "OK"
Start-Sleep -Seconds 6
Expect-Equal $section "GET sched after cleanup" "GET sched" "NIL"

# -------------------------------------------------------
$section = "11) WAL stress replay"
Section $section
1..50 | ForEach-Object {
    [void](Send-Command "SET heavy$_ val$_")
}
Wait-For-Restart "Restart server"
Expect-Equal $section "GET heavy25 after restart" "GET heavy25" "VAL val25"
Expect-Equal $section "GET heavy50 after restart" "GET heavy50" "VAL val50"

# -------------------------------------------------------
$section = "12) Snapshot + WAL truncate check"
Section $section
Expect-Equal $section "SET final1" "SET final1 value1" "OK"
Expect-Equal $section "SET final2 EX 30" "SET final2 value2 EX 30" "OK"
Wait-For-Restart "Restart server to snapshot + truncate WAL"
Expect-Equal $section "GET final1 after restart" "GET final1" "VAL value1"
Expect-TtlRange $section "TTL final2 after restart" "TTL final2" 25 30

Print-Summary
