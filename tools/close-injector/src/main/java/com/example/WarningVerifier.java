package com.example;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * The {@code WarningVerifier} class is responsible for verifying and retrieving
 * information about the initialization of fields within a class.
 * Specifically, it provides methods to determine if a field was initialized at
 * a specific line number within the source code.
 *
 * The primary functionality is provided by the
 * Å{@code getFieldInitializationInfo} method, which returns details about
 * the field initialization, including the field name and the class name.
 */
public class WarningVerifier {

    /**
     * Retrieves information about the field initialization at the specified line
     * number within the provided {@code CompilationUnit}. If a field is assigned a
     * value at the given line number, the method returns an {@code Optional}
     * containing a {@code FieldInitializationInfo} object with details about the
     * field and the class.
     *
     * @param cu         the {@code CompilationUnit} representing the source code to
     *                   analyze.
     * @param lineNumber the line number to check for field initialization.
     * @return an {@code Optional} containing {@code FieldInitializationInfo} if a
     *         field is initialized at the given line number, or an empty
     *         {@code Optional} if not.
     */
    public static Optional<FieldInitializationInfo> getFieldInitializationInfo(CompilationUnit cu, int lineNumber) {
        FieldInitializationVisitor visitor = new FieldInitializationVisitor(lineNumber);
        cu.accept(visitor, null);
        return visitor.getFieldInitializationInfo();
    }

    /**
     * The {@code FieldInitializationInfo} class is a data structure that
     * encapsulates information about a field initialization.
     * It stores the name of the field and the name of the class where the field is
     * initialized.
     */
    public static class FieldInitializationInfo {
        private final String fieldName;
        private final String className;

        public FieldInitializationInfo(String fieldName, String className) {
            this.fieldName = fieldName;
            this.className = className;
        }

        public String getFieldName() {
            return fieldName;
        }

        public String getClassName() {
            return className;
        }
    }

    /**
     * The {@code FieldInitializationVisitor} class is a visitor that traverses the
     * abstract syntax tree (AST) of the Java source code to identify the
     * initialization of fields within a class. It checks if a field is initialized
     * at a specific line number within a constructor.
     */
    private static class FieldInitializationVisitor extends VoidVisitorAdapter<Void> {
        private final int lineNumber;
        private String fieldName = null;
        private String className = null;
        private final Set<String> classFields = new HashSet<>();

        FieldInitializationVisitor(int lineNumber) {
            this.lineNumber = lineNumber;
        }

        @Override
        public void visit(ClassOrInterfaceDeclaration cd, Void arg) {
            super.visit(cd, arg);
            classFields.clear();
            cd.getFields().forEach(field -> classFields.addAll(getFieldNames(field)));
            cd.getConstructors().forEach(constructor -> constructor.accept(this, arg));
            cd.getMethods().forEach(method -> method.accept(this, arg));
        }

        @Override
        public void visit(MethodDeclaration md, Void arg) {
            super.visit(md, arg);
            // Check if the method has valid start and end positions.
            if (md.getBegin().isPresent() && md.getEnd().isPresent()) {
                int begin = md.getBegin().get().line;
                int end = md.getEnd().get().line;

                // Proceed only if the provided line number falls within this method's range.
                if (lineNumber >= begin && lineNumber <= end) {
                    // Look for assignment expressions within the method.
                    md.findAll(AssignExpr.class).forEach(assignExpr -> {
                        // If the assignment occurs on the specified line and targets a field, record
                        // the field name.
                        if (assignExpr.getBegin().isPresent() && assignExpr.getBegin().get().line == lineNumber) {
                            if (assignExpr.getTarget() instanceof NameExpr) {
                                String targetName = ((NameExpr) assignExpr.getTarget()).getNameAsString();
                                if (classFields.contains(targetName)) {
                                    fieldName = targetName;
                                    md.findAncestor(ClassOrInterfaceDeclaration.class).ifPresent(cd -> {
                                        className = cd.getNameAsString();
                                    });
                                }
                            } else if (assignExpr.getTarget() instanceof FieldAccessExpr) {
                                String targetName = ((FieldAccessExpr) assignExpr.getTarget()).getNameAsString();
                                if (classFields.contains(targetName)) {
                                    fieldName = targetName;
                                    md.findAncestor(ClassOrInterfaceDeclaration.class).ifPresent(cd -> {
                                        className = cd.getNameAsString();
                                    });
                                }
                            }
                        }
                    });
                }
            }
        }

        @Override
        public void visit(ConstructorDeclaration cd, Void arg) {
            super.visit(cd, arg);
            // Check if the constructor has valid start and end positions.
            if (cd.getBegin().isPresent() && cd.getEnd().isPresent()) {
                int begin = cd.getBegin().get().line;
                int end = cd.getEnd().get().line;

                // Proceed only if the provided line number falls within this constructor's
                // range.
                if (lineNumber >= begin && lineNumber <= end) {
                    // Look for assignment expressions within the constructor.
                    cd.findAll(AssignExpr.class).forEach(assignExpr -> {
                        // If the assignment occurs on the specified line and targets a field, record
                        // the field name.
                        if (assignExpr.getBegin().isPresent() && assignExpr.getBegin().get().line == lineNumber) {
                            if (assignExpr.getTarget() instanceof NameExpr) {
                                String targetName = ((NameExpr) assignExpr.getTarget()).getNameAsString();
                                if (classFields.contains(targetName)) {
                                    fieldName = targetName;
                                    className = cd.getNameAsString();
                                }
                            } else if (assignExpr.getTarget() instanceof FieldAccessExpr) {
                                String targetName = ((FieldAccessExpr) assignExpr.getTarget()).getNameAsString();
                                if (classFields.contains(targetName)) {
                                    fieldName = targetName;
                                    className = cd.getNameAsString();
                                }
                            }
                        }
                    });
                }
            }
        }

        /**
         * Returns the set of field names declared in the class.
         *
         * @param field the {@code FieldDeclaration} representing the field.
         * @return a set of field names.
         */

        private Set<String> getFieldNames(FieldDeclaration field) {
            Set<String> fieldNames = new HashSet<>();
            field.getVariables().forEach(variable -> fieldNames.add(variable.getNameAsString()));
            return fieldNames;
        }

        /**
         * Returns an {@code Optional} containing {@code FieldInitializationInfo} if a
         * field is initialized at the specified line number.
         *
         * @return an {@code Optional} containing {@code FieldInitializationInfo} or an
         *         empty {@code Optional} if no field was found.
         */
        public Optional<FieldInitializationInfo> getFieldInitializationInfo() {
            if (fieldName != null && className != null) {
                return Optional.of(new FieldInitializationInfo(fieldName, className));
            }
            return Optional.empty();
        }
    }

}
