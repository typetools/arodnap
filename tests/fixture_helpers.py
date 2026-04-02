from __future__ import annotations

from contextlib import contextmanager
from dataclasses import dataclass
import difflib
import hashlib
import json
from pathlib import Path
import shutil
from typing import Callable, Iterator
from unittest.mock import patch

from arodnap.build_adapters.base import GradleProject
from arodnap.contracts import ReanalyzeResult, StageResult
from arodnap.orchestrator.results import OutputLayout
from arodnap.stages.base import write_stage_result


FIXTURES_ROOT = Path(__file__).resolve().parent / "fixtures"
GRADLE_BASELINE_FIXTURE = FIXTURES_ROOT / "gradle-pipeline-baseline"
LEGACY_BASELINE_FIXTURE = FIXTURES_ROOT / "legacy-pipeline-baseline"

_WRAPPER_PATH = "src/main/java/com/arodnap/fixture/WrapperMissingClose.java"
_OWNING_PATH = "src/main/java/com/arodnap/fixture/OwningFieldReassignment.java"
_DIRECT_LEAK_PATH = "src/main/java/com/arodnap/fixture/DirectLeakExample.java"
_TRY_CATCH_PATH = "src/main/java/com/arodnap/fixture/TryCatchLeakExample.java"


@dataclass(frozen=True)
class RepairScenario:
    name: str
    close_changes_workspace: bool
    owning_changes_workspace: bool
    patch_target: str
    patch_transform: Callable[[str], str]


BASELINE_SCENARIO = RepairScenario(
    name="baseline",
    close_changes_workspace=False,
    owning_changes_workspace=False,
    patch_target=_DIRECT_LEAK_PATH,
    patch_transform=lambda text: replace_once(
        text,
        """        BufferedReader reader = new BufferedReader(new FileReader(path));
        return reader.readLine();
""",
        """        BufferedReader reader = new BufferedReader(new FileReader(path));
        try {
            return reader.readLine();
        } finally {
            reader.close();
        }
""",
    ),
)

CLOSE_INJECTOR_SCENARIO = RepairScenario(
    name="close_injector",
    close_changes_workspace=True,
    owning_changes_workspace=False,
    patch_target=_TRY_CATCH_PATH,
    patch_transform=lambda text: replace_once(
        text,
        """        try {
            FileInputStream stream = new FileInputStream(path);
            return stream.read();
        } catch (IOException e) {
            return -1;
        }
""",
        """        try (FileInputStream stream = new FileInputStream(path)) {
            return stream.read();
        } catch (IOException e) {
            return -1;
        }
""",
    ),
)

OWNING_FIELD_SCENARIO = RepairScenario(
    name="owning_field",
    close_changes_workspace=False,
    owning_changes_workspace=True,
    patch_target=_DIRECT_LEAK_PATH,
    patch_transform=BASELINE_SCENARIO.patch_transform,
)


def copy_fixture(name: str, destination_root: Path) -> Path:
    source = FIXTURES_ROOT / name
    destination = destination_root / name
    shutil.copytree(source, destination)
    return destination


def snapshot_files(root: Path, *, exclude: set[str] | None = None) -> dict[str, bytes]:
    ignored = exclude or set()
    snapshot: dict[str, bytes] = {}
    for path in sorted(candidate for candidate in root.rglob("*") if candidate.is_file()):
        relative = path.relative_to(root).as_posix()
        if relative in ignored:
            continue
        snapshot[relative] = path.read_bytes()
    return snapshot


def replace_once(text: str, old: str, new: str) -> str:
    if old not in text:
        raise AssertionError("Expected snippet was not present in fixture source.")
    return text.replace(old, new, 1)


def workspace_text_snapshot(repo_root: Path) -> dict[str, str]:
    src_root = repo_root / "src" / "main" / "java"
    return {
        path.relative_to(repo_root).as_posix(): path.read_text()
        for path in sorted(src_root.rglob("*.java"))
    }


