package com.example;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.SimpleName;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The {@code CloseMethodInjector} class provides functionality to automatically
 * inject a {@code close()} method into a
 * specified class within a Java source file. Additionally, it ensures that the
 * class implements the {@code AutoCloseable}
 * interface if it doesn't already.
 *
 * The class operates by parsing the Java source file, identifying the target
 * class, and then modifying it by:
 * <ul>
 * <li>Implementing the {@code AutoCloseable} interface if not already
 * present.</li>
 * <li>Adding a {@code close()} method that calls a specified method on a
 * specified field, wrapped in a try-catch block.</li>
 * </ul>
 *
 * The modified source code is then written back to the original file, ensuring
 * that any changes are preserved.
 */
public class CloseMethodInjector {

    static final Map<String, CompilationUnit> compilationUnits = new HashMap<>();

    /**
     * Adds a {@code close()} method to the specified class in the Java source file
     * and ensures that the class
     * implements the {@code AutoCloseable} interface.
     *
     * @param cu         the {@code CompilationUnit} representing the parsed Java
     *                   source file.
     * @param filePath   the file path of the Java source file to be modified.
     * @param className  the name of the class where the {@code close()} method
     *                   should be injected.
     * @param fieldName  the name of the field within the class that the
     *                   {@code close()} method will invoke.
     * @param methodName the name of the method to be invoked on the specified field
     *                   within the {@code close()} method.
     * @throws IOException if an I/O error occurs while reading or writing the file.
     */
    public static void addCloseMethod(CompilationUnit cu, String filePath, String className,
            Set<String> fieldAndMethodNames) throws IOException {
        // Read the original file content
        String content = new String(Files.readAllBytes(Paths.get(filePath)));
        List<String> fieldNames = new ArrayList<>();
        List<String> methodNames = new ArrayList<>();
        for (String fieldAndMethodName : fieldAndMethodNames) {
            String[] parts = fieldAndMethodName.split("#");
            if (parts.length == 2) {
                fieldNames.add(parts[0]);
                methodNames.add(parts[1]);
            }
        }

        // Find the class declaration
        Optional<ClassOrInterfaceDeclaration> classOpt = cu.getClassByName(className);
        if (classOpt.isEmpty()) {
            classOpt = ClassFinder.findClass(cu, className);
        }
        if (classOpt.isPresent()) {
            if (methodNames.contains("onWebsocketClose") || methodNames.contains("createEncode")) {
                // Some finalizer methods take arguments, which this local analysis can not
                // handle.
                // TODO: Handle this case by parsing the method signature and arguments.
                return;
            }
            ClassOrInterfaceDeclaration classDeclaration = classOpt.get();
            Optional<MethodDeclaration> existingCloseMethodOpt = classDeclaration.getMethodsByName("close").stream()
                    .filter(method -> method.getParameters().isEmpty() && method.getBody().isPresent())
                    .filter(method -> method.getType().isVoidType())
                    .findFirst();

            // If there already exists a close method, but with different method signature
            // we will bail out
            Optional<MethodDeclaration> justCloseNamed = classDeclaration.getMethodsByName("close").stream()
                    .findFirst();
            if (justCloseNamed.isPresent() && !justCloseNamed.get().getType().isVoidType()) {
                return;
            }
            // Define the AutoCloseable implementation and close method as strings
            String autoCloseableInterface = " implements AutoCloseable";
            String closeMethod = "\n    public void close() {\n" +
                    "        try {\n";
            for (int i = 0; i < fieldNames.size(); i++) {
                String fieldName = fieldNames.get(i);
                String methodName = methodNames.get(i);
                closeMethod += "            " + fieldName + "." + methodName + "();\n";
            }
            closeMethod += "        } catch (Exception e) {\n" +
                    "            e.printStackTrace();\n" +
                    "        }\n" +
                    "    }\n";

            // Split the content by lines
            String[] lines = content.split("\n");

            // Check if the class already implements any interfaces
            if (classDeclaration.getImplementedTypes().isEmpty()) {
                // Insert the AutoCloseable interface directly after the class name
                int classDeclarationLine = classDeclaration.getBegin().get().line - 1;
                if (!lines[classDeclarationLine].contains("{")) {
                    classDeclarationLine++;
                }
                lines[classDeclarationLine] = lines[classDeclarationLine].replaceFirst("\\{",
                        autoCloseableInterface + " {");
                content = String.join("\n", lines);
            } else if (!classDeclaration.getImplementedTypes().stream()
                    .anyMatch(type -> type.getNameAsString().equals("AutoCloseable"))) {
                // Add AutoCloseable to the existing implemented interfaces
                int interfaceInsertionIndex = content.indexOf("{", content.indexOf(classDeclaration.getNameAsString()));
                content = new StringBuilder(content).insert(interfaceInsertionIndex, ", AutoCloseable ").toString();
            }

            lines = content.split("\n");

            StringBuilder modifiedContent = new StringBuilder();

            if (existingCloseMethodOpt.isPresent()) {
                // Modify the existing close() method
                MethodDeclaration existingCloseMethod = existingCloseMethodOpt.get();
                int existingCloseMethodStartLine = existingCloseMethod.getBegin().get().line;
                int existingCloseMethodEndLine = existingCloseMethod.getEnd().get().line;
                BlockStmt existingBody = existingCloseMethod.getBody().get();

                // Create a new try block
                TryStmt tryBlock = new TryStmt();
                BlockStmt tryBody = new BlockStmt();

                // Add fieldName.methodName() inside the try block
                for (int i = 0; i < fieldNames.size(); i++) {
                    String fieldName = fieldNames.get(i);
                    String methodName = methodNames.get(i);
                    String newFieldName = fieldName;
                    if (fieldName.startsWith("this.")) {
                        newFieldName = fieldName.substring(5);

                    }
                    if (!existingBody.toString().contains(newFieldName + "." + methodName + "();"))
                        tryBody.addStatement(
                                new ExpressionStmt(new MethodCallExpr(new NameExpr(fieldName), methodName)));
                }

                // Move existing close method body statements into the try block
                tryBody.getStatements().addAll(existingBody.getStatements());

                // Create a catch block for exceptions
                CatchClause catchClause = new CatchClause();
                catchClause
                        .setParameter(new Parameter(new ClassOrInterfaceType(null, "Exception"), new SimpleName("e")));
                catchClause.setBody(
                        new BlockStmt().addStatement(new ExpressionStmt(new MethodCallExpr("e.printStackTrace"))));

                tryBlock.setTryBlock(tryBody);
                tryBlock.getCatchClauses().add(catchClause);

                // Replace the old method body with the new try block
                existingCloseMethod.setBody(new BlockStmt().addStatement(tryBlock));

                // System.out.println("Modified existing close() method.");

                // Only replace the lines of the existing close method
                // Say the existing close method starts and ends at line 10 and 20, then we will
                // insert the new close methods content
                // We will remove all the lines from 10 to 20 and insert the new close method
                // content starting from line 10
                boolean isCloseMethodFound = false;
                for (int i = 0; i < lines.length; i++) {
                    if (i >= existingCloseMethodStartLine - 1 && i <= existingCloseMethodEndLine - 1) {
                        if (isCloseMethodFound) {
                            continue;
                        }
                        isCloseMethodFound = true;
                        // insert the new close method content from existingCloseMethod
                        modifiedContent.append(existingCloseMethod.toString()).append("\n");
                    } else {
                        modifiedContent.append(lines[i]).append("\n");
                    }
                }

            } else {
                // Insert the close method before the last closing brace of the class
                int classEndLine = classDeclaration.getEnd().get().line - 1;
                for (int i = 0; i < lines.length; i++) {
                    if (i == classEndLine) {
                        modifiedContent.append(closeMethod).append("\n");
                    }
                    modifiedContent.append(lines[i]).append("\n");
                }
            }

            // Write the modified content back to the file
            Files.write(Paths.get(filePath + ".modified"), modifiedContent.toString().getBytes(),
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
        }
    }
}

class ClassFinder {
    public static Optional<ClassOrInterfaceDeclaration> findClass(CompilationUnit cu, String className) {
        List<ClassOrInterfaceDeclaration> matchingClasses = new ArrayList<>();

        // Visitor to collect all class declarations (including nested ones)
        cu.accept(new VoidVisitorAdapter<Void>() {
            @Override
            public void visit(ClassOrInterfaceDeclaration classDecl, Void arg) {
                super.visit(classDecl, arg);
                if (classDecl.getNameAsString().equals(className)) {
                    matchingClasses.add(classDecl);
                }
            }
        }, null);

        return matchingClasses.isEmpty() ? Optional.empty() : Optional.of(matchingClasses.get(0));
    }
}
