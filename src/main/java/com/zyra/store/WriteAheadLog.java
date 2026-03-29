package com.zyra.store;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public class WriteAheadLog {

    private static final String WAL_FILE = "zyra.wal";
    private static final Path WAL_PATH = Path.of(WAL_FILE);

    private static FileOutputStream fos;
    private static BufferedWriter writer;
    private static FileChannel channel;

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(WriteAheadLog::close, "zyra-wal-shutdown"));
    }

    private static synchronized void init() throws IOException {
        if (writer != null) {
            return;
        }

        fos = new FileOutputStream(WAL_PATH.toFile(), true);
        channel = fos.getChannel();
        writer = new BufferedWriter(
                new OutputStreamWriter(fos, StandardCharsets.UTF_8)
        );
    }

    public static synchronized void log(String commandLine) {
        try {
            init();
            writer.write(commandLine);
            writer.newLine();
            writer.flush();
            channel.force(true);
        } catch (IOException e) {
            throw new RuntimeException("WAL write failed", e);
        }
    }

    public static synchronized void replay(InMemoryStore store) {
        if (!Files.exists(WAL_PATH)) {
            return;
        }

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                        new FileInputStream(WAL_PATH.toFile()),
                        StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                CommandExecutor.replay(line, store);
            }

        } catch (IOException e) {
            throw new RuntimeException("WAL replay failed", e);
        }
    }

    public static synchronized void truncate() {
        close();

        try {
            Files.deleteIfExists(WAL_PATH);
            Files.writeString(
                    WAL_PATH,
                    "",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
            );
        } catch (IOException e) {
            throw new RuntimeException("WAL truncate failed", e);
        }
    }

    private static synchronized void close() {
        try {
            if (writer != null) {
                writer.close();
            }
            if (channel != null) {
                channel.close();
            }
            if (fos != null) {
                fos.close();
            }
        } catch (IOException ignored) {
        }
        writer = null;
        channel = null;
        fos = null;
    }
}
