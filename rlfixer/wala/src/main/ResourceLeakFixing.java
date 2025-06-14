package main;

import sourceFixStrategies.*;
import utils.CommonUtils;
import utils.ResourceEscapeType;
import utils.Warning;

public class ResourceLeakFixing {

	public static void computeSourceCodeFix(Warning w) {
		if (w.escapeTypes.contains(ResourceEscapeType.FIELD_SOURCE)) {
			w.escapeTypes.remove(ResourceEscapeType.FIELD_SOURCE);
			w.escapeTypes.add(ResourceEscapeType.FIELD);
		}
		// Pick the strategy based on the classification.
		// we are not going to suggest a fix if the resource
		// escapes to a field or array. These cases are too hard.
		if (false && w.isNonFinalFieldOverwrite && w.escapeTypes.isEmpty()) {
			owningFix(w);
			return;
		}
		else if (w.escapeTypes.contains(ResourceEscapeType.FIELD)) {
			w.unfixable = true;
			w.comments += "Field escape;";
			return;
		}
		else if (w.escapeTypes.contains(ResourceEscapeType.ARRAY)){
			w.unfixable = true;
			w.comments += "Array (or collection/map) escape;";
			return; 
		}
		else if (w.escapeTypes.contains(ResourceEscapeType.RETURN)) {
			ReturnFix.computeReturnFix(w);
			w.sourceLevelFixes.add(0, "NOTE: Resource escapes via return statement and needs to be closed in the callers of " + w.matchedCgnode.getMethod().getName());
			w.comments += "Return Fix;";
		} 
		else if (w.escapeTypes.contains(ResourceEscapeType.PARAM)) {
			ParamFix.computeParameterFix(w);
			w.sourceLevelFixes.add(0, "NOTE: Resource escapes via a parameter and needs to be closed in the callers of this function."  + w.matchedCgnode.getMethod().getName());
			w.comments += "Parameter Fix;";
		} 
		else if (LoopFix.resourceInForLoop(w)){
			w.comments += "Loop Fix;";
			w.isLoopFix = true;
			if (!LoopFix.resourceReleasableAtLoopEnd(w)) {
				// this is a hard case which we won't attempt to fix.
				w.unfixable = true;
				w.comments += "Resource not releasable at loop end;";
				return;  
			} else {
				// else we just fix it like any other case.
				simpleFix(w);
			}
		} else {
			simpleFix(w);
		}
		
		// Also need to delete unnecessary close statements
		if (!w.unfixable) {
			new RemoveExistingClosesFix(w).removeCloses();
		}
	}
	
	public static void simpleFix(Warning w) {
		if (TryCatchFix.resourceInTryCatch(w)) {
			new TryCatchFix(w).computeTryCatchFix();
			w.comments += "Try-catch Fix;";
		} else {
			new ThrowsFix(w).computeThrowsFix();
			w.comments += "Normal Fix;";
		}
	}

	private static void owningFix(Warning w) {
		int leakedLine = w.lineNumber;
		StringBuilder sb = new StringBuilder();
		sb.append("Add following code before line:")
				.append(leakedLine)
				.append(" (")
				.append(w.sourceFilename)
				.append(")\n");
		sb.append("if (this.")
				.append(w.owningFieldName)
				.append(" != null) {\n")
				.append("    try {\n")
				.append("        this.")
				.append(w.owningFieldName)
				.append(".")
				.append(FinalizerMappingLoader.getFinalizerMethod(w.getQualifiedResourceName()))
				.append("();\n")
				.append("    } catch (Exception e) {\n")
				.append("        // TODO\n")
				.append("    }\n")
				.append("}");
		w.sourceLevelFixes.add(sb.toString());
		w.comments += "Owning fix;";
	}

}