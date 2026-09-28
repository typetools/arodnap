package demo.app;

import demo.core.FirstByte;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.apache.commons.io.IOUtils;

public class Report {

    public String describe(String path) throws IOException {
        FileInputStream in = new FileInputStream(path);
        return new FirstByte().read(path) + ": " + IOUtils.toString(in, StandardCharsets.UTF_8);
    }
}
