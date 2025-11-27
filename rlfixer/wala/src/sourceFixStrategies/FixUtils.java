package sourceFixStrategies;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Set;

import com.ibm.wala.classLoader.IBytecodeMethod;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.shrike.shrikeCT.InvalidClassFileException;
import com.ibm.wala.util.WalaException;
import com.ibm.wala.ssa.DefUse;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSACFG;
import com.ibm.wala.ssa.SSAConditionalBranchInstruction;
import com.ibm.wala.ssa.SSAGotoInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAReturnInstruction;

import main.FinalizerMappingLoader;
import main.ResourceAliasIdentification;
import utils.CommonUtils;
import utils.Pair;
import utils.ProgramInfo;
import utils.Warning;

/*
 * Common utility functions which will be needed by several fix strategies.
 */
public class FixUtils {
	/*
	 * Computes which basic blocks are 'live' for the resource given by the warning.
	 
	public static Set<Integer> computeLiveBasicBlocksForResource(Warning w) {
		
		SSACFG cfg = w.matchedCgnode.getIR().getControlFlowGraph();
				
		// For each resource alias, compute all basic blocks it is involved in
		// and store it in the queue
		Queue<ISSABasicBlock> q = new LinkedList<ISSABasicBlock>();
		q.addAll(getBasicBlocksForAllAliasOccurrences(w,cfg));
		
		// All basic blocks reachable (transitively) from any of the basic blocks
		// in the queue using the back edges of the CFG are 'live'. 
		// (done using simple Breadth-first search)
		Set<Integer> liveBasicBlocks = new HashSet<Integer>();
		for (ISSABasicBlock bb : q) {
			liveBasicBlocks.add(bb.getNumber());
		}
		while(!q.isEmpty()) {
			ISSABasicBlock temp = q.remove();
			for (Iterator<ISSABasicBlock> it = cfg.getPredNodes(temp) ; it.hasNext() ; ) {
				ISSABasicBlock predBB = it.next();
				if (!liveBasicBlocks.contains(predBB.getNumber())) {
					liveBasicBlocks.add(predBB.getNumber());
					q.add(predBB);
				}
			}
		}
		return liveBasicBlocks;
	}*/

	
	public static List<ISSABasicBlock> getBasicBlocksForAllAliasOccurrences(Warning w, SSACFG cfg){
		List<Integer> resourceAliases = ResourceAliasIdentification.getAllResourceAliases(w.matchedCgnode, w.matchedInstruction.getDef());
		resourceAliases.add(w.matchedInstruction.getDef());

		ArrayList<ISSABasicBlock> listToReturn = new ArrayList<ISSABasicBlock>();
		for (int aliasVariableNum : resourceAliases) {
			// Add the definition
			SSAInstruction def = w.matchedCgnode.getDU().getDef(aliasVariableNum);
			if (def!=null) {
				if (def.iIndex() >= 0) {
					listToReturn.add(cfg.getBlockForInstruction(def.iIndex()));
				}
			}
			// Add each use of the alias
			Iterator<SSAInstruction> useInstructionsIterator = w.matchedCgnode.getDU().getUses(aliasVariableNum);
			while(useInstructionsIterator.hasNext()) {
				SSAInstruction useInstruction = useInstructionsIterator.next();
				if (CommonUtils.isCloseStatement(useInstruction)) {
					continue; // don't want to include the close statement since it's going to be removed anyways
				}
				if (useInstruction.iIndex() >= 0) {
					ISSABasicBlock aliasBB = cfg.getBlockForInstruction(useInstruction.iIndex());
					listToReturn.add(aliasBB);
				}
			}
		}
		return listToReturn;
	}

