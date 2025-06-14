import json
from patch_generation import generate_patch_for_project
from method_extraction import extract_method_with_context
from prompt_generation import generate_prompt
from batch_preparation import merge_project_files, prepare_batches
from batch_submission import submit_batches, submit_merged_batch
from file_utils import read_file
from parse_fix_suggestions import parse_fix_suggestions
from get_checkerframework_warnings import get_checkerframework_warnings
from match_fixes_to_warnings import match_fixes_to_warnings
from batch_status import check_batch_status
from metadata_store import store_task_metadata, retrieve_task_metadata
from prompt_generation import extract_prompt_parts
import os
import pprint
import argparse


def create_prompt_or_log(suggestion, warning, method, method_start, method_end, project_name, prompts, project_idx, filepath):
    """
    Creates the prompt or logs unmatched methods.
    """
    if method:
        store_task_metadata(project_name, project_idx,
                            filepath, method_start, method_end)
        prompt = generate_prompt(
            suggestion, warning, method, project_idx, project_name)
        prompts[project_name].append(prompt)
    else:
        with open("unmatched_methods.log", "a") as log_file:
            log_file.write(
                f"Method not found for suggestion {suggestion['line_number']} in {filepath}\n")
        # Store full file context if method not found
        store_task_metadata(project_name, project_idx, filepath, None, None)
        prompt = generate_prompt(suggestion, warning, read_file(
            filepath), project_idx, project_name)
        prompts[project_name].append(prompt)


def handle_suggestion(suggestion, warning, project_name, prompts, prompt_idx, is_escape=False):
    """
    Handle both standard suggestions and escape info suggestions.
    """
    if is_escape:
        if not suggestion["escape_info"]:
            print(
                f"No escape info found for {suggestion['line_number']} in {suggestion['filepath']}")
            return prompt_idx

        # Iterate over escape infos for this suggestion
        for escape_info in suggestion["escape_info"]:
            file_content = read_file(escape_info["filepath"])
            method, method_start, method_end = extract_method_with_context(
                file_content, escape_info["line_number"], escape_info["filepath"]
            )
            create_prompt_or_log(
                escape_info, warning, method, method_start, method_end, project_name, prompts, prompt_idx, escape_info[
                    "filepath"]
            )
            prompt_idx += 1
    else:
        file_content = read_file(suggestion["filepath"])
        method, method_start, method_end = extract_method_with_context(
            file_content, suggestion["line_number"], suggestion["filepath"]
        )
        create_prompt_or_log(
            suggestion, warning, method, method_start, method_end, project_name, prompts, prompt_idx, suggestion[
                "filepath"]
        )
        prompt_idx += 1

    return prompt_idx


def process_projects(rlfixer_folder, cf_warnings_folder):
    """Process all projects and dynamically extract suggestions and warnings."""

    project_files = [f for f in os.listdir(
        rlfixer_folder) if f.endswith(".txt")]
    prompts = {}
    for project_file in project_files:
        project_name = os.path.splitext(project_file)[0]
        prompts[project_name] = []
        prompt_idx = 1

        # Construct file paths for RLFixer and CF warnings
        rlfixer_filepath = os.path.join(rlfixer_folder, project_file)
        cf_warnings_filepath = os.path.join(cf_warnings_folder, project_file)
        if not os.path.exists(cf_warnings_filepath):
            cf_warnings_filepath = os.path.join(cf_warnings_folder.replace("_ReRun", ""), project_file)

        # Read file contents
        rlfixer_content = read_file(rlfixer_filepath)
        cf_warnings_content = read_file(cf_warnings_filepath)

        # Extract suggestions and warnings dynamically
        parsed_fixes = parse_fix_suggestions(rlfixer_content)
        parsed_warnings = get_checkerframework_warnings(cf_warnings_content)

        # Match suggestions to warnings
        matched_results = match_fixes_to_warnings(
            parsed_fixes, parsed_warnings)

        for suggestion, warning in matched_results:
            if suggestion["unfixable"]:
                print(
                    f"Skipping unfixable suggestion {suggestion['line_number']} in {suggestion['filepath']}")
                continue
            prompt_idx = handle_suggestion(
                suggestion, warning, project_name, prompts, prompt_idx, is_escape=suggestion["is_escape"])
        
        # print the project name and the number of prompts
        print(f"Project {project_name} has {len(prompts[project_name])} prompts")
        # Before preparing batches, merge prompts where the leak is in the same method
        grouped_prompts = []
        seen_prompts = set()
        for i in range(1, len(prompts[project_name]) + 1):
            if i in seen_prompts:
                continue
            seen_prompts.add(i)
            prompt = retrieve_task_metadata(project_name, i)
            if prompt is None:
                print(f"Metadata not found for {project_name} task {i}")
            if not prompt["start_line"]:
                grouped_prompts.append([i])
                continue
            current_group = [i]
            for j in range(i + 1, len(prompts[project_name]) + 1):
                if j in seen_prompts:
                    continue
                next_prompt = retrieve_task_metadata(project_name, j)
                if not next_prompt["start_line"]:
                    continue
                if prompt["file_path"] == next_prompt["file_path"] and prompt["start_line"] == next_prompt["start_line"] and prompt["end_line"] == next_prompt["end_line"]:
                    current_group.append(j)
                    seen_prompts.add(j)
            grouped_prompts.append(current_group)
        
        for group in grouped_prompts:
            if len(group) == 1:
                continue
            merged_prompt = ""
            for index, prompt_index in enumerate(group):
                extracted_prompt = extract_prompt_parts(prompts[project_name][prompt_index - 1])
                if extract_prompt_parts is None:
                    print(f"Prompt not found for {project_name} task {prompt_index}")
                if index == 0:
                    merged_prompt += extracted_prompt["init_cmd"]
                try:
                    merged_prompt += extracted_prompt["cf_warning"].replace("CF Warning:", f"CF Warning {index + 1}:") + "\n\n"
                    merged_prompt += extracted_prompt["rlfixer_suggestion"].replace("Fix Suggestion from RLFixer:", f"Fix Suggestion from RLFixer {index + 1}:") + "\n\n"
                except Exception:
                    print(f"Error merging prompts for {project_name} group {group}")
                if index == len(group) - 1:
                    merged_prompt += extracted_prompt["source_code"] + "\n\n"
                    merged_prompt += extracted_prompt["task"]
            # delete the prompts that are merged
            for prompt_index in group:
                prompts[project_name][prompt_index - 1] = ""
            prompts[project_name].append(merged_prompt)
            # also save the merged prompt to a file
            project_dir = os.path.join("prompts", project_name)
            os.makedirs(project_dir, exist_ok=True)
            # the prompt index is the length of prompts[project_name] - 1
            prompt_file = os.path.join(project_dir, f"prompt{len(prompts[project_name])}.txt")
            with open(prompt_file, "w") as file:
                file.write(merged_prompt)
            prompt_meta = retrieve_task_metadata(project_name, group[0])
            store_task_metadata(project_name, len(prompts[project_name]), prompt_meta["file_path"], prompt_meta["start_line"], prompt_meta["end_line"])
            # print which prompts are merged
            print(f"Merged prompts {group} for project {project_name}")
        
        # print the project name and the number of prompts after merging
        # the count is the length of prompts[project_name] minus the number of empty strings
        # only print the project name if there are prompts after merging
        len_after_merging = len([prompt for prompt in prompts[project_name] if prompt])
        if len_after_merging != len(prompts[project_name]):
            print(f"Project {project_name} has {len_after_merging} prompts after merging")
        print()
        
    # Generate batch input files from prompts and collect batch IDs
    prepare_batches(prompts)


