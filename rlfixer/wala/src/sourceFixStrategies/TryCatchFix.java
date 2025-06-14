package sourceFixStrategies;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.visitor.GenericVisitorAdapter;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSACFG;


import com.ibm.wala.ssa.SSAInstruction;

import main.FinalizerMappingLoader;
import main.ResourceAliasIdentification;
import utils.CommonUtils;
import utils.Pair;
import utils.Warning;

public class TryCatchFix {


	/*
	 * If any occurrence of the resource (or its aliases) has a direct
	 * link (in the CFG) to a catch-block, then the warning (or its aliases) 
	 * must be (at least partially) in a try-catch block.  
	 * Note: This is an under-approximation, but it seems to work
	 * okay in practice. 
	 * Under-approximation part: A block may not have a direct catch successor, 
	 * but may still be a part of a try-block because of an indirect catch successor.
	 * However, there is nothing much we can do about this. The try block information
	 * was lost when going from Java source to bytecode, and this approach of 
	 * recovering that information is imperfect.
	 */
	public static boolean resourceInTryCatch(Warning w) {
		ArrayList<Integer> resourceLineNumbers = FixUtils.computeLineNumbersForResource(w);
		CompilationUnit cu = CommonUtils.getCompilationUnit(w.sourceFilename);
		Boolean resourceInTryCatch = cu.accept(new TryCatchIdentificationVisitor(w,resourceLineNumbers), null);
		if (resourceInTryCatch == null) {
			return false;
		} else {
			return true; 
		}
	}

	private static class TryCatchIdentificationVisitor extends GenericVisitorAdapter<Boolean,Object>{
		ArrayList<Integer> resourceSrcLines;
		Warning warning;
		private TryCatchIdentificationVisitor(Warning w, ArrayList<Integer> a) {
			warning = w;
			resourceSrcLines = a;
		}
		@Override
		public Boolean visit(TryStmt tryStmt, Object dummy) {
			BlockStmt tryBlock = tryStmt.getTryBlock();
			// Make sure this is the try-block of interest.
			if (!tryBlock.hasRange()) {
				return null;
			}
			int tryBlockStart = tryBlock.getBegin().get().line;
			int tryBlockEnd = tryBlock.getEnd().get().line;
			// Deal with the special case where the try-catch block has a loop.
			if (warning.isLoopFix) {
				if (tryBlockStart < warning.loopStartLine) {
					// So we have a loop outside the try block, which we can't count.
					// But we also need to check if there is another try block inside the loop.
					return tryBlock.accept(this,null);
				}
			} 
			for (int line : resourceSrcLines) {
				if ( tryBlockEnd >= line && line >= tryBlockStart) {
					return true;  // at least one resource def/use is in the try block.
				}
			}
			
			return null;
		}
	}


	private static boolean basicBlockHasCatchSuccessor(ISSABasicBlock b, SSACFG cfg) {
		for (Iterator<ISSABasicBlock> it = cfg.getSuccNodes(b) ; it.hasNext() ; ) {
			ISSABasicBlock succBB = it.next();
			if (succBB.isCatchBlock()) {
				return true;
			}
		}
		// TODO Auto-generated method stub
		return false;
	}

	Warning w;
	SSACFG cfg;
	IR ir;

	public TryCatchFix(Warning a) {
		w = a;
		ir = w.matchedCgnode.getIR();
		cfg = ir.getControlFlowGraph();
	}

	public void computeTryCatchFix() {
		if (warningEscapesTryCatch()) {
			if (w.isLoopFix) {
				w.unfixable = true;
				w.comments += "Resource escapes try-catch and is in a loop;";
			} else {
				computeEscapedTryCatchFix();
			}
		} else {
			computeContainedTryCatchFix();
		}
	}



