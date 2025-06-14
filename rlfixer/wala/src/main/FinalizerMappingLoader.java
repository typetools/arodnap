package main;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.types.ResolvedReferenceType;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.*;

public class FinalizerMappingLoader {

    // Map of fully qualified type names to their finalizer method.
    private static final Map<String, String> finalizerMapping = new HashMap<>();

    public static HashSet<String> pseudoResourceClasses = new HashSet<>();

    public static void populateMappings(String WPIOutDir) {
        // If the WPIOutDir is null or empty, return early.
        if (WPIOutDir == null || WPIOutDir.isEmpty()) {
            return;
        }
        // if the directory does not exist, return early.
        File dir = new File(WPIOutDir);
        if (!dir.exists()) {
            return;
        }
        ProjectInfoCollector.collectProjectInfo(WPIOutDir);
        // Reset the JavaParser configuration
        StaticJavaParser.setConfiguration(new ParserConfiguration());
    }

    /**
     * Converts a WALA qualifier name to a Java parser qualifier name.
     * For example:
     *   Input:  Lorg/jsl/collider/ConnectorImpl$Stopper
     *   Output: org.jsl.collider.ConnectorImpl.Stopper
     *
     * @param qualifier the original qualifier in WALA format.
     * @return the converted qualifier in Java parser format.
     */
    private static String convertWalaQualifierToJava(String qualifier) {
        if (qualifier == null || qualifier.isEmpty()) {
            return qualifier;
        }
        // Remove the leading 'L' if present.
        if (qualifier.startsWith("L")) {
            qualifier = qualifier.substring(1);
        }
        // Remove a trailing ';' if present.
        if (qualifier.endsWith(";")) {
            qualifier = qualifier.substring(0, qualifier.length() - 1);
        }
        // Replace '/' and '\' with '.'
        qualifier = qualifier.replaceAll("[/\\\\]", ".");
        // Replace '$' with '.' for inner classes.
        qualifier = qualifier.replace('$', '.');
        return qualifier;
    }

    /**
     * Checks if the given fully qualified class name has an associated finalizer method.
     *
     * @param qualifiedName the fully qualified class name.
     * @return true if a mapping exists and is non-empty; false otherwise.
     */
    public static boolean hasFinalizer(String qualifiedName) {
        String method = finalizerMapping.get(convertWalaQualifierToJava(qualifiedName));
        return method != null && !method.isEmpty();
    }

    /**
     * Returns the finalizer method associated with the given fully qualified class name.
     *
     * @param qualifiedName the fully qualified class name.
     * @return the finalizer method, or null if none is mapped.
     */
    public static String getFinalizerMethod(String qualifiedName) {
        String finalizerMethodName = finalizerMapping.get(convertWalaQualifierToJava(qualifiedName));
        if (finalizerMethodName == null || finalizerMethodName.equals("close")) {
            return "close";
        }
        return finalizerMethodName;
    }

    public static String getFinalizerMethodFromInference(String qualifiedName) {
        return finalizerMapping.get(convertWalaQualifierToJava(qualifiedName));
    }

    public static void putFinalizerMethod(String qualifiedName, String finalizerMethod) {
        finalizerMapping.put(convertWalaQualifierToJava(qualifiedName), finalizerMethod);
    }


    /**
     * Returns the entire finalizer mapping.
     *
     * @return a map from qualified class names to their finalizer methods.
     */
    public Map<String, String> getFinalizerMapping() {
        return finalizerMapping;
    }
}


class ProjectInfoCollector {

