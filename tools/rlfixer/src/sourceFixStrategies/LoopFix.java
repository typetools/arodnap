package sourceFixStrategies;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Set;
import java.util.Stack;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.github.javaparser.ast.visitor.GenericVisitorAdapter;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSACFG;
import com.ibm.wala.ssa.SSAConditionalBranchInstruction;
import com.ibm.wala.ssa.SSAGotoInstruction;
import com.ibm.wala.ssa.SSAInstruction;

import utils.CommonUtils;
import utils.Pair;
import utils.Warning;

public class LoopFix {

	public static boolean resourceInForLoop(Warning w) {
		SSACFG cfg = w.matchedCgnode.getIR().getControlFlowGraph();
		ISSABasicBlock warningBB = cfg.getBlockForInstruction(w.matchedInstruction.iIndex());
		return basicBlockInLoop(warningBB, cfg);
	}

	public static boolean basicBlockInLoop(ISSABasicBlock blockToCheck, SSACFG cfg) {
		// Simple Breadth-first search for cycle detection
		Set<Integer> visitedBBs = new HashSet<Integer>();
		Queue<ISSABasicBlock> activeSet = new LinkedList<ISSABasicBlock>();
		activeSet.add(blockToCheck);

		while(!activeSet.isEmpty()) {
			ISSABasicBlock temp = activeSet.remove();
			for (Iterator<ISSABasicBlock> it = cfg.getSuccNodes(temp) ; it.hasNext() ; ) {
				ISSABasicBlock succBB = it.next();
				if (succBB.getNumber() == blockToCheck.getNumber()) {
					return true;  // cycle detected
				}
				if (!visitedBBs.contains(succBB.getNumber())) {
					visitedBBs.add(succBB.getNumber());
					activeSet.add(succBB);
				}
			}
		}
		// No loop detected
		return false;
	}

	/*
	 * Answers whether it is safe to release the resource at the
	 * end of the loop.
	 */
	public static boolean resourceReleasableAtLoopEnd(Warning w) {
		class LoopEscapeCheckingVisitor extends GenericVisitorAdapter<Boolean,Object>{
			ArrayList<Integer> resourceSrcLines;
			ArrayList<Integer> resourceDefinitionLines;
			
			private LoopEscapeCheckingVisitor(ArrayList<Integer> a, ArrayList<Integer> b) {
				resourceSrcLines = a;
				resourceDefinitionLines = b;
			}
			@Override
			public Boolean visit(ForStmt stmt, Object dummy) {
				return makeCheckInLoopBlock(stmt.getBody());
			}
			@Override
			public Boolean visit(ForEachStmt stmt, Object dummy) {
				return makeCheckInLoopBlock(stmt.getBody());
			}
			@Override
			public Boolean visit(WhileStmt stmt, Object dummy) {
				return makeCheckInLoopBlock(stmt.getBody());
			}
			@Override
			public Boolean visit(DoStmt stmt, Object dummy) {
				return makeCheckInLoopBlock(stmt.getBody());
			}
			public Boolean makeCheckInLoopBlock(Statement stmt) {
				// Make sure this is a block statement.
				if (!stmt.hasRange() || !stmt.isBlockStmt()) {
					return null;
				}
				BlockStmt loopBlock = stmt.asBlockStmt();
				int loopBlockStart = loopBlock.getBegin().get().line;
				int loopBlockEnd = loopBlock.getEnd().get().line;
				// Check if at least one definition is inside this loop-block
				boolean resourceUseInThisBlock = false;
				for (int line : resourceDefinitionLines) {
					if ( loopBlockEnd >= line && line >= loopBlockStart) {
						resourceUseInThisBlock = true;
					}
				}
				// If one definition is inside the loop block, all uses need to be.
				if (resourceUseInThisBlock) {
					for (int line : resourceSrcLines) {
						if ( loopBlockEnd < line || line < loopBlockStart) {
							return true;  // at least one use/def escapes
						}
					}
				}
				// Set the loop bounds for this warning.
				w.loopStartLine = loopBlockStart;
				w.loopEndLine = loopBlockEnd;
				// Also check recursively if there is a nested loop.
				return stmt.accept(this, null);
			}
		}
		/*
		IR ir = w.matchedCgnode.getIR();
		SSACFG cfg = ir.getControlFlowGraph();
		ISSABasicBlock warningBB = cfg.getBlockForInstruction(w.matchedInstruction.iIndex());
		Pair<ISSABasicBlock,ISSABasicBlock> p = findBackEdgeBBAndLoopHead(ir, cfg, warningBB);
		ISSABasicBlock backEdgeBB = p.fst;
		ISSABasicBlock loopHeadBB = p.snd;
		return !resourceEscapesLoop(w, backEdgeBB, loopHeadBB) && resourceDefinitionsDominateUses(w, loopHeadBB);
		*/
		ArrayList<Integer> resourceLineNumbers = FixUtils.computeLineNumbersForResource(w);
		ArrayList<Integer> resourceDefLineNumbers = FixUtils.computeLineNumbersForResourceDefs(w);
		CompilationUnit cu = CommonUtils.getCompilationUnit(w.sourceFilename);
		Boolean escapesLoop = cu.accept(new LoopEscapeCheckingVisitor(resourceLineNumbers,resourceDefLineNumbers), null);
		if (escapesLoop == null) {
			return true;
		} else {
			return false; 
		}
	}