	/* 
	 * Checks if the warning (or its aliases) escape the catch block
	 * The function assumes that the warning is in a try-catch block.
	 */
	private boolean warningEscapesTryCatch() {
		class EscapeCheckingVisitor extends GenericVisitorAdapter<Boolean,Object>{
			ArrayList<Integer> resourceSrcLines;
			private EscapeCheckingVisitor(ArrayList<Integer> a) {
				resourceSrcLines = a;
			}
			@Override
			public Boolean visit(TryStmt tryStmt, Object dummy) {
				BlockStmt tryBlock = tryStmt.getTryBlock();
				// Make sure this is the try-block of interest.
				if (!tryBlock.hasRange()) {
					return null;
				}
				int tryBlockStart = tryBlock.getBegin().get().line;
				int tryBlockEnd = tryBlock.getEnd().get().line;
				// Check if at least one use/def is inside this try-block
				boolean resourceUseInThisBlock = false;
				for (int line : resourceSrcLines) {
					if ( tryBlockEnd >= line && line >= tryBlockStart) {
						resourceUseInThisBlock = true;
					}
				}
				// If one use is in a try block, all uses need to be.
				if (resourceUseInThisBlock) {
					for (int line : resourceSrcLines) {
						if ( tryBlockEnd < line || line < tryBlockStart) {
							return true;  // at least one use/def escapes
						}
					}
				}
				return null;
			}
			/* Todo: Deal with the corner case where the first definition is inside an 'if-else' */
		}
		ArrayList<Integer> resourceLineNumbers = FixUtils.computeLineNumbersForResource(w);
		CompilationUnit cu = CommonUtils.getCompilationUnit(w.sourceFilename);
		Boolean escapes = cu.accept(new EscapeCheckingVisitor(resourceLineNumbers), null);
		if (escapes == null) {
			return false;
		} else {
			return true; 
		}
	}




