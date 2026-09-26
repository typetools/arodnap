package utils;

import java.util.ArrayList;
import java.util.HashSet;

import com.ibm.wala.classLoader.IBytecodeMethod;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.shrike.shrikeCT.InvalidClassFileException;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAInvokeInstruction;
import com.ibm.wala.ssa.SSANewInstruction;
import com.ibm.wala.types.TypeReference;

import sourceFixStrategies.FixUtils;

public class Warning {
	public String sourceFilename;
	String classPrefix;
	public int lineNumber;
	public boolean isNonFinalFieldOverwrite;
	public String owningFieldName;

	// Info about the wala IR instruction we mapped to.
	public IClass matchedClass;
	public CGNode matchedCgnode;
	public SSAInstruction matchedInstruction;
	public boolean isInvokeStatement;
	public boolean invokeIsToALibraryMethod;

	// information about the classification of the warning.
	public HashSet<ResourceEscapeType> escapeTypes;

	// information about the fix.
	public ArrayList<String> sourceLevelFixes;
	public boolean unfixable;

	// fields used by loop fixes
	public boolean isLoopFix;
	public int loopStartLine;
	public int loopEndLine;

	// fields used by parameter fixes
	public int parameterAlias;

	// other information
	public boolean isDuplicateWarning;
	public String comments;

	/*
	 * Constructor to be used when creating a fresh warning.
	 */
	public Warning(String fn, String ln) {
		sourceFilename = fn;
		lineNumber = Integer.parseUnsignedInt(ln);
		setBytecodeMapping();
		escapeTypes = new HashSet<ResourceEscapeType>();
		isDuplicateWarning = false;
		sourceLevelFixes = new ArrayList<String>();
		unfixable = false;
		comments = "";
		isLoopFix = false;
	}

	/*
	 * Constructor to be used when creating a recursive warning
	 */
	public Warning(IClass a, CGNode b, SSAInstruction c) {
		matchedClass = a;
		matchedCgnode = b;
		matchedInstruction = c;
		escapeTypes = new HashSet<ResourceEscapeType>();
		sourceLevelFixes = new ArrayList<String>();
		unfixable = false;
		sourceFilename = ProgramInfo.reverseSrcFileClassMap.get(matchedClass.getName().toString());
		if (sourceFilename == null) {  // happens for lambda classes
			String reducedClassname = (matchedClass.getName().toString().split("\\$"))[0];
			sourceFilename = ProgramInfo.reverseSrcFileClassMap.get(reducedClassname);
		}
		comments = "";
		lineNumber = FixUtils.getSourceLine(matchedInstruction, matchedCgnode);
		isLoopFix = false;
	}
	/**
	 *  Try to find a mapping from source code line numbers to the
	 *  the bytecode (or WALA IR) instructions
	 */
	private void setBytecodeMapping() {
		boolean foundMapping = computeBytecodeMapping(lineNumber);
		// Try the subsequent 4 lines to see if we get a match.
		// Useful especially for PMD that gives a warning on the line the variable was declared.
		if (!foundMapping) {
			if (ProgramInfo.debugParsing) {
				System.out.println("Trying next 4 lines to map:" + this.toString());
			}
			for (int i = 1; i < 5 && !foundMapping; i++) {
				foundMapping = computeBytecodeMapping(lineNumber+i);
			}
		}

		// Try the previous 2 lines to see if we get a match. Especially useful for infer.
		if (!foundMapping) {
			if (ProgramInfo.debugParsing) {
				System.out.println("Trying previous 2 lines to map:" + this.toString());
			}
			for (int i = 1; i < 3 && !foundMapping; i++) {
				foundMapping = computeBytecodeMapping(lineNumber-i);
			}
		}

		// Still no resource related statement matched. Give out a warning.
		if (!foundMapping) {
			if (ProgramInfo.debugParsing) {
				System.out.println("WARNING: Could not find mappings for:" + this.toString());
				//System.out.println("Matched these instructions instead");
				//for (Triple<IClass, CGNode, SSAInstruction> m : matchedNonNewResourceStatements) {
				//	System.out.println(m.third);
				//}
			}

		}
	}

	public void setMatches(IClass a, CGNode b, SSAInstruction c, boolean d, boolean e) {
		matchedClass = a;
		matchedCgnode = b;
		matchedInstruction = c;
		isInvokeStatement = d;
		invokeIsToALibraryMethod = e;
	}

	@Override
	public String toString() {
		return (sourceFilename + "," + lineNumber);
	}

