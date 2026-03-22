package com.zyra.parser;

import java.util.List;

/*
    Intuition:
    ----------
    Represents a parsed command.

    Example:
        Input:  "SET key value"
        Output: name = "SET", args = ["key", "value"]
*/

public class Command {

    private final String name;
    private final List<String> args;

    public Command(String name, List<String> args) {
        this.name = name;
        this.args = args;
    }

    public String getName() {
        return name;
    }

    public List<String> getArgs() {
        return args;
    }
}