	/*
	 * Computes a fix in the case where the resource escapes the
	 * try catch block.
	 * Assumption: we are not in a loop.
	 */
	private void computeEscapedTryCatchFix() {
		// Step 1: Find all uses that are not in a try-catch block
		HashSet<Integer> usesNotInTryCatchBlock = new HashSet<Integer>();
		List<Integer> resourceAliases = ResourceAliasIdentification.getAllResourceAliases(w.matchedCgnode, w.matchedInstruction.getDef());
		resourceAliases.add(w.matchedInstruction.getDef());
		for (int aliasVariableNum : resourceAliases) {
			// Add each use of the alias
			Iterator<SSAInstruction> useInstructionsIterator = w.matchedCgnode.getDU().getUses(aliasVariableNum);
			while(useInstructionsIterator.hasNext()) {
				SSAInstruction useIns = useInstructionsIterator.next();
				if (useIns == null) {
					continue;
				}
				if (useIns.getExceptionTypes().size() == 0) {  
					// instruction can't throw an exception, so it doesn't
					// need to be inside a try block
					continue;
				}
				if (CommonUtils.isCloseStatement(useIns)) {
					continue;  // don't care about close statements. They are going to be removed.
				}	
				if (basicBlockHasCatchSuccessor(cfg.getBlockForInstruction(useIns.iIndex()),cfg)) {
					// Instruction is already in a try block, so we don't need to worry.
					continue;
				}
				int sourceLineNum = FixUtils.getSourceLine(useIns, w.matchedCgnode);
				usesNotInTryCatchBlock.add(sourceLineNum);
			}
		}

		// Step 2: Add the required try-catch blocks.
		ArrayList<Integer> arr = new ArrayList<Integer>(usesNotInTryCatchBlock);
		if (arr.size() > 0) {
			Collections.sort(arr);
			// coalesce adjacent (or almost adjacent) lines 
			int startSrcLine = arr.get(0), endSrcLine = arr.get(0);
			for (int i = 1 ; i < arr.size(); i++) {
				if (arr.get(i) == arr.get(i-1) + 1 || arr.get(i) == arr.get(i-1) + 2) {
					// we have consecutive lines. They should be coalesced.
					endSrcLine = arr.get(i);
				} else {
					// we have come to the end of a set of consecutive lines.
					encloseInTryCatchBlock(startSrcLine, endSrcLine);
					startSrcLine = arr.get(i);
					endSrcLine = arr.get(i);
				}
			}
			// Finish the try-catch for the last line.
			encloseInTryCatchBlock(startSrcLine, endSrcLine);
			
		}
		
		// Step 3: Close the resource at the dominator
		Pair<Integer,Boolean> earliestPostDom = FixUtils.getEarliestPostDominatorInstruction(w,cfg,ir);
		if (earliestPostDom.fst == CommonUtils.NOT_FOUND) {
			w.unfixable = true;
			w.comments += "Escaped-try-catch and no post-dominator found;";
			return;
		}
		int closeLocation = earliestPostDom.fst;

		// Based on whether the resource is in a try block or outside, we can add
		// the close statement either to the finally or create a new try block.
		if (isInTryBlock(closeLocation)) {
			Integer finallyBlockLocation = getLocationForFinally(w,closeLocation);
			if (finallyBlockLocation == null) {
				w.unfixable = true;
				w.comments += "Finally method location not found;";
				return;
			}
			String pointerToClose = FixUtils.getPointerToClose(w);

			String line1 = "Add following code below line: " + finallyBlockLocation + " (" + w.sourceFilename + ")\n";
			String line2 = "finally{\n    try{ " + pointerToClose + "." + FinalizerMappingLoader.getFinalizerMethod(w.getQualifiedResourceName()) + "(); } catch(Exception e){ e.printStackTrace(); }\n}\n";
			String line3 = "";
			if (pointerToClose == CommonUtils.newVariableName) {
				int resourceLine = FixUtils.getSourceLine(w.matchedInstruction, w.matchedCgnode);
				line3 = "// where variable " + CommonUtils.newVariableName + " points to the resource from line " + resourceLine + "\n";
			}
			w.sourceLevelFixes.add(line1+line2+line3);
		} else {
			String relativeLocation = "";
			if (earliestPostDom.snd) {
				relativeLocation = "below";
			} else {
				relativeLocation = "above";
			}

			String pointerToClose = FixUtils.getPointerToClose(w);
			String line1 = "Add following code " + relativeLocation + " line:" + closeLocation + " (" + w.sourceFilename + ")\n";
			String line2 = "try{\n    " + pointerToClose + "." + FinalizerMappingLoader.getFinalizerMethod(w.getQualifiedResourceName()) + "();\n} catch(Exception e){ e.printStackTrace(); }\n";
			String line3 = "";
			if (pointerToClose == CommonUtils.newVariableName) {
				int resourceLine = FixUtils.getSourceLine(w.matchedInstruction, w.matchedCgnode);
				line3 = "// where variable " + CommonUtils.newVariableName + " points to the resource from line " + resourceLine + "\n";
			}
			w.sourceLevelFixes.add(line1+line2+line3);
		}
	}

	private void encloseInTryCatchBlock(int startSrcLine, int endSrcLine) {
		String pointerToClose = FixUtils.getPointerToClose(w);
		// Write out the try-catch blocks for these lines
		String line1 = "Add following code above line:" + startSrcLine + " (" + w.sourceFilename + ")\n";
		String line2 = "try{ ";
		w.sourceLevelFixes.add(line1+line2);

		String line3 = "Add following code below line:" + endSrcLine + " (" + w.sourceFilename + ")\n";
		String line4 = "} catch(Exception e){ \n";
		String line5 = "    try{ " + pointerToClose + "." + FinalizerMappingLoader.getFinalizerMethod(w.getQualifiedResourceName()) + "(); } catch(Exception e){ e.printStackTrace(); }\n";
		String line6 = "    throw e; \n}";
		w.sourceLevelFixes.add(line3+line4+line5+line6);
	}

	/*
	 * Returns whether the given line number is inside some try block.
	 */
	private boolean isInTryBlock(int lineNumber) {
		ArrayList<Integer> lineNumbers = new ArrayList<Integer>();
		lineNumbers.add(lineNumber);
		CompilationUnit cu = CommonUtils.getCompilationUnit(w.sourceFilename);
		Boolean resourceInTryCatch = cu.accept(new TryCatchIdentificationVisitor(w,lineNumbers), null);
		if (resourceInTryCatch == null) {
			return false;
		} else {
			return true; 
		}
	}

