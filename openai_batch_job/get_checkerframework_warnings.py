import re

def get_checkerframework_warnings(content):
    """
    Parse Checker Framework warnings from the log files.
    """
    warnings = []
    warning_pattern = re.compile(r"^(\/.+?):(\d+):\s+warning:", re.MULTILINE)
    matches = list(warning_pattern.finditer(content))

    for i, match in enumerate(matches):
        start = match.start()
        end = matches[i + 1].start() if i + 1 < len(matches) else len(content)
        warning_block = content[start:end].strip()

        warnings.append({
            "filepath": match.group(1),
            "line_number": int(match.group(2)),
            "message": warning_block
        })

    return warnings
