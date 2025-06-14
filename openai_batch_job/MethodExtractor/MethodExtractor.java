import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;

public class MethodExtractor {
    public static int[] getMethodLines(String filePath, int lineNumber) throws IOException {
        FileInputStream in = new FileInputStream(filePath);
        CompilationUnit cu = StaticJavaParser.parse(in);

        for (MethodDeclaration method : cu.findAll(MethodDeclaration.class)) {
            int startLine = method.getBegin().get().line;
            int endLine = method.getEnd().get().line;
            if (startLine <= lineNumber && lineNumber <= endLine) {
                return new int[]{startLine, endLine};
            }
        }
        throw new RuntimeException("Method not found for line: " + lineNumber);
    }
}
