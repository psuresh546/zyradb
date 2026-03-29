package com.zyra.parser;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CommandParserTest {

    private final CommandParser parser = new CommandParser();

    @Test
    void parsesCommandNameArgsAndRawLine() {
        Command command = parser.parse("SET alpha beta EX 5");

        assertEquals("SET", command.getName());
        assertEquals(List.of("alpha", "beta", "EX", "5"), command.getArgs());
        assertEquals("SET alpha beta EX 5", command.getRaw());
    }

    @Test
    void normalizesAliases() {
        assertEquals("DEL", parser.parse("DELETE key").getName());
        assertEquals("EXIT", parser.parse("QUIT").getName());
    }

    @Test
    void returnsNullForBlankInput() {
        assertNull(parser.parse("   "));
    }
}
