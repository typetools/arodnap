package demo;

import java.io.FileInputStream;
import java.io.IOException;

/** Written by build.sh, like a parser generator's output. */
public class Generated {

    public int read(String path) throws IOException {
        FileInputStream in = new FileInputStream(path);
        return in.read();
    }
}
