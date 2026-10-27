import os
import shutil
import logging
import glob
import Constants
from SourceProjectCompiler import SourceProjectCompiler

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s: %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S"
)

def create_folder_if_not_exists(folder):
    if not os.path.exists(folder):
        os.makedirs(folder, exist_ok=True)
        
def move_file_ignore_existing(source, destination_folder):
    """Move a file to the destination folder, ignoring if it already exists."""
    destination_file = os.path.join(destination_folder, os.path.basename(source))
    
    # Check if the file already exists in the destination and remove it
    if os.path.exists(destination_file):
        os.remove(destination_file)  # Remove the existing file before moving the new one
    
    # Move the file
    shutil.move(source, destination_file)
    

def move_files(source_folder, destination_folder, file_patterns):
    """Move files from source to destination based on patterns."""
    for pattern in file_patterns:
        for file_path in glob.glob(f"{source_folder}/{pattern}"):
            move_file_ignore_existing(file_path, destination_folder)

def store_inference_results(source_project_path, destination_folder, stage="initial"):
    """Store the inference results in the specified folder."""
    rlc_inf_folders = ["wpi-out"]
    inference_out_folder = f"{destination_folder}/{stage}_inference"
    create_folder_if_not_exists(inference_out_folder)
    for rlc_inf_folder in rlc_inf_folders:
        if os.path.exists(f"{inference_out_folder}/{rlc_inf_folder}"):
            shutil.rmtree(f"{inference_out_folder}/{rlc_inf_folder}")
        if os.path.exists(f"{source_project_path}/{rlc_inf_folder}"):
            shutil.move(f"{source_project_path}/{rlc_inf_folder}", inference_out_folder)
    log_files = "rlc-inference-log.txt"
    # copy the log file to the inference out folder
    if os.path.exists(f"{source_project_path}/{log_files}"):
        if os.path.exists(f"{inference_out_folder}/{log_files}"):
            os.remove(f"{inference_out_folder}/{log_files}")
        shutil.move(f"{source_project_path}/{log_files}", inference_out_folder)
        
def store_patch_and_log_files(destination_folder):
    """Store patch and log files for the benchmark."""
    patch_files = glob.glob(f"{Constants.SOURCE_PROJECT_FOLDER}/src/*.patch")
    create_folder_if_not_exists(destination_folder)
    for patch_file in patch_files:
        move_file_ignore_existing(patch_file, destination_folder)

    log_files = ["ep-log.txt"]
    for log_file in log_files:
        log_file_path = f"{Constants.SOURCE_PROJECT_FOLDER}/{log_file}"
        if os.path.exists(log_file_path):
            move_file_ignore_existing(log_file_path, destination_folder)
            
def clean_up_current():
    # clean up build folder, cf_srcs.txt file
    if os.path.exists(Constants.COMPILED_CLASSES_FOLDER):
        shutil.rmtree(Constants.COMPILED_CLASSES_FOLDER)
    if os.path.exists(Constants.SRC_FILES):
        os.remove(Constants.SRC_FILES)
    if os.path.exists("build"):
        shutil.rmtree("build")
 
       
def run_rlfixer(source_project_name, results_folder, source_project_path):
    """Run RLFixer if applicable."""
    logging.info(f"Running RLFixer for {source_project_name}...")
    SourceProjectCompiler.compile_benchmark_if_patched(source_project_path)

    rlfixer_output_fixes_folder = f"{Constants.RLFIXER_RESULTS_FOLDER}/fixes"
    rlfixer_output_debug_folder = f"{Constants.RLFIXER_RESULTS_FOLDER}/debug"
    create_folder_if_not_exists(rlfixer_output_fixes_folder)
    create_folder_if_not_exists(rlfixer_output_debug_folder)
    # the wpi-out directory is created by the CF analysis
    # its in the patch and logs folder, there can be two folders intitial_inference and close_injector_inference
    # first look at the close_injector_inference folder, if it exists, use it
    # otherwise look at the initial_inference folder
    wpi_out_dir = f"{Constants.PATCH_AND_LOGS_FOLDER}/{source_project_name}/close_injector_inference/wpi-out"
    # if the patch doesnt exist or the patch is empty, use the initial inference folder
    if not os.path.exists(wpi_out_dir) or os.path.getsize(wpi_out_dir) == 0:
        wpi_out_dir = f"{Constants.PATCH_AND_LOGS_FOLDER}/{source_project_name}/initial_inference/wpi-out"
    HERE = os.path.dirname(os.path.abspath(__file__))
    os.system(f"python3 {HERE}/RLFixerRunner.py --tool checkerframework "
              f"--results {results_folder}/{source_project_name}.txt --benchmark {os.path.dirname(source_project_path)} "
              f"--output {rlfixer_output_fixes_folder} --debug {rlfixer_output_debug_folder}"
              f" --wpioutdir {wpi_out_dir} ")
    
    
def compute_p_flag(patch_file_path):
    with open(patch_file_path, 'r') as f:
        for line in f:
            if line.startswith('--- '):
                old_path = line.split()[1]
                break
        else:
            return 6

    parts = old_path.split('/')

    # Find index where the filename part starts (relative path inside project)
    # We assume you want to strip up to 'src/...'
    if 'src' in parts:
        p_number = parts.index('src') + 1
    else:
        return 6

    return p_number
