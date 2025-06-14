import re
import os


def parse_escape_info(suggestion, base_project_path):
    """
    Extract escape details such as Add/Delete actions from the suggestion.
    Ensure no duplicate entries for the same file and line number.
    """
    escape_info = []
    seen_entries = set()

    # Pattern to match "Add" blocks, including multiple sections (above/after)
    add_pattern = re.compile(
        r"(\+\+\+ Add following code (above|below) line:(\d+)\s\((.+?)\)\n(.*?)(// where variable .*?\n))",
        re.DOTALL
    )

    # Pattern to match "Delete" lines.
    delete_pattern = re.compile(
        r"\+\+\+ Delete Line number (\d+)\s\((.+?)\)",
        re.DOTALL
    )

    # Find all Add tasks
    for match in add_pattern.finditer(suggestion):
        # Full matched block including code and comments
        full_text = match.group(0)
        line_number = int(match.group(3))  # Caller line number
        file_path = match.group(4).strip()  # Caller file path

        # Construct absolute path for the file
        caller_abs_path = os.path.join(base_project_path, "src", file_path)

        # Create a unique key to avoid duplicates
        entry_key = (caller_abs_path, line_number, full_text.strip())

        # Ensure no duplicate entries
        if entry_key not in seen_entries:
            seen_entries.add(entry_key)
            escape_info.append({
                "filepath": caller_abs_path,  # Absolute path of the caller file
                "line_number": line_number,  # Line number in the caller file
                "suggestion": full_text.strip()  # Full matched text for the fix
            })

    # Find all Delete tasks
    for match in delete_pattern.finditer(suggestion):
        line_number = int(match.group(1))
        file_path = match.group(2).strip()

        # Construct absolute path for the file
        caller_abs_path = os.path.join(base_project_path, "src", file_path)

        # Create a unique key to avoid duplicates
        entry_key = (caller_abs_path, line_number,
                     f"Delete Line number {line_number}")

        # Ensure no duplicate entries
        if entry_key not in seen_entries:
            seen_entries.add(entry_key)
            escape_info.append({
                "filepath": caller_abs_path,  # Absolute path of the caller file
                "line_number": line_number,  # Line number in the caller file
                "suggestion": f"Delete Line number {line_number}"
            })

    return escape_info


def parse_fix_suggestions(content):
    """
    Parse the fix suggestions from the content of an RLFixer file.
    """
    pattern = re.compile(
        r"(?P<number>\d+])\s*(?P<filepath>.+?);\s*Line\snumber\s(?P<line_number>\d+)\n(?P<suggestion>.+?)(?=--------------------------------------------|\Z)",
        re.DOTALL | re.VERBOSE,
    )

    matches = pattern.finditer(content)
    suggestions = []

    for match in matches:
        filepath = match.group("filepath")
        line_number = int(match.group("line_number"))
        suggestion = match.group("suggestion").strip()

        # Automatically extract base project path before 'src'
        base_project_path = filepath.split('src')[0].strip()

        # Check for escape or unfixable suggestion cases
        is_escape = "+++ NOTE: Resource escapes" in suggestion
        unfixable = "Nothing to be done" in suggestion

        escape_info = []
        if is_escape and not unfixable:
            # Extract escape info using helper function
            escape_info = parse_escape_info(suggestion, base_project_path)
            # Get all lines that start with '+++ NOTE'
            note_lines = [line.strip() for line in suggestion.splitlines() if line.startswith("+++ NOTE")]
            if note_lines:
                escape_note = "\n".join(note_lines)
                for info in escape_info:
                    info["suggestion"] = escape_note + "\n" + info["suggestion"]

        suggestions.append({
            "filepath": filepath,
            "line_number": line_number,
            "suggestion": suggestion,
            "is_escape": is_escape,
            "unfixable": unfixable,
            "escape_info": escape_info
        })

    return suggestions
