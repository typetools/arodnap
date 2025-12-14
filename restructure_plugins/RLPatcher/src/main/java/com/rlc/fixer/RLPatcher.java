package com.rlc.fixer;

import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

public class RLPatcher {
    public static void main(String[] args) throws Exception {
        if (args.length < 2 || !args[0].equals("--prompt")) {
            System.err.println("Usage: java -jar RLFixPatcher.jar --prompt path/to/prompt.(json|txt)");
            System.exit(1);
        }

        // Remove existing patch to avoid confusion
        Path patchPath = Paths.get("rlfixer.patch");
        Files.deleteIfExists(patchPath);

        Path promptPath = Paths.get(args[1]);
        String promptText = Files.readString(promptPath);

        // NEW: parse a list of leaks (JSON or legacy text)
        List<PromptInfo> infos = PromptParser.parseAll(promptText);
        if (infos == null || infos.isEmpty()) {
            System.err.println("❌ Failed to parse prompt(s).");
            return;
        }

        // Basic sanity: all leaks should target the same file/method batch
        // (Your upstream Python already groups per method. We just assert same file.)
        String leakFile = infos.get(0).leakSourceFile;
        boolean sameFile = infos.stream().allMatch(pi -> leakFile.equals(pi.leakSourceFile));
        if (!sameFile) {
            System.err.println("❌ Parsed leaks refer to multiple files. Expected a single method batch.");
            return;
        }

        // Log what we parsed
        System.out.println("✅ Parsed " + infos.size() + " leak(s):");
        for (int i = 0; i < infos.size(); i++) {
            PromptInfo pi = infos.get(i);
            System.out.println("  [" + (i + 1) + "] File: " + pi.leakSourceFile
                    + " | CF line: " + pi.cfLeakLine
                    + " | RL line: " + pi.fixLeakLine
                    + " | Type: " + pi.resourceType
                    + " | Patch: " + pi.patchType
                    + " | Finalizer: " + pi.finalizerMethod);
        }

        // Derive project root (walk up until '/src' then go one up) — keep your logic
        Path leakPath = Paths.get(leakFile);
        Path projectRoot = leakPath.toAbsolutePath().normalize();
        while (projectRoot != null && !projectRoot.endsWith("src")) {
            projectRoot = projectRoot.getParent();
        }
        if (projectRoot == null) {
            System.err.println("❌ Could not determine project root from leak file.");
            return;
        }
        projectRoot = projectRoot.getParent(); // one level up from /src

        // Compile before patch (baseline)
        List<String> baselineOutput = CompilerUtils.compile(projectRoot.toString());

        // For now we only support ONLY_FINALLY in this version
        boolean allOnlyFinally = infos.stream().allMatch(pi -> pi.patchType == PatchType.ONLY_FINALLY);
        boolean allTryWrapAndFinally = infos.stream().allMatch(pi -> pi.patchType == PatchType.TRY_WRAP_AND_FINALLY);
        boolean allPreCloseFieldBefore = infos.stream().allMatch(pi -> pi.patchType == PatchType.PRE_CLOSE_FIELD_BEFORE);
        boolean success = false;
        if (allOnlyFinally) {
            System.out.println("Applying ONLY_FINALLY patches...");
            success = OnlyFinallyTransformer.apply(infos, projectRoot.toString(), baselineOutput);
        } else if (allTryWrapAndFinally) {
            System.out.println("Applying TRY_WRAP_AND_FINALLY patches...");
            success = TryWrapAndFinallyTransformer.apply(infos, projectRoot.toString(), baselineOutput);
        } else if (allPreCloseFieldBefore) {
            System.out.println("Applying PRE_CLOSE_FIELD_BEFORE patches...");
            success = PreCloseFieldBeforeTransformer.apply(infos, projectRoot.toString(), baselineOutput);
        } else {
            System.err.println(
                    "❌ Mixed patch types detected. Only ALL ONLY_FINALLY or ALL TRY_WRAP_AND_FINALLY is supported.");
            return;
        }

        // Batch apply (single unified patch)
        if (success) {
            System.out.println("✅ Patch applied successfully: " + leakFile);
        } else {
            System.out.println("❌ Patch failed (compilation check failed): " + leakFile);
        }
    }
}
