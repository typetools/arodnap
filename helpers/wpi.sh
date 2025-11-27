#!/bin/bash
# set -eux

# This script is a template for the WPI loop for a project with -Ainfer=ajava
# added to its build file. Fill in the variables at the beginning of the
# script with values that make sense for your project; the values there
# now are examples.

# This scripts the projects path as an argument to run the WPI on.
PROJECT_PATH=$1
BENCHMARK_NAME=$(basename "$PROJECT_PATH")
CF_ROOT=$2
DATA_ROOT=$(dirname "$(dirname "$PROJECT_PATH")")

# Global variables
CF_DIST_JAR_ARG="-processorpath $CF_ROOT/checker/dist/checker.jar"
CHECKER_QUAL_JAR="$CF_ROOT/checker/dist/checker-qual.jar"
COMPILED_CLASSES_FOLDER="$PROJECT_PATH/classes"
lib_folder="$PROJECT_PATH/lib"
SRC_FILES="$PROJECT_PATH/cf_srcs.txt"

# Populate the source files information in SRC_FILES
find $PROJECT_PATH/src -name "*.java" > "$SRC_FILES"


CLASSPATH="$lib_folder:$CHECKER_QUAL_JAR"
for jar in "$lib_folder"/*.jar; do
  CLASSPATH="$CLASSPATH:$jar"
done

#-J-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=5005 \
#-AenableReturnsReceiverForRlc

BUILD_CMD="javac \
-J--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED \
-J--add-exports=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED \
-J--add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED \
-J--add-exports=jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED \
-J--add-exports=jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED \
-J--add-exports=jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED \
-J--add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED \
-J--add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED \
-J--add-opens=jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED \
$CF_DIST_JAR_ARG \
-processor org.checkerframework.checker.resourceleak.ResourceLeakChecker \
-Adetailedmsgtext \
-Aajava=$PROJECT_PATH/wpi-out \
-Ainfer=ajava \
-Awarns \
-Xmaxwarns 10000 \
-AshowPrefixInWarningMessages \
-J-Xmx32G \
-J-ea \
-g \
-d $COMPILED_CLASSES_FOLDER \
-cp $CLASSPATH \
@${SRC_FILES}"

echo $BUILD_CMD

CLEAN_CMD="rm -rf ./classes"

${BUILD_CMD} # Compile the program so that WPIOUTDIR is created.

echo "Running the WPI loop"

# Where should the output be placed at the end? This directory is also
# used to store intermediate WPI results. The directory does not need to
# exist. If it does exist when this script starts, it will be deleted.
# If you are using the subprojects script, set WPITEMPDIR to "$1"
WPITEMPDIR=$PROJECT_PATH/wpi-out
# Where is WPI's output placed by the Checker Framework? This is some
# directory ending in build/whole-program-inference. For most projects,
# this directory is just ./build/whole-program-inference .
# The example in this script is the output directory when running via the gradle plugin.
# (The CF automatically puts all WPI outputs in ./build/whole-program-inference,
# where . is the directory from which the javac command was invoked (ie, javac's
# working directory). In many build systems (e.g., Maven), that directory would be the project.
# But, some build systems, such as Gradle, cache build outputs in a central location
# per-machine, and as part of that it runs its builds from that central location.)
# The directory to use here might vary between build systems, between machines
# (e.g., depending on your local Gradle settings), and even between projects using the
# same build system (e.g., because of a project's settings.gradle file).

# Program needs to compiled before running script so WPI creates this directory.
# If you are using the subprojects script, set WPIOUTDIR to "$2"
WPIOUTDIR=build/whole-program-inference 

# Whether to run in debug mode. In debug mode, output is printed to the terminal
# at the beginning of each iteration, and the diff between each pair of iterations is
# saved in a file named iteration$count.diff, starting with iteration1.diff.
# (Note that these files are overwritten if they already exist.)
DEBUG=1

# End of variables. You probably don't need to make changes below this line.

rm -rf ${WPITEMPDIR}
mkdir -p ${WPITEMPDIR}
# Clean up
rm -f iteration*.diff

# Store all the intermediate ajava files for each iterations
WPIITERATIONOUTPUTS=$PROJECT_PATH/wpi-iterations
# rm -rf ${WPIITERATIONOUTPUTS}
mkdir -p ${WPIITERATIONOUTPUTS}

count=1
while : ; do
    if [[ ${DEBUG} == 1 ]]; then
    SECONDS=0
	echo "entering iteration ${count}"
    fi
    ${BUILD_CMD}
    ${CLEAN_CMD}
    # This mkdir is needed when the project has subprojects.
    mkdir -p "${WPITEMPDIR}"
    mkdir -p "${WPIOUTDIR}"
    DIFF_RESULT=$(diff -r ${WPITEMPDIR} ${WPIOUTDIR} || true)
    if [[ ${DEBUG} == 1 ]]; then
	echo "putting the diff for iteration $count into $(realpath iteration$count.diff)"
	echo ${DIFF_RESULT} > iteration$count.diff
    fi
    [[ "$DIFF_RESULT" != "" ]] || break
    rm -rf ${WPITEMPDIR}
    mv ${WPIOUTDIR} ${WPITEMPDIR}
    # Also store the intermediate WPI results
    mkdir -p "${WPIITERATIONOUTPUTS}/iteration${count}"
    cp -rf ${WPITEMPDIR}/* "${WPIITERATIONOUTPUTS}/iteration${count}"
    echo "ending iteration ${count}, time taken: $SECONDS seconds"
    echo
    ((count++))
done

# Clean up
rm -f $SRC_FILES iteration*.diff
rm -rf $PROJECT_PATH/build build