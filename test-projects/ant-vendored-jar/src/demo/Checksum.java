package demo;

import java.io.FileInputStream;
import java.io.IOException;
import vendor.Bytes;

public class Checksum {

    public long sum(String path) throws IOException {
        FileInputStream in = new FileInputStream(path);
        return Bytes.sum(in);
    }
}
