package org.arodnap.fields;

import com.google.errorprone.BugCheckerRefactoringTestHelper;
import com.google.errorprone.BugCheckerRefactoringTestHelper.TestMode;
import org.junit.Test;

public class ResourceFieldCanBeFinalTest {

    private final BugCheckerRefactoringTestHelper helper =
            BugCheckerRefactoringTestHelper.newInstance(ResourceFieldCanBeFinal.class, getClass());

    @Test
    public void resourceFieldAssignedInTheConstructorBecomesFinal() {
        helper.addInputLines("Reader.java",
                        "import java.io.*;",
                        "class Reader {",
                        "  private static InputStream shared = System.in;",
                        "  private InputStream in;",
                        "  Reader(String path) throws IOException {",
                        "    in = new FileInputStream(path);",
                        "  }",
                        "  int read() throws IOException { return in.read() + shared.read(); }",
                        "}")
                .addOutputLines("Reader.java",
                        "import java.io.*;",
                        "class Reader {",
                        "  private static final InputStream shared = System.in;",
                        "  private final InputStream in;",
                        "  Reader(String path) throws IOException {",
                        "    in = new FileInputStream(path);",
                        "  }",
                        "  int read() throws IOException { return in.read() + shared.read(); }",
                        "}")
                .doTest(TestMode.TEXT_MATCH);
    }

    @Test
    public void fieldsThatCannotHoldAResourceAreLeftAlone() {
        helper.addInputLines("Plain.java",
                        "import java.io.*;",
                        "import java.util.*;",
                        "class Plain {",
                        "  private int count;",
                        "  private String name;",
                        "  private List<InputStream> streams;",
                        "  private InputStream[] array;",
                        "  private Runnable task;",
                        "  Plain(String name, Runnable task) {",
                        "    this.count = 1;",
                        "    this.name = name;",
                        "    this.streams = new ArrayList<>();",
                        "    this.array = new InputStream[0];",
                        "    this.task = task;",
                        "  }",
                        "}")
                .expectUnchanged()
                .doTest(TestMode.TEXT_MATCH);
    }

    @Test
    public void nonPrivateResourceFieldsAreLeftAlone() {
        helper.addInputLines("Shared.java",
                        "import java.io.*;",
                        "class Shared {",
                        "  InputStream in;",
                        "  protected OutputStream out;",
                        "  Shared(InputStream in, OutputStream out) {",
                        "    this.in = in;",
                        "    this.out = out;",
                        "  }",
                        "}")
                .expectUnchanged()
                .doTest(TestMode.TEXT_MATCH);
    }

    @Test
    public void wrappersAreRecognizedByWhatTheyDisposeOf() {
        // Conn owns a socket and releases it in shutdown() (no close(), not AutoCloseable).
        // Pool wraps a Conn and releases it in stop(): a wrapper of a wrapper. Holder releases its
        // stream through a helper method. Viewer only references a Conn, and Sink is a plain
        // interface: neither owns anything, so fields of those types are left alone.
        helper.addInputLines("Conn.java",
                        "import java.io.*;",
                        "import java.net.Socket;",
                        "class Conn {",
                        "  private final Socket socket;",
                        "  Conn(Socket socket) { this.socket = socket; }",
                        "  void shutdown() throws IOException { socket.close(); }",
                        "}")
                .expectUnchanged()
                .addInputLines("Pool.java",
                        "import java.io.IOException;",
                        "class Pool {",
                        "  private final Conn conn;",
                        "  Pool(Conn conn) { this.conn = conn; }",
                        "  void stop() throws IOException { conn.shutdown(); }",
                        "}")
                .expectUnchanged()
                .addInputLines("Util.java",
                        "import java.io.*;",
                        "class Util {",
                        "  static void release(InputStream in) throws IOException { in.close(); }",
                        "}")
                .expectUnchanged()
                .addInputLines("Holder.java",
                        "import java.io.*;",
                        "class Holder {",
                        "  private final InputStream in;",
                        "  Holder(InputStream in) { this.in = in; }",
                        "  void dispose() throws IOException { Util.release(in); }",
                        "}")
                .expectUnchanged()
                .addInputLines("Viewer.java",
                        "class Viewer {",
                        "  private final Conn conn;",
                        "  Viewer(Conn conn) { this.conn = conn; }",
                        "}")
                .expectUnchanged()
                .addInputLines("Sink.java", "interface Sink {}")
                .expectUnchanged()
                .addInputLines("Client.java",
                        "class Client {",
                        "  private Conn conn;",
                        "  private Pool pool;",
                        "  private Holder holder;",
                        "  private Viewer viewer;",
                        "  private Sink sink;",
                        "  Client(Conn conn, Pool pool, Holder holder, Viewer viewer, Sink sink) {",
                        "    this.conn = conn;",
                        "    this.pool = pool;",
                        "    this.holder = holder;",
                        "    this.viewer = viewer;",
                        "    this.sink = sink;",
                        "  }",
                        "}")
                .addOutputLines("Client.java",
                        "class Client {",
                        "  private final Conn conn;",
                        "  private final Pool pool;",
                        "  private final Holder holder;",
                        "  private Viewer viewer;",
                        "  private Sink sink;",
                        "  Client(Conn conn, Pool pool, Holder holder, Viewer viewer, Sink sink) {",
                        "    this.conn = conn;",
                        "    this.pool = pool;",
                        "    this.holder = holder;",
                        "    this.viewer = viewer;",
                        "    this.sink = sink;",
                        "  }",
                        "}")
                .doTest(TestMode.TEXT_MATCH);
    }

