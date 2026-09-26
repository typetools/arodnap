package org.arodnap.engine.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * JSON for Arodnap's output files, formatted the way they have always been written (Python's
 * {@code json.dumps(value, indent=2)}): two-space indentation, {@code ": "} after keys, non-ASCII
 * escaped, and keys sorted where the file sorts them. Values are maps, lists, strings, numbers,
 * booleans, null, paths and optionals.
 */
public final class Json {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {}

    public static String write(Object value, boolean sortKeys) {
        StringBuilder out = new StringBuilder();
        write(out, value, sortKeys, 0);
        return out.toString();
    }

    /** Writes the value with a final newline, creating parent directories. */
    public static Path writeFile(Path file, Object value, boolean sortKeys) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.writeString(file, write(value, sortKeys) + "\n", StandardCharsets.UTF_8);
        return file;
    }

    public static JsonNode read(Path file) throws IOException {
        return MAPPER.readTree(file.toFile());
    }

    public static JsonNode parse(String text) throws IOException {
        return MAPPER.readTree(text);
    }

    private static void write(StringBuilder out, Object value, boolean sortKeys, int depth) {
        if (value instanceof Optional<?> optional) {
            write(out, optional.orElse(null), sortKeys, depth);
        } else if (value == null) {
            out.append("null");
        } else if (value instanceof String text) {
            string(out, text);
        } else if (value instanceof Path path) {
            string(out, path.toString());
        } else if (value instanceof Boolean flag) {
            out.append(flag ? "true" : "false");
        } else if (value instanceof Double || value instanceof Float) {
            out.append(decimal(((Number) value).doubleValue()));
        } else if (value instanceof Number number) {
            out.append(number.toString());
        } else if (value instanceof Map<?, ?> map) {
            Map<?, ?> entries = sortKeys ? new TreeMap<>(map) : map;
            if (entries.isEmpty()) {
                out.append("{}");
                return;
            }
            out.append("{\n");
            int index = 0;
            for (Map.Entry<?, ?> entry : entries.entrySet()) {
                indent(out, depth + 1);
                string(out, String.valueOf(entry.getKey()));
                out.append(": ");
                write(out, entry.getValue(), sortKeys, depth + 1);
                out.append(++index < entries.size() ? ",\n" : "\n");
            }
            indent(out, depth);
            out.append('}');
        } else if (value instanceof Collection<?> items) {
            if (items.isEmpty()) {
                out.append("[]");
                return;
            }
            out.append("[\n");
            List<?> list = new ArrayList<>(items);
            for (int i = 0; i < list.size(); i++) {
                indent(out, depth + 1);
                write(out, list.get(i), sortKeys, depth + 1);
                out.append(i + 1 < list.size() ? ",\n" : "\n");
            }
            indent(out, depth);
            out.append(']');
        } else {
            throw new IllegalArgumentException("Cannot write " + value.getClass().getName() + " as JSON");
        }
    }

    private static void indent(StringBuilder out, int depth) {
        out.append("  ".repeat(depth));
    }

    /** Like Python's float repr: "1.5", "2.0", "0.000123". */
    static String decimal(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException("Not a JSON number: " + value);
        }
        String plain = new BigDecimal(Double.toString(value)).toPlainString();
        return plain.contains(".") ? plain : plain + ".0";
    }

    private static void string(StringBuilder out, String text) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20 || c > 0x7e) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
