package main;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import com.ibm.wala.shrike.shrikeCT.InvalidClassFileException;
import com.ibm.wala.ssa.SSAInvokeInstruction;
import com.ibm.wala.ssa.SSANewInstruction;
import com.ibm.wala.util.*;
import com.ibm.wala.util.io.CommandLine;

import utils.CommonUtils;
import utils.ProgramInfo;
import utils.ResourceEscapeType;
import utils.Warning;

public class Main {
	public static void main(String[] args)
			throws WalaException, IllegalArgumentException, CancelException, IOException, InvalidClassFileException {
		// Read command line arguments and initialize program info.
		Properties p = CommandLine.parse(args);
		String classpath = p.getProperty("classpath");
		String warningsString = p.getProperty("warnings");
		String appClassesFile = p.getProperty("appClasses");
		String srcFilesList = p.getProperty("srcFiles");
		String projectDir = p.getProperty("projectDir");
		String debugOutput = p.getProperty("debugOutput");
		String exclusions = p.getProperty("exclusions");
		String wpiOutDir = p.getProperty("wpiOutDir");
		File exclusionsFile = null;
		if (exclusions != null) {
			exclusionsFile = new File(exclusions);
		}
		// Finalizer method information of CF Inference
	    FinalizerMappingLoader.populateMappings(wpiOutDir);
		
		long time1 = System.currentTimeMillis();
		ProgramInfo.initializeProgramInfo(classpath, null, appClassesFile,srcFilesList,projectDir,exclusionsFile);
		long time2 = System.currentTimeMillis();
		// Find the bytecode instructions for the warning messages.
		List<Warning> matchedWarnings = new ArrayList<Warning>();
		List<Warning> unmatchedWarnings = new ArrayList<Warning>();
		parseWarnings(warningsString, matchedWarnings, unmatchedWarnings);
		sanityCheckOnWarnings(matchedWarnings);
		
		// Identify wrappers for resources in the warnings.
		ResourceAliasIdentification.identifyWrappers(matchedWarnings);
		
		DuplicateWarningIdentification.identifyDuplicateWarnings(matchedWarnings);
		
		// Classify each warning and fix it accordingly.
		for (Warning w : matchedWarnings) {			
			new ResourceClassification(w).classifyWarning();
			ResourceLeakFixing.computeSourceCodeFix(w);
		}
		long time3 = System.currentTimeMillis();
		/* PRINT OUT ALL THE STUFF */
		
		if (debugOutput != null) {
			printWarningsInformation(matchedWarnings, unmatchedWarnings, debugOutput);
		}
		System.out.println(time2 - time1);
		System.out.println(time3 - time2);
		printSourceLevelFixes(matchedWarnings);
	}



