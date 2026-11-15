package demo;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

/** The reader is only ever used inside count(), so it can be a local variable. */
public class LineCounter {
    private BufferedReader reader;

    public long count(String path) throws IOException {
        reader = new BufferedReader(new FileReader(path));
        return reader.lines().count();
    }
}
