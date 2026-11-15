package org.arodnap.fields;

import com.google.errorprone.BugCheckerRefactoringTestHelper;
import com.google.errorprone.BugCheckerRefactoringTestHelper.TestMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Test;

public class ResourceFieldCanBeLocalTest {

    private final BugCheckerRefactoringTestHelper helper =
            BugCheckerRefactoringTestHelper.newInstance(ResourceFieldCanBeLocal.class, getClass());

    @Test
    public void fieldAssignedBeforeEveryReadBecomesLocal() {
        helper.addInputLines("Counter.java",
                        "import java.io.*;",
                        "class Counter {",
                        "  private Reader scratch;",
                        "  private int count;",
                        "",
                        "  int count(String path) throws IOException {",
                        "    scratch = new FileReader(path);",
                        "    count = scratch.read();",
                        "    return count;",
                        "  }",
                        "}")
                .addOutputLines("Counter.java",
                        "import java.io.*;",
                        "class Counter {",
                        "  private int count;",
                        "",
                        "  int count(String path) throws IOException {",
                        "    Reader scratch = new FileReader(path);",
                        "    count = scratch.read();",
                        "    return count;",
                        "  }",
                        "}")
                .doTest(TestMode.TEXT_MATCH);
    }

    @Test
    public void callBetweenAssignmentAndReadKeepsTheField() {
        // b() reassigns the field, so a() closes the stream b() opened. With two locals, a()
        // would close its own stream instead.
        helper.addInputLines("Test.java",
                        "import java.io.*;",
                        "class Test {",
                        "  private InputStream in;",
                        "  void a() throws IOException {",
                        "    in = new FileInputStream(\"a\");",
                        "    b();",
                        "    in.close();",
                        "  }",
                        "  void b() throws IOException {",
                        "    in = new FileInputStream(\"b\");",
                        "  }",
                        "}")
                .expectUnchanged()
                .doTest(TestMode.TEXT_MATCH);
    }

    @Test
    public void constructorCallBetweenAssignmentAndReadKeepsTheField() {
        helper.addInputLines("Test.java",
                        "import java.io.*;",
                        "class Test {",
                        "  private InputStream in;",
                        "  void a() throws IOException {",
                        "    in = new FileInputStream(\"a\");",
                        "    new Reopener(this);",
                        "    in.close();",
                        "  }",
                        "  void b() throws IOException {",
                        "    in = new FileInputStream(\"b\");",
                        "  }",
                        "  static class Reopener {",
                        "    Reopener(Test test) throws IOException {",
                        "      test.b();",
                        "    }",
                        "  }",
                        "}")
                .expectUnchanged()
                .doTest(TestMode.TEXT_MATCH);
    }

    @Test
    public void receiverAndArgumentsAreReadBeforeTheCallRuns() {
        helper.addInputLines("Connector.java",
                        "import java.io.*;",
                        "import java.net.*;",
                        "class Connector {",
                        "  private Socket socket;",
                        "  private Reader reader;",
                        "  void connect(SocketAddress address) throws IOException {",
                        "    socket = new Socket();",
                        "    socket.connect(address);",
                        "  }",
                        "  BufferedReader open(String path) throws IOException {",
                        "    reader = new FileReader(path);",
                        "    return new BufferedReader(reader);",
                        "  }",
                        "}")
                .addOutputLines("Connector.java",
                        "import java.io.*;",
                        "import java.net.*;",
                        "class Connector {",
                        "  void connect(SocketAddress address) throws IOException {",
                        "    Socket socket = new Socket();",
                        "    socket.connect(address);",
                        "  }",
                        "  BufferedReader open(String path) throws IOException {",
                        "    Reader reader = new FileReader(path);",
                        "    return new BufferedReader(reader);",
                        "  }",
                        "}")
                .doTest(TestMode.TEXT_MATCH);
    }

    @Test
    public void readInALoopAfterACallKeepsTheField() {
        // The second read() runs after the first one, which could have reassigned the field.
        helper.addInputLines("Skipper.java",
                        "import java.io.*;",
                        "class Skipper {",
                        "  private InputStream in;",
                        "  void skip(String path, int n) throws IOException {",
                        "    in = new FileInputStream(path);",
                        "    for (int i = 0; i < n; i++) {",
                        "      in.read();",
                        "    }",
                        "  }",
                        "}")
                .expectUnchanged()
                .doTest(TestMode.TEXT_MATCH);
    }

