package com.zyra.parser;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class CommandParser {

    public Command parse(String input) {
        if (input == null) {
            return null;
        }

        int length = input.length();
        int index = 0;
        while (index < length && Character.isWhitespace(input.charAt(index))) {
            index++;
        }

        if (index == length) {
            return null;
        }

        int end = length - 1;
        while (end >= index && Character.isWhitespace(input.charAt(end))) {
            end--;
        }

        String raw = input.substring(index, end + 1);
        int rawLength = raw.length();
        int tokenStart = 0;
        while (tokenStart < rawLength && !Character.isWhitespace(raw.charAt(tokenStart))) {
            tokenStart++;
        }

        String name = raw.substring(0, tokenStart).toUpperCase(Locale.ROOT);

        List<String> args = new ArrayList<>();
        while (tokenStart < rawLength) {
            while (tokenStart < rawLength && Character.isWhitespace(raw.charAt(tokenStart))) {
                tokenStart++;
            }

            if (tokenStart >= rawLength) {
                break;
            }

            int tokenEnd = tokenStart + 1;
            while (tokenEnd < rawLength && !Character.isWhitespace(raw.charAt(tokenEnd))) {
                tokenEnd++;
            }

            args.add(raw.substring(tokenStart, tokenEnd));
            tokenStart = tokenEnd;
        }

        return new Command(raw, name, args);
    }
}
