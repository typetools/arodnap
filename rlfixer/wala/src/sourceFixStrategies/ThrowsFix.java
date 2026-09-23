package sourceFixStrategies;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.visitor.GenericVisitorAdapter;
import com.ibm.wala.classLoader.IBytecodeMethod;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.shrike.shrikeCT.InvalidClassFileException;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.SSACFG;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAInvokeInstruction;

import main.FinalizerMappingLoader;
import main.ResourceAliasIdentification;
import utils.CommonUtils;
import utils.Pair;
import utils.Warning;
/*
 * The kind of fix we need when the resource is not inside a 
 * try-catch block, and its method instead declares a thrown exception.
 */
public class ThrowsFix {
	Warning w;
	SSACFG cfg;
	IR ir;

	public ThrowsFix(Warning a) {
		w = a;
		ir = w.matchedCgnode.getIR();
		cfg = ir.getControlFlowGraph();
	}

	public void computeThrowsFix() {
		// Step 1: Figure out where to add the try block
		ArrayList<Integer> resourceLineNumbers = FixUtils.computeLineNumbersForResource(w);
		int tryBlockStart = Collections.min(resourceLineNumbers);
		if (tryBlockStart == Integer.MAX_VALUE) {
			w.unfixable = true;
			w.comments += "Could not figure out where to start try block;";
			return;
		}
		String line1 = "Add following code above line:" + tryBlockStart + " (" + w.sourceFilename + ")\n";
		String line2 = "try{";
		w.sourceLevelFixes.add(line1+line2);
		
		/* Todo: Deal with the corner case where the first definition is inside an 'if-else' */
		
		// Step 2: Figure out where to add the finally block.
		/*
		Pair<Integer,Boolean> earliestPostDom = FixUtils.getEarliestPostDominatorInstruction(w,cfg,ir);
		int finishBlockStart = earliestPostDom.fst;
		String relativeLocation = "";
		if (earliestPostDom.snd) {
			relativeLocation = "below";
		} else {
			relativeLocation = "above";
		}
		 */
		int lastResourceUse = getLastResourceUseLine(w);
		if (lastResourceUse == -1) {
			w.unfixable = true;
			w.comments += "Could not figure out last resource use;";
			return;
		}
		Integer finallyBlockStart = getFinallyBlockLocation(tryBlockStart,lastResourceUse);
		if (finallyBlockStart == null) {
			w.unfixable = true;
			w.comments += "Could not figure out finally block location;";
			return;
		}
		String pointerToClose = FixUtils.getPointerToClose(w);
		String line3 = "Add following code after line:" + finallyBlockStart + " (" + w.sourceFilename + ")\n";
		String line4 = "}finally{\n    try{ " + pointerToClose  + "." + FinalizerMappingLoader.getFinalizerMethod(w.getQualifiedResourceName()) + "(); } catch(Exception e){ e.printStackTrace(); }\n}\n";
		String line5 = "";
		if (pointerToClose == CommonUtils.newVariableName) {
			int resourceLine = FixUtils.getSourceLine(w.matchedInstruction, w.matchedCgnode);
			line5 = "// where variable " + CommonUtils.newVariableName + " points to the resource from line " + resourceLine + "\n";
		}
		w.sourceLevelFixes.add(line3+line4+line5);
	}


	/*
	 * The high level idea is that the finally block location is the
	 * last line where the resource (or aliases) is used, but subject
	 * to the scoping rules.
	 */
	private Integer getFinallyBlockLocation(int tryBlockStart, int lastResourceUse) {
		CompilationUnit cu = CommonUtils.getCompilationUnit(w.sourceFilename);
		return cu.accept(new TryFinallyLocationVisitor(tryBlockStart,lastResourceUse),0);
	}

	private class TryFinallyLocationVisitor extends GenericVisitorAdapter<Integer,Integer>{
		int tryBlockStart;
		int lastResourceUse;
		private TryFinallyLocationVisitor(int b, int c) {
			tryBlockStart = b;
			lastResourceUse = c;
		}

