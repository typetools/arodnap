package sourceFixStrategies;

import java.util.ArrayList;

import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAInvokeInstruction;

import main.ResourceAliasIdentification;
import main.ResourceClassification;
import main.ResourceLeakFixing;
import utils.Pair;
import utils.ProgramInfo;
import utils.Warning;

public class ParamFix {
	public static void computeParameterFix(Warning w) {
		// Create a fake warning at each caller, and use the fake-warnings' fixes.
		if (!ProgramInfo.callersMap.containsKey(w.matchedCgnode)) {
			if (ProgramInfo.printWarnings) {
				System.out.println("WARNING: No callers found for method with parameter return:" + w.matchedCgnode.getMethod().getSignature());
				w.sourceLevelFixes.add("Nothing to be done. No callers found for method with resource return");
			}
			return;
		}
		createFakeWarnings(w, w.matchedCgnode, w.parameterAlias - 1);
		
	}

	/*
	 * Creates a fake warning and tries to fix it.
	 * Takes as input, the original warning, and the cgnode+paramIndex where
	 * we need to create the fake warning for.
	 */
	private static void createFakeWarnings(Warning warning, CGNode cgnode, int parameterIndex) {
		// Corner case
		if (!ProgramInfo.callersMap.containsKey(cgnode) ) {
			return;
		}
		// Normal case.
		for(Pair<CGNode, SSAInvokeInstruction> caller : ProgramInfo.callersMap.get(cgnode)) {	
			if (caller.fst == cgnode) {
				continue;
			}
			// Create the fake warning
			int argumentVariableNumber = caller.snd.getUse(parameterIndex);
			SSAInstruction fakeWarningInstruction = caller.fst.getDU().getDef(argumentVariableNumber);
			if (fakeWarningInstruction == null) { // deal with the corner case where there is no definition 
				// if the variable gets its value directly from a parameter send a recursive call upwards.
				if (argumentVariableNumber <= cgnode.getMethod().getNumberOfParameters()) { 
					createFakeWarnings(warning, caller.fst, argumentVariableNumber - 1);
				} else {
					// variable gets its value from a "null". Nothing to do here.
				}
				return;
			}
			Warning fakeWarning = new Warning(caller.fst.getMethod().getDeclaringClass(), caller.fst,fakeWarningInstruction);
			
			// Identify aliases
			ArrayList<Warning> fakeWarningsArray = new ArrayList<Warning>();
			fakeWarningsArray.add(fakeWarning);
			ResourceAliasIdentification.identifyWrappers(fakeWarningsArray);
			
			// classify and fix it.
			new ResourceClassification(fakeWarning).classifyWarning();
			ResourceLeakFixing.computeSourceCodeFix(fakeWarning);
			if (fakeWarning.unfixable) {
				warning.unfixable = true;
			}
			warning.comments += fakeWarning.comments;
			warning.sourceLevelFixes.addAll(fakeWarning.sourceLevelFixes);
			warning.escapeTypes.addAll(fakeWarning.escapeTypes);
		}
	}
}