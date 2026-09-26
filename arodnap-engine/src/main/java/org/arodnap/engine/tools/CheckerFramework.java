package org.arodnap.engine.tools;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.arodnap.engine.jdk.Jdk;
import org.arodnap.engine.jdk.JdkLocator;

/**
 * How Arodnap runs the Checker Framework: {@code java -jar checker.jar} on the JDK it resolved, not
 * the distribution's {@code javac} wrapper, which always uses the first {@code java} on PATH.
 */
public final class CheckerFramework {
    public static final String RESOURCE_LEAK_CHECKER = "org.checkerframework.checker.resourceleak.ResourceLeakChecker";
    private static final String CHECKER_CLASS_ENTRY = "org/checkerframework/checker/resourceleak/ResourceLeakChecker.class";

    private CheckerFramework() {}

    /** Thrown when the Checker Framework cannot run: a missing jar or a JDK that is too old. */
    public static final class CheckerFrameworkException extends Exception {
        private static final long serialVersionUID = 1L;

        public CheckerFrameworkException(String message) {
            super(message);
        }
    }

    /** The start of a javac command that runs with the Checker Framework on {@code jdk}. */
    public static List<String> javacCommand(Toolchain toolchain, Jdk jdk) {
        return List.of(jdk.java().toString(), "-jar", toolchain.checkerJar().toString());
    }

    /** The oldest JDK that can run this Checker Framework, read from its class file version. */
    public static int minimumJdk(Toolchain toolchain) throws CheckerFrameworkException {
        try (ZipFile jar = new ZipFile(toolchain.checkerJar().toFile())) {
            ZipEntry entry = jar.getEntry(CHECKER_CLASS_ENTRY);
            if (entry == null) {
                throw new CheckerFrameworkException("Cannot find the Resource Leak Checker in " + toolchain.checkerJar());
            }
            try (InputStream in = jar.getInputStream(entry)) {
                byte[] header = in.readNBytes(8);
                return (((header[6] & 0xff) << 8) | (header[7] & 0xff)) - 44;
            }
        } catch (IOException e) {
            throw new CheckerFrameworkException("Cannot read the Resource Leak Checker from " + toolchain.checkerJar() + ": " + e.getMessage());
        }
    }

    /**
     * The JDK that runs the analysis: JAVA_HOME, else {@code java} on PATH. It must be new enough for
     * both the Checker Framework and RLFixer.
     */
    public static Jdk analysisJdk(Toolchain toolchain, JdkLocator locator) throws CheckerFrameworkException {
        Jdk jdk;
        try {
            jdk = locator.locate();
        } catch (JdkLocator.JdkNotFoundException e) {
            throw new CheckerFrameworkException(e.getMessage());
        }
        int minimum = Math.max(minimumJdk(toolchain), Jdk.RLFIXER_MINIMUM);
        if (jdk.majorVersion() < minimum) {
            throw new CheckerFrameworkException("Arodnap with Checker Framework " + toolchain.checkerFrameworkVersion()
                    + " needs JDK " + minimum + " or newer; found JDK " + jdk.majorVersion() + " at " + jdk.home()
                    + " (from " + jdk.source() + "). Set JAVA_HOME to a newer JDK.");
        }
        return jdk;
    }
}
