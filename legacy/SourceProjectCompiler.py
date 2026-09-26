import os
import shutil
import logging
from Constants import COMPILED_CLASSES_FOLDER, SRC_FILES, JAVAC_WITH_FLAGS
import glob
from collections import defaultdict

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s: %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S"
)

class SourceProjectCompiler:
    @staticmethod
    def compile_benchmark_if_patched(source_project_path):
        """Recompile the benchmark jar if patches are detected."""
        if True or any(file.endswith(".patch") for file in os.listdir(f"{source_project_path}/src")):
            logging.info("Recompiling benchmark jar...")
            SourceProjectCompiler.compile_benchmark_and_generate_jar(source_project_path)

    @staticmethod
    def compile_benchmark_and_generate_jar(source_project_path):
        compiled_classes_path = os.path.join(source_project_path, COMPILED_CLASSES_FOLDER)
        if not os.path.exists(compiled_classes_path):
            os.mkdir(compiled_classes_path)

        find_srcs_command = f'find {source_project_path}/src -name "*.java" > {SRC_FILES}'
        os.system(find_srcs_command)

        lib_folder = os.path.join(source_project_path, "lib")
        jars = []
        if os.path.exists(lib_folder):
            jars = glob.glob(os.path.join(lib_folder, "*.jar"))
        classpath_entries = [lib_folder] + jars
        classpath = ":".join(classpath_entries)
        
        jarfile = os.path.basename(source_project_path) + ".jar"
        jarfile_path = os.path.join(source_project_path, "jarfile", jarfile)

        javac_command = (
            f"{JAVAC_WITH_FLAGS} -g -d {compiled_classes_path} "
            f"-cp {classpath} "
            f"@{SRC_FILES}"
        )
        
        
        os.system(javac_command)

        temp_dir = os.path.join(source_project_path, "temp")
        os.makedirs(temp_dir, exist_ok=True)
        os.system(f"cp -r {compiled_classes_path}/* {temp_dir}")
        os.system(f"cp -r {lib_folder}/* {temp_dir}")

        jar_command = f"jar cf {jarfile_path} -C {temp_dir} ."
        
        os.system(jar_command)

        shutil.rmtree(temp_dir)

        logging.info(f"Benchmark {os.path.basename(source_project_path)} compiled successfully.")

if __name__ == "__main__":
    import sys
    project_path = sys.argv[1]
    SourceProjectCompiler.compile_benchmark_and_generate_jar(project_path)