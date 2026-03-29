package com.zyra.parser;

import java.util.ArrayList;
import java.util.List;

public class CommandParser {

    public Command parse(String input) {

        if (input == null || input.isBlank()) {
            return null;
        }

        String[] tokens = input.trim().split("\\s+");

        String rawName = tokens[0].toUpperCase();

        // ---- Aliases ----
        String name = switch (rawName) {
            case "DELETE" -> "DEL";
            case "QUIT", "EXIT" -> "EXIT";
            default -> rawName;
        };

        List<String> args = new ArrayList<>();

        for (int i = 1; i < tokens.length; i++) {
            args.add(tokens[i]);
        }

        return new Command(input.trim(), name, args);
    }
}
