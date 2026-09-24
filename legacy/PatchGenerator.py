import os
import logging
import shutil
from Constants import (
    RLFIXER_RESULTS_FOLDER, PATCH_AND_LOGS_FOLDER, RESULTS_BASE_FOLDER
)

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s: %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S"
)

# Define the paths for common folders
HERE = os.path.dirname(os.path.abspath(__file__))


LLM_SCRIPTS_FOLDER = os.path.join(HERE, "openai_batch_job")

# Function to create folder if it does not exist
def create_folder_if_not_exists(folder):
    if not os.path.exists(folder):
        os.makedirs(folder)

# Function to forcefully move contents from source to destination (replaces if exists)
def force_move(source_path, destination_path):
    # If destination exists, remove it
    if os.path.exists(destination_path):
        if os.path.isdir(destination_path):
            shutil.rmtree(destination_path)  # Remove directory if it exists
        else:
            os.remove(destination_path)  # Remove file if it exists
    # Move the source to the destination
    shutil.move(source_path, destination_path)

# Function to move all contents from a folder with force replacement
def move_all_contents(source_folder, destination_folder):
    for item in os.listdir(source_folder):
        source_path = os.path.join(source_folder, item)
        destination_path = os.path.join(destination_folder, item)
        create_folder_if_not_exists(destination_folder)
        force_move(source_path, destination_path)

# Main function for patch generation
def generate_patches(source_project_name, source_project_path):
    logging.info("Generating patches from RLFixer results...")

    LLM_OUT_FOLDER = os.path.join(PATCH_AND_LOGS_FOLDER, source_project_name, "LLM")
    input_folder = os.path.join(LLM_OUT_FOLDER, "input")  # Folder to store input files
    output_folder = os.path.join(LLM_OUT_FOLDER, "output")  # Folder to store output files
    errors_folder = os.path.join(LLM_OUT_FOLDER, "errors")  # Folder to store error files
    prompts_folder = os.path.join(LLM_OUT_FOLDER, "prompts")  # Folder to store prompts
    patches_folder = os.path.join(LLM_OUT_FOLDER, "patches")  # Folder to store patches
    logs_folder = os.path.join(LLM_OUT_FOLDER, "logs")
    rlfixer_output_fixes_folder = f"{RLFIXER_RESULTS_FOLDER}/fixes"
    
    # Execute the command for generating patches using RLFixer
    os.system(f"python3 {LLM_SCRIPTS_FOLDER}/main.py --rlfixer_folder {rlfixer_output_fixes_folder} --cf_warnings_folder {RESULTS_BASE_FOLDER}")

    # Move all input, output, prompts, and patches to the common folder
    folders_to_move = {
        "batch_input": input_folder,
        "batch_output": output_folder,
        "batch_errors": errors_folder,
        "prompts": prompts_folder,
        "patches": patches_folder,
    }

    for folder_name, destination_folder in folders_to_move.items():
        current_folder = f"{folder_name}/"  # Temporary folder (adjust based on where RLFixer stores them)
        if os.path.exists(current_folder) and os.listdir(current_folder):
            logging.info(f"Moving {folder_name} to the common folder...")
            create_folder_if_not_exists(destination_folder)
            move_all_contents(current_folder, destination_folder)
            shutil.rmtree(current_folder)

    # Moving specific log files
    specific_files_to_move = [
        "unmatched_methods.log",
        "skipped_prompts.json",
        "token_limit_errors.log",
        "llm_out.log",
    ]
    for i in range(1, 1):  # Assuming N could be large, adjust as necessary
        specific_files_to_move.append(f"merged_batch_{i}.jsonl")

    for file_name in specific_files_to_move:
        if os.path.exists(file_name):
            logging.info(f"Moving {file_name} to the logs folder...")
            create_folder_if_not_exists(logs_folder)
            force_move(file_name, os.path.join(logs_folder, os.path.basename(file_name)))

    logging.info("--------------------\n")

