package com.rlc.fixer;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;

/**
 * Spells a resource type the way the file would: the simple name when the file imports
 * exactly that type or declares it in the same package, otherwise the qualified name.
 */
final class TypeNames {

    private TypeNames() {
    }

    /** {@code binaryName} as written in a declaration in {@code cu}, e.g. "java.io.InputStream" → "InputStream". */
    static String inFile(CompilationUnit cu, String binaryName) {
        String qualified = binaryName.replace('$', '.');
        int dot = qualified.lastIndexOf('.');
        if (dot < 0)
            return qualified;
        String simple = qualified.substring(dot + 1);
        String pkg = qualified.substring(0, dot);

        boolean declaredHere = cu.findAll(TypeDeclaration.class).stream()
                .anyMatch(t -> t.getNameAsString().equals(simple));
        if (declaredHere)
            return qualified;
        for (ImportDeclaration imp : cu.getImports()) {
            if (imp.isStatic() || imp.isAsterisk())
                continue;
            String imported = imp.getNameAsString();
            if (imported.equals(qualified))
                return simple;
            if (imported.endsWith("." + simple))
                return qualified; // another type with the same simple name is imported
        }
        String filePackage = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
        return pkg.equals(filePackage) ? simple : qualified;
    }
}