	/*
	private static boolean resourceDefinitionsDominateUses(Warning w, ISSABasicBlock loopHeadBB) {
		SSACFG cfg = w.matchedCgnode.getIR().getControlFlowGraph();
		ISSABasicBlock warningBB = cfg.getBlockForInstruction(w.matchedInstruction.iIndex());
		for (ISSABasicBlock b : FixUtils.getUseBasicBlocks(w,cfg)) {
			if (findPathToLoopHead(b,warningBB,cfg,loopHeadBB)) {
				return true;
			}
		}

		// Couldn't find a path to the loop head.
		return true;
	}
	*/
	
	/*
	 * Tries to find a path to the loop head without using the warning basic-block.
	
	private static boolean findPathToLoopHead(ISSABasicBlock b, ISSABasicBlock warningBB, SSACFG cfg, ISSABasicBlock loopHeadBB) {
		// Simple Breadth-first search for cycle detection
		Set<Integer> visitedBBs = new HashSet<Integer>();
		Queue<ISSABasicBlock> activeSet = new LinkedList<ISSABasicBlock>();
		activeSet.add(warningBB);

		while(!activeSet.isEmpty()) {
			ISSABasicBlock temp = activeSet.remove();
			for (Iterator<ISSABasicBlock> it = cfg.getPredNodes(temp) ; it.hasNext() ; ) {
				ISSABasicBlock predBB = it.next();
				if (predBB.getNumber() == warningBB.getNumber()) {
					continue;  // not allowed to use the warning's basic block
				}
				if (predBB.getNumber() == loopHeadBB.getNumber()) {
					return true;  // path detected
				}
				if (!visitedBBs.contains(predBB.getNumber())) {
					visitedBBs.add(predBB.getNumber());
					activeSet.add(predBB);
				}
			}
		}
		return false;
	}
	 */
	/*
	 * Answers whether the resource or its aliases are accessible
	 * outside the loop. Does this by checking if all uses of the
	 * resource (or aliases) can reach the 
	 * Assumes that the warning-resource basic-block is in a loop.
	 
	private static boolean resourceEscapesLoop(Warning w, ISSABasicBlock backEdgeBB, ISSABasicBlock loopHeadBB) {
		SSACFG cfg = w.matchedCgnode.getIR().getControlFlowGraph();
		HashSet<Integer> loopBBs = computeLoopBasicBlocks(w, backEdgeBB, loopHeadBB, cfg);
		for (ISSABasicBlock b : FixUtils.getBasicBlocksForAllAliasOccurrences(w,cfg)) {
			if (!loopBBs.contains(b.getNumber())) {
				return true;
			}
		}
		return false; // no violations found. Hence no resource escapes
	}*/

