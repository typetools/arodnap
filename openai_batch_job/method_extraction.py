import jpype
import jpype.imports
from jpype.types import *
import os

# Start the JVM for Java parsing
HERE = os.path.dirname(os.path.abspath(__file__))
jpype.startJVM(classpath=[f"{HERE}/MethodExtractor/", f"{HERE}/MethodExtractor/javaparser-core-3.23.1.jar"])
MethodExtractor = jpype.JClass("MethodExtractor")

def extract_method_with_context(content, line_number, file_path=None):
    """Extracts the method containing the given line number from Java source."""
    lines = content.splitlines(keepends=True)
    
    try:
        method_start_line, method_end_line = MethodExtractor.getMethodLines(file_path, JInt(line_number))
        method_lines = lines[method_start_line - 1 : method_end_line]

        # Mark the line for potential fixes
        for i in range(len(method_lines)):
            if method_start_line + i == line_number:
                method_lines[i] = method_lines[i].rstrip() + f"   //__LINE{line_number}__//\n"

        return ''.join(method_lines), method_start_line, method_end_line
    except Exception as e:
        print(f"Error extracting method: {e}")
        return None, None, None
