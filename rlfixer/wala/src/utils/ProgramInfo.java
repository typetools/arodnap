package utils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.*;
import java.nio.file.*;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.github.javaparser.utils.Log;
import com.ibm.wala.classLoader.BinaryDirectoryTreeModule;
import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.classLoader.Language;
import com.ibm.wala.ipa.callgraph.AnalysisCacheImpl;
import com.ibm.wala.ipa.callgraph.AnalysisOptions;
import com.ibm.wala.ipa.callgraph.AnalysisOptions.ReflectionOptions;
import com.ibm.wala.ipa.callgraph.AnalysisScope;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ipa.callgraph.CallGraphBuilder;
import com.ibm.wala.ipa.callgraph.Entrypoint;
import com.ibm.wala.ipa.callgraph.cha.CHACallGraph;
import com.ibm.wala.ipa.callgraph.impl.DefaultEntrypoint;
import com.ibm.wala.ipa.callgraph.impl.Util;
import com.ibm.wala.ipa.cha.ClassHierarchy;
import com.ibm.wala.ipa.cha.ClassHierarchyException;
import com.ibm.wala.ipa.cha.ClassHierarchyFactory;
import com.ibm.wala.ssa.SSAInvokeInstruction;
import com.ibm.wala.ssa.SSAPutInstruction;
import com.ibm.wala.util.CancelException;
//import com.ibm.wala.util.config.AnalysisScopeReader;
import com.ibm.wala.ipa.callgraph.AnalysisScope;
import com.ibm.wala.types.ClassLoaderReference;
import com.ibm.wala.core.java11.JrtModule;
import com.ibm.wala.core.util.io.FileProvider;
import com.ibm.wala.classLoader.SourceDirectoryTreeModule;
import com.ibm.wala.util.config.StringFilter;
import com.ibm.wala.classLoader.JarFileModule;

import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.SSAInstruction;

public class ProgramInfo {
	public static boolean use0CFA = true;
	private static final String walaFakeRoot = "Lcom/ibm/wala/FakeRootClass";
	public static ClassHierarchy cha;
	public static CallGraph callgraph;
	public static AnalysisScope analysisScope;
	public static Iterable<Entrypoint> entrypoints;
	// a map from callee to their callers
	// caller is identified by the caller-cgnode and call-site-instruction.
	// cannot use just call-site-instruction since it doesn't have a pointer to the cgnode.
	public static HashMap<CGNode, ArrayList<Pair<CGNode, SSAInvokeInstruction>>> callersMap;
	//public static HashMap<CGNode, ArrayList<CGNode>> calleeMap;
	// a map from a class to its methods' CGNode objects
	public static HashMap<String, ArrayList<CGNode>> appMethodsMap;
	// a map from a class-name to the IClass object
	public static HashMap<String, IClass> appClassesMap;
	public static HashMap<IClass, String> appClassesMapReverse = new HashMap<>();
	// a map from a class-name to the IClass object
	public static HashMap<String, IClass> libClassesMap;
	// A map from a file-name prefix to the matching set of class names
	public static HashMap<String, ArrayList<String>> srcFileClassMap;
	public static HashMap<String, String> reverseSrcFileClassMap;

	// A map from the method signature to its CGNode
	public static HashMap<String, CGNode> methodCGNodeMap;
	// Key: Field signature. Value: the number of times it is written to
	public static HashMap<String, Integer> fieldWritesCount = null;
	// The Closeable interface
	public static IClass closeableInterface;
	public static IClass autoCloseableInterface;
	public static IClass collectionInterface;
	public static IClass mapInterface;

	public static boolean extraSrcAdded = false;
	public static String projectSrcDir;


	// Constants
	public static final int MAX_ALLOWED_FANOUT = 10;
	public static final boolean SKIP_LIBRARY = true;

	public static final boolean printWarnings = true;
	// if true, it prints debug information for the parsing of warnings.
	public static final boolean debugParsing = false;
	//public static final boolean debugResourceClassification = true;
	//public static final boolean debugWrapperIdentification = true;

