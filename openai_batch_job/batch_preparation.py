import math
import os
import json


def count_tokens(prompt, model="gpt-4.1"):
    # This is a simplified token counting function; adjust it according to your tokenization method.
    # For OpenAI, you can use a tokenizer to count tokens accurately.
    return len(prompt.split())  # Approximation: 1 token ~ 3/4 of a word


def generate_jsonl_file(project_name, prompts):
    batch_dir = os.path.join("batch_input", project_name)
    os.makedirs(batch_dir, exist_ok=True)
    jsonl_file = os.path.join(batch_dir, f"{project_name}.jsonl")
    skipped_file = "skipped_prompts.json"
    skipped_prompts = []  # List to store the skipped prompt numbers

    with open(jsonl_file, "w") as file:
        for index, prompt in enumerate(prompts):
            if prompt == "":
                continue
            if count_tokens(prompt) > 30000:  # Check if the prompt exceeds the max token limit
                skipped_prompts.append(index + 1)  # Log the skipped prompt number (1-based index)
                continue  # Skip this prompt
            request_data = {
                "custom_id": f"{project_name}${index+1}",
                "method": "POST",
                "url": "/v1/chat/completions",
                "body": {
                    "model": "gpt-4.1",
                    "messages": [
                        {"role": "system", "content": "You are a helpful assistant."},
                        {"role": "user", "content": prompt},
                    ],
                    "max_tokens": 30000,
                    "temperature": 0.2,
                },
            }
            file.write(json.dumps(request_data) + "\n")
    
    # Check if the skipped_prompts.json file exists and load its content, or create an empty list
    if os.path.exists(skipped_file):
        with open(skipped_file, "r") as skipped_json:
            all_skipped_data = json.load(skipped_json)
    else:
        all_skipped_data = []

    # Append the current project's skipped prompts to the existing data
    project_skipped_data = {
        "project_name": project_name,
        "skipped_prompts": skipped_prompts
    }
    if skipped_prompts:
        all_skipped_data.append(project_skipped_data)

    # Write the consolidated skipped data back to the skipped_prompts.json file
    with open(skipped_file, "w") as skipped_json:
        json.dump(all_skipped_data, skipped_json, indent=4)


def prepare_batches(projects_with_prompts):
    """Generates .jsonl files for each project."""
    for project_name, prompts in projects_with_prompts.items():
        if "urlfc1c29991b_nil_mish_InJava_tgz" in project_name:
            print("stop")
        generate_jsonl_file(project_name, prompts)


def merge_project_files(batch_input_folder, num_splits=1):
    """
    Merges all individual project .jsonl files and splits them dynamically into a specified number of parts.
    
    Args:
    - batch_input_folder: Folder containing the project subfolders with .jsonl files.
    - num_splits: Number of parts to split the merged batch into.
    
    Returns:
    - A list containing the paths to the merged output files.
    """
    batch_requests = []
    
    # Loop through each project folder
    for project_folder in os.listdir(batch_input_folder):
        project_file = os.path.join(
            batch_input_folder, project_folder, f"{project_folder}.jsonl"
        )

        # Open each project's .jsonl file and read the requests
        with open(project_file, "r") as f:
            for line in f:
                request = json.loads(line)
                batch_requests.append(request)

    # Calculate the size of each batch based on the number of splits
    split_size = math.ceil(len(batch_requests) / num_splits)

    output_files = []
    
    # Split the batch requests dynamically and write to separate files
    for i in range(num_splits):
        start_index = i * split_size
        end_index = start_index + split_size
        batch_part = batch_requests[start_index:end_index]
        
        # Create dynamic file name based on the number of splits
        output_file = f"merged_batch_{i + 1}.jsonl"
        output_files.append(output_file)
        
        with open(output_file, "w") as f:
            for request in batch_part:
                f.write(json.dumps(request) + "\n")
        
        print(f"Saved split batch {i + 1} to {output_file}")

    return output_files