def log_error_case(response, error_message):
    # Log the error case for manual patching
    with open("token_limit_errors.log", "a") as log_file:
        log_file.write(
            f"Error for custom_id {response['custom_id']}: {error_message}\n"
        )


if __name__ == "__main__":
    # delete the batch_input and batch_output folders
    os.system("rm -rf batch_input")
    os.system("rm -rf batch_output")
    os.system("rm -rf patches")
    os.system("rm -rf prompts")
    parser = argparse.ArgumentParser()
    parser.add_argument("--rlfixer_folder", type=str,
                        help="Path to the folder containing RLFixer results")
    parser.add_argument("--cf_warnings_folder", type=str,
                        help="Path to the folder containing Checker Framework warnings")
    args = parser.parse_args()

    rlfixer_folder = args.rlfixer_folder
    cf_warnings_folder = args.cf_warnings_folder

    process_projects(rlfixer_folder, cf_warnings_folder)

    merged_batch_files = merge_project_files("batch_input")
    
    for merged_batch_file in merged_batch_files:
        # Step 2: Submit the merged batch
        batch_id = submit_merged_batch(merged_batch_file)

        if batch_id:
            print(f"Batch ID {batch_id} submitted successfully.")
            check_batch_status(batch_id)
            for output_file in os.listdir("batch_output"):
                output_filepath = os.path.join("batch_output", output_file)
                if os.path.isfile(output_filepath):
                    with open(output_filepath, "r") as f:
                        llm_responses = [json.loads(line)
                                        for line in f.readlines()]
                        for llm_response in llm_responses:
                            if llm_response["error"] is not None:
                                error_message = llm_response["error"].get(
                                    "message", "Unknown error"
                                )
                                if (
                                    "Please reduce the length of the messages or completion"
                                    in error_message.lower()
                                ):
                                    # Handle token limit exceeded error
                                    print(
                                        f"Token limit exceeded for custom_id {llm_response['custom_id']}: {error_message}"
                                    )
                                    log_error_case(llm_response, error_message)
                                else:
                                    # Handle other errors
                                    print(
                                        f"Error for custom_id {llm_response['custom_id']}: {error_message}"
                                    )
                                    log_error_case(llm_response, error_message)
                                continue
                            project_name = llm_response["custom_id"].split("$")[0]
                            task_index = int(
                                llm_response["custom_id"].split("$")[1])
                            metadata = retrieve_task_metadata(
                                project_name, task_index)
                            if metadata is None:
                                print(
                                    f"Metadata not found for {project_name} task {task_index}"
                                )
                            generate_patch_for_project(
                                project_name,
                                llm_response,
                                metadata["file_path"],
                                metadata["start_line"],
                                metadata["end_line"],
                                task_index,
                            )
