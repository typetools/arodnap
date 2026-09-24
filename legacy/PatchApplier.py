import os
import shutil
import logging
from subprocess import run, CalledProcessError
import re
import subprocess
import glob
from Constants import COMPILED_CLASSES_FOLDER, SRC_FILES, JAVAC_WITH_FLAGS, PATCH_AND_LOGS_FOLDER
from collections import defaultdict
from utils import compute_p_flag

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s: %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S"
)

HERE = os.path.dirname(os.path.abspath(__file__))

class BenchmarkCompiler:
    def __init__(self, benchmark_path):
        self.benchmark_path = benchmark_path
        self.benchmark_name = os.path.basename(benchmark_path)
        self.compiled_classes_path = os.path.join(benchmark_path, COMPILED_CLASSES_FOLDER)
        os.makedirs(self.compiled_classes_path, exist_ok=True)

    def generate_classpath(self):
        lib_folder = os.path.join(self.benchmark_path, "lib")
        jars = []
        if os.path.exists(lib_folder):
            jars = glob.glob(os.path.join(lib_folder, "*.jar"))
        classpath_entries = [lib_folder] + jars
        classpath = ":".join(classpath_entries)
        return classpath

    def prepare_sources(self):
        find_srcs_command = f'find {self.benchmark_path}/src -name "*.java" > {SRC_FILES}'
        os.system(find_srcs_command)

    def compile_benchmark(self):
        self.prepare_sources()
        classpath = self.generate_classpath()
        javac_command = (
            f"{JAVAC_WITH_FLAGS} -g "
            f"-d {self.compiled_classes_path} -cp {classpath} @{SRC_FILES}"
        )

        result = subprocess.run(javac_command, shell=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        return result.stdout, result.stderr

    @staticmethod
    def compare_outputs(before_output, after_output):
        return before_output != after_output  # True if outputs differ

# Initialize a counter for failed patches
failed_patch_count = 0
total_patches_count = 0
compilation_errors = 0

# Function to create folder if it does not exist
def create_folder_if_not_exists(folder):
    if not os.path.exists(folder):
        os.makedirs(folder)

# Function to move all `.diff` files from one folder to another
def move_files(source_folder, destination_folder, patterns=["*.diff"]):
    import glob
    for pattern in patterns:
        for file_path in glob.glob(os.path.join(source_folder, pattern)):
            if os.path.isfile(file_path):
                shutil.copy2(file_path, os.path.join(destination_folder, os.path.basename(file_path)))
                
def count_errors(log):
    errors = re.findall(r'(\d+) errors?', log)
    return sum(int(e) for e in errors)

# Function to apply a single patch file from the root of the project using `-p7`
def apply_patch(patch_file, source_project_path, log_file_path, error_count_before=0):
    global failed_patch_count, total_patches_count, compilation_errors
    total_patches_count += 1
    try:
        os.system(f"dos2unix {patch_file} > /dev/null 2>&1") # Convert patch file to Unix-style line endings
        print("Computed P flag for the patch file:", compute_p_flag(patch_file))
        dry_run_command = f"cd {source_project_path} && patch --dry-run --forward -p{compute_p_flag(patch_file)} -u --ignore-whitespace -i {patch_file}"
        run(dry_run_command, shell=True, check=True, cwd=source_project_path)
        patch_apply_command = f"cd {source_project_path} && patch --forward -p{compute_p_flag(patch_file)} -u --ignore-whitespace -i {patch_file}"
        run(patch_apply_command, shell=True, check=True, cwd=source_project_path)
        logging.info(f"Successfully applied patch: {patch_file}")
        # Compile the benchmark after applying the patch
        logging.info(f"Compiling benchmark after applying patch: {patch_file}")
        # remove the /src from the path benchmark_path
        root_benchmark_path = os.path.dirname(source_project_path)
        source_project_name = os.path.basename(root_benchmark_path)
        _, stderr_after = BenchmarkCompiler(root_benchmark_path).compile_benchmark()
        error_count_after = count_errors(stderr_after)
        # Compare outputs
        if error_count_before != error_count_after:
            logging.error(f"Outputs differ after applying patches for {source_project_name}.")
            compilation_errors += 1
            # write the diff of comparsion and proejct names to a log file
            with open("compilation_errors.log", "a") as log_file:
                log_file.write(f"Outputs differ after applying patches for {source_project_name}.\n")
                log_file.write(f"After:\n{stderr_after}\n")
                log_file.write("\n")
            # reverse the patch
            reverse_patch_command = f"cd {source_project_path} && patch --reverse -p{compute_p_flag(patch_file)} -u --ignore-whitespace -i {patch_file}"
            run(reverse_patch_command, shell=True, check=True, cwd=source_project_path)
            logging.warning(f"Reversed patch: {patch_file}")
    except CalledProcessError as e:
        logging.error(f"Failed to apply patch {patch_file}: {e}")
        with open(log_file_path, "a") as log_file:
            log_file.write(f"Failed to apply patch {patch_file}: {e}\n")
        # Increment the failed patch count
        failed_patch_count += 1
        return False
    return True

# Main function to copy patches to the benchmark and apply them
def process_benchmark(source_project_path, patches_folder):
    source_project_name = os.path.basename(source_project_path)
    src_folder = os.path.join(source_project_path, "src")
    
    # Ensure log file is located in the source directory
    log_file_path = os.path.join("failed_patches", f"{source_project_name}.log")

    # create the log file path if it does not exist
    create_folder_if_not_exists("failed_patches")

    # Apply each patch from the llm_patches folder to the benchmark
    stderr_before = ""
    error_count_before = 0
    if os.path.exists(patches_folder) and os.listdir(patches_folder):
        # capture the before compilation output
        # Compile before patch
        _, stderr_before = BenchmarkCompiler(source_project_path).compile_benchmark()
        error_count_before = count_errors(stderr_before)
    if os.path.exists(patches_folder):
        for patch_file in os.listdir(patches_folder):
            logging.info(f"Applying patch: {patch_file}")
            patch_file_path = os.path.join(patches_folder, patch_file)
            
            # Copy patch to the project root (src) and apply it
            # patch_in_root = os.path.join(src_folder, os.path.basename(patch_file_path))
            print(f"Copying patch {patch_file_path} to {src_folder}")
            shutil.copy(patch_file_path, src_folder)

            # Apply patch from the project root using `-p7` and log failures
            patch_file_path = os.path.join(src_folder, patch_file)
            success = apply_patch(patch_file_path, src_folder, log_file_path, error_count_before)

            # Clean up patch after applying
            # os.remove(patch_file_path)

    # Move the log and failed patches to the patch and logs folder
    destination_folder = os.path.join(PATCH_AND_LOGS_FOLDER, source_project_name, "LLM")
    if os.path.exists(log_file_path):
        if not os.path.exists(destination_folder):
            os.makedirs(destination_folder)
        shutil.move(log_file_path, os.path.join(destination_folder, os.path.basename(log_file_path)))
    

def apply_llm_generated_patches(source_project_path, patches_and_logs_folder):
    source_project_name = os.path.basename(source_project_path)
    patches_folder = os.path.join(patches_and_logs_folder, source_project_name, "LLM", "patches", source_project_name)
    process_benchmark(source_project_path, patches_folder)

    # Print the summary of failed patches
    logging.info(f"Summary: {total_patches_count} patches applied, {failed_patch_count} failed.")
    logging.info(f"Compilation errors encountered: {compilation_errors}")