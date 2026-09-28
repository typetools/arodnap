package sourceFixStrategies;

import java.util.ArrayList;

import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ssa.SSAInvokeInstruction;

import main.ResourceAliasIdentification;
import main.ResourceClassification;
import main.ResourceLeakFixing;
import utils.Pair;
import utils.ProgramInfo;
import utils.Warning;

public class ReturnFix {

	public static void computeReturnFix(Warning w){
		// Create a fake warning at each caller, and use the fake-warnings' fixes.
		if (!ProgramInfo.callersMap.containsKey(w.matchedCgnode)) {
			if (ProgramInfo.printWarnings) {
				System.out.println("WARNING: No callers found for method with resource return:" + w.matchedCgnode.getMethod().getSignature());
				w.sourceLevelFixes.add("Nothing to be done. No callers found for method with resource return");
			}
			return;
		}
		for(Pair<CGNode, SSAInvokeInstruction> caller : ProgramInfo.callersMap.get(w.matchedCgnode)) {	
			Warning fakeWarning = new Warning(caller.fst.getMethod().getDeclaringClass(), caller.fst, caller.snd);
			// Get aliases
			ArrayList<Warning> fakeWarningsArray = new ArrayList<Warning>();
			fakeWarningsArray.add(fakeWarning);
			ResourceAliasIdentification.identifyWrappers(fakeWarningsArray);
			// Classify and fix it.
			new ResourceClassification(fakeWarning).classifyWarning();
			ResourceLeakFixing.computeSourceCodeFix(fakeWarning);
			if (fakeWarning.unfixable) {
				w.unfixable = true;
			}
			w.comments += fakeWarning.comments;
			w.sourceLevelFixes.addAll(fakeWarning.sourceLevelFixes);
			w.escapeTypes.addAll(fakeWarning.escapeTypes);
		}
	}

	

}
