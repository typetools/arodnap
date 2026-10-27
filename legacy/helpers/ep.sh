#!/bin/bash
# set -eux

PROJECT_PATH=$1
SKIP_LOCAL=$2
JARS_PATH=$3
COMPILED_CLASSES_FOLDER="$PROJECT_PATH/classes"
SRC_FILES="$PROJECT_PATH/cf_srcs.txt"
lib_folder="$PROJECT_PATH/lib"
PATCHFILE_PATH="$PROJECT_PATH/src"

# Populate the source files information in SRC_FILES
find $PROJECT_PATH/src -name "*.java" > "$SRC_FILES"


BUILD_CMD="javac \
  -J--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED \
  -J--add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED \
  -J--add-exports=jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED \
  -J--add-exports=jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED \
  -J--add-exports=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED \
  -J--add-exports=jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED \
  -J--add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED \
  -J--add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED \
  -J--add-opens=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED \
  -J--add-opens=jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED \
  -XDcompilePolicy=simple \
  -processorpath $JARS_PATH/FieldCanBeFinalWithTryCatch-1.0-SNAPSHOT.jar:$JARS_PATH/error_prone_core-2.28.0-with-dependencies.jar:$JARS_PATH/dataflow-errorprone-3.45.0.jar \
  -d $COMPILED_CLASSES_FOLDER \
  '-Xplugin:ErrorProne -XepDisableAllChecks -Xep:FieldCanBeFinalWithTryCatch -XepPatchChecks:FieldCanBeFinalWithTryCatch -XepPatchLocation:$PATCHFILE_PATH' \
  -cp $lib_folder/*:$JARS_PATH/FieldCanBeFinalWithTryCatch-1.0-SNAPSHOT.jar \
  @${SRC_FILES}"


# echo $BUILD_CMD

# eval $BUILD_CMD

echo "Running the patch: FieldCanBeFinalWithTryCatch"
dos2unix $PROJECT_PATH/src/error-prone.patch
cd $PROJECT_PATH/src/ && patch --forward -p0 -u --ignore-whitespace -i error-prone.patch
mv $PROJECT_PATH/src/error-prone.patch $PROJECT_PATH/src/error-prone-FieldCanBeFinalWithTryCatch.patch

[[ "$SKIP_LOCAL" -eq 1 ]] && exit 0

BUILD_CMD="javac \
  -J--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED \
  -J--add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED \
  -J--add-exports=jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED \
  -J--add-exports=jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED \
  -J--add-exports=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED \
  -J--add-exports=jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED \
  -J--add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED \
  -J--add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED \
  -J--add-opens=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED \
  -J--add-opens=jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED \
  -XDcompilePolicy=simple \
  -processorpath $JARS_PATH/FieldCanBeLocalWithTryCatch-1.0-SNAPSHOT.jar:$JARS_PATH/error_prone_core-2.28.0-with-dependencies.jar:$JARS_PATH/dataflow-errorprone-3.45.0.jar \
  -d $COMPILED_CLASSES_FOLDER \
  '-Xplugin:ErrorProne -XepDisableAllChecks -Xep:FieldCanBeLocalWithTryCatch -XepPatchChecks:FieldCanBeLocalWithTryCatch -XepPatchLocation:$PATCHFILE_PATH' \
  -cp $lib_folder/*:$JARS_PATH/FieldCanBeLocalWithTryCatch/target/FieldCanBeLocalWithTryCatch-1.0-SNAPSHOT.jar \
  @${SRC_FILES}"


# echo $BUILD_CMD

# eval $BUILD_CMD


echo "Running the patch: FieldCanBeLocalWithTryCatch"
dos2unix $PROJECT_PATH/src/error-prone.patch
cd $PROJECT_PATH/src/ && patch --forward -p0 -u --ignore-whitespace -i error-prone.patch
mv $PROJECT_PATH/src/error-prone.patch $PROJECT_PATH/src/error-prone-FieldCanBeLocalWithTryCatch.patch


