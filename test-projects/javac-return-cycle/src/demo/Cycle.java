package demo;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

public class Cycle {
    // The stream escapes by return on one path and leaks on the other, where the method
    // returns the result of calling itself: RLFixer's return fix visits this caller again.
    static InputStream open(File f, int depth) throws IOException {
        InputStream in = new FileInputStream(f);
        if (depth > 3) {
            return in;
        }
        return open(f, depth + 1);
    }

    public static int first(File f) throws IOException {
        try (InputStream in = open(f, 0)) {
            return in.read();
        }
    }
}