	/*
	public static ArrayList<ISSABasicBlock> getUseBasicBlocks(Warning w, SSACFG cfg) {
		List<Pair<CGNode, Integer>> resourceAliases = ResourceAliasIdentification.getAllResourceAliases(w.matchedCgnode, w.matchedInstruction.getDef());
		resourceAliases.add(new Pair<CGNode, Integer>(w.matchedCgnode, w.matchedInstruction.getDef()));

		ArrayList<ISSABasicBlock> listToReturn = new ArrayList<ISSABasicBlock>();
		for (Pair<CGNode, Integer> alias : resourceAliases) {
			// Sanity check
			if (alias.fst != w.matchedCgnode) {
				if (ProgramInfo.printWarnings) {
					System.out.println("WARNING: Alias" + alias.fst.getMethod().getSignature() + "," + alias.snd);
					System.out.println("Doesn't match warning cgnode:" + w.matchedCgnode.getMethod().getSignature());
				}
			}
			// Add each use of the alias
			Iterator<SSAInstruction> useInstructionsIterator = alias.fst.getDU().getUses(alias.snd);
			while(useInstructionsIterator.hasNext()) {
				int instructionIndex = useInstructionsIterator.next().iIndex();
				if (instructionIndex >= 0) {
					ISSABasicBlock aliasBB = cfg.getBlockForInstruction(instructionIndex);
					listToReturn.add(aliasBB);
				}
			}
		}
		return listToReturn;
	}*/
	
	
	public static String getPointerToClose(Warning w) {
		IR ir = w.matchedCgnode.getIR();
		DefUse defUseInfo = w.matchedCgnode.getDU();
		List<Integer> resourceAliases = ResourceAliasIdentification.getAllResourceAliases(w.matchedCgnode, w.matchedInstruction.getDef());
		resourceAliases.add(w.matchedInstruction.getDef());
		// Check the use instructions for the name
		for (int aliasVariableNum : resourceAliases) {
			// SM: Check if the class has a finalizer method implemented
			boolean hasFinalizerMethod = false;
			IClass wrapperClass = w.matchedCgnode.getMethod().getDeclaringClass();
			for (IMethod m : wrapperClass.getAllMethods()) {
				if (CommonUtils.isCloseMethod(m.getReference())) { // just the 'this' parameter.
					hasFinalizerMethod = true;
					break;
				}
			}
			if (!hasFinalizerMethod) {
				// Check from the inference results
				String className = ProgramInfo.appClassesMapReverse.get(wrapperClass);
				hasFinalizerMethod = FinalizerMappingLoader.hasFinalizer(className);
			}
			if (!hasFinalizerMethod) {
				continue;
			}
			for ( Iterator<SSAInstruction> it = defUseInfo.getUses(aliasVariableNum); it.hasNext();) {
				SSAInstruction useInstruction = it.next();
				if (useInstruction.iIndex() < 0) {
					continue;
				}
				if (nullable(w.matchedCgnode, aliasVariableNum)) {
					continue;  // don't want to introduce a potential null pointer exception.
				}
				String[] localNames = ir.getLocalNames(useInstruction.iIndex(), aliasVariableNum);
				if (localNames != null) {
					for (String name : localNames) {
						if (name!=null) {
							return name;
						}
					}
				}
			}
		}
		// If we couldn't find it, check the def instruction.
		SSAInstruction [] insArray = ir.getControlFlowGraph().getInstructions();
		for (int aliasVariableNum : resourceAliases) {
			SSAInstruction defInstruction = defUseInfo.getDef(aliasVariableNum);
			if (defInstruction.iIndex() < 0) {
				continue;
			}
			if (nullable(w.matchedCgnode, aliasVariableNum)) {
				continue;  // don't want to introduce a potential null pointer exception.
			}
			// get the instruction immediately after the def instruction for
			// the local name.
			int nextNonNullInstructionIndex = -1;
			for (int i = defInstruction.iIndex()+1; i < insArray.length; i++) {
				if (insArray[i] != null) {
					nextNonNullInstructionIndex = i;
					break;
				}
			}
			if (nextNonNullInstructionIndex != -1) {
				String[] localNames = ir.getLocalNames(nextNonNullInstructionIndex, aliasVariableNum);
				if (localNames != null) {
					for (String name : localNames) {
						if (name!=null) {
							return name;
						}
					}
				}
			}
		}
		return CommonUtils.newVariableName;  // Couldn't find anything else.
	}
	
