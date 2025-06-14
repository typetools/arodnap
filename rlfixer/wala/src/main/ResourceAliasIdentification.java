package main;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.Iterator;
import java.util.LinkedList;

import com.ibm.wala.analysis.typeInference.TypeInference;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.SSACheckCastInstruction;
import com.ibm.wala.ssa.SSAGetInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAInvokeInstruction;
import com.ibm.wala.ssa.SSAPhiInstruction;
import com.ibm.wala.ssa.SSAPutInstruction;
import com.ibm.wala.ssa.SSAReturnInstruction;
import com.ibm.wala.types.FieldReference;
import com.ibm.wala.types.TypeReference;

import utils.*;

/**
 * Provides functionality to identify all resource wrapper pairs.
 *
 */
public class ResourceAliasIdentification {
	// Key = Full variable name, represented as 'method-signature#variableNumber'
	// Value = Arraylist of resource aliases (wrappers and wrappee variables) in the same method
	// (Note: Doesn't cover transitive aliases. Transitive aliases need to be computed.)
	private static HashMap<String,ArrayList<Integer>> resourceAliasesMap
		= new HashMap<String,ArrayList<Integer>>();
	private static HashSet<String> visitedVariables = new HashSet<String>();
	private static HashSet<String> visitedPointers = new HashSet<String>();
	private static HashMap<String, Boolean> visitedWrappedClasses = new HashMap<>();
	
	
	public static void identifyWrappers(List<Warning> warnings) {	
		for (Warning w : warnings) {
			computeWarningResourceAliases(w.matchedCgnode,w.matchedInstruction.getDef());
		}

		for (Warning w : warnings) {
			computePointerAliases(w.matchedCgnode,w.matchedInstruction.getDef());
			for (int aliasVariableNum : getAllResourceAliases(w.matchedCgnode,w.matchedInstruction.getDef())) {
				computePointerAliases(w.matchedCgnode, aliasVariableNum);
			}
		}
	}
	
	/*
	 * Computes aliases using the usual demand-driven pointer analysis.
	 * Doesn't compute inter-procedural aliases. We won't need those as of now.
	 */
	public static void computePointerAliases(CGNode cgnode, int variableNumber) {		
		// First check that we are not revisiting the same variable
		String variableId = CommonUtils.getVariableId(cgnode, variableNumber);
		if (ResourceAliasIdentification.visitedPointers.contains(variableId)) {
			return;
		} else {
			ResourceAliasIdentification.visitedPointers.add(variableId);
		}
		
		// Otherwise just find aliases by looking at uses
		java.util.Iterator<SSAInstruction> useIterator = cgnode.getDU().getUses(variableNumber);
		while (useIterator.hasNext()) {
			SSAInstruction useInstruction = useIterator.next();			
			if (useInstruction instanceof SSAPhiInstruction ||
					useInstruction instanceof SSACheckCastInstruction) {
				// Set the alias in the aliases map.
				recordWrapperAndWrappee(cgnode, useInstruction.getDef(),variableNumber);
				// Also recursively search for aliases.
				computePointerAliases(cgnode, useInstruction.getDef());
			}
		}
	}