	// Boilerplate code for making callgraph and class-hierarchy
	public static void initializeProgramInfo(String classpath, String exclusionsFileName, String appClassesFile, String srcFilesList, String projectDir, File exclusionsFile)
			throws ClassHierarchyException, IOException, IllegalArgumentException, CancelException {
		//WALA expects a Primordial class loader pointing to the JDK runtime classes but in java >=9 JDK uses jmods/

		// String tempJdkClasses = "tmp_jdk_classes";
        // extractJmods(tempJdkClasses);

		// String combinedClasspath = tempJdkClasses + File.pathSeparator + classpath;

		analysisScope = AnalysisScope.createJavaAnalysisScope();
		analysisScope.addToScope(
                ClassLoaderReference.Primordial,
                new JrtModule(System.getProperty("java.home"))
        );

		Set<String> excludedClasses = new HashSet<>();
		if (exclusionsFile != null && exclusionsFile.exists()) {
			try (BufferedReader br = new BufferedReader(new FileReader(exclusionsFile))) {
				String line;
				while ((line = br.readLine()) != null) {
					excludedClasses.add(line.trim());
				}
			}
		}

		File appDir = new File(classpath);
		List<Path> includedFiles = new ArrayList<>();
		Files.walk(appDir.toPath())
				.filter(Files::isRegularFile)
				.filter(p -> p.toString().endsWith(".class"))
				.filter(p -> {
					// Convert path to class name format
					String className = appDir.toPath().relativize(p).toString().replace(File.separatorChar, '/');
					return !excludedClasses.contains(className);
				})
				.forEach(includedFiles::add);
		JarFileModule appModule = new JarFileModule(new JarFile(appDir)) {
			public Iterable<String> getFileNames() {
				List<String> names = new ArrayList<>();
				for (Path p : includedFiles) {
					names.add(p.toString());
				}
				return names;
			}
		};
		analysisScope.addToScope(ClassLoaderReference.Application, appModule);
		

		cha = ClassHierarchyFactory.make(analysisScope);
		readApplicationClasses(appClassesFile);
		populateClassesMap();
		generateCallgraph();
		populateAppMethodsMap();
		populateCallersMap();
		populateMethodCgnodeMap();
		populateAllFieldWriteCounts();
		setImportantInterfaces();
		populateSrcFileClassMap(srcFilesList,projectDir);
	}

	// simplified version of the method with the same name
	public static void simplifiedInitializeProgramInfo(String classpath, String appClassesFile)
			throws ClassHierarchyException, IOException, IllegalArgumentException, CancelException {
		//analysisScope = AnalysisScope.makeJavaBinaryAnalysisScope(classpath, null);

		analysisScope = AnalysisScope.createJavaAnalysisScope();
		// Add JDK classes (Primordial)
		analysisScope.addToScope(
				ClassLoaderReference.Primordial,
				new JrtModule(System.getProperty("java.home"))
		);
		// Add your application classes
		File appClasses = new File(classpath);
		SourceDirectoryTreeModule appModule = new SourceDirectoryTreeModule(appClasses);
		analysisScope.addToScope(ClassLoaderReference.Application, appModule);
		cha = ClassHierarchyFactory.make(analysisScope);
		readApplicationClasses(appClassesFile);
		populateClassesMap();
		generateCallgraph();
		populateAppMethodsMap();
		populateMethodCgnodeMap();
		setImportantInterfaces();
	}

	private static void populateAllFieldWriteCounts() {
		fieldWritesCount = new HashMap<String, Integer>();
		for (CGNode cgnode : callgraph) {
			IR ir = cgnode.getIR();
			if (ir != null) {
				ir.visitAllInstructions(new RecordFieldWriteCounts());
			}
		}
	}

	private static void populateMethodCgnodeMap() {
		methodCGNodeMap = new HashMap<String, CGNode>();
		for (CGNode cgnode : callgraph) {
			IMethod method = cgnode.getMethod();
			//IClass c = method.getDeclaringClass();
			methodCGNodeMap.put(method.getSignature(), cgnode);
			//if (CommonUtils.checkIfResourceClass(c.getReference())) {
		}
	}

	public static Set<CGNode> getTargets(CGNode srcMethod, CallSiteReference callsite) {
		Set<CGNode> possibleTargets = callgraph.getPossibleTargets(srcMethod, callsite);

		if (SKIP_LIBRARY) {
			Set<CGNode> possibleAppTargets = new HashSet<CGNode>();
			for (CGNode target : possibleTargets) {
				String classname = target.getMethod().getDeclaringClass().getName().toString();
				if (appClassesMap.containsKey(classname)) {
					possibleAppTargets.add(target);
				}
			}
			possibleTargets = possibleAppTargets;
		}

		if (possibleTargets.size() > MAX_ALLOWED_FANOUT ) {
			return new HashSet<CGNode>();
		} else {
			return possibleTargets;
		}
	}

	public static int getMethodCount(String classname) {
		return appMethodsMap.get(classname).size();
	}

	public static Set<String> getAllClasses() {
		return appClassesMap.keySet();
	}

	public static IClass getClassObject(String classname) {
		return appClassesMap.get(classname);
	}

