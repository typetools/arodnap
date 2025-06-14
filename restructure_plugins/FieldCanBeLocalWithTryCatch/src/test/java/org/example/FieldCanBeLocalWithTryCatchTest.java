package org.example;

import com.google.errorprone.CompilationTestHelper;
import org.junit.Test;

public class FieldCanBeLocalWithTryCatchTest {

    private final CompilationTestHelper compilationHelper = CompilationTestHelper
            .newInstance(FieldCanBeLocalWithTryCatch.class, getClass());

    @Test
    public void testPositiveCase() {
        compilationHelper
                .addSourceLines(
                        "TestClass.java",
                        "class TestClass {",
                        "  // BUG: Diagnostic contains: Field can be converted to a local variable",
                        "  private int myField;",
                        "  void myMethod() {",
                        "    try {",
                        "       myField = 5;",
                        "    } catch (Exception e) {",
                        "      // handle exception",
                        "    }",
                        "  }",
                        "}")
                .doTest();
    }

    @Test
    public void testPositiveCaseTwo() {
        compilationHelper
                .addSourceLines(
                        "TestClass.java",
                        "class TestClass {",
                        "  private int myField;",
                        "  void myMethod() {",
                        "    try {",
                        "       int myField = 5;",
                        "    } catch (Exception e) {",
                        "      // handle exception",
                        "    }",
                        "  }",
                        "}")
                .expectNoDiagnostics()
                .doTest();
    }

}