	public static void computeWarningResourceAliases(CGNode cgnode, int variableNumber) {
		// Make sure we avoid revisiting the same variable.
		String variableId = CommonUtils.getVariableId(cgnode, variableNumber);
		if (ResourceAliasIdentification.visitedVariables.contains(variableId)) {
			return;
		} else {
			ResourceAliasIdentification.visitedVariables.add(variableId);
		}
				
		// Part 1: Check if the Resource from warning 'w' is wrapper 
		// for some other resource.
		checkIfResourceIsWrapper(cgnode, variableNumber);
					
		// Part 2: Check if Resource from warning 'w' is wrapped
		// by some other resource.
		checkIfResourceIsWrapped(cgnode, variableNumber);
	}

	
	private static void checkIfResourceIsWrapped(CGNode cgnode, int variableNumber) {
		java.util.Iterator<SSAInstruction> useIterator = cgnode.getDU().getUses(variableNumber);
		while (useIterator.hasNext()) {
			SSAInstruction useInstruction = useIterator.next();			
			if (useInstruction instanceof SSAInvokeInstruction) {
				SSAInvokeInstruction invokeIns = (SSAInvokeInstruction) useInstruction;
				
				// Check 1: the invoke should be an init function 
				Set<CGNode> possibleTargets = ProgramInfo.callgraph.getPossibleTargets(cgnode, invokeIns.getCallSite());
				if (possibleTargets.size() != 1) {
					System.out.println("ERROR: Multiple targets for invoke instruction:" + invokeIns);
					continue; // not an init function
				}
				CGNode target = new ArrayList<>(possibleTargets).get(0);
				boolean invokeInsIsInit = target.getMethod().isInit();
				
				// Check 2: the first parameter should be a resource.				
				TypeInference ti = TypeInference.make(cgnode.getIR(), false);
				TypeReference firstParamType = ti.getType(invokeIns.getUse(0)).getTypeReference();

				boolean firstParamIsResource = (firstParamType!=null) && CommonUtils.checkIfResourceClass(firstParamType);

				// Check 3: make sure that we are looking at the init function of a different resource.
				boolean initForDifferentResource = (invokeIns.getUse(0)!=variableNumber);

				
				// Pre-emptive check to see if we have a potential wrapper
				// This is done to avoid the expensive checkWrapperCondition() call
				// SM: removed the firstParamIsResource flag from the next if condition
				if (!(invokeInsIsInit && initForDifferentResource)) {
					continue;
				}
				
				// Check 4: Finally, make the wrapper condition check, 
				// and insert into the map if true.
				int originalResourceIndex = CommonUtils.getParameterNumber(cgnode,invokeIns,variableNumber,false);
				if (originalResourceIndex != -1) {
					if (checkWrapperCondition(target, originalResourceIndex, true)) {
						// Set the wrapper in the wrapper map.
						recordWrapperAndWrappee(cgnode, invokeIns.getReceiver(),variableNumber);
						
						// Recursively perform wrapper/wrappee checking
						computeWarningResourceAliases(cgnode,invokeIns.getReceiver());
					}
				}
			}
		}
	}

	private static void checkIfResourceIsWrapper(CGNode cgnode, int variableNumber) {
		// TODO: Not sure if we can skip this check.
		//if (w.matchedInstruction instanceof SSANewInstruction) {

		// First find the constructor for this new object.
		java.util.Iterator<SSAInstruction> useIterator = cgnode.getDU().getUses(variableNumber);
		while (useIterator.hasNext()) {
			SSAInstruction useInstruction = useIterator.next();			
			if (useInstruction instanceof SSAInvokeInstruction) {
				SSAInvokeInstruction invokeIns = (SSAInvokeInstruction) useInstruction;

				// Check that this is the init function (i.e. constructor) corresponding to
				// the new instruction.
				Set<CGNode> possibleTargets = ProgramInfo.callgraph.getPossibleTargets(cgnode, invokeIns.getCallSite());
				if (possibleTargets.size() != 1) {
					System.out.println("ERROR: Multiple targets for invoke instruction:" + invokeIns);
					continue; // not an init function
				}
				CGNode target = new ArrayList<>(possibleTargets).get(0);
				boolean targetIsCorrectInitFunction = 
						target.getMethod().isInit() && invokeIns.getUse(0)==variableNumber;
				if (!targetIsCorrectInitFunction) {
					continue;
				}

				// Check if one of the parameters is a resource. 
				// (start from 1 because 'this' will always be a resource)
				int resourceParameterIndex = -1;
				int resourceParameterVariableNumber = -1;
				for (int i = 1; i < invokeIns.getNumberOfPositionalParameters(); i++) {
					TypeInference ti = TypeInference.make(cgnode.getIR(), false);
					TypeReference tr = ti.getType(invokeIns.getUse(i)).getTypeReference();
					if ( (tr!=null) && CommonUtils.checkIfResourceClass(tr)) {
						if (checkWrapperCondition(target, i, false)) {
							resourceParameterIndex = i;
							resourceParameterVariableNumber = invokeIns.getUse(i);
							break;  // Note: Not allowing for constructors with multiple resource parameters.
						}

					}
				}
				// Set the wrapper in the wrapper map.
				if (resourceParameterIndex >= 0) {
					recordWrapperAndWrappee(cgnode, invokeIns.getReceiver(),resourceParameterVariableNumber);
					// Recursively perform wrapper/wrappee checking
					computeWarningResourceAliases(cgnode,resourceParameterVariableNumber);
				}
			}
		}
	} 
	
