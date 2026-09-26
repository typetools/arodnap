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
                        "    scratch.close();",
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
                        "    scratch.close();",
                        "    return count;",
                        "  }",
                        "}")
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
                        "  int read(String n) throws IOException {",
                        "    opened = open();",
                        "    annotated = open();",
                        "    name = n;",
                        "    return opened.read() + annotated.read() + name.length();",
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