	private static boolean nullable(CGNode fst, Integer snd) {
		// TODO Auto-generated method stub
		return false;
	}


	/* 
	 * Computes the valid successors.
	 * The exit block is a valid successor only the last instruction of the
	 * block is a return instruction.
	 */
	public static ArrayList<ISSABasicBlock> getValidSucc(ISSABasicBlock bb, SSACFG cfg, IR ir) {
		ArrayList<ISSABasicBlock> successsors = new ArrayList<ISSABasicBlock>();
		for (Iterator<ISSABasicBlock> it2 = cfg.getSuccNodes(bb) ; it2.hasNext() ; ) {
			ISSABasicBlock succBB = it2.next();
			if (succBB.isExitBlock()) {
				// exit block is valid successor only for return instruction blocks.
				if (bb.getLastInstructionIndex() >= 0) {
					SSAInstruction lastIns = ir.getInstructions()[bb.getLastInstructionIndex()];
					if (lastIns!=null && lastIns instanceof SSAReturnInstruction) {
						successsors.add(succBB);
					}
				}
			} else {
				successsors.add(succBB);
			}
		}
		return successsors;
	}


	
	
	/* 
	 * Returns two values.
	 * The first is the post-dominator of all definitions/uses of 
	 * the resource (or aliases). This is a valid location to add the finally block. 
	 * An additional condition is that this earliest post-dominator
	 * basic block shouln't have a conditional-branch instruction (since we don't
	 * want the finally block at a for loop).
	 * The second value returned specifies whether the post-dominator block has a use
	 * of the resource 
	 */
	public static Pair<Integer,Boolean> getEarliestPostDominatorInstruction(Warning w, SSACFG cfg, IR ir) {
		// Deal with the corner case where there is no return statement - happens when there is a non-terminating loop.
		if (!hasReturnInstruction(ir)) {
			return new Pair<Integer,Boolean>(CommonUtils.NOT_FOUND,false);
		}
		// Normal case.
		HashMap<Integer, HashSet<Integer>> postDominators = computePostDominatorsForBBs(cfg, ir);
		// Get the common dominators of all the defs/uses of the resource.
		List<ISSABasicBlock> resourceBBs = FixUtils.getBasicBlocksForAllAliasOccurrences(w, cfg);
		ArrayList<HashSet<Integer>> resourceBBpostDominators = new ArrayList<HashSet<Integer>>();
		for (ISSABasicBlock resourceBb : resourceBBs) {
			resourceBBpostDominators.add(postDominators.get(resourceBb.getNumber()));
		}
		HashSet<Integer> commonPostDominators = CommonUtils.computeIntersection(resourceBBpostDominators);

		// Pick the earliest basic block that is not a conditional branch.
		int earliestDomInstruction = Integer.MAX_VALUE;
		ISSABasicBlock earliestDom = null;
		for (int domNumber : commonPostDominators) {
			ISSABasicBlock domBlock = cfg.getBasicBlock(domNumber);
			if (domBlock.getLastInstructionIndex() < 0) {
				continue;  // last instruction is a created instruction.
			}
			SSAInstruction lastIns = ir.getInstructions()[domBlock.getLastInstructionIndex()];
			if (lastIns == null || isLoopHeadBB(domBlock,postDominators,cfg,ir)) {
				continue;
			}
			IBytecodeMethod<?> method = (IBytecodeMethod<?>)w.matchedCgnode.getMethod();
			try {
				int bytecodeIndex = method.getBytecodeIndex(lastIns.iIndex());
				int sourceLineNum = method.getLineNumber(bytecodeIndex);
				if (sourceLineNum < earliestDomInstruction) {
					earliestDomInstruction = sourceLineNum;
					earliestDom = domBlock;
				}
			} catch (InvalidClassFileException e) {
				System.out.println("ERROR: Invalid class file exception.");
			}
		}
		// If we couldn't find a dominator.
		if (earliestDom == null) {
			return new Pair<Integer,Boolean>(CommonUtils.NOT_FOUND,false);
		}
		// Next, check if this earliest post-dominator has a use of the resource.
		boolean earliestDomHasUse = false;
		for (ISSABasicBlock bb : resourceBBs) {
			if (bb.getNumber() == earliestDom.getNumber()) {
				earliestDomHasUse = true;
			}
		}
		
		// Finally, return the 2 values we computed
		return new Pair<Integer,Boolean>(earliestDomInstruction,earliestDomHasUse);
	}
	
	
	private static boolean hasReturnInstruction(IR ir) {
		for (SSAInstruction ins : ir.getInstructions()) {
			if (ins instanceof SSAReturnInstruction) {
				return true;
			}
		}
		// return instruction not found
		return false;
	}

