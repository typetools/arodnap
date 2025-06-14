package main;

import java.util.*;

import com.ibm.wala.analysis.typeInference.TypeInference;
import com.ibm.wala.classLoader.IBytecodeMethod;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IField;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ssa.SSAArrayLoadInstruction;
import com.ibm.wala.ssa.SSAArrayStoreInstruction;
import com.ibm.wala.ssa.SSACheckCastInstruction;
import com.ibm.wala.ssa.SSAConditionalBranchInstruction;
import com.ibm.wala.ssa.SSAGetInstruction;
import com.ibm.wala.ssa.SSAInstanceofInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAInvokeInstruction;
import com.ibm.wala.ssa.SSAMonitorInstruction;
import com.ibm.wala.ssa.SSANewInstruction;
import com.ibm.wala.ssa.SSAPhiInstruction;
import com.ibm.wala.ssa.SSAPutInstruction;
import com.ibm.wala.ssa.SSAReturnInstruction;
import com.ibm.wala.types.FieldReference;
import com.ibm.wala.types.TypeReference;

import sourceFixStrategies.FixUtils;
import utils.CommonUtils;
import utils.Pair;
import utils.ProgramInfo;
import utils.ResourceEscapeType;
import utils.Warning;



public class ResourceClassification {
	// The warning for which we are doing the classification.
	Warning warning;
	// Variables visited in the demand driven analysis. This is to
	// avoid cycles in the demand-driven analysis.
	HashSet<String> visitedVariables;
	HashSet<String> visitedDefintions;

	public ResourceClassification(Warning w) {
		warning = w;
		visitedVariables = new HashSet<String>();
		visitedDefintions = new HashSet<String>();
	}

	/* We classify the warning using a demand-driven analysis.
	 * We only do constraint-generation,
	 * and that itself is the Reachability query: this is sufficient
	 * for classifying the resource into the 4 resource-escape-types.
	 * (The downside is that we may recompute the same edge for
	 * multiple warnings). (Don't need any constraint-solving)
	 */
	public void classifyWarning() {
//		if (warning.isNonFinalFieldOverwrite && !warning.matchedCgnode.getMethod().isInit()
//				&& warning.matchedInstruction instanceof SSANewInstruction) {
//			boolean privateField = handleNonFinalOwningOverwrite();
//			if (privateField) {
//				return;
//			}
//		}
		// Do an escape analysis on the matched resource.
		int variableNumber = warning.matchedInstruction.getDef();
		makeEscapeAnalysisQuery(warning.matchedCgnode,variableNumber);

		// Make additional queries on resource aliases
		List<Integer> resourceAliases = ResourceAliasIdentification.getAllResourceAliases(warning.matchedCgnode, warning.matchedInstruction.getDef());
		for (int alias : resourceAliases) {
			makeEscapeAnalysisQuery(warning.matchedCgnode,alias);
		}

		// Check if the resource escapes via an input parameter
		checkForParameterEscape();

		// If the warning is given at an invoke statement, we should also check
		// where the return value gets its value from
		checkDefinitionsForEscape(warning.matchedCgnode, warning.matchedInstruction.getDef());
	}