	/*
	 * Computes the basic blocks for the loop that the warning is a part of.
	 * Go backward from the backward-edge until we reach the 
	 * head of the loop. All basic-blocks encountered are part of the loop.
	
	private static HashSet<Integer> computeLoopBasicBlocks(Warning w, ISSABasicBlock backEdgeBB, ISSABasicBlock loopHeadBB, SSACFG cfg) {
		// Breadth-first search
		HashSet<Integer> visitedLoopBBs = new HashSet<Integer>();
		Queue<ISSABasicBlock> activeSet = new LinkedList<ISSABasicBlock>();
		activeSet.add(backEdgeBB);
		visitedLoopBBs.add(backEdgeBB.getNumber());
		boolean done = false;

		while(!done) {
			ISSABasicBlock temp = activeSet.remove();
			for (Iterator<ISSABasicBlock> it = cfg.getPredNodes(temp) ; it.hasNext() ; ) {
				ISSABasicBlock predBB = it.next();
				if (predBB.getNumber() == loopHeadBB.getNumber()) {
					// We have reached the loop head. All loop basic blocks
					// have been covered.
					done = true; 
					break;
				}
				if (!visitedLoopBBs.contains(predBB.getNumber())) {
					visitedLoopBBs.add(predBB.getNumber());
					activeSet.add(predBB);
				}
			}
		}
		return visitedLoopBBs;
	}
	 */
	
	/*
	public static Pair<ISSABasicBlock, ISSABasicBlock> findBackEdgeBBAndLoopHead(IR ir, SSACFG cfg,
			ISSABasicBlock bbToCheck) {
		// Step 1: Compute the closest basic-block cycle that the warning is a part of
		// Simple Depth-first search for cycle detection
		Set<Integer> visitedBBs = new HashSet<Integer>();
		Stack<ISSABasicBlock> stack = new Stack<ISSABasicBlock>();
		dfsCycleDetection(bbToCheck, stack, visitedBBs, bbToCheck, cfg);

		// Step 2: Find the back-edge and head of the loop based on the found cycle.
		// In the loop discovered from step1, the back-edge and head are consecutive
		// basic blocks where the back edge is a goto and the head is a conditional branch.
		ISSABasicBlock backEdgeBB = null;
		ISSABasicBlock loopHeadBB = null;
		ISSABasicBlock bbArray [] = new ISSABasicBlock[stack.size()];
		bbArray = stack.toArray(bbArray);
		for (int i = 0 ; i < bbArray.length - 1 ; i++) {
			if (bbArray[i].getLastInstructionIndex()>=0 && bbArray[i+1].getLastInstructionIndex()>=0) {
				SSAInstruction ins1 = ir.getInstructions()[bbArray[i].getLastInstructionIndex()];
				SSAInstruction ins2 = ir.getInstructions()[bbArray[i+1].getLastInstructionIndex()];
				if (ins1 instanceof SSAGotoInstruction && ins2 instanceof SSAConditionalBranchInstruction) {
					backEdgeBB = bbArray[i];
					loopHeadBB = bbArray[i+1];
				}
			}
		}
		if (backEdgeBB == null || loopHeadBB == null) {
			System.out.println("WARNING: Back-edge and loop-head not found. Exiting");
			System.exit(1);
		}
		return new Pair<ISSABasicBlock,ISSABasicBlock>(backEdgeBB,loopHeadBB);
	}
	*/
	/* 
	 * Detects a cycle starting and ending at the warning basic-block. 
	 * Return true if a cycle was detected. Cycle is stored in the stack.
	
	private static boolean dfsCycleDetection(ISSABasicBlock nextBB, Stack<ISSABasicBlock> stack, Set<Integer> visitedBBs, ISSABasicBlock warningBB, SSACFG cfg) {
		stack.push(nextBB);
		for (Iterator<ISSABasicBlock> it = cfg.getSuccNodes(nextBB) ; it.hasNext() ; ) {
			ISSABasicBlock succBB = it.next();
			if (succBB.getNumber() == warningBB.getNumber()) {
				return true;	// cycle detected
			}
			if (!visitedBBs.contains(succBB.getNumber())) {
				visitedBBs.add(succBB.getNumber());
				boolean foundCycle = dfsCycleDetection(succBB, stack, visitedBBs,warningBB,cfg);
				if (foundCycle) {
					return true;
				}
			}
		}
		stack.pop();
		return false;
	}
	 */
}
