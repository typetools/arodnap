package org.arodnap.engine;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Arodnap's version, as the reports and logs name it. */
public final class Version {
    public static final String VERSION = load();

    private Version() {}

    private static String load() {
        try (InputStream in = Version.class.getResourceAsStream("version.properties")) {
            Properties properties = new Properties();
            if (in != null) {
                properties.load(in);
            }
            String version = properties.getProperty("version", "unknown");
            return version.startsWith("${") ? "unknown" : version;
        } catch (IOException e) {
            return "unknown";
        }
    }
}
