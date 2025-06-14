import os
import subprocess
import shlex
import Constants
import logging

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s: %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S"
)


class FieldEnhancementRunner:
    @staticmethod
    def run_field_enhancements(source_project_name, source_project_path):
        """Run field enhancement plugins if applicable."""
        logging.info(f"Running field enhancements for {source_project_name}...")
        FieldEnhancementRunner.run_ep_plugins(source_project_name, source_project_path)

    @staticmethod
    def run_ep_plugins(source_project_name, source_project_path):
        logging.info(f"Running EP field enhancement plugins on {source_project_name}...")
        with open(
            f"{source_project_path}/{Constants.EP_FIELD_ENHANCEMENT_LOG_FILENAME}", "w"
        ) as file:
            process = subprocess.Popen(
                shlex.split(
                    f"{Constants.EP_FIELD_ENHANCEMENT_SCRIPT_PATH} {source_project_path} '0' {Constants.JARS_ROOT}"
                ),
                stdout=file,
                stderr=subprocess.STDOUT,
            )
            try:
                process.communicate(timeout=Constants.TIMEOUT)
                logging.info(f"EP plugins finished successfully on {source_project_name}.")
            except subprocess.TimeoutExpired:
                logging.error(
                    f"Command timed out after {Constants.TIMEOUT} seconds while running EP plugins on {source_project_name}."
                )
                process.kill()
                process.wait()
                os.system("sudo killall -9 javac")
