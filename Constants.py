# Config.py
import os

HERE = os.path.dirname(os.path.abspath(__file__))

# Paths
SOURCE_PROJECT_FOLDER = "null"
RESULTS_BASE_FOLDER = os.path.abspath(os.path.join("tool_results", "rlc_results"))
RLFIXER_RESULTS_FOLDER = os.path.abspath(os.path.join("tool_results", "rlfixer_results"))
PATCH_AND_LOGS_FOLDER = os.path.abspath(os.path.join("tool_results", "inference_and_patches"))
COMPILED_CLASSES_FOLDER = "cf_classes"
SRC_FILES = "cf_srcs.txt"
JARS_ROOT = os.path.abspath(os.path.join(HERE, "restructure_plugins", "prebuilt_plugin_jars"))
OWNING_FIELD_JAR = os.path.join(JARS_ROOT, "OwningFieldFixer-1.0-SNAPSHOT.jar")
CLOSE_INJECTOR_JAR = os.path.join(JARS_ROOT, "AutoCloseInjector-1.0-SNAPSHOT.jar")
STUBS_FOLDER = os.path.abspath(os.path.join(HERE, "checker_framework", "stubs"))

# `javac` flags
JAVAC_WITH_FLAGS = (
    "javac "
    "-J--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED "
    "-J--add-exports=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED "
    "-J--add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED "
    "-J--add-exports=jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED "
    "-J--add-exports=jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED "
    "-J--add-exports=jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED "
    "-J--add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED "
    "-J--add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED "
    "-J--add-opens=jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED"
)
# Checker Framework command
CF_ROOT = os.path.abspath(os.path.join(HERE, "checker_framework", "checker-framework-3.49.0"))
CF_COMMAND = "-processor org.checkerframework.checker.resourceleak.ResourceLeakChecker -Adetailedmsgtext"
CF_DIST_JAR_ARG = f"-processorpath {CF_ROOT}/checker/dist/checker.jar"
CHECKER_QUAL_JAR = f"{CF_ROOT}/checker/dist/checker-qual.jar"

# Timeouts
TIMEOUT = 60 * 60  # 60 minutes


# Additional settings
SKIP_COMPLETED = False  # skips if the output file is already there

RLC_INFERENCE_SCRIPT_PATH = os.path.abspath(os.path.join(HERE, "helpers", "wpi.sh"))
RLC_INFERENCE_LOG_FILENAME = "rlc-inference-log.txt"

EP_FIELD_ENHANCEMENT_SCRIPT_PATH = os.path.abspath(os.path.join(HERE, "helpers", "ep.sh"))
EP_FIELD_ENHANCEMENT_LOG_FILENAME = "ep-log.txt"

# LLM_SCRIPTS_FOLDER = "/home/anon123/RLF/cf-rlc/cf_analysis/scripts/llm/batch"