	/*
	 * Decides if the loop block corresponds to a loop 
	 */
	private static boolean isLoopHeadBB(ISSABasicBlock blockToCheck, HashMap<Integer, HashSet<Integer>> postDominators, SSACFG cfg, IR ir) {
		// Condition 1: Last instruction should be a conditional branch
		SSAInstruction lastIns = ir.getInstructions()[blockToCheck.getLastInstructionIndex()];
		if (lastIns == null || !(lastIns instanceof SSAConditionalBranchInstruction)) {
			return false;
		}
		// Condition 2: The basic block of the back-edge from the goto should be 
		// post-dominated by this block
		for (Iterator<ISSABasicBlock> it = cfg.getPredNodes(blockToCheck) ; it.hasNext() ; ) {
			ISSABasicBlock predBB = it.next();
			SSAInstruction predBBlastIns = ir.getInstructions()[predBB.getLastInstructionIndex()];
			if (predBBlastIns instanceof SSAGotoInstruction) {
				if (postDominators.get(predBB.getNumber()).contains(blockToCheck.getNumber())) {
					return true;  // both conditions satisfied at this point.
				}
			}
		}
		// Couldn't satisfy the two conditions
		return false;
	}

	// Computes the post-dominators for every node. See wikipedia or any
	// online algorithm for an explanation of the algorithm.
	private static HashMap<Integer, HashSet<Integer>> computePostDominatorsForBBs(SSACFG cfg, IR ir) {
		HashMap<Integer, HashSet<Integer>> postDominators = new HashMap<Integer, HashSet<Integer>>();
		// Allocate space basic blocks is its own post-dominator.
		for (Iterator<ISSABasicBlock> it = ir.getBlocks() ; it.hasNext() ;) {
			ISSABasicBlock bb = it.next();
			postDominators.put(bb.getNumber(), new HashSet<Integer>());

		}
		// Step1: Exit block is its own dominator
		int exitBlockNumber = cfg.exit().getNumber();
		postDominators.get(exitBlockNumber).add(exitBlockNumber);
		
		// Step2: Set every node as the dominator for every other node
		ArrayList<Integer> basicBlockNumbers = new ArrayList<Integer>();
		for (Iterator<ISSABasicBlock> it = ir.getBlocks() ; it.hasNext() ;) {
			ISSABasicBlock bb = it.next();
			basicBlockNumbers.add(bb.getNumber());
		}
		for (Iterator<ISSABasicBlock> it = ir.getBlocks() ; it.hasNext() ;) {
			ISSABasicBlock bb = it.next();
			if (!bb.isExitBlock()) {
				for (int bbNum : basicBlockNumbers) {
					postDominators.get(bb.getNumber()).add(bbNum);
				}
			}
		}
		
		// Step3: Iteratively eliminate nodes that are not dominators.
		boolean changeInIteration = true;
		while (changeInIteration) {
			changeInIteration = false;
			for (Iterator<ISSABasicBlock> it = ir.getBlocks() ; it.hasNext() ;) {
				ISSABasicBlock bb = it.next();
				if (!bb.isExitBlock()) {
					// Recompute the dominator set for this basic block
					HashSet<Integer> newDominatorSet = new HashSet<Integer>();
					// add the basic block itself as the dominator
					newDominatorSet.add(bb.getNumber());
					// Dom(n) = {n} union with interection over Dom(s) for all s in succ(n)
					ArrayList<HashSet<Integer>> successorDominators = new ArrayList<HashSet<Integer>>();
					for (ISSABasicBlock succBB : FixUtils.getValidSucc(bb,cfg, ir)) {
						successorDominators.add(postDominators.get(succBB.getNumber()));
					}
					for (int intersectionBB : CommonUtils.computeIntersection(successorDominators)) {
						newDominatorSet.add(intersectionBB);
					}
					
					// Check if anything has changed.
					for (int a : postDominators.get(bb.getNumber())){
						if (!newDominatorSet.contains(a)) {
							changeInIteration = true;
						}
					}
					// Add the newly computed dominator set to the map
					postDominators.put(bb.getNumber(),newDominatorSet);
				}
			}
		}
		return postDominators;
	}


	
	public static int getSourceLine(SSAInstruction ins, CGNode cgnode) {
		if (ins == null || ins.iIndex() < 0) {
			return CommonUtils.NOT_FOUND;
		}
		IBytecodeMethod<?> method = (IBytecodeMethod<?>)cgnode.getMethod();
		try {
			int bytecodeIndex = method.getBytecodeIndex(ins.iIndex());
			return method.getLineNumber(bytecodeIndex);
		} catch (InvalidClassFileException e) {
			System.out.println("ERROR: Invalid class file exception.");
		}
		return CommonUtils.NOT_FOUND;
	}
	
