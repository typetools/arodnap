# Running Arodnap on a Single Java Project

This repository contains the script to run the full Arodnap pipeline. The pipeline runs some code transformations, uses CF RLC to detect resource leaks and RLFixer to generate patches for feasible leaks on a **normalized Java project**.

---

## Requirements

Ensure the following are installed before running:

- **Java 11**
- **GNU `patch`**
- **`dos2unix`**
- Python packages (install via the requirement file: `pip3 install -r requirements.txt`):
  - `openai`
  - `jpype1`

### OpenAI API Key

To enable LLM-based patch generation, set your OpenAI API key as an environment variable:

```bash
export OPENAI_API_KEY="your-api-key"
```

---

## Expected Project Structure

Each project must be in a **normalized format** with the following layout:

```
<project-root>/
├── src/                   # Standard Java source structure
├── lib/                   # Dependencies (JARs or class files)
└── info/
    ├── sources            # Relative paths to Java source files (from root)
    └── classes            # Fully qualified Java class names to analyze
```

### `info/sources`

Each line lists a Java source file relative to the project root:

```
src/com/example/Foo.java
src/com/example/utils/Bar.java
```

### `info/classes`

Each line lists a fully-qualified Java class. Use `$` to refer to inner/anonymous classes:

```
com.example.Foo
com.example.Foo$Helper
```

---

## Running the Script

```bash
python3 run_arodnap.py --source_project_folder <path_to_project>
```

### Arguments

| Argument | Short | Description |
| -------- | ----- | ----------- |
| `--source_project_folder` | `-s` | **(Required)** Path to the normalized project root |
| `--rlc_results_folder` | `-r` | Optional: path to store RLC results (default: `tool_results/rlc_results`) |
| `--rlfixer_results_folder` | `-f` | Optional: path to store RLFixer results (default: `tool_results/rlfixer_results`) |
| `--patch_and_logs_folder` | `-p` | Optional: path to store LLM-generated patches and logs (default: `tool_results/inference_and_patches`) |

---

## Example

Run with only the required argument:

```bash
python3 code/arodnap/run_arodnap.py -s /path/to/project
```

Run with custom output directories:

```bash
python3 code/arodnap/run_arodnap.py \
  -s /path/to/project \
  -r results/rlc \
  -f results/rlfixer \
  -p results/patches_and_logs
```

---

## Output

- Patches are **automatically applied** to the project after execution.
- All patch files and logs are saved in the `--patch_and_logs_folder` (or its default).
- You can inspect, revert, or reuse the concrete patches from this folder.


---