	public static Collection<CGNode> getEntrypointNodes() {
		return callgraph.getEntrypointNodes();
	}

	/* HELPER METHODS */
	private static void generateCallgraph() throws IllegalArgumentException, CancelException{
		//entrypoints = Util.makeMainEntrypoints(analysisScope, cha);

		entrypoints = new HashSet<Entrypoint>();
		for (IClass c : cha) {
			String classname = c.getName().toString();
			if (!appClassesMap.containsKey(classname) && SKIP_LIBRARY) {
				continue;  // skip library stuff
			}
			for (IMethod m : c.getDeclaredMethods()) {
				((HashSet<Entrypoint>) entrypoints).add(new DefaultEntrypoint(m, cha));
			}
		}
		/*
		for(Entrypoint e : Util.makeMainEntrypoints(analysisScope, cha)) {
			((HashSet<Entrypoint>) entrypoints).add(e);
		}*/
		// generate callgraph
		if (use0CFA) {
			// Iterable<Entrypoint> entrypoints = Util.makeMainEntrypoints(scope, cha);
			AnalysisOptions options = new AnalysisOptions(analysisScope, entrypoints);
			options.setReflectionOptions(ReflectionOptions.NONE);
			CallGraphBuilder<?> builder = Util.makeZeroCFABuilder(Language.JAVA, options, new AnalysisCacheImpl(), cha,
					analysisScope);
			callgraph = builder.makeCallGraph(options, null);
		} else { // use CHA
			CHACallGraph cg = new CHACallGraph(cha);
			cg.init(entrypoints);
			callgraph = cg;
		}
	}

	private static void setImportantInterfaces() {
		for (IClass c : cha) {
			if (c.getName().toString().equalsIgnoreCase("Ljava/io/Closeable")){
				closeableInterface = c;
			}
			if (c.getName().toString().equalsIgnoreCase("Ljava/lang/AutoCloseable")){
				autoCloseableInterface = c;
			}
			if (c.getName().toString().equalsIgnoreCase("Ljava/util/Collection")){
				collectionInterface = c;
			}
			if (c.getName().toString().equalsIgnoreCase("Ljava/util/Map")){
				mapInterface = c;
			}
		}

		if (closeableInterface == null) {
			System.out.println("ERROR: Closeable Interface not found");
			System.exit(1);
		}
		if (autoCloseableInterface == null) {
			System.out.println("ERROR: AutoCloseable Interface not found");
			System.exit(1);
		}
		if (collectionInterface == null) {
			System.out.println("ERROR: Collection Interface not found");
			System.exit(1);
		}
		if (mapInterface == null) {
			System.out.println("ERROR: Collection Interface not found");
			System.exit(1);
		}
	}

	private static void populateCallersMap() {
		callersMap = new HashMap<CGNode, ArrayList<Pair<CGNode, SSAInvokeInstruction>>>();
		for (CGNode cgnode : callgraph) {
			String classname = cgnode.getMethod().getDeclaringClass().getName().toString();
			if (!appClassesMap.containsKey(classname) && SKIP_LIBRARY) {
				continue;  // skip library stuff
			}
			if (cgnode.getIR() != null) {
				cgnode.getIR().visitAllInstructions(new InvokeVisitor(cgnode));
			}
		}
	}

	private static void populateAppMethodsMap() {
		appMethodsMap = new HashMap<String, ArrayList<CGNode>>();
		// fill in the methods for each class.
		for (CGNode cgnode : callgraph) {
			String classname = cgnode.getMethod().getDeclaringClass().getName().toString();
			if (!appClassesMap.containsKey(classname)) {
				continue;  // skip library stuff
			}
			if (!appMethodsMap.containsKey(classname)) {
				appMethodsMap.put(classname, new ArrayList<CGNode>());
			}
			appMethodsMap.get(classname).add(cgnode);
		}
		// handle fake root
		appMethodsMap.put(walaFakeRoot, new ArrayList<CGNode>());
	}

	private static void populateClassesMap() {
		libClassesMap = new HashMap<String, IClass>();
		for (IClass cl : cha) {
			String classname = cl.getName().toString();
			if (appClassesMap.containsKey(classname)) {
				appClassesMap.put(classname, cl);
				appClassesMapReverse.put(cl, classname);
			} else {
				libClassesMap.put(classname, cl);
			}
		}
		// handle fake root
		//IClass fakeRootClass = callgraph.getFakeRootNode().getMethod().getDeclaringClass();
		//appClassesMap.put(walaFakeRoot, fakeRootClass);
	}