	/*
	 * Checks the 3 conditions for a wrapper resource
	 * 1. The resource variable passed as parameter should be assigned to a
	 * field by the end of the init (constructor) function. It could be assigned
	 * in the init function or in one of its callees (like for OutputStreamWriter)
	 * 2. Ideally, the field should 'must-point-to' the resource variable,
	 * but we aren't checking this directly. Instead we will count the number
	 * of field writes and hopefully this is 1. If it is greater than 1, it's probably
	 * not a must-points to (however there is a corner case usage where it could be).
	 * (*) There is an issue with this condition. Sometimes, this field is assigned null
	 * at the end of the close method, and that becomes the second write.
	 * 3. The 'close' function in the class of the init function should
	 * also call close on the field from the first condition.
	 */
	private static boolean checkWrapperCondition(CGNode initFunction, int parameterIndex, boolean isCheckingWrapped) {
		String variableId = CommonUtils.getVariableId(initFunction, parameterIndex);
		if (isCheckingWrapped && visitedWrappedClasses.containsKey(variableId)) {
			return visitedWrappedClasses.get(variableId);
		}
		// Check condition 1.
		FieldReference assignedField = checkWrapperCondition1(initFunction,parameterIndex+1);
		if (assignedField == null) {
			return false;
		}
		
		// Issue with condition 2: Read comments above.
		/* 
		if (ProgramInfo.fieldWritesCount.get(assignedField.getSignature()) > 1) {
			//return false;
		}
		*/
		
		// Find the close function in the wrapper class
		CGNode closeFunction = null;
		IClass wrapperClass = initFunction.getMethod().getDeclaringClass();
		for (IMethod m : wrapperClass.getAllMethods()) {
			if (CommonUtils.isCloseMethod(m.getReference())) { // just the 'this' parameter.
				if (!ProgramInfo.methodCGNodeMap.containsKey(m.getSignature())) {
					return true; // Hack: the close method is not in the call-graph. Adding it causes some Wala Lambda error. So lets go out on a limb and accept this.
				}
				closeFunction = ProgramInfo.methodCGNodeMap.get(m.getSignature());
				break;
			}
		}
		
		// Check condition 3
		if (closeFunction != null && checkFieldCloseCondition(closeFunction, assignedField, new HashSet<String>())) {
			// Step 1: gather all CGNodes (methods) for wrapperClass
			String className = ProgramInfo.appClassesMapReverse.get(wrapperClass);
			List<CGNode> classMethods = ProgramInfo.appMethodsMap.get(className);
			if (classMethods == null || classMethods.isEmpty()) {
				// If the class has no known methods in the call graph, we can't see any usage
				// Typically, we assume "no usage => no escape."
				return true;
			}

			// Step 2: find all 'getfield' instructions referencing assignedField
			List<Pair<CGNode,SSAInstruction>> initialWorklist = new ArrayList<>();
			for (CGNode methodNode : classMethods) {
				IR ir = methodNode.getIR();
				if (ir == null) continue;

				for (SSAInstruction ins : ir.getInstructions()) {
					if (ins instanceof SSAGetInstruction) {
						SSAGetInstruction getIns = (SSAGetInstruction) ins;
						if (getIns.getDeclaredField().equals(assignedField)) {
							initialWorklist.add(new Pair<>(methodNode, getIns));
						}
					}
				}
			}

			// Step 3: Perform the escape analysis
			for (Pair<CGNode,SSAInstruction> p : initialWorklist) {
				// Create a dummy warning object
				Warning dummy = new Warning(wrapperClass, p.fst, p.snd);
				ResourceClassification resourceClassification = new ResourceClassification(dummy);
				resourceClassification.classifyWarning();
				if (dummy.escapeTypes.contains(ResourceEscapeType.ARRAY) || dummy.escapeTypes.contains(ResourceEscapeType.FIELD)) {
					// found an escape, so not a wrapper
					visitedWrappedClasses.put(variableId, false);
					return false;
				}
			}
//			FinalizerMappingLoader.pseudoResourceClasses.add(className);
			visitedWrappedClasses.put(variableId, true);
			return true;
		} else {
			if (!isCheckingWrapped) {
				return false;
			}
			// Step 1: gather all CGNodes (methods) for wrapperClass
			String className = ProgramInfo.appClassesMapReverse.get(wrapperClass);
			List<CGNode> classMethods = ProgramInfo.appMethodsMap.get(className);
			if (classMethods == null || classMethods.isEmpty()) {
				// If the class has no known methods in the call graph, we can't see any usage
				// Typically, we assume "no usage => no escape."
				return true;
			}

			// Step 2: find all 'getfield' instructions referencing assignedField
			List<Pair<CGNode,SSAInstruction>> initialWorklist = new ArrayList<>();
			for (CGNode methodNode : classMethods) {
				IR ir = methodNode.getIR();
				if (ir == null) continue;

				for (SSAInstruction ins : ir.getInstructions()) {
					if (ins instanceof SSAGetInstruction) {
						SSAGetInstruction getIns = (SSAGetInstruction) ins;
						if (getIns.getDeclaredField().equals(assignedField)) {
							initialWorklist.add(new Pair<>(methodNode, getIns));
						}
					}
				}
			}

			// Step 3: Perform the escape analysis
			for (Pair<CGNode,SSAInstruction> p : initialWorklist) {
				// Create a dummy warning object
				Warning dummy = new Warning(wrapperClass, p.fst, p.snd);
				ResourceClassification resourceClassification = new ResourceClassification(dummy);
				resourceClassification.classifyWarning();
				if (dummy.escapeTypes.contains(ResourceEscapeType.ARRAY) || dummy.escapeTypes.contains(ResourceEscapeType.FIELD)) {
					// found an escape, so not a wrapper
					visitedWrappedClasses.put(variableId, false);
					return false;
				}
			}
			FinalizerMappingLoader.pseudoResourceClasses.add(className);
			visitedWrappedClasses.put(variableId, true);
			return true;
		}
	}

