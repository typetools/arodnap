from openai import OpenAI
import os

client = OpenAI()

def upload_batch_file(file_path):
    if os.stat(file_path).st_size == 0:
            return None
    with open(file_path, 'rb') as f:
        batch_input_file = client.files.create(
            file=f,
            purpose="batch"
        )
    return batch_input_file.id

def create_batch(batch_input_file_id):
    response = client.batches.create(
        input_file_id=batch_input_file_id,
        endpoint="/v1/chat/completions",
        completion_window="24h",
        metadata={
            "description": "Processing project fixes"
        }
    )
    return response.id

def submit_batches():
    project_batch_map = []
    batch_input_folder = "batch_input"
    
    for project_folder in os.listdir(batch_input_folder):
        jsonl_file_path = os.path.join(batch_input_folder, project_folder, f"{project_folder}.jsonl")
        batch_input_file_id = upload_batch_file(jsonl_file_path)
        if batch_input_file_id is None:
            print(f"Skipping empty file: {jsonl_file_path}")
            continue
        batch_id = create_batch(batch_input_file_id)
        project_batch_map.append((project_folder, batch_id))
        print(f"Submitted batch for {project_folder}, Batch ID: {batch_id}")
    
    return project_batch_map


def submit_merged_batch(merged_batch_file):
    """
    Submits the merged batch file to the OpenAI API.
    """
    batch_input_file_id = upload_batch_file(merged_batch_file)
    if batch_input_file_id:
        batch_id = create_batch(batch_input_file_id)
        print(f"Submitted merged batch with Batch ID: {batch_id}")
        return batch_id
    return None