	/* Checks where the definition of this variable ever gets its value from.
	 * It helps give the complete escape information
	 */
	private void checkDefinitionsForEscape(CGNode cgnode, int variableNumber) {
		// Record visited variables so that you don't visit them again.
		String fullVariableName = cgnode.getMethod().getSignature() + "#" + variableNumber;
		if (visitedDefintions.contains(fullVariableName)) {
			return;
		} else {
			visitedDefintions.add(fullVariableName);
		}

		SSAInstruction defIns = cgnode.getDU().getDef(variableNumber);
		if (defIns instanceof SSAArrayLoadInstruction) {
			warning.escapeTypes.add(ResourceEscapeType.ARRAY);
		}
		else if (defIns instanceof SSACheckCastInstruction) {
			SSACheckCastInstruction castCheckIns = (SSACheckCastInstruction) defIns;
			checkDefinitionsForEscape(cgnode,castCheckIns.getResult());
		}
		else if (defIns instanceof SSAGetInstruction) {
			if (CommonUtils.checkIfResourceClass(((SSAGetInstruction)defIns).getDeclaredFieldType())) {
				warning.escapeTypes.add(ResourceEscapeType.FIELD_SOURCE);
				// Some debugging print
				System.out.println("For warning: " + warning);
				System.out.println("[FIELD SOURCE] At source: " + cgnode.getMethod().getDeclaringClass() + ", Line: " + FixUtils.getSourceLine(defIns, cgnode));

			}
		}
		else if (defIns instanceof SSAPhiInstruction) {
			SSAPhiInstruction phiIns = (SSAPhiInstruction) defIns;
			for (int v = 0; v < phiIns.getNumberOfUses() ; v++) {
				checkDefinitionsForEscape(cgnode, phiIns.getUse(v));
			}
		}
		else if (defIns instanceof SSAInvokeInstruction) {
			SSAInvokeInstruction invokeIns = (SSAInvokeInstruction) defIns;
			// Make a recursive call to all the called methods. Query the return value.
			for (CGNode target : ProgramInfo.getTargets(cgnode, invokeIns.getCallSite())) {
				for (SSAInstruction ins : target.getIR().getInstructions()) {
					if (ins instanceof SSAReturnInstruction) {
						SSAReturnInstruction returnIns = (SSAReturnInstruction) ins;
						if (!returnIns.returnsVoid()) {
							checkDefinitionsForEscape(target, returnIns.getResult());
						}
					}
				}

			}
		}
		else if (defIns instanceof SSANewInstruction) {
			// do nothing
		}
		else if (defIns == null) {  // If defined from say a paramter
			// make a query on all corresponding parameters
			if (variableNumber <= cgnode.getMethod().getNumberOfParameters()) { // this is a null value.
				// Corner case
				if (!ProgramInfo.callersMap.containsKey(cgnode) ) {
					return;
				}
				// Normal case
				for (Pair<CGNode, SSAInvokeInstruction> caller : ProgramInfo.callersMap.get(cgnode)) {
					int argumentNum = variableNumber - 1;
					checkDefinitionsForEscape(caller.fst, caller.snd.getUse(argumentNum));
				}
			} else {
				// variable gets its value from a "null". Nothing to do here.
			}
		}
		else {
			// Error: we should have covered all possible cases by now.
			System.out.println("WARNING: Unknown instruction in def escape check(" + defIns.getClass() + "): " + defIns);
			System.out.println("Warning:" + warning.sourceFilename + "," + warning.lineNumber);
		}

		// Also need to check the uses for escape.
		makeEscapeAnalysisQuery(cgnode, variableNumber);
	}

	/*
	 *  Checks if one of the aliases of the resource escapes via an input parameter
	 */
	private void checkForParameterEscape() {
		List<Integer> resourceAliases = ResourceAliasIdentification.getAllResourceAliases(warning.matchedCgnode, warning.matchedInstruction.getDef());
		for (int aliasVariableNum : resourceAliases) {
			if (aliasVariableNum <= warning.matchedCgnode.getMethod().getNumberOfParameters()) {
				// If an alias is a parameter, add a parameter escape type
				warning.escapeTypes.add(ResourceEscapeType.PARAM);
				// Make a recursive call to the caller methods.
				ArrayList<Pair<CGNode, SSAInvokeInstruction>> callers
						= ProgramInfo.callersMap.get(warning.matchedCgnode);
				if (callers != null) {  // if this function has any callers
					for (Pair<CGNode, SSAInvokeInstruction> caller : callers) {
						warning.parameterAlias = aliasVariableNum;
						int argumentNumber = aliasVariableNum - 1;
						int argumentVariableNumber = caller.snd.getUse(argumentNumber);
						makeEscapeAnalysisQuery(caller.fst, argumentVariableNumber);
					}
				}
			}
		}
	}

