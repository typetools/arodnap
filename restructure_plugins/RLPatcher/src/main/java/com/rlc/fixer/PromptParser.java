package com.rlc.fixer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.PrimitiveType;

public class PromptParser {

    // -------- Public API --------

    // /** Legacy-compatible: returns the first leak (or null). */
    // public static PromptInfo parse(String content) {
    // List<PromptInfo> all = parseAll(content);
    // return all.isEmpty() ? null : all.get(0);
    // }

    /**
     * New multi-leak API: parse JSON arrays, or fall back to legacy single blob.
     */
    public static List<PromptInfo> parseAll(String content) {
        String text = content == null ? "" : content.trim();
        return parseAllFromJson(text);
    }

    // -------- JSON path --------

    /**
     * Expected JSON:
     * {
     * "CF Leaks": ["<CF block 1>", "<CF block 2>", ...],
     * "RLFixer hint": ["<hint 1>", "<hint 2>", ...]
     * }
     */
    private static List<PromptInfo> parseAllFromJson(String jsonText) {
        List<PromptInfo> out = new ArrayList<>();
        JsonObject obj = JsonParser.parseString(jsonText).getAsJsonObject();

        JsonArray cfArr = obj.has("CF Leaks") ? obj.getAsJsonArray("CF Leaks") : new JsonArray();
        JsonArray hintArr = obj.has("RLFixer hint") ? obj.getAsJsonArray("RLFixer hint") : new JsonArray();

        int n = Math.min(cfArr.size(), hintArr.size());
        for (int i = 0; i < n; i++) {
            String cf = asString(cfArr.get(i));
            String hint = asString(hintArr.get(i));
            PromptInfo pi = parsePair(cf, hint, i);
            if (pi != null)
                out.add(pi);
        }
        return out;
    }

    private static String asString(JsonElement e) {
        return (e == null || e.isJsonNull()) ? "" : e.getAsString();
    }

    // -------- Legacy single-blob path --------

    // -------- Core parser for one CF block + one RLFixer hint --------

    private static PromptInfo parsePair(String cf, String hint, int index) {
        PromptInfo info = new PromptInfo();
        info.index = index;

        // --- leak file + CF line ---
        Matcher pathMatcher = Pattern.compile("(/.*\\.java):(\\d+)").matcher(cf);
        if (pathMatcher.find()) {
            info.leakSourceFile = pathMatcher.group(1);
            info.cfLeakLine = toInt(pathMatcher.group(2), -1);
        }

        // --- resource type ---
        Matcher typeMatcher = Pattern.compile("The type of object is:\\s*([a-zA-Z0-9_.$]+)").matcher(cf);
        if (typeMatcher.find()) {
            info.resourceType = trimDot(typeMatcher.group(1));
        }

        // --- finalizer + allocation from $$ … $$ ---
        Matcher allocExprMatcher = Pattern.compile(
                "\\$\\$\\s*\\d+\\s*\\$\\$\\s*method\\s+(\\S+)\\s*\\$\\$\\s*([^$]+?)\\s*\\$\\$")
                .matcher(cf);
        if (allocExprMatcher.find()) {
            info.finalizerMethod = allocExprMatcher.group(1).trim();
            info.allocationExprText = allocExprMatcher.group(2).trim();
        }

        // --- RLFixer: file + line ---
        Matcher fileLineMatcher = Pattern.compile("vim \\+(\\d+) (/.+\\.java)").matcher(hint);
        if (fileLineMatcher.find()) {
            info.fixLeakLine = toInt(fileLineMatcher.group(1), -1);
            info.rlfixerSourceFile = fileLineMatcher.group(2);
        }

        // --- delete-line hints from RLFixer ---
        Matcher delMatcher = Pattern.compile(
                "\\+\\+\\+\\s*Delete\\s+Line\\s+number\\s*(\\d+)",
                Pattern.CASE_INSENSITIVE).matcher(hint);
        while (delMatcher.find()) {
            int ln = toInt(delMatcher.group(1), -1);
            if (ln > 0) {
                info.linesToDelete.add(ln);
            }
        }

        inferFinalizerDefaultArgs(info);

        // --- patch type detection ---
        Matcher tryStartMatcher = Pattern.compile(
                "Add\\s+following\\s+code\\s+above\\s+line:(\\d+)\\s*.*?try\\s*\\{",
                Pattern.DOTALL | Pattern.CASE_INSENSITIVE).matcher(hint);
        Matcher finallyAfterMatcher = Pattern.compile(
                "Add\\s+following\\s+code\\s+after\\s+line:(\\d+)\\s*.*?finally\\s*\\{",
                Pattern.DOTALL | Pattern.CASE_INSENSITIVE).matcher(hint);
        Matcher finallyBelowMatcher = Pattern.compile(
                "Add\\s+following\\s+code\\s+below\\s+line:\\s*(\\d+)",
                Pattern.CASE_INSENSITIVE).matcher(hint);
        // Detect the specific CF reason and the TODO marker in RLFixer hint
        boolean cfOwnFieldOverwrite = Pattern.compile(
                "\\bNon[- ]final\\s+owning\\s+field\\s+(?:might\\s+be\\s+)?overwritten\\b",
                Pattern.CASE_INSENSITIVE).matcher(cf).find();

        boolean hintHasTODO = Pattern.compile(
                "//\\s*TODO",
                Pattern.CASE_INSENSITIVE).matcher(hint).find();
        Matcher fieldNameMatcher = Pattern.compile("\\bfield\\s+([A-Za-z_][A-Za-z0-9_]*)").matcher(cf);
        if (fieldNameMatcher.find()) {
            info.owningFieldName = fieldNameMatcher.group(1);
        }

        if (tryStartMatcher.find() && finallyAfterMatcher.find()) {
            info.patchType = PatchType.TRY_WRAP_AND_FINALLY;
            info.tryWrapStartLine = toInt(tryStartMatcher.group(1), -1);
            info.tryWrapEndLine = toInt(finallyAfterMatcher.group(1), -1);
            info.finallyInsertLine = info.tryWrapEndLine;
        } else if (fieldNameMatcher.find() && cfOwnFieldOverwrite && hintHasTODO) {
            info.patchType = PatchType.PRE_CLOSE_FIELD_BEFORE;
            info.preInsertBeforeLine = toInt(fieldNameMatcher.group(1), -1);
        } else if (finallyBelowMatcher.find()) {
            info.patchType = PatchType.ONLY_FINALLY;
            info.finallyInsertLine = toInt(finallyBelowMatcher.group(1), -1);
        } else {
            // Default to ONLY_FINALLY if unspecified
            info.patchType = PatchType.ONLY_FINALLY;
        }

        // Validation
        if (info.leakSourceFile == null || info.cfLeakLine <= 0) {
            return null;
        }
        return info;
    }