	public static ArrayList<Integer> computeLineNumbersForResource(Warning w){
		ArrayList<Integer> resourceLineNumbers = new ArrayList<Integer>();
		List<Integer> resourceAliases = ResourceAliasIdentification.getAllResourceAliases(w.matchedCgnode, w.matchedInstruction.getDef());
		resourceAliases.add(w.matchedInstruction.getDef());

		for (int aliasVariableNum : resourceAliases) {
			// Add the definition
			SSAInstruction def = w.matchedCgnode.getDU().getDef(aliasVariableNum);
			if (def!=null && def.iIndex() >= 0) {
				resourceLineNumbers.add(FixUtils.getSourceLine(def, w.matchedCgnode));
			}
			// Add each use of the alias
			Iterator<SSAInstruction> useInstructionsIterator = w.matchedCgnode.getDU().getUses(aliasVariableNum);
			while(useInstructionsIterator.hasNext()) {
				SSAInstruction use = useInstructionsIterator.next();
				if (use != null && use.iIndex() >=0 ) {
					resourceLineNumbers.add(FixUtils.getSourceLine(use, w.matchedCgnode));
				}
			}
		}
		return resourceLineNumbers;
	}


	public static ArrayList<Integer> computeLineNumbersForResourceDefs(Warning w) {
		ArrayList<Integer> resourceDefLineNumbers = new ArrayList<Integer>();
		List<Integer> resourceAliases = ResourceAliasIdentification.getAllResourceAliases(w.matchedCgnode, w.matchedInstruction.getDef());
		resourceAliases.add(w.matchedInstruction.getDef());

		for (int aliasVariableNum : resourceAliases) {
			// Add the definition
			SSAInstruction def = w.matchedCgnode.getDU().getDef(aliasVariableNum);
			if (def!=null && def.iIndex() >= 0) {
				resourceDefLineNumbers.add(FixUtils.getSourceLine(def, w.matchedCgnode));
			}
		}
		return resourceDefLineNumbers;
	}
}
