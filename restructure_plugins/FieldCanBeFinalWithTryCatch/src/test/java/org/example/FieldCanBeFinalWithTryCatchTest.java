package org.example;

import com.google.errorprone.CompilationTestHelper;
import org.junit.Test;

public class FieldCanBeFinalWithTryCatchTest {

    private final CompilationTestHelper compilationHelper = CompilationTestHelper
            .newInstance(FieldCanBeFinalWithTryCatch.class, getClass());

    @Test
    public void testPositiveCase() {
        compilationHelper
                .addSourceLines(
                        "TestClass.java",
                        "class TestClass {",
                        "  // BUG: Diagnostic contains: Field can be converted to a final variable",
                        "  private int myField;",
                        "  public TestClass() {",
                        "     myField = 5;",
                        "  }",
                        "}")
                .doTest();
    }
    
    @Test
    public void testFooCase() {
        compilationHelper
                .addSourceLines(
                        "TestClass.java",
                        "class TestClass {",
                        "  // BUG: Diagnostic contains: Field can be converted to a final variable",
                        "  private int myField;",
                        "  public TestClass() {",
                        "     myField = 5;",
                        "  }",
                        "  public void foo() {",
                        "     myField = 6;",
                        "  }",
                        "}")
                .expectNoDiagnostics()
                .doTest();
    }

    /*
     * Problem: In the try/catch statement plugin tries to replace field with a temp but will replace it to this.tempMyField instead of tempMyField. 
     *          this.tempMyField will produce an error and won't compile.
     * Solution: Remove "this." from the temp variable name which will eliminate the error. 
     */
    @Test
    public void testTryCatch_1_1() {
        compilationHelper
                .addSourceLines(
                        "TestClass.java",
                        "import java.io.PrintWriter;",
                        "import java.io.File;",
                        "import java.io.FileNotFoundException;",
                        "class TestClass {",
                        "  // BUG: Diagnostic contains: Field can be converted to a final variable",
                        "  private PrintWriter myField;",
                        "  public TestClass(File f) {",
                        "     try {",
                        "       this.myField = new PrintWriter(f);",
                        "     } catch (FileNotFoundException e) {",
                        "       e.printStackTrace();",
                        "     }",
                        "  }",
                        "}")
                .doTest();
    }

    @Test
    /*
     * Can't properly test this due to plugin not working with protected field. 
     */
    public void testTryCatch_1_2() {
        compilationHelper
                .addSourceLines(
                        "TestClass.java",
                        "import java.io.PrintWriter;",
                        "import java.io.File;",
                        "import java.io.FileNotFoundException;",
                        "class TestClass {",
                        "  // BUG: Diagnostic contains: Field can be converted to a final variable",
                        "  protected PrintWriter out;",
                        "  public TestClass(File f) {",
                        "     try {",
                        "       this.out = new PrintWriter(f);",
                        "     } catch (FileNotFoundException e) {",
                        "       e.printStackTrace();",
                        "     }",
                        "  }",
                        "}")
                .doTest();
    }
/*
     * Problem: Since the field is protected it could potenitally be called in a different class. 
     * Solution: Invoke a different plugin to set protected fields to private IF VALID before running the final plugin. 
     * Note: All tests involved protected fields is expected to fail. 
     */
    @Test
    public void testProtectedFinal_1_1() {
        compilationHelper
                .addSourceLines(
                        "TestClass.java",
                        "import java.io.PrintWriter;",
                        "import java.io.File;",
                        "import java.io.FileNotFoundException;",
                        "class TestClass {",
                        "  // BUG: Diagnostic contains: Field can be converted to a final variable",
                        "  protected PrintWriter dh;",
                        "  public TestClass(File f) throws FileNotFoundException{",
                        "       dh = new PrintWriter(f);",
                        "  }",
                        "}")
                .doTest();
    }

    @Test
    public void testProtectedFinal_1_2() {
        compilationHelper
                .addSourceLines(
                        "TestClass.java",
                        "import java.io.IOException;",
                        "import java.nio.channels.SocketChannel;",
                        "class TestClass {",
                        "  // BUG: Diagnostic contains: Field can be converted to a final variable",
                        "  protected SocketChannel socketChannel;",
                        "  public TestClass(SocketChannel channel) throws IOException {",
                        "       if(channel == null){",
                        "           throw new IllegalArgumentException( \"parameter must not be null\" );",
                                "}",
                        "       this.socketChannel = channel;",
                        "  }",
                        "}")
                .doTest();
    }