	private static void readApplicationClasses(String appClassesFile) throws FileNotFoundException {
		appClassesMap = new HashMap<String, IClass>();
		Scanner reader = new Scanner(new File(appClassesFile));
		while (reader.hasNextLine()) {
			String classname = reader.nextLine();
			String formattedClassname = formatClassName(classname);
			appClassesMap.put(formattedClassname,null);
		}
		reader.close();
	}

	private static String formatClassName(String classname) {
		return "L" + classname.replace('.', '/');
	}

	// This map is important when we need to map a (file name
	// + line number) into a (class + method + instruction).
	// It gives us a list of classes contained within a filename.
	private static void populateSrcFileClassMap(String srcFilesListFile, String projectDir) {
		srcFileClassMap = new HashMap<String, ArrayList<String>>();
		reverseSrcFileClassMap = new HashMap<String, String>();
		// Initialize javaparser logging.
		Log.setAdapter(new Log.StandardOutStandardErrorAdapter());

		if (srcFilesListFile == null || projectDir == null) {
			// We don't have the list of source files. We will just use
			// the prefixes are the source file names
			for (String classname : appClassesMap.keySet()) {
				String classPrefix = classname.split("\\$")[0];
				String potentialFileName = classPrefix.substring(1) + ".java"; // remove the 'L' and add .java
				enterIntoRelevantMaps(potentialFileName, classname);
			}
		} else {
			// We have the list of src files.
			try (BufferedReader f = new BufferedReader(new FileReader(srcFilesListFile))){
				String line = f.readLine();
				// Check if the file names use "src" and set the
				// project source directory accordingly.
				if (line.substring(0, 4).equals("src/")) {
					extraSrcAdded = true;
				}
				if (extraSrcAdded) {
					projectSrcDir = projectDir + "/src";
				} else {
					projectSrcDir = projectDir;
				}

				// Read the rest of the files
				for (; line != null; line = f.readLine() ) {
					String filename;
					if (extraSrcAdded) {
						filename = line.substring(4);  // skip the src/
					} else {
						filename = line;
					}
					CompilationUnit cu = CommonUtils.getCompilationUnit(filename);
					// Map to store the fully qualified name of each class or enum type
					Map<TypeDeclaration<?>, String> typeQualifiedNameMap = new HashMap<>();

					// Map to track anonymous class counts (including enum constants)
					Map<String, Integer> anonymousClassCounts = new HashMap<>();

					for ( TypeDeclaration<?> definedType : cu.findAll(TypeDeclaration.class)) {
						String fullClassname = buildNestedQualifiedName(definedType);
						typeQualifiedNameMap.put(definedType, fullClassname);
						String bytecodeClassname = CommonUtils.formatClassName(fullClassname);
						//System.out.println("MAP ENTRY:" + filename + "," + bytecodeClassname);
						enterIntoRelevantMaps(filename, bytecodeClassname);
					}

					cu.accept(new VoidVisitorAdapter<Map<String, Integer>>() {
						@Override
						public void visit(ObjectCreationExpr expr, Map<String, Integer> counter) {
							if (expr.getAnonymousClassBody().isPresent()) {
								String enclosingClass = getEnclosingClass(expr, typeQualifiedNameMap);

								// Increment count for anonymous class
								int count = counter.getOrDefault(enclosingClass, 0) + 1;
								counter.put(enclosingClass, count);

								// Generate anonymous class name with $N suffix
								String anonymousClassName = enclosingClass + "$" + count;
								String formattedAnonymousClass = CommonUtils.formatClassName(anonymousClassName);
								enterIntoRelevantMaps(filename, formattedAnonymousClass);
							}
							super.visit(expr, counter);
						}

						@Override
						public void visit(EnumConstantDeclaration enumConstant, Map<String, Integer> counter) {
							if (!enumConstant.getClassBody().isEmpty()) {
								String enclosingEnum = getEnclosingEnum(enumConstant);

								// Increment count for anonymous enum classes
								int count = counter.getOrDefault(enclosingEnum, 0) + 1;
								counter.put(enclosingEnum, count);

								// Generate anonymous enum class name with $N suffix
								String anonymousEnumClassName = enclosingEnum + "$" + count;
								String formattedEnumClass = CommonUtils.formatClassName(anonymousEnumClassName);
								enterIntoRelevantMaps(filename, formattedEnumClass);
							}
							super.visit(enumConstant, counter);
						}
					}, anonymousClassCounts);


					/*
					try (BufferedReader f2 = new BufferedReader(new FileReader(srcFile))){
						String packageName = "";
						for (String line2 = f2.readLine(); line2 != null ; line2 = f2.readLine()) {
							String [] splitLine = line2.split(" ");
							for (int i = 0 ; i < splitLine.length - 1 ; i++) {
								if (splitLine[i].equals("class") || splitLine[i].equals("interface")) {
									// the split("<")[0] is necessary for Generic classes.
									// the split("{")[0] is necessary to avoid the open bracket
									String classname = (splitLine[i+1].split("<")[0]).split("\\{")[0];
									String fullClassname = packageName + "." + classname;
									String bytecodeClassname = CommonUtils.formatClassName(fullClassname);
									String filename;
									if (line.substring(0, 4).equals("src/")) {
										filename = line.substring(4);  // skip the src/
									} else {
										filename = line;
									}
									//System.out.println("MAP ENTRY:" + filename + "," + classname);
									enterIntoRelevantMaps(filename, bytecodeClassname);
								}
								if (splitLine[i].equals("package")) {
									packageName = splitLine[i+1].split(";")[0];
								}
							}
						}
					}*/
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
		}

	}

	private static String buildNestedQualifiedName(TypeDeclaration<?> td) {
		// Start with the simple name of this type
		StringBuilder nestedName = new StringBuilder(td.getNameAsString());

		Node currentParent = td.getParentNode().orElse(null);
		while (currentParent != null) {
			if (currentParent instanceof TypeDeclaration) {
				TypeDeclaration<?> parentType = (TypeDeclaration<?>) currentParent;
				nestedName.insert(0, parentType.getNameAsString() + "$");
			}
			currentParent = currentParent.getParentNode().orElse(null);
		}

		// Finally, prepend the package name if it exists
		Optional<CompilationUnit> optCu = td.findCompilationUnit();
		if (optCu.isPresent()) {
			CompilationUnit cu = optCu.get();
			if (cu.getPackageDeclaration().isPresent()) {
				String packageName = cu.getPackageDeclaration().get().getNameAsString();
				return packageName + "." + nestedName;
			}
		}

		return nestedName.toString();
	}

	private static String getEnclosingClass(ObjectCreationExpr expr, Map<TypeDeclaration<?>, String> typeQualifiedNameMap) {
		return expr.findAncestor(ClassOrInterfaceDeclaration.class)
				.map(typeQualifiedNameMap::get)
				.orElse("Unknown");
	}

	private static String getEnclosingEnum(EnumConstantDeclaration enumConstant) {
		return enumConstant.findAncestor(EnumDeclaration.class)
				.map(EnumDeclaration::getFullyQualifiedName)
				.flatMap(opt -> opt)
				.orElse("Unknown");
	}

	private static void enterIntoRelevantMaps(String filename, String classname) {
		if (!srcFileClassMap.containsKey(filename)) {
			srcFileClassMap.put(filename,new ArrayList<String>());
		}
		srcFileClassMap.get(filename).add(classname);
		reverseSrcFileClassMap.put(classname, filename);
	}

	private static class InvokeVisitor implements SSAInstruction.IVisitor {
		CGNode cgnode;

		public InvokeVisitor(CGNode a) {
			cgnode = a;
		}

		@Override
		public void visitInvoke(SSAInvokeInstruction ins) {
			for (CGNode target : getTargets(cgnode, ins.getCallSite())) {
				if (!callersMap.containsKey(target)) {
					callersMap.put(target, new ArrayList<Pair<CGNode, SSAInvokeInstruction>>());
				}
				Pair<CGNode, SSAInvokeInstruction> caller = new Pair<CGNode, SSAInvokeInstruction>(cgnode, ins);
				callersMap.get(target).add(caller);
			}
		}
	}

	private static class RecordFieldWriteCounts implements SSAInstruction.IVisitor {
		@Override
		public void visitPut(SSAPutInstruction ins) {
			String fieldSignature = ins.getDeclaredField().getSignature();
			if (!fieldWritesCount.containsKey(fieldSignature)) {
				fieldWritesCount.put(fieldSignature, 0);
			}
			fieldWritesCount.put(fieldSignature, fieldWritesCount.get(fieldSignature)+1);
		}
	}
}

	/*
	private static boolean isStdLib(CGNode cgnode) {
		TypeName t1 = cgnode.getMethod().getDeclaringClass().getName();
		if (t1.getPackage() == null){
			return false;
		}
		String packageName = t1.getPackage().toString();
		if (packageName.startsWith("java/") ||
			packageName.startsWith("javax/") ||
		    packageName.startsWith("sun/") || 
		    packageName.startsWith("com/oracle/")  || 
		    packageName.startsWith("com/sun/")  || 
		    packageName.startsWith("org/ietf/")) { // skip during debugging
			return true;
		}
		return false;
	}*/