    /**
     * Recursively scans the given base directory for ".ajava" files, and for every
     * class that either has an
     *
     * @InheritableMustCall annotation or inherits one from a superclass, collects
     *                      its fully qualified name
     *                      and the set of finalizer methods. The result is then
     *                      stored in a plain text file.
     *
     *                      The output file is passed as an argument (e.g.,
     *                      "projectname.txt") and is created inside the folder
     *                      "finalizer-methods-from-inference".
     *
     *                      The output format is:
     *                      FullyQualifiedClassName->finalizerMethod
     *
     *                      If no mapping is found, no output file is created.
     *
     * @param baseDirPath    the base directory of the project to scan
     */
    public static void collectProjectInfo(String baseDirPath) {
        // Map: (qualified class name) -> (set of finalizer methods)
        Map<String, Set<String>> classToFinalizerMapping = new HashMap<>();
        // Map: (qualified class name) -> ClassOrInterfaceDeclaration (for later
        // inheritance checks)
        Map<String, ClassOrInterfaceDeclaration> allClassesMap = new HashMap<>();

        try {
            // Set up the type solver to resolve types from standard libraries and project
            // sources.
            CombinedTypeSolver typeSolver = new CombinedTypeSolver();
            typeSolver.add(new ReflectionTypeSolver());
            typeSolver.add(new JavaParserTypeSolver(new File(baseDirPath)));
            JavaSymbolSolver symbolSolver = new JavaSymbolSolver(typeSolver);
            StaticJavaParser.getConfiguration().setSymbolResolver(symbolSolver);

            // Recursively retrieve all files ending with ".ajava".
            List<File> ajavaFiles = getAJavaFiles(new File(baseDirPath));

            // First pass: Process files and record classes that are directly annotated.
            for (File file : ajavaFiles) {
                try {
                    CompilationUnit cu = StaticJavaParser.parse(file);
                    List<ClassOrInterfaceDeclaration> classes = cu.findAll(ClassOrInterfaceDeclaration.class);
                    for (ClassOrInterfaceDeclaration clazz : classes) {
                        String qualifiedName;
                        try {
                            qualifiedName = clazz.resolve().getQualifiedName();
                        } catch (Exception e) {
                            // Fallback: build qualified name from package declaration.
                            qualifiedName = cu.getPackageDeclaration()
                                    .map(pd -> pd.getNameAsString() + "." + clazz.getNameAsString())
                                    .orElse(clazz.getNameAsString());
                        }
                        // Save every class for later processing.
                        allClassesMap.put(qualifiedName, clazz);

                        Optional<AnnotationExpr> annotationOpt = clazz.getAnnotationByName("InheritableMustCall");
                        if (annotationOpt.isPresent()) {
                            AnnotationExpr annotation = annotationOpt.get();
                            // Assuming the annotation is of type @InheritableMustCall("finalizerMethod")
                            if (annotation instanceof SingleMemberAnnotationExpr) {
                                SingleMemberAnnotationExpr singleMember = (SingleMemberAnnotationExpr) annotation;
                                String finalizerMethod = singleMember.getMemberValue().toString();
                                // Normalize the method name (e.g. remove quotes if necessary)
                                finalizerMethod = finalizerMethod.replaceAll("[\\{\\}\"]", "").trim();
                                classToFinalizerMapping
                                        .computeIfAbsent(qualifiedName, k -> new HashSet<>())
                                        .add(finalizerMethod);
                            }
                        }
                    }
                } catch (Exception e) {
                    System.out.println("Error parsing file: " + file.getAbsolutePath());
                    e.printStackTrace();
                }
            }

            // Second pass: For every class, check its inheritance chain.
            // If any ancestor (direct or indirect) has finalizer methods, add them.
            for (Map.Entry<String, ClassOrInterfaceDeclaration> entry : allClassesMap.entrySet()) {
                String qualifiedName = entry.getKey();
                Set<String> inheritedFinalizers = getInheritedFinalizers(entry.getValue(), classToFinalizerMapping, baseDirPath);
                if (!inheritedFinalizers.isEmpty()) {
                    classToFinalizerMapping
                            .computeIfAbsent(qualifiedName, k -> new HashSet<>())
                            .addAll(inheritedFinalizers);
                }
            }

            // Only create the output file if there is at least one mapping.
            if (classToFinalizerMapping.isEmpty()) {
                return;
            }

            for (Map.Entry<String, Set<String>> entry : classToFinalizerMapping.entrySet()) {
                FinalizerMappingLoader.putFinalizerMethod(entry.getKey(), entry.getValue().iterator().next());
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Recursively collects all files in the directory tree that end with ".ajava".
     *
     * @param directory the base directory to search
     * @return a list of files with the ".ajava" extension
     */
    private static List<File> getAJavaFiles(File directory) {
        List<File> ajavaFiles = new ArrayList<>();
        if (directory.exists() && directory.isDirectory()) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        ajavaFiles.addAll(getAJavaFiles(file));
                    } else if (file.getName().endsWith(".ajava")) {
                        ajavaFiles.add(file);
                    }
                }
            }
        }
        return ajavaFiles;
    }

    /**
     * Given a class (as a ClassOrInterfaceDeclaration), this method walks its inheritance chain
     * (using the resolved type) to collect finalizer methods from any ancestor that is mapped.
     * It returns the union of all finalizer methods found in the inheritance chain.
     *
     * @param clazz                   the AST node for the class
     * @param classToFinalizerMapping the mapping from qualified class names to their finalizer methods
     * @return a set of inherited finalizer methods, or an empty set if none found
     */
    private static Set<String> getInheritedFinalizers(ClassOrInterfaceDeclaration clazz,
                                                      Map<String, Set<String>> classToFinalizerMapping,
                                                      String baseDirPath) {
        Set<String> inherited = new HashSet<>();
        try {
            ResolvedReferenceTypeDeclaration resolved = clazz.resolve();
            // Get all ancestors (direct and indirect)
            for (ResolvedReferenceType ancestor : resolved.getAllAncestors()) {
                String ancestorQualifiedName = ancestor.getQualifiedName();
                // If an ancestor is in our mapping, add its finalizer methods.
                if (classToFinalizerMapping.containsKey(ancestorQualifiedName)) {
                    inherited.addAll(classToFinalizerMapping.get(ancestorQualifiedName));
                }
            }
        } catch (UnsolvedSymbolException e) {
            // get extended type
            for (Node extendedType : clazz.getExtendedTypes()) {
                String extendedTypeName = extendedType.toString().replaceAll("<[^>]+>", "");
                // bad heuristic
                for (String classQname : classToFinalizerMapping.keySet()) {
                    if (classQname.endsWith("." + extendedTypeName)) {
                        inherited.addAll(classToFinalizerMapping.get(classQname));
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return inherited;
    }
}