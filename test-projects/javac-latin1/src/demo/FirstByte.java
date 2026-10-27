package demo;

import java.io.FileInputStream;
import java.io.IOException;

/** Reads the first byte of a file. Autor: José Müller. */
public class FirstByte {
    static int firstByte(String path) throws IOException {
        FileInputStream in = new FileInputStream(path);
        return in.read();
    }
}
