package wrapper;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/** A resource wrapper that owns a stream but has no close() method. */
public class Wrapper {
    private final InputStream in;

    public Wrapper(String path) throws IOException {
        this.in = new FileInputStream(path);
    }

    public int read() throws IOException {
        return in.read();
    }
}
