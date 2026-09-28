package org.arodnap.engine.diagnostics;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One warning block of Checker Framework output: it starts with
 * {@code /absolute/File.java:<line>: warning:} and runs until the next such line.
 *
 * @param file the absolute path the checker reported
 * @param line the line number
 * @param message the whole block, trimmed
 */
public record CheckerWarning(String file, int line, String message) {
    private static final Pattern HEADER = Pattern.compile("^(/.+?):(\\d+):\\s+warning:", Pattern.MULTILINE);
    private static final Pattern COUNT = Pattern.compile("^\\d+ (?:warnings?|errors?)$", Pattern.MULTILINE);
    // Checker Framework 3.x prints "(key)"; 4.x prints "[key]" or "[checker:key]".
    private static final Pattern REQUIRED_METHOD_NOT_CALLED = Pattern.compile("[(\\[](?:[\\w.]+:)?required\\.method\\.not\\.called[)\\]]");
    private static final String OWNING_FIELD_OVERWRITE = "Non-final owning field might be overwritten";

    public static List<CheckerWarning> parseAll(String diagnostics) {
        return blocks(diagnostics).stream().map(Block::warning).toList();
    }

    /**
     * A warning and where its block is in the output: from its header to the next one, or, for the
     * last, to javac's closing count ({@code 3 warnings}) or the end. (The last warning's message
     * runs to the end, as it always has; it is part of RLPatcher's prompt.)
     */
    public record Block(CheckerWarning warning, int start, int end) {}

    public static List<Block> blocks(String diagnostics) {
        record Header(int start, String file, int line) {}
        List<Header> headers = new ArrayList<>();
        Matcher matcher = HEADER.matcher(diagnostics);
        while (matcher.find()) {
            headers.add(new Header(matcher.start(), matcher.group(1), Integer.parseInt(matcher.group(2))));
        }
        List<Block> blocks = new ArrayList<>();
        for (int i = 0; i < headers.size(); i++) {
            Header header = headers.get(i);
            int next = i + 1 < headers.size() ? headers.get(i + 1).start() : diagnostics.length();
            int end = next;
            Matcher count = COUNT.matcher(diagnostics);
            if (i + 1 == headers.size() && count.find(header.start())) {
                end = count.start();
            }
            String text = diagnostics.substring(header.start(), next).strip();
            blocks.add(new Block(new CheckerWarning(header.file(), header.line(), text), header.start(), end));
        }
        return blocks;
    }

    /** The first line of the block, where the key and the structured fields are. */
    public String firstLine() {
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }

    /** True for the {@code required.method.not.called} warnings that are resource leaks. */
    public boolean isLeak() {
        return REQUIRED_METHOD_NOT_CALLED.matcher(firstLine()).find();
    }

    /** True when the leak is a non-final owning field that might be overwritten. */
    public boolean isOwningFieldOverwrite() {
        return message.contains(OWNING_FIELD_OVERWRITE);
    }
}