@dataclass
class FixtureRepairHarness:
    repo_root: Path
    scenario: RepairScenario

    def __post_init__(self) -> None:
        self.repo_root = self.repo_root.resolve()
        self.analysis_calls: list[str] = []

    @contextmanager
    def patch_pipeline(self) -> Iterator["FixtureRepairHarness"]:
        with patch("arodnap.orchestrator.pipeline.reanalyze", side_effect=self._fake_reanalyze):
            with patch(
                "arodnap.orchestrator.pipeline.run_close_injector_stage",
                side_effect=self._fake_close_injector,
            ):
                with patch(
                    "arodnap.orchestrator.pipeline.run_owning_field_stage",
                    side_effect=self._fake_owning_field,
                ):
                    with patch("arodnap.orchestrator.pipeline.run_rlfixer_stage", side_effect=self._fake_rlfixer):
                        with patch(
                            "arodnap.orchestrator.pipeline.run_rlpatcher_stage",
                            side_effect=self._fake_rlpatcher,
                        ):
                            yield self

    def _fake_reanalyze(self, config, *, workspace_root, label, artifacts_root):
        workspace_root = Path(workspace_root).resolve()
        if workspace_root == self.repo_root:
            raise AssertionError("repair should analyze a copied workspace, not the original repo")

        self.analysis_calls.append(label)
        layout = OutputLayout.from_root(Path(artifacts_root))
        analysis_paths = layout.analysis_paths(label)
        analysis_paths.ensure()

        source_root = workspace_root / "src" / "main" / "java"
        source_files = sorted(source_root.rglob("*.java"))
        compiled_outputs_root = workspace_root / "build" / "classes" / "java" / "main"
        compiled_outputs_root.mkdir(parents=True, exist_ok=True)

        app_classes: list[str] = []
        for source_file in source_files:
            relative = source_file.relative_to(source_root)
            if relative.name == "module-info.java":
                continue
            app_classes.append(relative.with_suffix("").as_posix().replace("/", "."))
            class_output = compiled_outputs_root / relative.with_suffix(".class")
            class_output.parent.mkdir(parents=True, exist_ok=True)
            class_output.write_bytes(f"{label}:{relative.as_posix()}".encode("utf-8"))

        analysis_paths.source_files_file.write_text(
            "\n".join(str(path.resolve()) for path in source_files) + "\n"
        )
        analysis_paths.app_classes_file.write_text("\n".join(app_classes) + "\n")
        analysis_paths.classpath_entries_file.write_text(str(compiled_outputs_root.resolve()) + "\n")
        analysis_paths.adapter_metadata_path.write_text(
            json.dumps(
                {"compiled_classes_root": str(compiled_outputs_root.resolve())},
                indent=2,
                sort_keys=True,
            )
            + "\n"
        )
        analysis_paths.wpi_log_path.write_text(f"reanalyze:{label}\n")
        analysis_paths.inference_dir.mkdir(parents=True, exist_ok=True)
        (analysis_paths.inference_dir / f"{label}.ajava").write_text(f"// {self.scenario.name}:{label}\n")

        diagnostics = self._diagnostic_lines_for(label=label, workspace_root=workspace_root)
        analysis_paths.diagnostics_path.write_text("\n".join(diagnostics) + "\n")

        return ReanalyzeResult(
            workspace_root=workspace_root,
            label=label,
            wpi_log_path=analysis_paths.wpi_log_path.resolve(),
            inference_dir=analysis_paths.inference_dir.resolve(),
            diagnostics_path=analysis_paths.diagnostics_path.resolve(),
            warning_count=len(diagnostics),
            source_files_file=analysis_paths.source_files_file.resolve(),
            app_classes_file=analysis_paths.app_classes_file.resolve(),
            classpath_entries_file=analysis_paths.classpath_entries_file.resolve(),
            adapter_metadata_path=analysis_paths.adapter_metadata_path.resolve(),
        )

    def _fake_close_injector(self, config, *, workspace_root, diagnostics_path, stage_output_dir):
        return self._run_stage_change(
            stage="close_injector",
            workspace_root=Path(workspace_root),
            stage_output_dir=Path(stage_output_dir),
            changed=self.scenario.close_changes_workspace,
            target_relpath=_WRAPPER_PATH,
            patch_name="close_injector.patch",
            transform=_inject_wrapper_close,
            note_prefix="close-injector",
        )

    def _fake_owning_field(self, config, *, workspace_root, diagnostics_path, stage_output_dir):
        return self._run_stage_change(
            stage="owning_field",
            workspace_root=Path(workspace_root),
            stage_output_dir=Path(stage_output_dir),
            changed=self.scenario.owning_changes_workspace,
            target_relpath=_OWNING_PATH,
            patch_name="owning_field.patch",
            transform=_close_before_reassign,
            note_prefix="owning-field",
        )

    def _run_stage_change(
        self,
        *,
        stage: str,
        workspace_root: Path,
        stage_output_dir: Path,
        changed: bool,
        target_relpath: str,
        patch_name: str,
        transform: Callable[[str], str],
        note_prefix: str,
    ) -> StageResult:
        stage_output_dir = stage_output_dir.resolve()
        stage_output_dir.mkdir(parents=True, exist_ok=True)
        log_path = stage_output_dir / "stage.log"
        patch_path = stage_output_dir / patch_name

        if not changed:
            log_path.write_text(f"{stage}: noop\n")
            result = StageResult(
                stage=stage,
                changed=False,
                changed_files=[],
                rerun_required=False,
                artifacts={"log": str(log_path.resolve())},
                notes=[f"No {note_prefix} patch was generated."],
                success=True,
            )
            write_stage_result(stage_output_dir, result)
            return result

        source_path = workspace_root / target_relpath
        before = source_path.read_text()
        after = transform(before)
        patch_path.write_text(unified_patch(target_relpath, before, after))
        source_path.write_text(after)
        log_path.write_text(f"{stage}: changed {target_relpath}\n")

        result = StageResult(
            stage=stage,
            changed=True,
            changed_files=[target_relpath],
            rerun_required=True,
            artifacts={
                "log": str(log_path.resolve()),
                "patch": str(patch_path.resolve()),
            },
            notes=[f"Applied {note_prefix} patch affecting 1 file(s)."],
            success=True,
        )
        write_stage_result(stage_output_dir, result)
        return result

    def _fake_rlfixer(
        self,
        *,
        workspace_root,
        diagnostics_path,
        inference_dir,
        compatibility_bundle_root,
        stage_output_dir,
    ):
        bundle_root = Path(compatibility_bundle_root).resolve()
        metadata_path = bundle_root / "metadata.json"
        if not metadata_path.is_file():
            raise AssertionError("repair should generate the RLFixer compatibility bundle before running RLFixer")

        stage_output_dir = Path(stage_output_dir).resolve()
        stage_output_dir.mkdir(parents=True, exist_ok=True)
        log_path = stage_output_dir / "stage.log"
        fixes_path = stage_output_dir / "fixes.txt"
        debug_path = stage_output_dir / "debug.txt"

        log_path.write_text("rlfixer: fixture-backed run\n")
        fixes_path.write_text(f"fixes:{self.scenario.name}\n")
        debug_path.write_text(f"debug:{self.scenario.name}\n")

        result = StageResult(
            stage="rlfixer",
            changed=False,
            changed_files=[],
            rerun_required=False,
            artifacts={
                "log": str(log_path.resolve()),
                "fixes": str(fixes_path.resolve()),
                "debug": str(debug_path.resolve()),
                "compatibility_bundle_metadata": str(metadata_path.resolve()),
            },
            notes=["RLFixer completed without mutating workspace sources."],
            success=True,
        )
        write_stage_result(stage_output_dir, result)
        return result

    def _fake_rlpatcher(
        self,
        *,
        workspace_root,
        diagnostics_path,
        inference_dir,
        fixes_path,
        debug_path,
        stage_output_dir,
        rlpatcher_jar,
    ):
        stage_output_dir = Path(stage_output_dir).resolve()
        stage_output_dir.mkdir(parents=True, exist_ok=True)
        patch_dir = stage_output_dir / "patches"
        patch_dir.mkdir(parents=True, exist_ok=True)
        log_path = stage_output_dir / "stage.log"
        manifest_path = stage_output_dir / "patch_manifest.json"

        target_relpath = self.scenario.patch_target
        workspace_file = Path(workspace_root) / target_relpath
        before = workspace_file.read_text()
        after = self.scenario.patch_transform(before)
        patch_path = patch_dir / f"{Path(target_relpath).stem}.patch"
        patch_path.write_text(unified_patch(target_relpath, before, after))

        preimage_hash = hashlib.sha256((self.repo_root / target_relpath).read_bytes()).hexdigest()
        manifest = {
            "stage": "rlpatcher",
            "patches": [
                {
                    "patch_file": str(patch_path.resolve()),
                    "stage": "rlpatcher",
                    "strip_level": 0,
                    "target_root": ".",
                    "changed_files": [target_relpath],
                    "preimage_hashes": {target_relpath: preimage_hash},
                }
            ],
        }
        manifest_path.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n")
        log_path.write_text(f"rlpatcher: materialized {target_relpath}\n")

        result = StageResult(
            stage="rlpatcher",
            changed=False,
            changed_files=[],
            rerun_required=False,
            artifacts={
                "log": str(log_path.resolve()),
                "patch_manifest": str(manifest_path.resolve()),
                "patch_dir": str(patch_dir.resolve()),
            },
            notes=["Materialized 1 normalized patch(es)."],
            success=True,
        )
        write_stage_result(stage_output_dir, result)
        return result

    def _diagnostic_lines_for(self, *, label: str, workspace_root: Path) -> list[str]:
        direct_path = workspace_root / _DIRECT_LEAK_PATH
        wrapper_path = workspace_root / _WRAPPER_PATH
        owning_path = workspace_root / _OWNING_PATH
        try_catch_path = workspace_root / _TRY_CATCH_PATH

        if self.scenario.name == "baseline":
            return [f"{direct_path}:10: warning: resource leak"]
        if self.scenario.name == "close_injector":
            if label == "initial":
                return [
                    f"{wrapper_path}:11: warning: wrapper missing close",
                    f"{try_catch_path}:9: warning: resource leak",
                ]
            return [f"{try_catch_path}:9: warning: resource leak"]
        if self.scenario.name == "owning_field":
            if label == "initial":
                return [
                    f"{owning_path}:16: warning: owning field overwritten",
                    f"{direct_path}:10: warning: resource leak",
                ]
            return [f"{direct_path}:10: warning: resource leak"]
        raise AssertionError(f"Unsupported scenario: {self.scenario.name}")


