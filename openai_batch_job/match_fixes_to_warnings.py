def match_fixes_to_warnings(fix_suggestions, cf_warnings):
    """
    Match the parsed fix suggestions with corresponding CF warnings.
    """
    matched = []

    for suggestion in fix_suggestions:
        for warning in cf_warnings:
            if (suggestion["filepath"] == warning["filepath"] and 
                suggestion["line_number"] == warning["line_number"]):
                matched.append((suggestion, warning))
                break

    return matched
