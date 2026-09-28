package com.rlc.fixer;

public class LogEntry {
    public final String file;
    public final String field;

    public LogEntry(String file, String field) {
        this.file = file;
        this.field = field;
    }
}