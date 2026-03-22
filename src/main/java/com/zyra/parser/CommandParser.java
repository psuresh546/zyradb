package com.zyra.parser;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/*
    Intuition:
    ----------
    Converts raw string input into a Command object.

    Handles:
        - Trimming
        - Splitting
        - Uppercasing command name
*/

public class CommandParser {

    public Command parse(String input) {

        if (input == null || input.trim().isEmpty()) {
            return null;
        }

        String[] tokens = input.trim().split("\\s+");

        String commandName = tokens[0].toUpperCase();

        List<String> args = tokens.length > 1
                ? Arrays.asList(tokens).subList(1, tokens.length)
                : Collections.emptyList();

        return new Command(commandName, args);
    }
}