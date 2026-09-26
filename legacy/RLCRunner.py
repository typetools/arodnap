import os
import glob
import shutil
import logging
from collections import defaultdict
from BenchmarkCleaner import BenchmarkCleaner
from RLCInferenceRunner import RLCInferenceRunner
import Constants

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s: %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S"
)

class RLCRunner:
    ADDITIONAL_JAVAC_AND_CF_FLAGS = f"-AenableReturnsReceiverForRlc " f"-ApermitStaticOwning " f"-Adetailedmsgtext " f"-Awarns " f"-Xmaxwarns 10000 " f"-AshowPrefixInWarningMessages " f"-Astubs={Constants.STUBS_FOLDER} " f"-J-Xmx32G " f"-J-ea "
    
    def __init__(self, results_folder, run_rlc_inference=False):
        self.results_folder = results_folder
        self.run_rlc_inference = run_rlc_inference

    def run_cf_analysis(self, source_project_name, source_project_path, is_rerun=False):
        if not is_rerun:
            logging.info(f"Cleaning up {source_project_name}...")
            BenchmarkCleaner.clean_up(source_project_path)
        if self.run_rlc_inference:
            RLCInferenceRunner.run_rlc_inference(
                source_project_name, source_project_path, self.results_folder, is_rerun
            )

        logging.info(f"Running RLC analysis on {source_project_name}...")

        # create a folder for the compiled classes if it doesn't exist
        if not os.path.exists(Constants.COMPILED_CLASSES_FOLDER):
            os.mkdir(Constants.COMPILED_CLASSES_FOLDER)

        lib_folder = os.path.join(source_project_path, "lib")
        jars = glob.glob(os.path.join(lib_folder, "*.jar"))
        classpath_entries = [lib_folder] + jars + [Constants.CHECKER_QUAL_JAR]
        classpath = ":".join(classpath_entries)

        find_srcs_command = f'find {source_project_path}/src -name "*.java" > {Constants.SRC_FILES}'
        os.system(find_srcs_command)

        if is_rerun:
            # make sure the new results folder exists
            os.makedirs(self.results_folder + "_ReRun", exist_ok=True)
        command = (
            f"{Constants.JAVAC_WITH_FLAGS} "
            f"{Constants.CF_DIST_JAR_ARG} "
            f"{Constants.CF_COMMAND} "
            f'{"-Aajava=" + Constants.SOURCE_PROJECT_FOLDER + "/wpi-out" if self.run_rlc_inference else ""} '
            f"{self.ADDITIONAL_JAVAC_AND_CF_FLAGS} "
            f"-d {Constants.COMPILED_CLASSES_FOLDER} "
            f"-cp {classpath} "
            f"@{Constants.SRC_FILES} "
            f"2> {self.results_folder}{'_ReRun' if is_rerun else ''}/{source_project_name}.txt"
        )
        os.system(command)

        try:
            # remove the classes folder
            shutil.rmtree(Constants.COMPILED_CLASSES_FOLDER)
            os.remove(Constants.SRC_FILES)
        except:
            pass