	private void computeContainedTryCatchFix() {
		ArrayList<Integer> resourceLineNumbers = FixUtils.computeLineNumbersForResource(w);
		int tryBlockStart = Collections.min(resourceLineNumbers);
		String pointerToClose = FixUtils.getPointerToClose(w);
		Integer finallyBlockFirstLine = getFinallyBlock(tryBlockStart);
		if (finallyBlockFirstLine != null) {  //If there already is a finally block.
			String line1 = "Add following code below line: " + finallyBlockFirstLine + " (" + w.sourceFilename + ")\n";
			String line2 = "try{ " + pointerToClose + "." + FinalizerMappingLoader.getFinalizerMethod(w.getQualifiedResourceName()) + "(); } catch(Exception e){ e.printStackTrace(); }\n";
			String line3 = "";
			if (pointerToClose == CommonUtils.newVariableName) {
				int resourceLine = FixUtils.getSourceLine(w.matchedInstruction, w.matchedCgnode);
				line3 = "// where variable " + CommonUtils.newVariableName + " points to the resource from line " + resourceLine + "\n";
			}
			w.sourceLevelFixes.add(line1+line2+line3);
		} else {
			Integer finallyMethodLocation = getLocationForFinally(w, tryBlockStart);
			if (finallyMethodLocation == null) {
				w.unfixable = true;
				w.comments += "Finally method location not found;";
				return;
			}
			String line1 = "Add following code below line: " + finallyMethodLocation + " (" + w.sourceFilename + ")\n";
			String line2 = "finally{\n    try{ " + pointerToClose + "." + FinalizerMappingLoader.getFinalizerMethod(w.getQualifiedResourceName()) + "(); } catch(Exception e){ e.printStackTrace(); }\n}\n";
			String line3 = "";
			if (pointerToClose == CommonUtils.newVariableName) {
				int resourceLine = FixUtils.getSourceLine(w.matchedInstruction, w.matchedCgnode);
				line3 = "// where variable " + CommonUtils.newVariableName + " points to the resource from line " + resourceLine + "\n";
			}
			w.sourceLevelFixes.add(line1+line2+line3);
		}
	}

	private Integer getFinallyBlock(int lineNumberToCheck) {
		CompilationUnit cu = CommonUtils.getCompilationUnit(w.sourceFilename);
		DeepestMatchingTryVisitor v = new DeepestMatchingTryVisitor(w,lineNumberToCheck);
		cu.accept(v,null);
		if (v.deepestMatchingTry == null) {
			return null;
		}
		Optional<BlockStmt> finallyStmt = v.deepestMatchingTry.getFinallyBlock();
		if (finallyStmt.isPresent()) {
			return finallyStmt.get().getBegin().get().line;
		} else {
			return null;
		}
	}

	private static class DeepestMatchingTryVisitor extends GenericVisitorAdapter<Object,Object>{
		int lineNumber;
		TryStmt deepestMatchingTry;
		Warning warning;

		private DeepestMatchingTryVisitor(Warning w, int a) {
			lineNumber = a;
			warning = w;
			deepestMatchingTry = null;
		}

		@Override
		public Object visit(TryStmt tryStmt, Object dummy) {
			BlockStmt tryBlock = tryStmt.getTryBlock();
			// Make sure this is the try-block of interest.
			if (!tryBlock.hasRange()) {
				return null;
			}
			int tryBlockStart = tryBlock.getBegin().get().line;
			int tryBlockEnd = tryBlock.getEnd().get().line;
			if (lineNumber > tryBlockEnd || lineNumber < tryBlockStart) {
				return null;
			}
			// Also make sure that this is within the loop body if in a loop fix
			if (warning.isLoopFix) {
				if (tryBlockStart < warning.loopStartLine) {
					// So we have a loop outside the try block, which we can't count.
					// But we also need to check if there is another try block inside the loop.
					return tryBlock.accept(this,null);
				}
			}
			// Mark this as the current deepest 
			deepestMatchingTry = tryStmt;
			// Recursively check the children
			tryStmt.getTryBlock().accept(this, null);
			if (tryStmt.getFinallyBlock().isPresent()) {
				tryStmt.getFinallyBlock().get().accept(this,null);
			}
			tryStmt.getCatchClauses().accept(this, null);
			return null;
		}
	}

