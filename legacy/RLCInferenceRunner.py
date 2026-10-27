import os
import subprocess
import shlex
import logging
import Constants

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s: %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S"
)


class RLCInferenceRunner:
    @staticmethod
    def run_rlc_inference(source_project_name, source_project_path, results_folder, is_rerun=False):
        logging.info(f"Running RLC inference on {source_project_name}.")
        logging.info(f"Rerun: {is_rerun}")
        with open(
            f"{source_project_path}/{Constants.RLC_INFERENCE_LOG_FILENAME}",
            "w" if not is_rerun else "a",
        ) as file:
            process = subprocess.Popen(
                shlex.split(
                    f"{Constants.RLC_INFERENCE_SCRIPT_PATH} {source_project_path} {Constants.CF_ROOT}"
                ),
                stdout=file,
                stderr=subprocess.STDOUT,
            )
            try:
                process.communicate(timeout=Constants.TIMEOUT)
                logging.info(
                    f"Running RLC inference finished successfully on {source_project_name}.")
            except subprocess.TimeoutExpired:
                logging.error(
                    f"Command timed out after {Constants.TIMEOUT} seconds while running RLC inference on {source_project_name}."
                )
                process.kill()
                process.wait()
                os.system("sudo killall -9 javac")
            except Exception as e:
                logging.error(f"Error occurred: {e}")
