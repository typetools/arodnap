package com.rlc.fixer;

import java.util.ArrayList;
import java.util.List;

public class PromptInfo {
    public String leakSourceFile;     // From CF Warning
    public String rlfixerSourceFile;  // From RLFixer fix lines
    public String resourceType;       // From CF Warning
    public int cfLeakLine;            // Leak location from CF warning
    public int fixLeakLine;           // From RLFixer: line that references the resource

    public PatchType patchType;       // ONLY_FINALLY or TRY_WRAP_AND_FINALLY

    // For ONLY_FINALLY
    public int finallyInsertLine;

    // For TRY_WRAP_AND_FINALLY
    public int tryWrapStartLine;
    public int tryWrapEndLine;
    public List<Integer> linesToDelete = new ArrayList<>(); // Lines to delete in the original file

    public String allocationExprText;
    public String finalizerMethod = "close"; 
    public List<String> finalizerDefaultArgs = new ArrayList<>();
    int index;

    public int preInsertBeforeLine = -1;        // “before line: <n>”
    public String owningFieldName = null; // parsed from “field <name>” in CF
}
