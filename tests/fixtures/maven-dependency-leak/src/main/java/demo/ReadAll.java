package demo;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.apache.commons.io.IOUtils;

public class ReadAll {

    public String read(String path) throws IOException {
        FileInputStream in = new FileInputStream(path);
        return IOUtils.toString(in, StandardCharsets.UTF_8);
    }
}
