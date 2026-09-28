package com.arodnap.fixture;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

public class WrapperMissingClose {

    private final InputStream stream;

    public WrapperMissingClose(String path) throws IOException {
        this.stream = new FileInputStream(path);
    }

    public int readByte() throws IOException {
        return stream.read();
    }
}