	/*
	 * Makes a demand-driven query (in the forward direction.i.e. looking at uses)
	 * for the escape analysis. Takes 3 main inputs
	 * 1) The variable to track: Given by the class, cgnode and variableNumber
	 * 2) escapeTypes: an accumulator for the kinds of escapes..
	 * 3) depth: How deep the query has gone.
	 */
	private void makeEscapeAnalysisQuery(CGNode cgnode, int variableNumber) {
		// Record visited variables so that you don't visit them again.
		String fullVariableName = cgnode.getMethod().getSignature() + "#" + variableNumber;
		if (visitedVariables.contains(fullVariableName)) {
			return;
		} else {
			visitedVariables.add(fullVariableName);
		}
		java.util.Iterator<SSAInstruction> useIterator = cgnode.getDU().getUses(variableNumber);
		while (useIterator.hasNext()) {
			SSAInstruction nextInstruction = useIterator.next();

			if (nextInstruction instanceof SSAArrayStoreInstruction) {
				warning.escapeTypes.add(ResourceEscapeType.ARRAY);
			}
			else if (nextInstruction instanceof SSACheckCastInstruction) {
				SSACheckCastInstruction castCheckIns = (SSACheckCastInstruction) nextInstruction;
				makeEscapeAnalysisQuery(cgnode,castCheckIns.getDef());
			}
			else if (nextInstruction instanceof SSAPutInstruction) {
				SSAPutInstruction putIns = (SSAPutInstruction) nextInstruction;
				int rhs = putIns.getVal();
				if (rhs == variableNumber) {
					warning.escapeTypes.add(ResourceEscapeType.FIELD);
					System.out.println("For warning: " + warning);
					System.out.println("[FIELD] At source: " + cgnode.getMethod().getDeclaringClass() + ", Line: " + FixUtils.getSourceLine(putIns, cgnode));
				}
			}
			else if (nextInstruction instanceof SSAPhiInstruction) {
				SSAPhiInstruction phiIns = (SSAPhiInstruction) nextInstruction;
				makeEscapeAnalysisQuery(cgnode,phiIns.getDef());
			}
			else if (nextInstruction instanceof SSAInvokeInstruction) {
				SSAInvokeInstruction invokeIns = (SSAInvokeInstruction) nextInstruction;
				// First deal with the special case where the invoke instruction
				// an init function (constructor) of a resource-alias for the variable.
				boolean isAlias = false;
				List<Integer> resourceAliases = ResourceAliasIdentification.getAllResourceAliases(cgnode, variableNumber);
				for (int aliasVariableNum : resourceAliases) {
					if (warning.matchedCgnode == cgnode && aliasVariableNum == invokeIns.getReceiver()) {
						isAlias = true;
						break;
					}
				}
				if (isAlias) {
					continue;
				}
				// Further try to identify resource aliases recursively
				ResourceAliasIdentification.computeWarningResourceAliases(cgnode, variableNumber);

				// Second, deal with the special case where this is the
				// constructor of the resource from the warning.
				Set<CGNode> possibleTargets = ProgramInfo.callgraph.getPossibleTargets(cgnode, invokeIns.getCallSite());
				if (possibleTargets.size() == 1) {
					CGNode target = new ArrayList<>(possibleTargets).get(0);
					boolean invokeInsIsInit = target.getMethod().isInit();
					boolean initForSameResource = (invokeIns.getUse(0)==variableNumber);
					if (invokeInsIsInit && initForSameResource) {
						continue;
					}
				}

				// Third, deal with the special case where this goes to a collection
				if (CommonUtils.isCollectionOrMapMethod(cgnode,invokeIns)) {
					warning.escapeTypes.add(ResourceEscapeType.ARRAY);
					continue;
				}

				// Next, deal with the regular case.
				warning.escapeTypes.add(ResourceEscapeType.INVOKE);

				// Make a recursive call to all the called methods.
				int parameterNumber = CommonUtils.getParameterNumber(cgnode,invokeIns,variableNumber,true);
				for (CGNode target : ProgramInfo.getTargets(cgnode, invokeIns.getCallSite())) {
					makeEscapeAnalysisQuery(target,parameterNumber+1);
					makeEscapeAnalysisQueryOnResourceAliases(target,parameterNumber+1);
				}
			}
			else if (nextInstruction instanceof SSAReturnInstruction) {
				String originalCGnode = warning.matchedCgnode.getMethod().getSignature();
				if (cgnode.getMethod().getSignature().equalsIgnoreCase(originalCGnode)){
					// If this is a return from the same method as the warning.
					warning.escapeTypes.add(ResourceEscapeType.RETURN);
				}

				// Make a recursive call to the returned methods.
				ArrayList<Pair<CGNode, SSAInvokeInstruction>> callers
						= ProgramInfo.callersMap.get(cgnode);
				if (callers != null) {  // if this function has any callers
					for (Pair<CGNode, SSAInvokeInstruction> caller : callers) {
						makeEscapeAnalysisQuery(caller.fst, caller.snd.getDef());
					}
				}
			}
			else if (nextInstruction instanceof SSAGetInstruction) {
				// do nothing
			}
			else if (nextInstruction instanceof SSAConditionalBranchInstruction) {
				// do nothing
			}
			else if (nextInstruction instanceof SSAInstanceofInstruction) {
				// do nothing
			}
			else if (nextInstruction instanceof SSAMonitorInstruction) {
				// do nothing
			}
			else {
				// Error: we should have covered all possible cases by now.
				System.out.println("WARNING: Unknown instruction(" + nextInstruction.getClass() + "): " + nextInstruction);
				System.out.println("Warning:" + warning.sourceFilename + "," + warning.lineNumber);
			}
		}


	}

