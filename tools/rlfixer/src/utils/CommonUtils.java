package utils;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.*;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.ibm.wala.analysis.typeInference.TypeInference;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.cha.ClassHierarchy;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSACFG;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAInvokeInstruction;
import com.ibm.wala.ssa.SSANewInstruction;
import com.ibm.wala.types.MethodReference;
import com.ibm.wala.types.TypeReference;

import main.FinalizerMappingLoader;


public class CommonUtils {
	public static final String newVariableName = "<NEW_VARIABLE>";
	public static final int NOT_FOUND = 1;
	/**
	 * Checks if the type-reference is for a resource type.
	 * @param t
	 * @return
	 */
	public static boolean checkIfResourceClass(TypeReference t) {
		if (t.isPrimitiveType() || t.isArrayType()) {
			return false; // primitive types are not resources. 
			// the array could be a resource-array, but we skip this case.
		}
		String classname = t.getName().toString();
		
		// Get the iclass object
		IClass c;
		if (ProgramInfo.appClassesMap.containsKey(classname)) {
			c = ProgramInfo.appClassesMap.get(classname);
		}
		else {
			c = ProgramInfo.libClassesMap.get(classname);
		}
		// First check if it implements the closeable/autocloseable interface.
		if (c == null) {
			if (classname.equalsIgnoreCase("Ljava/lang/PrintStream") || 
					classname.equalsIgnoreCase("Ljava/lang/InputStream")) {
				// These seem to be a Wala bug. (the correct class is java/io/PrintStream)
				// So this is just accomodating for that issue.
				return true;
			}
			if (ProgramInfo.printWarnings) {
				System.out.println("WARNING: Class not found" + classname);
			}
			return false;
		}
		if (c.getAllImplementedInterfaces().contains(ProgramInfo.closeableInterface) ||
				c.getAllImplementedInterfaces().contains(ProgramInfo.autoCloseableInterface)) {
			return true;
		}
		// Else, check if it has a 'close' method. 
		// This is needed for resources that don't implement closeable/autocloseable
		for (IMethod m : c.getAllMethods()) {
			if (isCloseMethod(m.getReference())) {
				return true;
			}
		}
		if (FinalizerMappingLoader.hasFinalizer(classname)) {
			return true;
		}
		if (FinalizerMappingLoader.pseudoResourceClasses.contains(classname)) {
			return true;
		}
		Set<IClass> sub = getAllSubtypes(ProgramInfo.cha, c);

		if (ProgramInfo.appClassesMap.containsKey(classname)) {
			for (IClass r : sub) {
				if (FinalizerMappingLoader.pseudoResourceClasses.contains(ProgramInfo.appClassesMapReverse.get(r))) {
					return true;
				}
			}
		}
		return false;
	}
	
	public static boolean isCloseMethod(MethodReference m) {
		String methodName = m.getName().toString();
		if (methodName.equalsIgnoreCase("close")
				&& m.getNumberOfParameters() == 0) { 
			return true;
		} else {
			String declaringClass = m.getDeclaringClass().getName().toString();
			String finalizerMethod = FinalizerMappingLoader.getFinalizerMethod(declaringClass);
			if (finalizerMethod != null && finalizerMethod.equalsIgnoreCase(methodName)) {
				return true;
			}
			return false;
		}
	}

	public static HashSet<String> readAppClasses(String appClassesFile) throws FileNotFoundException {	
		HashSet<String> appClasses = new HashSet<String>();
		Scanner reader = new Scanner(new File(appClassesFile));
		while (reader.hasNextLine()) {
			String classname = reader.nextLine();
			String formattedClassname = formatClassName(classname);
			appClasses.add(formattedClassname);
		}
		reader.close();
		return appClasses;
	}
	
	static String formatClassName(String classname) {
		String c = classname.replace('.', '/');
		return "L" + c;
	}

	public static String getVariableId(CGNode cgnode, int val) {
		return cgnode.getMethod().getSignature() + "#" + val;
	}

	/*
	 *  Computes the parameter number of the variableNumber in the invoke
	 *  instruction. Throws an error if the variable doesn't exist.
	 */
	public static int getParameterNumber(CGNode cgnode, SSAInvokeInstruction invokeIns, int variableNumber, boolean mustMatch) {
		int parameterNumber = -1;
		for (int i = 0; i < invokeIns.getNumberOfPositionalParameters(); i++) {
			if (invokeIns.getUse(i) == variableNumber) {
				parameterNumber = i;
				break;
			}
		}
		if (mustMatch && parameterNumber == -1) {
			System.out.println("ERROR: Parameter number not found while checking for wrapper(" + cgnode + "): " + invokeIns);
			System.exit(1);
		}
		return parameterNumber;
	}
	
	public static void printInstructions(CGNode cgnode) {
		if (cgnode.getIR() != null) {
			SSAInstruction[] instructions = cgnode.getIR().getInstructions();
			for (int i = 0 ; i < instructions.length ; i++) {
				System.out.println(i + " : " + instructions[i]);
			}
			
			Iterator<SSAInstruction> insIterator = cgnode.getIR().iterateAllInstructions();
			while (insIterator.hasNext()) {
				SSAInstruction ins = insIterator.next();
				if (ins != null) {
					System.out.println("index:" + ins.iIndex());
					System.out.println(ins);
				}
			}
		}
	}

