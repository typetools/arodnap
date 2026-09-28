package demo.core;

import java.io.FileInputStream;
import java.io.IOException;

public class FirstByte {

    public int read(String path) throws IOException {
        FileInputStream in = new FileInputStream(path);
        return in.read();
    }
}
