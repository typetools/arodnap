import os
import logging
import argparse
import time
from RLCRunner import RLCRunner
from FieldEnhancementRunner import FieldEnhancementRunner
from CloseInjector import CloseInjector
from OwningFieldModifier import run_owning_field_plugin, check_for_owning_patch
from BenchmarkCleaner import BenchmarkCleaner
from utils import (
    create_folder_if_not_exists,
    store_inference_results,
    store_patch_and_log_files,
    clean_up_current,
    run_rlfixer
)
from CloseInjector import CloseInjector
import Constants
from RLPatcherRunner import run_rlpatcher_for_project

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s: %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S"
)


def run_arodnap():
    results_folder = f"{Constants.RESULTS_BASE_FOLDER}"
    create_folder_if_not_exists(results_folder)
    rlc_runner = RLCRunner(results_folder, run_rlc_inference=True)
    start_time = time.perf_counter()
    source_project_name = os.path.basename(
        Constants.SOURCE_PROJECT_FOLDER.rstrip('/'))
    logging.info(f"Running on {source_project_name}...")

    BenchmarkCleaner.restore_benchmark(Constants.SOURCE_PROJECT_FOLDER)

    # Convert the source files to Unix line endings
    os.system(f"cd {Constants.SOURCE_PROJECT_FOLDER}/src" +
              " && find . -type f -name \"*.java\" -exec dos2unix {} + > /dev/null 2>&1")

    # FieldEnhancementRunner.run_field_enhancements(
    #     source_project_name, Constants.SOURCE_PROJECT_FOLDER)

    rlc_runner.run_cf_analysis(
        source_project_name, Constants.SOURCE_PROJECT_FOLDER)
    store_inference_results(
        Constants.SOURCE_PROJECT_FOLDER, f"{Constants.PATCH_AND_LOGS_FOLDER}/{source_project_name}", stage="initial")
    return
    CloseInjector.run_close_injector(
        source_project_name, Constants.SOURCE_PROJECT_FOLDER, results_folder)
    if CloseInjector.check_for_close_injector_patch(Constants.SOURCE_PROJECT_FOLDER):
        logging.info(
            "Close Injector patch detected. Rerunning CF analysis before running RLFixer...")
        rlc_runner.run_cf_analysis(
            source_project_name, Constants.SOURCE_PROJECT_FOLDER, is_rerun=True)
        store_inference_results(
            Constants.SOURCE_PROJECT_FOLDER, f"{Constants.PATCH_AND_LOGS_FOLDER}/{source_project_name}", stage="close_injector")

    if os.path.exists(results_folder + "_ReRun/" + source_project_name + ".txt"):
        results_folder = results_folder + "_ReRun"

    logging.info(
        f"Running owning field modifier plugin on {source_project_name}.")
    run_owning_field_plugin(
        f"{results_folder}/{source_project_name}.txt",
        Constants.SOURCE_PROJECT_FOLDER,
    )

    if check_for_owning_patch(Constants.SOURCE_PROJECT_FOLDER):
        logging.info("Owning patch detected. Rerunning CF analysis again.")
        rlc_runner.run_cf_analysis(
            source_project_name, Constants.SOURCE_PROJECT_FOLDER, is_rerun=True)
        store_inference_results(
            Constants.SOURCE_PROJECT_FOLDER, f"{Constants.PATCH_AND_LOGS_FOLDER}/{source_project_name}", stage="close_injector")

    run_rlfixer(source_project_name, results_folder,
                Constants.SOURCE_PROJECT_FOLDER)

    elapsed_time = time.perf_counter() - start_time
    logging.info(
        f"Time taken for {source_project_name}: {elapsed_time:.2f} seconds")
    store_patch_and_log_files(
        f"{Constants.PATCH_AND_LOGS_FOLDER}/{source_project_name}")
    
    logging.info(f"Invoking RLPatcher for materialization for {source_project_name}...")
    # Put patches under: tool_results/inference_and_patches/<project>/rlpatcher_patches
    rlpatcher_out_dir = os.path.join(
        Constants.PATCH_AND_LOGS_FOLDER,
        source_project_name,
        "rlpatcher_patches"
    )
    run_rlpatcher_for_project(
        project_name=source_project_name,
        rlc_results_folder=results_folder,  # NOTE: may already be *_ReRun
        rlfixer_results_folder=Constants.RLFIXER_RESULTS_FOLDER,
        rlpatcher_jar=Constants.RLPATCHER_JAR,
        out_dir=rlpatcher_out_dir,
        success_text="Patch applied successfully",
        keep_temp_json=False,
    )
    clean_up_current()
    print()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(
        description="Run RLC experiments",
        epilog=(
            "Any of the following path arguments, if omitted,\n"
            "will default to the current working directory:\n"
            "  --source_project_folder, --rlc_results_folder,\n"
            "  --rlfixer_results_folder, --patch_and_logs_folder"
        )
    )
    parser.add_argument(
        "--source_project_folder", "-s",
        help="the path to the source projects root folder",
        required=True
    )
    parser.add_argument(
        "--rlc_results_folder", "-r",
        help="rlc results folder",
    )
    parser.add_argument(
        "--rlfixer_results_folder", "-f",
        help="rlfixer results folder",
    )
    parser.add_argument(
        "--patch_and_logs_folder", "-p",
        help="the path to the patch and logs folder",
    )

    args = parser.parse_args()
    Constants.SOURCE_PROJECT_FOLDER = os.path.normpath(
        args.source_project_folder)
    if not os.path.exists(Constants.SOURCE_PROJECT_FOLDER):
        raise FileNotFoundError(
            f"Source project folder does not exist: {Constants.SOURCE_PROJECT_FOLDER}")
    Constants.RESULTS_BASE_FOLDER = args.rlc_results_folder if args.rlc_results_folder else Constants.RESULTS_BASE_FOLDER
    Constants.RLFIXER_RESULTS_FOLDER = args.rlfixer_results_folder if args.rlfixer_results_folder else Constants.RLFIXER_RESULTS_FOLDER
    Constants.PATCH_AND_LOGS_FOLDER = args.patch_and_logs_folder if args.patch_and_logs_folder else Constants.PATCH_AND_LOGS_FOLDER
    create_folder_if_not_exists(Constants.RESULTS_BASE_FOLDER)
    create_folder_if_not_exists(Constants.RLFIXER_RESULTS_FOLDER)
    create_folder_if_not_exists(Constants.PATCH_AND_LOGS_FOLDER)
    Constants.RLFIXER_RESULTS_FOLDER = os.path.abspath(
        Constants.RLFIXER_RESULTS_FOLDER)
    logging.info(f"Source project folder: {Constants.SOURCE_PROJECT_FOLDER}")
    logging.info(f"RLC results folder: {Constants.RESULTS_BASE_FOLDER}")
    logging.info(f"RLFixer results folder: {Constants.RLFIXER_RESULTS_FOLDER}")
    logging.info(f"Patch and logs folder: {Constants.PATCH_AND_LOGS_FOLDER}")
    run_arodnap()
