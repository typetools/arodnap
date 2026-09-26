package demo;

import java.io.FileNotFoundException;

public class Client {
    public void print() throws FileNotFoundException {
        TempFileWriter writer = new TempFileWriter("out.txt");
        writer.printSomething();
    }
}
