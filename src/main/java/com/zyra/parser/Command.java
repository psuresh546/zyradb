package com.zyra.parser;

import java.util.List;

public class Command {

    private final String raw;
    private final String name;
    private final List<String> args;

    public Command(String name, List<String> args) {
        this(buildRaw(name, args), name, args);
    }

    public Command(String raw, String name, List<String> args) {
        this.raw = raw;
        this.name = name;
        this.args = args;
    }

    public String getRaw() {
        return raw;
    }

    public String getName() {
        return name;
    }

    public List<String> getArgs() {
        return args;
    }

    private static String buildRaw(String name, List<String> args) {
        if (args == null || args.isEmpty()) {
            return name;
        }

        return name + " " + String.join(" ", args);
    }
}
