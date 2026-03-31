package com.arodnap.fixture;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

public class DirectLeakExample {

    public String readFirstLine(String path) throws IOException {
        BufferedReader reader = new BufferedReader(new FileReader(path));
        return reader.readLine();
    }
}