	public static void printBasicBlocks(CGNode cgnode) {
		SSACFG cfg = cgnode.getIR().getControlFlowGraph();
		Queue<ISSABasicBlock> q = new LinkedList<ISSABasicBlock>();
		Set<Integer> visitedBB = new HashSet<Integer>();
		q.add(cfg.entry());
		visitedBB.add(cfg.entry().getNumber());
		
		System.out.println("BASIC BLOCKS GRAPH");
		while(!q.isEmpty()) {
			ISSABasicBlock temp = q.remove();
			System.out.println(temp);
			//System.out.println(temp.getNumber() + " : " + temp.getFirstInstructionIndex() + "," + temp.getLastInstructionIndex());
			
			for (Iterator<ISSABasicBlock> it = cfg.getSuccNodes(temp) ; it.hasNext() ; ) {
				ISSABasicBlock b = it.next();
				System.out.println(temp.getNumber() + "->" + b.getNumber());
				if (!visitedBB.contains(b.getNumber())) {
					visitedBB.add(b.getNumber());
					q.add(b);
				}
			}
		}
	}

	// Computes intersection over a list of sets.
	public static HashSet<Integer> computeIntersection(ArrayList<HashSet<Integer>> sets) {
		HashSet<Integer> intersection = new HashSet<Integer>();
		if (sets.size() == 0) {
			return intersection;
		}
		for (int elem : sets.get(0)) {
			boolean elemInIntersection = true;
			for (HashSet<Integer> set : sets) {
				if (!set.contains(elem)) {
					elemInIntersection = false;
				}
			}
			if (elemInIntersection) {
				intersection.add(elem);
			}
		}
		return intersection;
	}	
	
	public static CompilationUnit getCompilationUnit(String relativePath) {
		ParseResult<CompilationUnit> pr = null;
		try {
			String srcFile = ProgramInfo.projectSrcDir + "/" + relativePath;
			if (relativePath == null) {
				System.out.println("ERROR:");
			}
			pr = new JavaParser().parse(new File(srcFile));
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		}
		CompilationUnit cu = pr.getResult().get();
		return cu;
	}

	public static boolean isCollectionOrMapMethod(CGNode cgnode, SSAInvokeInstruction invokeIns) {
		TypeInference ti = TypeInference.make(cgnode.getIR(), false);
		TypeReference firstParamType = ti.getType(invokeIns.getUse(0)).getTypeReference();
		
		if (firstParamType==null || firstParamType.isPrimitiveType() || firstParamType.isArrayType()) {
			return false; // these cases are not collections.
		}
		String firstParamClassname = firstParamType.getName().toString();
		
		// Get the iclass object
		IClass c;
		if (ProgramInfo.appClassesMap.containsKey(firstParamClassname)) {
			c = ProgramInfo.appClassesMap.get(firstParamClassname);
		}
		else {
			c = ProgramInfo.libClassesMap.get(firstParamClassname);
		}
		// Check if it implements the collection interface.
		if (c == null) {
			return false;
		}
		// Filter java.util.Properties methods
		if (c.getName().toString().equals("Ljava/util/Properties")) {
			return false;
		}
		if (c.getAllImplementedInterfaces().contains(ProgramInfo.collectionInterface) ||
				c.getAllImplementedInterfaces().contains(ProgramInfo.mapInterface)) {
			return true;
		}
		return false;
	}

	/**
	 * Checks if the 'ins' is a 'New' instruction for a resource object.
	 * @param ins
	 * @return
	 */
	public static boolean isNewResourceStatement(SSAInstruction ins) {
		if (ins instanceof SSANewInstruction) {
			TypeReference t = ((SSANewInstruction) ins).getConcreteType();
			return checkIfResourceClass(t);
		}
		// else it isn't a new resource. return false.
		return false;
	}
	
	/*
	 * Checks if the given instruction is a close statement.
	 */
	public static boolean isCloseStatement(SSAInstruction useIns) {
		if (useIns instanceof SSAInvokeInstruction) {
			SSAInvokeInstruction invokeIns = (SSAInvokeInstruction) useIns;
			if (CommonUtils.isCloseMethod(invokeIns.getDeclaredTarget())){
				return true;
			}
		}
		// In other cases return false.
		return false;
	}

	public static Set<IClass> getAllSubtypes(ClassHierarchy cha, IClass target) {
		Set<IClass> result = new HashSet<>();
		Deque<IClass> worklist = new ArrayDeque<>();
		worklist.add(target);

		while (!worklist.isEmpty()) {
			IClass current = worklist.poll();

			// For classes: get direct subclasses
			for (IClass sub : cha.getImmediateSubclasses(current)) {
				if (result.add(sub)) {
					worklist.add(sub);
				}
			}

			// For interfaces: get implementors
			if (current.isInterface()) {
				for (IClass impl : cha.getImplementors(current.getReference())) {
					if (result.add(impl)) {
						worklist.add(impl);
					}
				}
			}
		}

		return result;
	}

}
