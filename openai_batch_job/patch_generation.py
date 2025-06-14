import difflib
import os
from file_utils import read_file, create_temp_file, replace_method_in_file


def generate_patch_for_project(
    project_name, llm_output, original_file_path, method_start_line, method_end_line, patch_index
):
    """
    Generate patches for the original source file based on LLM output and save the patch.
    """
    original_content = read_file(original_file_path)

    # Extract LLM-modified method from output
    modified_method = llm_output["response"]["body"]["choices"][0]["message"]["content"]

    # Remove ```java and ``` from the response
    if modified_method.startswith("```java"):
        modified_method = "\n".join(modified_method.splitlines()[1:-1])
    # Ensure a final newline at the end of the modified method
    if not modified_method.endswith("\n"):
        modified_method += "\n"

    generate_patch_file(
        original_content,
        modified_method,
        method_start_line,
        method_end_line,
        original_file_path,
        project_name,
        patch_index
    )


def generate_patch_file(
    original_content,
    modified_method,
    method_start_line,
    method_end_line,
    file_path,
    project_name,
    patch_index
):
    """
    Replace the method in a temp file, generate the diff between the original and modified temp files,
    and accumulate the patches in a single file for the project.
    """
    if method_start_line is None or method_end_line is None:
        modified_content = modified_method
    else:
        # Replace the method in the original content
        modified_content = replace_method_in_file(
            original_content, method_start_line, method_end_line, modified_method
        )
    
    # Create two temp files: one for the original, one for the modified content
    original_temp_file = create_temp_file(original_content)
    modified_temp_file = create_temp_file(modified_content)

    # Read the contents of the temp files
    with open(original_temp_file, "r") as orig_file, open(
        modified_temp_file, "r"
    ) as mod_file:
        original_lines = orig_file.readlines()
        modified_lines = mod_file.readlines()

    # Generate the unified diff with correct file paths (without 'original/' and 'modified/')
    diff = difflib.unified_diff(
        original_lines,
        modified_lines,
        fromfile=f"{file_path}",
        tofile=f"{file_path}",
    )

    # Convert the diff generator to a list of strings
    diff_lines = list(diff)

    if diff_lines:
        # Create a single patch file for the project
        patch_dir = os.path.join("patches", project_name)
        os.makedirs(patch_dir, exist_ok=True)
        
        # Save each patch as a separate file (e.g., patch_1.diff, patch_2.diff)
        patch_file_path = os.path.join(patch_dir, f"patch_{patch_index}.diff")
        with open(patch_file_path, "w") as patch_file:
            patch_file.writelines(diff_lines)
        print(f"Patch {patch_index} generated for project {project_name}: {patch_file_path}")

    else:
        print("No differences found. No patch file updated.")

    # Clean up the temp files
    os.remove(original_temp_file)
    os.remove(modified_temp_file)