	// Check if 'close' is directly called on the required field inside this cgnode
	// or any of its callees. (Not going to check if this is true on every single
	// path, because for classes like 'Socket', close() is called on its
	// wrappee only if the wrappee exists.)
	private static boolean checkFieldCloseCondition(CGNode cgnode, FieldReference assignedField, HashSet<String> visitedMethods) {
		// First ensure that we are not in a recursive call-chain.
		if (visitedMethods.contains(cgnode.getMethod().getSignature())) {
			return false;   // don't want to revisit the node in case of a recursive call-chain.
		} else {
			visitedMethods.add(cgnode.getMethod().getSignature());
		}
		
		// Then do the check by loop through all instructions.
		IR ir = cgnode.getIR();
		if (ir == null) {
			return false;
		}
		Iterator<SSAInstruction> insIterator = ir.iterateAllInstructions();
		while (insIterator.hasNext()) {
			SSAInstruction nextInstruction = insIterator.next();
			// check if the required field gets dereferenced.
			if (nextInstruction instanceof SSAGetInstruction) {
				SSAGetInstruction getIns = (SSAGetInstruction) nextInstruction;
				if (getIns.getDeclaredField().getSignature().equalsIgnoreCase(assignedField.getSignature())) {
					if (checkIfCloseCallMade(cgnode, getIns.getDef(),new HashSet<String>())) {
						return true;
					}
				}
			}
			// otherwise recursively call this function on all callees.
			else if (nextInstruction instanceof SSAInvokeInstruction) {
				SSAInvokeInstruction invokeIns = (SSAInvokeInstruction) nextInstruction;
				for (CGNode target : ProgramInfo.callgraph.getPossibleTargets(cgnode, invokeIns.getCallSite())) {
					if (checkFieldCloseCondition(target,assignedField,visitedMethods)) {
						return true;
					}
				}
			}
		}
		
		// The required close call wasn't made
		return false;
	}

