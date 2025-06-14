import os
import json
import pprint
import time
from openai import OpenAI

client = OpenAI()

def check_batch_status(batch_id):
    while True:
        batch_info = client.batches.retrieve(batch_id)
        status = batch_info.status
        if status == 'completed' or status == 'expired':
            print(f"Batch {batch_id} is completed.")
            if status == 'expired':
                print("The batch expired before completion.")
            result_file_id = batch_info.output_file_id
            download_and_split_batch_results(result_file_id)
            if status == 'expired':
                error_file_id = batch_info.error_file_id
                download_and_split_batch_results(error_file_id, 'batch_errors')
            break
        elif status == 'failed':
            print(f"Batch {batch_id} failed.")

            pprint.pprint(batch_info)
            break
        else:
            print(f"Batch {batch_id} status: {status}. Waiting for completion...")
            time.sleep(30)

def download_and_split_batch_results(file_id, output_folder='batch_output'):
    """
    Downloads the results of a batch and splits them back into individual project files.
    """
    file_content = client.files.content(file_id)

    # Load the content and split into project-specific files based on the custom_id
    os.makedirs(output_folder, exist_ok=True)

    for line in file_content.text.splitlines():
        result = json.loads(line)
        custom_id = result['custom_id']
        project_name = custom_id.split("$")[0]  # Extract project name from custom_id

        # Write each result to the corresponding project file
        project_output_file = os.path.join(output_folder, f"{project_name}.jsonl")
        with open(project_output_file, "a") as project_file:
            project_file.write(json.dumps(result) + "\n")

    print(f"Batch results saved and split into {output_folder}")
