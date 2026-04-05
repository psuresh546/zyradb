package com.zyra.parser;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class CommandParser {

    public Command parse(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }

        String[] tokens = input.trim().split("\\s+");
        String name = tokens[0].toUpperCase();

        List<String> args = new ArrayList<>();
        for (int i = 1; i < tokens.length; i++) {
            args.add(tokens[i]);
        }

        return new Command(input.trim(), name, args);
    }
}