	// Finds if a close call is eventually called on the given variable.
	private static boolean checkIfCloseCallMade(CGNode cgnode, int variableNumber, HashSet<String> visitedVariables) {
		// First ensure that we are not in a recursive call-chain.
		String fullVariableId = cgnode.getMethod().getSignature() + "#" + variableNumber;
		if (visitedVariables.contains(fullVariableId)) {
			return false;   // don't want to revisit the node in case of a recursive call-chain.
		} else {
			visitedVariables.add(fullVariableId);
		}
				
		java.util.Iterator<SSAInstruction> useIterator = cgnode.getDU().getUses(variableNumber);
		while (useIterator.hasNext()) {
			SSAInstruction nextInstruction = useIterator.next();
						
			if (nextInstruction instanceof SSACheckCastInstruction) {
				if (checkIfCloseCallMade(cgnode,nextInstruction.getDef(),visitedVariables)) {
					return true;
				}
			}
			else if (nextInstruction instanceof SSAPhiInstruction) {
				if (checkIfCloseCallMade(cgnode,nextInstruction.getDef(),visitedVariables)) {
					return true;
				}
			}
			else if (nextInstruction instanceof SSAInvokeInstruction) {
				SSAInvokeInstruction invokeIns = (SSAInvokeInstruction) nextInstruction;
				
				// Check if we reached the close call
				if (CommonUtils.isCloseMethod(invokeIns.getCallSite().getDeclaredTarget())) {
					return true;
				}
					
				// Otherwise, make a recursive call to all the called methods.
				int parameterNumber = CommonUtils.getParameterNumber(cgnode,invokeIns,variableNumber,true);
				for (CGNode target : ProgramInfo.getTargets(cgnode, invokeIns.getCallSite())) {
					if (checkIfCloseCallMade(target,parameterNumber+1,visitedVariables)) {
						return true;
					}
				}
			}
		}
		// The required close call wasn't made
		return false;
	}

	// Checks if some field of the class gets assigned to the given variableNumber in the init
	// function or any of its callees.
	// Basically a demand-driven alias query.
	// TODO: I don't know how to do this. Think.
	private static FieldReference checkWrapperCondition1(CGNode initFunction, int variableNumber) {
		// Get all aliases (inside the init function) of the input variable.
		HashSet<String> aliases = getAllAliases(initFunction,variableNumber, new HashSet<String>(),null,null);
		
		return examineFieldPuts(initFunction,aliases,new HashSet<String>());
	}

	/* Examines if any of the field writes use any of the aliases. 
	 * Returns the corresponding field.
	 */
	private static FieldReference examineFieldPuts(CGNode cgnode, HashSet<String> aliases, HashSet<String> visitedMethods) {
		// First ensure that we are not in a recursive call-chain.
		if (visitedMethods.contains(cgnode.getMethod().getSignature())) {
			return null;   // don't want to revisit the node in case of a recursive call-chain.
		} else {
			visitedMethods.add(cgnode.getMethod().getSignature());
		}
		
		IR ir = cgnode.getIR();
		if (ir == null) {
			return null;
		}
		Iterator<SSAInstruction> insIterator = ir.iterateAllInstructions();
		while (insIterator.hasNext()) {
			SSAInstruction nextInstruction = insIterator.next();
			if (nextInstruction instanceof SSAPutInstruction) {
				SSAPutInstruction putIns = (SSAPutInstruction) nextInstruction;
				if (!CommonUtils.checkIfResourceClass(putIns.getDeclaredField().getFieldType())) {
					continue;  // we want this only for fields which are of resource class
					// a good example where the field is not a resource is for Writer
				}
				String rhsVariableId = CommonUtils.getVariableId(cgnode, putIns.getVal());
				if (aliases.contains(rhsVariableId)) {
					return putIns.getDeclaredField();
				}
			}
			else if (nextInstruction instanceof SSAInvokeInstruction) {
				SSAInvokeInstruction invokeIns = (SSAInvokeInstruction) nextInstruction;
				for (CGNode target : ProgramInfo.callgraph.getPossibleTargets(cgnode, invokeIns.getCallSite())) {
					FieldReference matchedField = examineFieldPuts(target, aliases,visitedMethods);
					if (matchedField != null) {
						return matchedField;
					}
				}
			}
		}
		
		// Field was not found.
		return null;
	}

