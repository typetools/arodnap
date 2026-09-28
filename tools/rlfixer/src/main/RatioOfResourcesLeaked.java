package main;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.cha.ClassHierarchyException;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSANewInstruction;
import com.ibm.wala.util.CancelException;
import com.ibm.wala.util.io.CommandLine;

import sourceFixStrategies.FixUtils;
import utils.CommonUtils;
import utils.ProgramInfo;
import utils.Warning;

public class RatioOfResourcesLeaked {

	public static void main(String[] args) throws ClassHierarchyException, IllegalArgumentException, IOException, CancelException {
		// Read command line arguments and initialize program info.
		Properties p = CommandLine.parse(args);
		String classpath = p.getProperty("classpath");
		String warningsString = p.getProperty("warnings");
		String appClassesFile = p.getProperty("appClasses");
		String srcFilesList = p.getProperty("srcFiles");
		String projectDir = p.getProperty("projectDir");

		ProgramInfo.initializeProgramInfo(classpath, null, appClassesFile,srcFilesList,projectDir,null);
		// Find the bytecode instructions for the warning messages.
		List<Warning> warningsList = new ArrayList<Warning>();
		List<Warning> unmatchedWarnings = new ArrayList<Warning>();
		Main.parseWarnings(warningsString, warningsList, unmatchedWarnings);
		Main.sanityCheckOnWarnings(warningsList);
		
		// Identify wrappers for resources in the warnings.
		for (ArrayList<CGNode> methodLists : ProgramInfo.appMethodsMap.values() ) {
			for (CGNode cgnode : methodLists){
				for (SSAInstruction ins : cgnode.getIR().getInstructions()) {
					if (CommonUtils.isNewResourceStatement(ins)) {
						ResourceAliasIdentification.computeWarningResourceAliases(cgnode,ins.getDef());
						ResourceAliasIdentification.computePointerAliases(cgnode,ins.getDef());
					}
				}
			}
		}
		
		// Keep track of the variables we counted, in order to avoid double counts
		HashSet<String> visitedVariables = new HashSet<String>();
		// Iterate through the new resource instructions and just count
		// how many got repeated.
		int totalResources = 0;
		int resourcesLeaksReported = 0;
		for (ArrayList<CGNode> methodLists : ProgramInfo.appMethodsMap.values() ) {
			for (CGNode cgnode : methodLists){
				for (SSAInstruction ins : cgnode.getIR().getInstructions()) {
					if (CommonUtils.isNewResourceStatement(ins)) {
						// Skip if resource it's already reported
						boolean duplicateResource = false;
						List<Integer> resourceAliases = ResourceAliasIdentification.getAllResourceAliases(cgnode, ins.getDef());
						resourceAliases.add(ins.getDef());
						for (int alias : resourceAliases) {
							String aliasId = CommonUtils.getVariableId(cgnode, alias);
							if (visitedVariables.contains(aliasId)) {
								duplicateResource = true;
							}
						}
						if (duplicateResource) {
							continue;
						}
						// Record this as a resource.
						String variableId = CommonUtils.getVariableId(cgnode,ins.getDef());
						visitedVariables.add(variableId);
						totalResources += 1;
						System.out.println(cgnode.getMethod().getSignature());
						
						// Go through the warnings to see if it matches any of them.
						int instructionSrcLine = FixUtils.getSourceLine(ins, cgnode); 
						Warning matchedWarning = null;
				 label: for (Warning w : warningsList) {
							for (int i = -2; i < 3; i++) {
								if (w.lineNumber + i == instructionSrcLine) {
									resourcesLeaksReported += 1;
									matchedWarning = w;
									break label;
								}
							}
							
						}
						if (matchedWarning != null) {
							warningsList.remove(matchedWarning);
						}
					}
					
					
				}
			}
		}
		
		System.out.println(resourcesLeaksReported);
		System.out.println(totalResources);
	}

}