    @Test
    public void classesThatAllocateAResourceInTheirConstructorAreWrappers() {
        // Close injection will give TempWriter a close() method (the paper's TempFileWriter).
        helper.addInputLines("TempWriter.java",
                        "import java.io.*;",
                        "class TempWriter {",
                        "  private final PrintStream stream;",
                        "  TempWriter(String path) throws IOException { stream = new PrintStream(path); }",
                        "}")
                .expectUnchanged()
                .addInputLines("Client.java",
                        "class Client {",
                        "  private TempWriter writer;",
                        "  Client(TempWriter writer) { this.writer = writer; }",
                        "}")
                .addOutputLines("Client.java",
                        "class Client {",
                        "  private final TempWriter writer;",
                        "  Client(TempWriter writer) { this.writer = writer; }",
                        "}")
                .doTest(TestMode.TEXT_MATCH);
    }

    @Test
    public void assignmentInsideTryUsesATemporaryAndFinally() {
        helper.addInputLines("Server.java",
                        "import java.io.IOException;",
                        "import java.net.ServerSocket;",
                        "class Server {",
                        "  private ServerSocket socket;",
                        "  Server(int port) {",
                        "    try {",
                        "      this.socket = new ServerSocket(port);",
                        "    } catch (IOException e) {",
                        "      e.printStackTrace();",
                        "    }",
                        "  }",
                        "}")
                .addOutputLines("Server.java",
                        "import java.io.IOException;",
                        "import java.net.ServerSocket;",
                        "class Server {",
                        "  private final ServerSocket socket;",
                        "  Server(int port) {",
                        "    ServerSocket tempSocket = null;",
                        "    try {",
                        "      tempSocket = new ServerSocket(port);",
                        "    } catch (IOException e) {",
                        "      e.printStackTrace();",
                        "    } finally {",
                        "      this.socket = tempSocket;",
                        "    }",
                        "  }",
                        "}")
                .doTest(TestMode.TEXT_MATCH);
    }

    @Test
    public void tryRewriteIsSkippedWhenCodeAfterTheAssignmentCouldReadTheField() {
        // init() runs before finally would assign the field, so it would see null.
        helper.addInputLines("Server.java",
                        "import java.io.IOException;",
                        "import java.net.ServerSocket;",
                        "class Server {",
                        "  private ServerSocket socket;",
                        "  Server(int port) {",
                        "    try {",
                        "      socket = new ServerSocket(port);",
                        "      init();",
                        "    } catch (IOException e) {",
                        "      e.printStackTrace();",
                        "    }",
                        "  }",
                        "  void init() { System.out.println(socket); }",
                        "}")
                .expectUnchanged()
                .doTest(TestMode.TEXT_MATCH);
    }
}
