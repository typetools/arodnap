package sourceFixStrategies;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;

import com.ibm.wala.classLoader.IBytecodeMethod;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.shrikeCT.InvalidClassFileException;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAInvokeInstruction;

import main.ResourceAliasIdentification;
import utils.CommonUtils;
import utils.Pair;
import utils.ProgramInfo;
import utils.Warning;

public class RemoveExistingClosesFix{
	Warning w;
	IR ir;
	private static HashSet<String> visitedVariables;
	
	public RemoveExistingClosesFix(Warning a) {
		w = a;
		ir = w.matchedCgnode.getIR();
	}

	public void removeCloses() {
		visitedVariables = new HashSet<String>();
		List<Integer> resourceAliases = ResourceAliasIdentification.getAllResourceAliases(w.matchedCgnode, w.matchedInstruction.getDef());
		resourceAliases.add(w.matchedInstruction.getDef());

		for (int aliasVariableNum : resourceAliases) {
			removeClosesRecursively(w.matchedCgnode, aliasVariableNum);
		}
	}
	
	private void removeClosesRecursively(CGNode cgnode, Integer variableNumber) {
		// Make sure we avoid revisiting the same variable.
		String variableId = CommonUtils.getVariableId(cgnode, variableNumber);
		if (visitedVariables.contains(variableId)) {
			return;
		} else {
			visitedVariables.add(variableId);
		}
		
		// For each use of the variable, check if it is a close instruction.
		// Else follow it recursively to remove other close instructions.
		Iterator<SSAInstruction> useInstructionsIterator = cgnode.getDU().getUses(variableNumber);
		while(useInstructionsIterator.hasNext()) {
			SSAInstruction useInstruction = useInstructionsIterator.next();
			if (useInstruction instanceof SSAInvokeInstruction) {
				SSAInvokeInstruction invokeIns = (SSAInvokeInstruction) useInstruction;
				if (CommonUtils.isCloseMethod(invokeIns.getDeclaredTarget())) {
					IBytecodeMethod<?> method = (IBytecodeMethod<?>)cgnode.getMethod();
					try {
						int bytecodeIndex = method.getBytecodeIndex(invokeIns.iIndex());
						int sourceLineNum = method.getLineNumber(bytecodeIndex);
						String line = "Delete Line number " + sourceLineNum + " (" + w.sourceFilename + ")\n";
						w.sourceLevelFixes.add(line);
					} catch (InvalidClassFileException e) {
						System.out.println("ERROR: Invalid class file exception.");
					}
				} else {
					for (CGNode target : ProgramInfo.getTargets(cgnode, invokeIns.getCallSite())) {
						int paramNumber = CommonUtils.getParameterNumber(cgnode,invokeIns,variableNumber,true);
						removeClosesRecursively(target, paramNumber+1);
					}
					
				}
			}
		}
	}

}
