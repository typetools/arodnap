import os
import tempfile
import difflib
import re

def read_file(filepath):
    """Read the contents of a file."""
    with open(filepath, "r") as file:
        return file.read()

def save_prompt_to_file(project_name, prompt, prompt_index):
    """
    Save the generated prompt to a file in the specified project directory.
    """
    project_dir = os.path.join("prompts", project_name)
    os.makedirs(project_dir, exist_ok=True)
    prompt_file = os.path.join(project_dir, f"prompt{prompt_index}.txt")
    with open(prompt_file, "w") as file:
        file.write(prompt)

def save_fix_to_file(project_name, fix_suggestion, fix_index):
    """
    Save the generated fix to a file in the specified project directory.
    """
    fix_dir = os.path.join("fixes", project_name)
    os.makedirs(fix_dir, exist_ok=True)
    fix_file = os.path.join(fix_dir, f"fix{fix_index}.txt")
    with open(fix_file, "w") as file:
        file.write(fix_suggestion)

def create_temp_file(content):
    """
    Create a temporary file with the provided content and return the file path.
    """
    temp_file = tempfile.NamedTemporaryFile(delete=False, mode="w")
    temp_file.write(content)
    temp_file.close()
    return temp_file.name

def replace_method_in_file(original_content, method_start_line, method_end_line, modified_method):
    """
    Replace the original method in the source code with the modified method.
    """
    lines = original_content.splitlines(keepends=True)
    # Regular expression to match `//__LINE{line_number}__//` at the end of a line
    line_pattern = re.compile(r"   //__LINE\d+__//", re.MULTILINE)

    # Remove `//__LINE{line_number}__//` from the end of lines in the modified method
    cleaned_modified_method = line_pattern.sub("", modified_method)
    new_content = (
        lines[: method_start_line - 1]
        + cleaned_modified_method.splitlines(keepends=True)
        + lines[method_end_line:]
    )
    return "".join(new_content)

def generate_patch_file(original_content, modified_method, method_start_line, method_end_line, file_path, project_name):
    """
    Generate a patch file by comparing the original and modified content.
    """
    modified_content = replace_method_in_file(original_content, method_start_line, method_end_line, modified_method)
    original_temp_file = create_temp_file(original_content)
    modified_temp_file = create_temp_file(modified_content)

    with open(original_temp_file, 'r') as orig_file, open(modified_temp_file, 'r') as mod_file:
        original_lines = orig_file.readlines()
        modified_lines = mod_file.readlines()

    diff = difflib.unified_diff(
        original_lines,
        modified_lines,
        fromfile=f"{file_path}",
        tofile=f"{file_path}",
        lineterm=''
    )
    diff_lines = list(diff)

    if diff_lines:
        patch_dir = os.path.join("patches", project_name)
        os.makedirs(patch_dir, exist_ok=True)
        patch_file = os.path.join(patch_dir, f"{project_name}.patch")

        with open(patch_file, "a") as file:
            file.writelines(diff_lines)
            file.write("\n")
        print(f"Patch file updated: {patch_file}")
    else:
        print("No differences found. No patch file updated.")
