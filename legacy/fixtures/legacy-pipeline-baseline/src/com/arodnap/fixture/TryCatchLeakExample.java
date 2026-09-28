package com.arodnap.fixture;

import java.io.FileInputStream;
import java.io.IOException;

public class TryCatchLeakExample {

    public int readFirstByteOrMinusOne(String path) {
        try {
            FileInputStream stream = new FileInputStream(path);
            return stream.read();
        } catch (IOException e) {
            return -1;
        }
    }
}