    // -------- utils --------

    private static int toInt(String s, int dflt) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return dflt;
        }
    }

    private static String trimDot(String s) {
        if (s == null)
            return null;
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }

    // Heuristic: infer default args for non-close finalizers by parsing the
    // resource
    // type's source file under the nearest .../src. If not found, or anything
    // fails,
    // leave finalizerDefaultArgs empty (call with no args).
    private static void inferFinalizerDefaultArgs(PromptInfo info) {
        if (info == null)
            return;
        if (info.finalizerMethod == null)
            return;
        if ("close".equalsIgnoreCase(info.finalizerMethod))
            return;
        if (info.resourceType == null || info.resourceType.isEmpty())
            return;
        if (info.leakSourceFile == null || info.leakSourceFile.isEmpty())
            return;

        try {
            // Locate src root (nearest ancestor named "src")
            Path leak = Paths.get(info.leakSourceFile).toAbsolutePath().normalize();
            Path p = leak;
            Path srcRoot = null;
            while (p != null) {
                if (p.getFileName() != null && "src".equals(p.getFileName().toString())) {
                    srcRoot = p;
                    break;
                }
                p = p.getParent();
            }
            if (srcRoot == null)
                return;

            // Map FQN -> file
            String rel = info.resourceType.replace('.', java.io.File.separatorChar) + ".java";
            Path typeFile = srcRoot.resolve(rel);
            if (!Files.isRegularFile(typeFile))
                return;

            // Parse the declaring file
            CompilationUnit cu = StaticJavaParser.parse(typeFile);
            String simpleName = info.resourceType.substring(info.resourceType.lastIndexOf('.') + 1);
            String pkg = cu.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse("");

            MethodDeclaration md = findMethodInTypeOrParents(
                    cu, simpleName, info.finalizerMethod, srcRoot, pkg, /* maxDepth */ 2);
            if (md == null)
                return;

            // Build default args from parameters
            java.util.List<String> args = new java.util.ArrayList<>();
            for (Parameter param : md.getParameters()) {
                // If varargs (last parameter), passing nothing is allowed → skip adding an arg
                if (param.isVarArgs())
                    continue;

                args.add(defaultArgForType(param.getType()));
            }
            info.finalizerDefaultArgs.clear();
            info.finalizerDefaultArgs.addAll(args);
        } catch (Exception ignore) {
            // Fail quietly → leave as no-args
        }
    }

    private static MethodDeclaration findMethodInTypeOrParents(
            CompilationUnit cu,
            String typeSimpleName,
            String methodName,
            Path srcRoot,
            String currentPackage,
            int depthRemaining) {

        TypeDeclaration<?> td = findType(cu, typeSimpleName);
        if (td != null) {
            // 1) direct lookup in this type
            java.util.List<MethodDeclaration> here = td.findAll(MethodDeclaration.class,
                    m -> m.getNameAsString().equals(methodName));
            if (!here.isEmpty()) {
                here.sort(java.util.Comparator.comparingInt(m -> m.getParameters().size()));
                return here.get(0);
            }

            // 2) walk parents (shallow), resolving each extended type to a file via
            // imports/pkg
            if (depthRemaining > 0 && td instanceof ClassOrInterfaceDeclaration) {
                ClassOrInterfaceDeclaration cid = (ClassOrInterfaceDeclaration) td;

                for (com.github.javaparser.ast.type.ClassOrInterfaceType ext : cid.getExtendedTypes()) {
                    // ext may be simple or qualified (and may reference nested types)
                    String nameWithScope = ext.getNameWithScope();
                    java.util.List<String> parentFqns = new java.util.ArrayList<>();

                    if (nameWithScope.contains(".")) {
                        // treat as qualified (best effort)
                        parentFqns.add(nameWithScope);
                    } else {
                        // simple name: resolve via imports, wildcard imports, and same-package
                        parentFqns.addAll(resolveParentCandidates(nameWithScope, currentPackage, cu));
                    }

                    for (String parentFqn : parentFqns) {
                        Path parentPath = srcRoot.resolve(parentFqn.replace('.', java.io.File.separatorChar) + ".java");
                        if (!java.nio.file.Files.isRegularFile(parentPath))
                            continue;
                        try {
                            CompilationUnit parentCu = StaticJavaParser.parse(parentPath);
                            String parentPkg = parentCu.getPackageDeclaration()
                                    .map(pd -> pd.getNameAsString())
                                    .orElse(parentFqn.contains(".")
                                            ? parentFqn.substring(0, parentFqn.lastIndexOf('.'))
                                            : currentPackage);

                            MethodDeclaration md = findMethodInTypeOrParents(
                                    parentCu,
                                    parentFqn.substring(parentFqn.lastIndexOf('.') + 1),
                                    methodName,
                                    srcRoot,
                                    parentPkg,
                                    depthRemaining - 1);
                            if (md != null)
                                return md;
                        } catch (Exception ignore) {
                            // keep trying other candidates
                        }
                    }
                }
            }
        }
        return null;
    }

    private static TypeDeclaration<?> findType(CompilationUnit cu, String simpleName) {
        for (TypeDeclaration<?> td : cu.getTypes()) {
            if (td.getNameAsString().equals(simpleName))
                return td;
            // also check nested types
            java.util.Optional<TypeDeclaration> nested = td.findFirst(
                    TypeDeclaration.class, t -> t.getNameAsString().equals(simpleName));
            if (nested.isPresent())
                return nested.get();
        }
        return null;
    }

    private static String defaultArgForType(Type t) {
        if (t.isPrimitiveType()) {
            PrimitiveType p = t.asPrimitiveType();
            switch (p.getType()) {
                case BOOLEAN:
                    return "false";
                // byte, short, int, long, char → "0" works as a constant; Java will convert if
                // legal
                default:
                    return "0";
            }
        }
        // Reference/array/generics → null
        return "null";
    }

    private static java.util.List<String> resolveParentCandidates(
            String simpleName,
            String currentPackage,
            CompilationUnit cu) {

        java.util.List<String> out = new java.util.ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();

        // a) exact imports (import some.pkg.Parent;)
        for (ImportDeclaration id : cu.getImports()) {
            if (!id.isAsterisk()) {
                String fqn = id.getNameAsString();
                String ident = id.getName().getIdentifier();
                if (ident.equals(simpleName)) {
                    if (seen.add(fqn))
                        out.add(fqn);
                }
            }
        }

        // b) wildcard imports (import some.pkg.*;) -> some.pkg.SimpleName
        for (ImportDeclaration id : cu.getImports()) {
            if (id.isAsterisk()) {
                String pkg = id.getNameAsString();
                String fqn = pkg + "." + simpleName;
                if (seen.add(fqn))
                    out.add(fqn);
            }
        }

        // c) same package fallback
        if (currentPackage != null && !currentPackage.isEmpty()) {
            String fqn = currentPackage + "." + simpleName;
            if (seen.add(fqn))
                out.add(fqn);
        } else {
            // default package fallback (rare)
            if (seen.add(simpleName))
                out.add(simpleName);
        }

        return out;
    }

}