    /*
     * Problem: Invalid test due to field being package-private.
     * Solution: Test passes when field is set to private variable. This means that test works with multiple constructors but field must be private. 
     */
    @Test
    public void testMultipleConstructors_3_1() {
        compilationHelper
                .addSourceLines(
                        "TestClass.java",
                        "import java.io.IOException;",
                        "import java.io.LineNumberReader;",
                        "import java.io.Reader;",
                        "import java.io.InputStream;",
                        "import java.io.InputStreamReader;",
                        "class TestClass {",
                        "  // BUG: Diagnostic contains: Field can be converted to a final variable",
                        " LineNumberReader in;",
                        " TestClass(Reader _in, int size){",
                        "       in = new LineNumberReader(_in,size); }",
                        "  TestClass(Reader _in){",
                        "       in = new LineNumberReader(_in); }",
                        "  TestClass(InputStream in_str){",
                        "       this(new InputStreamReader(in_str)); }",                 
                        "}")
                .doTest();
    }

    /*
     * Problem: Test failed because of "this.selector" in the try statement. The problem is not due to having various constructors. 
     */
    @Test
    public void testMultipleConstructors_3_2() {
        compilationHelper
                .addSourceLines(
                        "TestClass.java",
                        "import java.nio.channels.spi.SelectorProvider;",
                        "import java.nio.channels.Selector;",
                        "class TestClass {",
                        "  // BUG: Diagnostic contains: Field can be converted to a final variable",
                        "  private Selector selector;",
                        " TestClass(){",
                        "     try{",
                        "        this.selector = SelectorProvider.provider().openSelector();",
                        "     } catch (java.io.IOException ioe) {",
                        "       throw new RuntimeException(ioe);",
                        "     }",
                        "    };",
                        "  TestClass(int test){",
                        "       this();",
                        "       int test1 = test;",
                              "  }",             
                        "}")
                .doTest();
    }

    @Test
    public void testMultipleConstructors_3_3() {
        compilationHelper
                .addSourceLines(
                        "TestClass.java",
                        "import java.nio.channels.spi.SelectorProvider;",
                        "import java.nio.channels.Selector;",
                        "class TestClass {",
                        "  // BUG: Diagnostic contains: Field can be converted to a final variable",
                        "  protected Selector selector;",
                        " TestClass(Selector selector){",
                        "        this.selector = selector;",
                        "     };",
                        "  TestClass(int test){",
                        "       int test1 = test;",
                              "  }",             
                        "}")
                .doTest();
    }

    /*
     * Problem: Failed because the default declaration is package private. This means that others classes under the same package can change the field. 
     * Approach: Can ignore test case since this problem not being valid. 
     */
    @Test
    public void testPackagePrivate_1_1() {
        compilationHelper
                .addSourceLines(
                        "TestClass.java",
                        "import java.net.Socket;",
                        "class TestClass {",
                        "  // BUG: Diagnostic contains: Field can be converted to a final variable",
                        " Socket cs;",
                        " TestClass(Socket cs){",
                        "        this.cs = cs;",
                        "     };",            
                        "}")
                        .expectNoDiagnostics()   
                .doTest();
    }

    @Test
    public void testTryCatch_1_3() {
        compilationHelper
                .addSourceLines(
                        "TestClass.java",
                        "import java.io.PrintWriter;",
                        "import java.io.File;",
                        "import java.io.FileNotFoundException;",
                        "class TestClass {",
                        "  // BUG: Diagnostic contains: Field can be converted to a final variable",
                        "  private PrintWriter myField = null;",
                        "  public TestClass(File f) {",
                        "     try {",
                        "       this.myField = new PrintWriter(f);",
                        "     } catch (FileNotFoundException e) {",
                        "       e.printStackTrace();",
                        "     }",
                        "  }",
                        "}")
                .doTest();
    }
}
