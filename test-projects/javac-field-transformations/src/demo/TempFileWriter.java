package demo;

import java.io.FileNotFoundException;
import java.io.PrintStream;

/** A wrapper that owns a stream but has no close() method (the paper's Fig. 2). */
public class TempFileWriter {
    private PrintStream stream;
    private String path;

    public TempFileWriter(String path) throws FileNotFoundException {
        this.path = path;
        stream = new PrintStream(path);
    }

    public void printSomething() {
        stream.println("hello " + path);
    }
}
