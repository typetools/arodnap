# This will act like a static variable
task_metadata = {}

def store_task_metadata(project_name, idx, file_path, start_line, end_line):
    if project_name not in task_metadata:
        task_metadata[project_name] = {}
    task_metadata[project_name][idx] = {
        "file_path": file_path,
        "start_line": start_line,
        "end_line": end_line
    }

def retrieve_task_metadata(project_name, idx):
    return task_metadata.get(project_name, {}).get(idx, None)
