package main;

import java.util.HashSet;
import java.util.List;

import com.ibm.wala.ipa.callgraph.CGNode;

import utils.CommonUtils;
import utils.Pair;
import utils.Warning;

public class DuplicateWarningIdentification {

	public static void identifyDuplicateWarnings(List<Warning> matchedWarnings) {
		HashSet<String> variableIDs = new HashSet<String>();
		for (Warning w : matchedWarnings) {
			String variableID = CommonUtils.getVariableId(w.matchedCgnode, w.matchedInstruction.getDef());
			// The the same variable-id is reported again it is a duplicate
			if (variableIDs.contains(variableID)) {
				w.isDuplicateWarning = true;
				continue;
			} else {
				variableIDs.add(variableID);
			}
			// If any of its aliases is reported again it is a duplicate.
			List<Integer> resourceAliases = ResourceAliasIdentification.getAllResourceAliases(w.matchedCgnode, w.matchedInstruction.getDef());
			for (int aliasVariableNum : resourceAliases) {
				String aliasID = CommonUtils.getVariableId(w.matchedCgnode,aliasVariableNum);
				if (variableIDs.contains(aliasID)) {
					w.isDuplicateWarning = true;
					break;
				}
			}
		}
	}

}
