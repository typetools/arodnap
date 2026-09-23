package owning;

import java.io.Closeable;
import java.io.FileWriter;
import java.io.IOException;

/** Owning field that is public and non-final but only assigned in the constructor. */
public class PublicSink implements Closeable {
    public FileWriter out;

    public PublicSink(String path) throws IOException {
        out = new FileWriter(path);
    }

    public void write(String text) throws IOException {
        out.write(text);
    }

    @Override
    public void close() throws IOException {
        out.close();
    }
}