		@Override
		public Integer visit(BlockStmt blockStmt, Integer currentDepth) {
			// Make sure this is a block of interest.
			if (!blockStmt.hasRange()) {
				return null;
			}
			int blockStart = blockStmt.getBegin().get().line;
			int blockEnd = blockStmt.getEnd().get().line;
			if (tryBlockStart > blockEnd || tryBlockStart < blockStart) {
				return null;
			}
			// Check if we are at the right depth
			boolean correctScopingDepth = false;
			for (Statement st : blockStmt.getStatements()) {
				if (!st.hasRange()) {
					return null;
				}
				int statementStart = st.getBegin().get().line;
				if (statementStart == tryBlockStart) {
					correctScopingDepth = true;
				}
			}
			// If we are at the right depth, figure out the position for
			// the finally block - the line after the last use,
			// but adjusted for scoping.
			if (correctScopingDepth) {
				for (Statement st : blockStmt.getStatements()) {
					if (!st.hasRange()) {
						return null;
					}
					int statementStart = st.getBegin().get().line;
					int statementEnd = st.getEnd().get().line;
					if (statementStart <= lastResourceUse && lastResourceUse <= statementEnd ) {
						// found the last use.
						return statementEnd;
					}
				}
			} else {
				for (Statement st : blockStmt.getStatements()) {
					Integer result = st.accept(this, null);
					if (result != null) {
						return result;
					}
				}
			}
			// Couldn't find the right location.
			return null;
		}
	}


	public static int getLastResourceUseLine(Warning w) {
		int maxSrcLine = -1;
		List<Integer> resourceAliases = ResourceAliasIdentification.getAllResourceAliases(w.matchedCgnode, w.matchedInstruction.getDef());
		resourceAliases.add(w.matchedInstruction.getDef());

		for (int aliasVariableNum : resourceAliases) {
			// Add each use of the alias
			Iterator<SSAInstruction> useInstructionsIterator = w.matchedCgnode.getDU().getUses(aliasVariableNum);
			while(useInstructionsIterator.hasNext()) {
				SSAInstruction useIns = useInstructionsIterator.next();
				if (useIns == null || useIns.iIndex() < 0) {
					continue;
				}
				if (CommonUtils.isCloseStatement(useIns)) {
					continue; // don't want to consider the close statements since we are going to remove them.
				}
				int sourceLineNum = FixUtils.getSourceLine(useIns, w.matchedCgnode);
				if (sourceLineNum > maxSrcLine) {
					maxSrcLine = sourceLineNum;
				}
			}
		}
		return maxSrcLine;
	}
	
	
	/*
	// Gets the earliest definition among all resource aliases
	private int getTryBlockStartLine() {
		int earliestSourceLine = Integer.MAX_VALUE;
		List<Pair<CGNode, Integer>> resourceAliases = ResourceAliasIdentification.getAllResourceAliases(w.matchedCgnode, w.matchedInstruction.getDef());
		resourceAliases.add(new Pair<CGNode, Integer>(w.matchedCgnode, w.matchedInstruction.getDef()));
		for (Pair<CGNode, Integer> alias : resourceAliases) {
			SSAInstruction ins = w.matchedCgnode.getDU().getDef(alias.snd);
			if (ins == null || ins.iIndex() < 0) {
				continue;
			}
			IBytecodeMethod<?> method = (IBytecodeMethod<?>)w.matchedCgnode.getMethod();
			try {
				int bytecodeIndex = method.getBytecodeIndex(ins.iIndex());
				int sourceLineNum = method.getLineNumber(bytecodeIndex);
				if (sourceLineNum < earliestSourceLine) {
					earliestSourceLine = sourceLineNum;
				}
			} catch (InvalidClassFileException e) {
				System.out.println("ERROR: Invalid class file exception.");
			}
		}
		return earliestSourceLine;
	}	
	*/

}
