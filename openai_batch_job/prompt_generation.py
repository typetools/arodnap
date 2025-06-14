import os
import re

def generate_prompt(suggestion, warning, method, prompt_index, project_name):
    """
    Generate a prompt and store it in a project-specific folder.
    """
    prompt = (
        "You are given a Java source code where a (or more than one) potential resource leak has been detected. "
        "The Checker Framework (CF) has provided a warning indicating the issue, and a leak repair tool, "
        "RLFixer, has suggested a potential fix.\n\n"
        f"CF Warning:\n{warning['message']}\n\n"
        f"Fix Suggestion from RLFixer:\n{suggestion['suggestion']}\n\n"
        f"Source code:\n{method}\n\n"
        "Task:\n"
        "Please apply the fix suggested by RLFixer to resolve the resource leak in the provided method, "
        "ensuring that the resource is properly managed and the method's functionality remains intact."
        "\n\nAlso, you may need to declare additional temporary variables for the resource to close the same resource being declared if the resource leaked is not assigned to a variable in the original code."
        "\n\nIMPORTANT NOTES:\n"
        "- Ensure the generated code is **compilation-ready** and does not introduce any errors."
        "- If you declare additional temporary resource variables, use fully qualified names for any new resource classes (e.g., `java.io.FileInputStream`) to avoid import issues.\n"
        "- If you introduce a `try-finally` or `try-with-resources` block, **DO NOT CHANGE THE ORIGINAL INDENTATION OF THE CODE**.\n"
        "- All variables used in try/catch/finally blocks must be declared and initialized (to null if necessary) outside those blocks to avoid uninitialized variable errors.\n"
        "- All exceptions from your inserted code (finalizer method's) must be caught locally (do NOT change the method signature to pass exceptions).\n"
        "- Always declare resource variables outside the try block and initialize them to null.\n"
        "- In the finally block, always check if the resource variable is not null before calling .close() on it.\n"
        "- Do not introduce unused variables, unreachable code, or redundant statements.\n"
        "- If you need to call a method that declares throws Throwable (such as finalize()), always catch Throwable (not just Exception) in the catch block to avoid compilation errors.\n"
        "- Before returning the code, double-check for any common Java compilation errors such as uninitialized variables, missing imports,try block without catch/finally, or incorrect exception handling.\n\n"
        "Please refactor the provided Java 'Source code' to resolve the resource leak as suggested, but return only the modified code with no explanations or comments. Do return the whole modified method if the input source code is a method. If the input source code is the whole file, then return the whole java file with the modified method."
    )

    # Save the generated prompt to a file
    project_dir = os.path.join("prompts", project_name)
    os.makedirs(project_dir, exist_ok=True)
    prompt_file = os.path.join(project_dir, f"prompt{prompt_index}.txt")
    with open(prompt_file, "w") as file:
        file.write(prompt)

    return prompt



def extract_prompt_parts(prompt):
    """
    Extract different parts of the prompt for analysis.
    """
    # Regex to extract different parts
    init_cmd_match = re.search(r"^(.*?)CF Warning:\n", prompt, re.DOTALL)
    cf_warning_match = re.search(r"(CF Warning:\n.*?)\n\nFix Suggestion from RLFixer:", prompt, re.DOTALL)
    rlfixer_suggestion_match = re.search(r"(Fix Suggestion from RLFixer:\n.*?)\n\nSource code:", prompt, re.DOTALL)
    source_code_match = re.search(r"(Source code:\n.*?)\n\nTask:", prompt, re.DOTALL)
    task_match = re.search(r"(Task:\n.*)", prompt, re.DOTALL)

    # Extracted components
    init_cmd = init_cmd_match.group(1) if init_cmd_match else None
    cf_warning = cf_warning_match.group(1) if cf_warning_match else None
    rlfixer_suggestion = rlfixer_suggestion_match.group(1) if rlfixer_suggestion_match else None
    source_code = source_code_match.group(1) if source_code_match else None
    task = task_match.group(1) if task_match else None

    # Return the parts as a dictionary
    return {
        "init_cmd": init_cmd,
        "cf_warning": cf_warning,
        "rlfixer_suggestion": rlfixer_suggestion,
        "source_code": source_code,
        "task": task
    }