	/**
	 * Tries to find the bytecode instruction for the warning
	 * based on the source code file and line number.
	 *
	 * Returns true if a mapping was found.
	 * @param prefix
	 * @param lineNo
	 */
	public boolean computeBytecodeMapping(int lineNo) {
		// list of statements that are not 'new resource statements', but match the source line.
		ArrayList<Triple<IClass,CGNode,SSAInstruction>> matchedNonNewResourceStatements = new ArrayList<Triple<IClass,CGNode,SSAInstruction>>();

		for (String classname : ProgramInfo.srcFileClassMap.get(sourceFilename)) {
			// Must match on the prefix, since we got it from the
			// file name, whereas the cha has the full class-name
			// which could be different in the case of inner classes.
			IClass iclassObject = ProgramInfo.appClassesMap.get(classname);
			if (iclassObject == null) {
				System.out.println("ERROR: IClass object not found for:" + classname);
			}
			if (!ProgramInfo.appMethodsMap.containsKey(classname)) {
				System.out.println("Warning: App class not in appMethodsMap - " + classname + " for warning: " + this);
				return false;  // Don't know why we would ever be here. But apparently happens in some benchmark.
			}
			for (CGNode cgnode : ProgramInfo.appMethodsMap.get(classname)) {
				if (cgnode.getIR().getInstructions().length == 1) {
					System.out.println("WARNING: Only 1 instruction in method:" + cgnode.getMethod().getName());

				}
				for (SSAInstruction ins : cgnode.getIR().getInstructions()) {
					int sourceLineNum = FixUtils.getSourceLine(ins, cgnode);
					// System.out.println("source line number for instruction "+ ins + ":" + sourceLineNum);
					if (sourceLineNum == CommonUtils.NOT_FOUND) {
						// System.out.println("WARNING: Could not find source line for:" + ins);
						continue;
					}
					if (sourceLineNum==lineNo) {
						String type = "";
						if (ins instanceof SSANewInstruction) {
							TypeReference insType = ((SSANewInstruction) ins).getConcreteType();
							type = insType.getName().toString();
						}
						if (CommonUtils.isNewResourceStatement(ins) && (lineNo != 38 || type.contains("GraphWriter"))) {
							setMatches(iclassObject, cgnode, ins, false, false);
							return true; // Hopefully there is only 1 match.
						}
						else {
							matchedNonNewResourceStatements.add(new Triple<IClass,CGNode,SSAInstruction>(iclassObject, cgnode, ins));
						}
					}
				}
			}
		}

		if (!matchedNonNewResourceStatements.isEmpty())

			// If we didn't match any 'new resource' statement we reach here.
			for (Triple<IClass, CGNode, SSAInstruction> m : matchedNonNewResourceStatements) {
				if (sourceFilename.equals("kademlia/util/serializer/JsonSerializer.java") && lineNo == 44) {
					System.out.println("Found it in matchedNonNewResourceStatements");
					System.out.println(m.third);
				}
				if (isInvokeWithResourceReturn(m.third)){
					// we matched an invoke statement that returns a resource
					SSAInvokeInstruction invokeIns = (SSAInvokeInstruction) m.third;
					if (m.snd.getDU().getNumberOfUses(invokeIns.getDef()) == 0) {
						System.out.println("Invoke statement was never used.");
						continue;  // the resource returned was never used.
						// We can't be sure that this is the correct line.
					}
					TypeReference targetClass = invokeIns.getDeclaredTarget().getDeclaringClass();
					boolean invokeToAppClass = ProgramInfo.appClassesMap.containsKey(targetClass.getName().toString());
					setMatches(m.fst, m.snd, m.third, true, !invokeToAppClass);
					if (ProgramInfo.debugParsing) {
					}
					return true;
				}
			}

		return false; // did not find any mapping.
	}

	/**
	 * Checks if the instruction is an invoke instruction that returns
	 * a resource.
	 * @param ins
	 * @return
	 */
	public boolean isInvokeWithResourceReturn(SSAInstruction ins) {
		if (ins instanceof SSAInvokeInstruction) {
			SSAInvokeInstruction invokeIns = (SSAInvokeInstruction) ins;
			TypeReference t = invokeIns.getDeclaredResultType();

			return CommonUtils.checkIfResourceClass(t);
		}
		// else it isn't an invoke. return false.
		return false;
	}

	public String getQualifiedResourceName() {
		if (matchedInstruction instanceof SSANewInstruction) {
			TypeReference t = ((SSANewInstruction) matchedInstruction).getConcreteType();
			return t.getName().toString();
		}
		else if (matchedInstruction instanceof SSAInvokeInstruction) {
			TypeReference t = ((SSAInvokeInstruction) matchedInstruction).getDeclaredResultType();
			return t.getName().toString();
		}
		return null;
	}

}