def unified_patch(relative_path: str, before: str, after: str) -> str:
    return "".join(
        difflib.unified_diff(
            before.splitlines(keepends=True),
            after.splitlines(keepends=True),
            fromfile=relative_path,
            tofile=relative_path,
        )
    )


def _inject_wrapper_close(text: str) -> str:
    return replace_once(
        text,
        """
    public int readByte() throws IOException {
        return stream.read();
    }
}
""",
        """
    public int readByte() throws IOException {
        return stream.read();
    }

    public void close() throws IOException {
        stream.close();
    }
}
""",
    )


def _close_before_reassign(text: str) -> str:
    return replace_once(
        text,
        """
    public void replace(String secondPath) throws IOException {
        this.current = new FileInputStream(secondPath);
    }
""",
        """
    public void replace(String secondPath) throws IOException {
        this.current.close();
        this.current = new FileInputStream(secondPath);
    }
""",
    )


class FixtureGradleAdapter:
    def __init__(
        self,
        repo_root: Path,
        *,
        compile_target: str | None = None,
        build_args: list[str] | None = None,
    ) -> None:
        self.repo_root = Path(repo_root).resolve()
        self.compile_target = compile_target or "classes"
        self.build_args = list(build_args or [])

    def inspect(self) -> GradleProject:
        source_root = self.repo_root / "src" / "main" / "java"
        compiled_classes_root = self.repo_root / "build" / "classes" / "java" / "main"
        compiled_classes_root.mkdir(parents=True, exist_ok=True)

        for source_file in sorted(source_root.rglob("*.java")):
            relative = source_file.relative_to(source_root)
            if relative.name == "module-info.java":
                continue
            class_output = compiled_classes_root / relative.with_suffix(".class")
            class_output.parent.mkdir(parents=True, exist_ok=True)
            class_output.write_bytes(relative.as_posix().encode("utf-8"))

        return GradleProject(
            repo_root=self.repo_root,
            build_file=_detect_build_file(self.repo_root),
            build_tool=("gradle",),
            compile_target=self.compile_target,
            source_root=source_root,
            compiled_classes_root=compiled_classes_root,
        )

    def write_source_files_file(self, project: GradleProject, output_path: Path) -> Path:
        source_files = sorted(path.resolve() for path in project.source_root.rglob("*.java") if path.is_file())
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_text("\n".join(str(path) for path in source_files) + "\n")
        return output_path

    def write_app_classes_file(self, project: GradleProject, output_path: Path) -> Path:
        class_names = []
        for path in sorted(project.compiled_classes_root.rglob("*.class")):
            if not path.is_file() or path.name == "module-info.class":
                continue
            class_names.append(".".join(path.relative_to(project.compiled_classes_root).with_suffix("").parts))
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_text("\n".join(class_names) + "\n")
        return output_path

    def write_classpath_entries_file(self, project: GradleProject, output_path: Path) -> Path:
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_text(str(project.compiled_classes_root.resolve()) + "\n")
        return output_path

    def write_adapter_metadata_file(
        self,
        project: GradleProject,
        *,
        source_files_file: Path,
        app_classes_file: Path,
        classpath_entries_file: Path,
        output_path: Path,
    ) -> Path:
        payload = {
            "repo_root": str(project.repo_root),
            "build_file": str(project.build_file),
            "build_tool": list(project.build_tool),
            "compile_target": project.compile_target,
            "source_root": str(project.source_root),
            "compiled_classes_root": str(project.compiled_classes_root.resolve()),
            "source_files_file": str(source_files_file.resolve()),
            "app_classes_file": str(app_classes_file.resolve()),
            "classpath_entries_file": str(classpath_entries_file.resolve()),
        }
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n")
        return output_path


def _detect_build_file(repo_root: Path) -> Path:
    for filename in ("build.gradle", "build.gradle.kts"):
        candidate = repo_root / filename
        if candidate.is_file():
            return candidate
    raise AssertionError(f"Expected fixture Gradle build file under {repo_root}")


__all__ = [
    "BASELINE_SCENARIO",
    "CLOSE_INJECTOR_SCENARIO",
    "FixtureGradleAdapter",
    "FixtureRepairHarness",
    "GRADLE_BASELINE_FIXTURE",
    "LEGACY_BASELINE_FIXTURE",
    "OWNING_FIELD_SCENARIO",
    "copy_fixture",
    "snapshot_files",
    "workspace_text_snapshot",
]