    @Test
    public void forEachOverAnIterableCallsItsIterator() {
        // Iterator.hasNext() and next() run before each iteration; indexing an array calls nothing.
        helper.addInputLines("Names.java",
                        "import java.io.*;",
                        "class Names {",
                        "  private InputStream in;",
                        "  InputStream last(Iterable<String> names) throws IOException {",
                        "    in = new FileInputStream(\"a\");",
                        "    InputStream last = null;",
                        "    for (String name : names) {",
                        "      last = in;",
                        "    }",
                        "    return last;",
                        "  }",
                        "}")
                .expectUnchanged()
                .addInputLines("NameArray.java",
                        "import java.io.*;",
                        "class NameArray {",
                        "  private InputStream in;",
                        "  InputStream last(String[] names) throws IOException {",
                        "    in = new FileInputStream(\"a\");",
                        "    InputStream last = null;",
                        "    for (String name : names) {",
                        "      last = in;",
                        "    }",
                        "    return last;",
                        "  }",
                        "}")
                .addOutputLines("NameArray.java",
                        "import java.io.*;",
                        "class NameArray {",
                        "  InputStream last(String[] names) throws IOException {",
                        "    InputStream in = new FileInputStream(\"a\");",
                        "    InputStream last = null;",
                        "    for (String name : names) {",
                        "      last = in;",
                        "    }",
                        "    return last;",
                        "  }",
                        "}")
                .doTest(TestMode.TEXT_MATCH);
    }

    @Test
    public void tryWithResourcesClosesItsResourcesBeforeTheNextStatement() {
        // close() runs when the try block ends, before the read.
        helper.addInputLines("Copier.java",
                        "import java.io.*;",
                        "class Copier {",
                        "  private InputStream in;",
                        "  int read(InputStream other) throws IOException {",
                        "    try (InputStream copy = other) {",
                        "      in = new FileInputStream(\"a\");",
                        "    }",
                        "    return in.read();",
                        "  }",
                        "}")
                .expectUnchanged()
                .doTest(TestMode.TEXT_MATCH);
    }

    @Test
    public void readInsideTryWithResourcesInAnotherMethodKeepsTheField() {
        // Seen in Apktool: the read in run()'s resource specification was not noticed, so the
        // field became a constructor-local and run() no longer compiled.
        helper.addInputLines("Forwarder.java",
                        "import java.io.*;",
                        "class Forwarder extends Thread {",
                        "  private final InputStream in;",
                        "  Forwarder(InputStream in) {",
                        "    this.in = in;",
                        "  }",
                        "  public void run() {",
                        "    try (BufferedReader reader = new BufferedReader(new InputStreamReader(in))) {",
                        "      reader.readLine();",
                        "    } catch (IOException e) {",
                        "    }",
                        "  }",
                        "}")
                .expectUnchanged()
                .doTest(TestMode.TEXT_MATCH);
    }

    @Test
    public void fieldsWithInitializersAnnotationsOrNonResourceTypesAreLeftAlone() {
        helper.addInputLines("Keep.java",
                        "import java.io.*;",
                        "class Keep {",
                        "  private InputStream opened = open();",
                        "  @SuppressWarnings(\"unused\")",
                        "  private InputStream annotated;",
                        "  private String name;",
                        "  static InputStream open() { return System.in; }",
                        "  int readOpened() throws IOException {",
                        "    opened = open();",
                        "    return opened.read();",
                        "  }",
                        "  int readAnnotated() throws IOException {",
                        "    annotated = open();",
                        "    return annotated.read();",
                        "  }",
                        "  int length(String n) {",
                        "    name = n;",
                        "    return name.length();",
                        "  }",
                        "}")
                .expectUnchanged()
                .doTest(TestMode.TEXT_MATCH);
    }

    @Test
    public void fieldsNamedInStringLiteralsAreLeftAlone() throws Exception {
        Path names = Files.createTempFile("reflected", ".txt");
        Files.write(names, List.of("scratch"));
        helper.setArgs("-XepOpt:ArodnapFields:ReflectedNamesFile=" + names)
                .addInputLines("Counter.java",
                        "import java.io.*;",
                        "class Counter {",
                        "  private Reader scratch;",
                        "  int count(String path) throws IOException {",
                        "    scratch = new FileReader(path);",
                        "    return scratch.read();",
                        "  }",
                        "}")
                .expectUnchanged()
                .doTest(TestMode.TEXT_MATCH);
    }

    @Test
    public void assignmentInsideTryIsDeclaredBeforeTheTry() {
        helper.addInputLines("Loader.java",
                        "import java.io.*;",
                        "class Loader {",
                        "  private InputStream in;",
                        "  int load(String path) throws IOException {",
                        "    try {",
                        "      in = new FileInputStream(path);",
                        "    } catch (FileNotFoundException e) {",
                        "      return -1;",
                        "    }",
                        "    return in.read();",
                        "  }",
                        "}")
                .addOutputLines("Loader.java",
                        "import java.io.*;",
                        "class Loader {",
                        "  int load(String path) throws IOException {",
                        "    InputStream in = null;",
                        "    try {",
                        "      in = new FileInputStream(path);",
                        "    } catch (FileNotFoundException e) {",
                        "      return -1;",
                        "    }",
                        "    return in.read();",
                        "  }",
                        "}")
                .doTest(TestMode.TEXT_MATCH);
    }
}
