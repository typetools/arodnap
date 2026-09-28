package com.arodnap.fixture;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

public class OwningFieldReassignment {

    private InputStream current;

    public OwningFieldReassignment(String firstPath) throws IOException {
        this.current = new FileInputStream(firstPath);
    }

    public void replace(String secondPath) throws IOException {
        this.current = new FileInputStream(secondPath);
    }

    public int readByte() throws IOException {
        return current.read();
    }
}