	private void makeEscapeAnalysisQueryOnResourceAliases(CGNode cgnode, int variableNumber) {
		// Identify aliases
		ResourceAliasIdentification.computeWarningResourceAliases(cgnode, variableNumber);

		// Make additional queries on resource aliases
		List<Integer> resourceAliases = ResourceAliasIdentification.getAllResourceAliases(cgnode, variableNumber);
		for (int alias : resourceAliases) {
			makeEscapeAnalysisQuery(cgnode,alias);
		}
	}

	private boolean handleNonFinalOwningOverwrite() {
		int def = warning.matchedInstruction.getDef();
		IClass iClass = warning.matchedCgnode.getMethod().getDeclaringClass();
		String className = ProgramInfo.appClassesMapReverse.get(iClass);
		Iterator<SSAInstruction> uses = warning.matchedCgnode.getDU().getUses(def);
		FieldReference matchedFieldRef = null;
		while (uses.hasNext()) {
			SSAInstruction use = uses.next();
			if (use instanceof SSAPutInstruction) {
				SSAPutInstruction put = (SSAPutInstruction) use;
				// This stores the allocated value to a field — now check if the field is private
				FieldReference fieldRef = put.getDeclaredField();
				IField field = iClass.getField(fieldRef.getName());
				if (field != null && field.isPrivate()) {
					matchedFieldRef = fieldRef;
					warning.owningFieldName = matchedFieldRef.getName().toString();
					break;
				}
			}
		}
		if (matchedFieldRef == null) {
			return false;
		}
		boolean allAssignmentsAreNew = true;
		List<Pair<CGNode,SSAInstruction>> initialWorklist = new ArrayList<>();
		// Run the escape analysis on all the field reads
		for (CGNode node : ProgramInfo.appMethodsMap.get(className)) {
			for (SSAInstruction ins : node.getIR().getInstructions()) {
				if (ins instanceof  SSAPutInstruction) {
					SSAPutInstruction putIns = (SSAPutInstruction) ins;
					if (putIns.getDeclaredField().equals(matchedFieldRef)) {
						int assignedVal = putIns.getVal();
						SSAInstruction defInstr = node.getDU().getDef(assignedVal);
						if (defInstr == null) {
							continue;
						}
						if (!(defInstr instanceof SSANewInstruction)) {
							allAssignmentsAreNew = false;
							break;
						}
					}
				}
				if (ins instanceof SSAGetInstruction) {
					SSAGetInstruction getIns = (SSAGetInstruction) ins;
					if (getIns.getDeclaredField().equals(matchedFieldRef)) {
						initialWorklist.add(new Pair<>(node, ins));
					}
				}
			}
		}
		if (!allAssignmentsAreNew) {
			return false;
		}
		// Step 3: Perform the escape analysis
		for (Pair<CGNode,SSAInstruction> p : initialWorklist) {
			// Create a dummy warning object
			Warning dummy = new Warning(iClass, p.fst, p.snd);
			ResourceClassification resourceClassification = new ResourceClassification(dummy);
			resourceClassification.classifyWarning();
			if (dummy.escapeTypes.contains(ResourceEscapeType.ARRAY) || dummy.escapeTypes.contains(ResourceEscapeType.FIELD)) {
				// found an escape, so not a wrapper
				return false;
			}
		}
		return  true;
	}
}
