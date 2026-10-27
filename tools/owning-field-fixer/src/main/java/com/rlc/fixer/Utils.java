package com.rlc.fixer;

public class Utils {
    public static String sanitize(String path) {
        return path.replaceAll("[^a-zA-Z0-9-_\\.]", "_");
    }
}