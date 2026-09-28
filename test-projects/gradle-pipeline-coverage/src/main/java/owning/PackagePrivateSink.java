package owning;

import java.io.Closeable;
import java.io.FileWriter;
import java.io.IOException;

/** Owning field that is neither final nor private but only assigned in the constructor. */
public class PackagePrivateSink implements Closeable {
    FileWriter out;

    public PackagePrivateSink(String path) throws IOException {
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
