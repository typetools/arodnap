package org.arodnap.engine.tools;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Properties;

/**
 * The tools this Arodnap release runs, as Maven coordinates. The CLI distribution bundles these
 * artifacts; the Maven and Gradle plugins download them. Their versions are set in one place, the
 * build's parent POM.
 */
public final class ToolCoordinates {
    /** JDKs the Checker Framework release Arodnap uses is tested on (its wpi.sh lists them). */
    public static final List<Integer> CHECKER_FRAMEWORK_TESTED_JDKS = List.of(8, 11, 17, 21, 24, 25, 26);

    private static final Properties PROPERTIES = load();

    private ToolCoordinates() {}

    /** The coordinates {@code group:artifact[:classifier]:version} of a tool, by name (see tools.properties). */
    public static String of(String tool) {
        String value = PROPERTIES.getProperty(tool);
        if (value == null) {
            throw new IllegalArgumentException("No such tool: " + tool);
        }
        return value;
    }

    public static String checkerFrameworkVersion() {
        return of("checkerframework.version");
    }

    public static String arodnapVersion() {
        return of("arodnap.version");
    }

    private static Properties load() {
        try (InputStream in = ToolCoordinates.class.getResourceAsStream("/org/arodnap/engine/tools.properties")) {
            Properties properties = new Properties();
            if (in != null) {
                properties.load(in);
            }
            return properties;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
