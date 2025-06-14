package com.rlc.fixer;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.FieldDeclaration;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * The {@code FieldModifier} class provides utilities to programmatically modify
 * Java field declarations by adding {@code private} and/or {@code final}
 * modifiers.
 * <p>
 * It uses JavaParser to locate and rewrite field declarations in the source
 * file.
 */
public class FieldModifier {

    /**
     * Attempts to modify the specified field in the given Java file by adding
     * {@code private}
     * and/or {@code final} modifiers, depending on the provided flags.
     * <p>
     * If the modification succeeds, the affected lines in the source file are
     * updated.
     *
     * @param filePath    the path to the Java source file
     * @param fieldName   the name of the field to modify
     * @param makeFinal   whether to add the {@code final} modifier
     * @param makePrivate whether to add the {@code private} modifier
     * @return {@code true} if the file was successfully modified; {@code false}
     *         otherwise
     * @throws IOException if an I/O error occurs during file reading or writing
     */
    public static boolean tryModifyField(String filePath, String fieldName, boolean makeFinal, boolean makePrivate)
            throws IOException {
        CompilationUnit cu = StaticJavaParser.parse(new File(filePath));
        List<String> lines = Files.readAllLines(Paths.get(filePath));
        boolean modified = false;

        for (FieldDeclaration field : cu.findAll(FieldDeclaration.class)) {
            if (field.getVariables().stream().anyMatch(v -> v.getNameAsString().equals(fieldName))) {
                if (makePrivate && !field.isPrivate()) {
                    field.getModifiers().removeIf(mod -> {
                        Modifier.Keyword keyword = mod.getKeyword();
                        return keyword == Modifier.Keyword.PRIVATE ||
                                keyword == Modifier.Keyword.PROTECTED ||
                                keyword == Modifier.Keyword.PUBLIC;
                    });
                    field.setPrivate(true);
                    modified = true;
                }
                if (makeFinal && !field.isFinal()) {
                    field.setFinal(true);
                    modified = true;
                }

                if (modified && field.getBegin().isPresent() && field.getEnd().isPresent()) {
                    int beginLine = field.getBegin().get().line - 1;
                    int endLine = field.getEnd().get().line - 1;
                    String updated = field.toString().strip(); // preserves formatting inside just this node
                    List<String> updatedLines = Arrays.asList(updated.split("\\R"));

                    // Replace only those lines in the source file
                    List<String> newFileLines = new ArrayList<>(lines);
                    newFileLines.subList(beginLine, endLine + 1).clear();
                    newFileLines.addAll(beginLine, updatedLines);

                    Files.write(Paths.get(filePath), newFileLines);
                    return true;
                }
            }
        }

        return false;
    }
}