	/*
	 * Computes all aliases of the given variable in this function and in all
	 * callees.
	 */
	private static HashSet<String> getAllAliases(CGNode cgnode, int variableNumber, 
			HashSet<String> visitedVariables, CGNode callerMethod, SSAInvokeInstruction callerInvoke) {
		HashSet<String> aliases = new HashSet<String>();
		String fullVariableId = CommonUtils.getVariableId(cgnode, variableNumber);
		aliases.add(fullVariableId);
		
		// First ensure that we are not in a recursive call-chain.
		if (visitedVariables.contains(fullVariableId)) {
			return aliases;   // don't want to revisit the node in case of a recursive call-chain.
		} else {
			visitedVariables.add(fullVariableId);
		}

		if (cgnode.getIR() == null){
			return aliases;
		}
		java.util.Iterator<SSAInstruction> useIterator = cgnode.getDU().getUses(variableNumber);
		while (useIterator.hasNext()) {
			SSAInstruction nextInstruction = useIterator.next();

			if (nextInstruction instanceof SSACheckCastInstruction) {
				HashSet<String> aliasesFromRecursiveCall = getAllAliases(
						cgnode,nextInstruction.getDef(),visitedVariables,callerMethod, callerInvoke);
				aliases.addAll(aliasesFromRecursiveCall);
			}
			else if (nextInstruction instanceof SSAPhiInstruction) {
				HashSet<String> aliasesFromRecursiveCall = getAllAliases(
						cgnode,nextInstruction.getDef(),visitedVariables,callerMethod, callerInvoke);
				aliases.addAll(aliasesFromRecursiveCall);
			}
			else if (nextInstruction instanceof SSAInvokeInstruction) {
				SSAInvokeInstruction invokeIns = (SSAInvokeInstruction) nextInstruction;

				// Otherwise, make a recursive call to all the called methods.
				int parameterNumber = CommonUtils.getParameterNumber(cgnode,invokeIns,variableNumber,true);
				for (CGNode target : ProgramInfo.callgraph.getPossibleTargets(cgnode, invokeIns.getCallSite())) {
					// add the formal parameter as alias
					String formalParameterId = CommonUtils.getVariableId(target,parameterNumber+1);
					aliases.add(formalParameterId); 
					// check for more aliases in the called methods.
					HashSet<String> aliasesFromRecursiveCall = getAllAliases(
							target,parameterNumber+1,visitedVariables, cgnode, invokeIns);
					aliases.addAll(aliasesFromRecursiveCall);
				}
			}
			else if (nextInstruction instanceof SSAReturnInstruction) {
				if (callerMethod!=null && callerInvoke!=null) {
					HashSet<String> aliasesFromRecursiveCall = getAllAliases(
							callerMethod,callerInvoke.getDef(),visitedVariables,null,null);
					aliases.addAll(aliasesFromRecursiveCall);
				}
			}
		}
		// The required close call wasn't made
		return aliases;
	}

	// Records the pair of wrapper and wrappee in the relevant maps.
	private static void recordWrapperAndWrappee(CGNode cgnode, int wrapperVariableNumber, int baseResourceNumber) {
		String wrapperId = CommonUtils.getVariableId(cgnode,wrapperVariableNumber);
		String baseResourceId = CommonUtils.getVariableId(cgnode,baseResourceNumber);
		// Add to wrapper as alias of base resource
		if (!resourceAliasesMap.containsKey(baseResourceId)) {
			resourceAliasesMap.put(baseResourceId, new ArrayList<Integer>());
		}
		resourceAliasesMap.get(baseResourceId).add(wrapperVariableNumber);
		// Add to base resource as alias for wrapper
		if (!resourceAliasesMap.containsKey(wrapperId)) {
			resourceAliasesMap.put(wrapperId, new ArrayList<Integer>());
		}
		resourceAliasesMap.get(wrapperId).add(baseResourceNumber);
	}
	
	// Returns all resource aliases (transitive wrappers and wrappees) of the
	// the resource from the warning.
	public static List<Integer> getAllResourceAliases(CGNode cgnode, int variableNumber){
		ArrayList<Integer> resourceAliases = new ArrayList<Integer>();
		
		// Collect all transitive aliases of the resource.
		// Done using a breadth first search starting at the warning resource.
		HashSet<Integer> seenResources = new HashSet<Integer>();
		Queue<Integer> bfsQueue = new LinkedList<Integer>();
		bfsQueue.add(variableNumber);
		while (!bfsQueue.isEmpty()) {
			int temp = bfsQueue.remove();
			if (seenResources.contains(temp)) {
				continue;  // skip. Already visited.
			} else {
				seenResources.add(temp);
				if (temp != variableNumber) {
					// As long as it isn't the original resource itself,
					// add it to the list of aliases.
					resourceAliases.add(temp);
				}
			}
			String tempResourceId = CommonUtils.getVariableId(cgnode, temp);
			if (resourceAliasesMap.containsKey(tempResourceId)) {
				for (int alias : resourceAliasesMap.get(tempResourceId)) {
					bfsQueue.add(alias);
				}
			}	
		}
		return resourceAliases;
	}
}
