import os
import shutil
import logging
from subprocess import run, CalledProcessError

from Constants import RLC_INFERENCE_LOG_FILENAME

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s: %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S"
)


class BenchmarkCleaner:
    @staticmethod
    def clean_up(source_project_path):
        items_to_delete = ["wpi-iterations", RLC_INFERENCE_LOG_FILENAME, "wpi-out"]
        for item in items_to_delete:
            path = os.path.join(source_project_path, item)
            if os.path.exists(path):
                if os.path.isfile(path) or os.path.islink(path):
                    os.remove(path)
                else:
                    shutil.rmtree(path)
                 
    @staticmethod                
    def restore_benchmark(source_project_path):
        """Restore the benchmark to its initial state using git."""
        logging.info(f"Restoring benchmark at {source_project_path} to its initial state...")
        try:
            run(f"cd {source_project_path} && git restore . && git clean -f d && git clean -f", shell=True, check=True)
        except CalledProcessError as e:
            logging.error(f"Failed to restore benchmark: {e}")