	private static Integer getLocationForFinally(Warning w, int lineNumberToCheck){
		CompilationUnit cu = CommonUtils.getCompilationUnit(w.sourceFilename);
		DeepestMatchingTryVisitor v = new DeepestMatchingTryVisitor(w,lineNumberToCheck);
		cu.accept(v,null);

		// Then return the end of the last catch block.
		TryStmt tryStmt = v.deepestMatchingTry;
		int maxLineNumber = -1;
		if (tryStmt == null) {
			System.out.println("WARNING: Null try statement matched:" + w);
			return null;
		}
		for (CatchClause cc : tryStmt.getCatchClauses()) {
			if (cc.hasRange()) {
				int ccEndLine = cc.getEnd().get().line;
				if (ccEndLine > maxLineNumber) {
					maxLineNumber = ccEndLine;
				}
			}
		}
		if (maxLineNumber == -1) {
			return null;
		} else {
			return maxLineNumber;
		}
	}

	/*
	 * private boolean warningEscapesTryCatch() {
	ISSABasicBlock warningBasicBlock = cfg.getBlockForInstruction(w.matchedInstruction.iIndex());
	HashSet<Integer> warningReachableCatchBlocks = getReachableCatchBlocks(warningBasicBlock);

	for (ISSABasicBlock b : FixUtils.getBasicBlocksForAllAliasOccurrences(w,cfg)) {
		for (int catchBlockNum : warningReachableCatchBlocks) {
			if (!(getReachableCatchBlocks(b).contains(catchBlockNum))) {
				return true;  // found one use which escapes the try block
			}
		}

	}
	return false;  // found no use which escapes
	 */

	/*
    private HashSet<Integer> getReachableCatchBlocks(ISSABasicBlock initialBB) {
		HashSet<Integer> reachableBBs = new HashSet<Integer>();
		// Simple Breadth-first search
		Set<Integer> visitedBBs = new HashSet<Integer>();
		Queue<ISSABasicBlock> activeSet = new LinkedList<ISSABasicBlock>();
		activeSet.add(initialBB);

		while(!activeSet.isEmpty()) {
			ISSABasicBlock temp = activeSet.remove();
			for (Iterator<ISSABasicBlock> it = cfg.getSuccNodes(temp) ; it.hasNext() ; ) {
				ISSABasicBlock succBB = it.next();
				if (succBB.isCatchBlock()) {
					reachableBBs.add(succBB.getNumber());
				}
				if (!visitedBBs.contains(succBB.getNumber())) {
					visitedBBs.add(succBB.getNumber());
					activeSet.add(succBB);
				}
			}
		}
		return reachableBBs;
	}
	 */

	/*
	public static boolean resourceInTryCatch(Warning w) {

		SSACFG cfg = w.matchedCgnode.getIR().getControlFlowGraph();
		for (ISSABasicBlock b : FixUtils.getBasicBlocksForAllAliasOccurrences(w,cfg)) {
			if (basicBlockHasCatchSuccessor(b,cfg)) {
				return true;
			}
		}
		// Couldn't find any such catch-block. So resource not in a catch block.
		return false;
	}
	 */

	/*
	private void computeContainedTryCatchFix() {

		//int finallyMethodLocation = getLocationForFinally();
		CommonUtils.printBasicBlocks(w.matchedCgnode);
		CommonUtils.printInstructions(w.matchedCgnode);
		Pair<Integer,Boolean> earliestPostDom = FixUtils.getEarliestPostDominatorInstruction(w,cfg,ir);
		if (earliestPostDom.fst == -1) {
			w.unfixable = true;
			w.comments += "Contained try-catch and no post-dominator found;";
			return;
		}
		int finallyMethodLocation = earliestPostDom.fst;
		String relativeLocation = "";
		if (earliestPostDom.snd) {
			relativeLocation = "below";
		} else {
			relativeLocation = "above";
		}
	 */
}