	/* Simply prints out all the important information corresponding to
	 * each warning.
	 */
	private static void printWarningsInformation(List<Warning> matchedWarnings, List<Warning> unmatchedWarnings, String debugOutput) {
		try (BufferedWriter writer = new BufferedWriter(new FileWriter(debugOutput))){
			writer.write("Index^Source File^Line Number^Matched Method^MatchedInstruction^Aliases^Classification^Duplicate^Unfixable^Comments\n");
			
			int index = -1;
			for (Warning w : matchedWarnings) {
				index += 1;
				writer.write(index + "^");
				writer.write(w.sourceFilename + "^");
				writer.write(w.lineNumber + "^");
				writer.write(w.matchedCgnode.getMethod().getSignature() + "^");
				writer.write(w.matchedInstruction + "^");
				
				List<Integer> resourceAliases = ResourceAliasIdentification.getAllResourceAliases(w.matchedCgnode, w.matchedInstruction.getDef());
				if (resourceAliases.size() == 0) {
					writer.write("NULL");
				} else {
					for (int aliasVariableNum : resourceAliases) {
						writer.write(CommonUtils.getVariableId(w.matchedCgnode,aliasVariableNum) + ",");
					}
				}
				writer.write("^");
				for (ResourceEscapeType t : w.escapeTypes) {
					writer.write(t + ",");
				}
				writer.write("^");
				writer.write(w.isDuplicateWarning + "^");
				writer.write(w.unfixable + "^");
				writer.write(w.comments);
				writer.write("\n");
			}	
			
			for (Warning w2 : unmatchedWarnings) {
				index += 1;
				writer.write(index + "^");
				writer.write(w2.sourceFilename + "^");
				writer.write(w2.lineNumber + "^");
				writer.write("UNMATCHED^UNMATCHED^NULL^NULL^NULL^true^NULL\n");
			}
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	
	public static void printSourceLevelFixes(List<Warning> matchedWarnings) {
		System.out.println("\nSOURCE LEVEL FIXES\n");
		for (int i = 0 ; i < matchedWarnings.size() ; i ++) {
			Warning w = matchedWarnings.get(i);
			if (w.unfixable || w.isDuplicateWarning) {
				continue;
			}
			System.out.print(i + "] ");
			System.out.println(ProgramInfo.projectSrcDir + "/" + w.sourceFilename + "; Line number " + w.lineNumber);
			System.out.println("vim +" + w.lineNumber + " " + ProgramInfo.projectSrcDir + "/" + w.sourceFilename);
			System.out.println("");
			for (String repairSuggestion : w.sourceLevelFixes) {
				System.out.println("+++ " + repairSuggestion);
			}
			System.out.println("--------------------------------------------");
		}
	}


	/* HELPER functions */
	public static void parseWarnings(String warningsString, 
			List<Warning> matchedWarnings, List<Warning> unmatchedWarnings) {	
		for(String ws : warningsString.split("#")) {
			String filename = ws.split(",")[0];
			String lineNumber = ws.split(",")[1];
			Warning w = new Warning(filename,lineNumber);
			if (ws.split(",")[3].equals("True")) {
				w.isNonFinalFieldOverwrite = true;
			}
			// Only add warnings that we could match. 
			// We can't fix the rest.
			if (w.matchedInstruction == null) {
				unmatchedWarnings.add(w);
			} else {
				matchedWarnings.add(w);
			}
		}
	}

	public static void sanityCheckOnWarnings(List<Warning> warnings) {
		for (Warning warning : warnings) {
			if (warning.matchedInstruction instanceof SSANewInstruction ||
					warning.matchedInstruction instanceof SSAInvokeInstruction) {
				// No problem
			} 
			else {
				// Error: These are the only possible cases.
				System.out.println("ERROR: Warning has incorrect matched instruction(" 
						+ warning.matchedInstruction.getClass() + "): " 
						+ warning.matchedInstruction);
				System.exit(1);
			}
		}
	}
	
	/* UNUSED HELPER FUNCTIONS */

	/*
	private static void printInstructions(CGNode cgnode) {
		if (cgnode.getIR() != null) {
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

	private static HashMap<Integer, Integer> getNextInstructionMapping(CGNode cgnode) {
		HashMap<Integer, Integer> nextMap = new HashMap<Integer, Integer>();
		// Then add all the local variables.
		SSAInstruction[] instructionList = cgnode.getIR().getInstructions();
		for (int i = 0; i < instructionList.length; i++) {
			if (instructionList[i] != null) {
				// find the next non-null instruction
				boolean foundNext = false;
				for (int j = i + 1; j < instructionList.length; j++) {
					if (instructionList[j] != null) {
						nextMap.put(i, j);
						foundNext = true;
						break;
					}
				}
				if (!foundNext) {
					nextMap.put(i, -1); // corner case when there is no next instruction
				}
			}
		} // the last instruction must be a return statement anyways.
		return nextMap;
	}*/
	
	/*
	 * // Returns the set of valueNumbers used in the method // Achieves this by
	 * adding all the Uses and Defs in all the instructions. private static
	 * Set<Integer> getValueNumbersInMethod(CGNode cgnode){ HashSet<Integer>
	 * valueNums = new HashSet<Integer>(); // First add all the formal parameters
	 * for (int i = 1 ; i <= cgnode.getMethod().getNumberOfParameters() ; i++) {
	 * valueNums.add(i); } // Then add all the local variables.
	 * Iterator<SSAInstruction> insIterator =
	 * cgnode.getIR().iterateAllInstructions(); while (insIterator.hasNext()) {
	 * SSAInstruction ins = insIterator.next(); if (ins != null) {
	 * System.out.println("index:" + ins.iIndex()); System.out.println(ins); for
	 * (int i=0; i<ins.getNumberOfDefs(); i++) { valueNums.add(ins.getDef(i)); } for
	 * (int i=0; i<ins.getNumberOfUses(); i++) { valueNums.add(ins.getUse(i)); } } }
	 * return valueNums; }
	 * 
	 * private static boolean isStdLib(TypeName t1){ if (t1!=null){ String pName =
	 * t1.getPackage().toString(); if (pName!=null){ if (pName.startsWith("java/")
	 * || pName.startsWith("javax/") || pName.startsWith("sun/") ||
	 * pName.startsWith("com/ibm/wala")){ return false; } else {
	 * System.out.println("---" + pName); return true; } } } return true; }
	 * 
	 * 
	 * for (CGNode cgnode : callgraph) {
			IClass c = cgnode.getMethod().getDeclaringClass();
			String classname = c.getName().toString();
			if (appClassesSet.contains(classname)){
				IR ir = cgnode.getIR();
				if (ir != null) {
					System.out.println("------------------");
					System.out.println(classname);
					System.out.println(cgnode.getMethod());
					for (Iterator<SSAInstruction> iter = ir.iterateAllInstructions() ; iter.hasNext();) {
						SSAInstruction i = iter.next();
						
					    IBytecodeMethod method = (IBytecodeMethod)ir.getMethod();
					    if (i.iIndex() > 0) {
					    	int bytecodeIndex = method.getBytecodeIndex(i.iIndex());
						    int sourceLineNum = method.getLineNumber(bytecodeIndex);
						    System.out.println(i);
						    System.out.println(bytecodeIndex + "," + sourceLineNum);
					    }
					}
				}
			}
		}
	 */
	
	
}
