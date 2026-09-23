import json
import os
import tempfile
import unittest
from pathlib import Path

from arodnap.build_adapters.base import UnsupportedProjectError
from arodnap.build_adapters.capture import (
    analysis_root,
    load_compile_units,
    merge_compile_units,
    parse_javac_invocation,
    snapshot_classpath_candidates,
)


class ParseJavacInvocationTest(unittest.TestCase):
    def test_maven_forked_javac_arguments(self) -> None:
        # Recorded from maven-compiler-plugin with fork=true.
        cwd = Path("/ws/proj")
        unit = parse_javac_invocation(
            [
                "-d", "/ws/proj/target/classes",
                "-classpath", "/ws/proj/target/classes:/m2/commons-io-2.16.1.jar:",
                "-sourcepath", "/ws/proj/src/main/java:",
                "/ws/proj/src/main/java/demo/Leak.java",
                "-s", "/ws/proj/target/generated-sources/annotations",
                "-g", "--release", "11", "-encoding", "UTF-8",
            ],
            cwd=cwd,
        )
        self.assertEqual(unit.sources, (Path("/ws/proj/src/main/java/demo/Leak.java"),))
        self.assertEqual(unit.classpath, (Path("/ws/proj/target/classes"), Path("/m2/commons-io-2.16.1.jar")))
        self.assertEqual(unit.output_dir, Path("/ws/proj/target/classes"))
        self.assertEqual(unit.generated_source_dir, Path("/ws/proj/target/generated-sources/annotations"))
        self.assertEqual(unit.release, 11)
        self.assertEqual(unit.encoding, "UTF-8")

    def test_relative_paths_long_options_and_legacy_source_levels(self) -> None:
        unit = parse_javac_invocation(
            ["--class-path=lib/a.jar", "-source", "1.8", "-d", "out", "src/A.java"],
            cwd=Path("/ws/proj"),
            label=":compileJava",
        )
        self.assertEqual(unit.classpath, (Path("/ws/proj/lib/a.jar"),))
        self.assertEqual(unit.sources, (Path("/ws/proj/src/A.java"),))
        self.assertEqual(unit.release, 8)
        self.assertEqual(unit.label, ":compileJava")

    def test_classpath_wildcards_expand_to_jars(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            lib = Path(temp_dir) / "lib"
            lib.mkdir()
            (lib / "b.jar").write_bytes(b"")
            (lib / "a.jar").write_bytes(b"")
            (lib / "notes.txt").write_text("")
            unit = parse_javac_invocation(["-cp", "lib/*", "A.java"], cwd=Path(temp_dir))
        self.assertEqual(unit.classpath, (lib / "a.jar", lib / "b.jar"))

    def test_invocations_without_sources_are_ignored(self) -> None:
        self.assertIsNone(parse_javac_invocation(["-version"], cwd=Path("/ws")))
        self.assertIsNone(parse_javac_invocation(["-d", "out", "-cp", "x.jar"], cwd=Path("/ws")))


class MergeCompileUnitsTest(unittest.TestCase):
    def test_multi_module_build_drops_sibling_artifacts_but_keeps_real_dependencies(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir).resolve()
            workspace = root / "ws"
            for relpath in (
                "core/src/main/java/demo/core/Store.java",
                "app/src/main/java/demo/app/Leak.java",
                "libs/vendor.jar",
            ):
                (workspace / relpath).parent.mkdir(parents=True, exist_ok=True)
                (workspace / relpath).write_text("")
            # A jar checked into the repo, just cloned, so its timestamp is as new as the build's.
            before_build = snapshot_classpath_candidates(workspace)
            # Written by the build: the core module's jar and classes.
            (workspace / "core/build/libs").mkdir(parents=True)
            (workspace / "core/build/libs/core.jar").write_bytes(b"")
            (workspace / "core/build/classes/java/main").mkdir(parents=True)
            external = root / "gradle-cache/commons-io-2.16.1.jar"
            external.parent.mkdir()
            external.write_bytes(b"")

            capture = root / "capture.jsonl"
            capture.write_text(
                "\n".join(
                    json.dumps(record)
                    for record in [
                        {
                            "cwd": str(workspace / "core"),
                            "task": ":core:compileJava",
                            "args": ["-d", str(workspace / "core/build/classes/java/main"), "-classpath", "",
                                     "--release", "11", str(workspace / "core/src/main/java/demo/core/Store.java")],
                        },
                        {
                            "cwd": str(workspace / "app"),
                            "task": ":app:compileJava",
                            "args": ["-d", str(workspace / "app/build/classes/java/main"), "-classpath",
                                     f"{workspace}/core/build/libs/core.jar:{external}:{workspace}/libs/vendor.jar",
                                     "--release", "17", str(workspace / "app/src/main/java/demo/app/Leak.java")],
                        },
                    ]
                )
                + "\n"
            )
            units = load_compile_units(capture)
            inputs = merge_compile_units(units, workspace_root=workspace, before_build=before_build)

        self.assertEqual([unit.label for unit in units], [":core:compileJava", ":app:compileJava"])
        self.assertEqual(
            inputs.sources,
            (workspace / "core/src/main/java/demo/core/Store.java", workspace / "app/src/main/java/demo/app/Leak.java"),
        )
        # core.jar is dropped (the build wrote it); the vendored jar and the dependency are kept.
        self.assertEqual(inputs.classpath, (external, workspace / "libs/vendor.jar"))
        self.assertEqual(inputs.release, 17)
        self.assertEqual(inputs.analysis_root, workspace)

    def test_an_existing_jar_the_build_rewrites_is_an_artifact(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            workspace = Path(temp_dir).resolve()
            (workspace / "src").mkdir()
            (workspace / "src/A.java").write_text("")
            stale = workspace / "build/libs/stale.jar"
            stale.parent.mkdir(parents=True)
            stale.write_bytes(b"old")
            before_build = snapshot_classpath_candidates(workspace)
            stale.write_bytes(b"rebuilt")
            # Make sure the rewrite is visible even on coarse-timestamp filesystems.
            os.utime(stale, ns=(stale.stat().st_atime_ns, before_build[stale] + 1_000_000_000))
            unit = parse_javac_invocation(["-cp", str(stale), str(workspace / "src/A.java")], cwd=workspace)
            inputs = merge_compile_units((unit,), workspace_root=workspace, before_build=before_build)
        self.assertEqual(inputs.classpath, ())

    def test_generated_sources_are_analyzed_separately(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            workspace = Path(temp_dir).resolve()
            source = workspace / "src/main/java/A.java"
            generated = workspace / "target/generated-sources/annotations/A_Builder.java"
            for path in (source, generated):
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("")
            unit = parse_javac_invocation(
                ["-s", str(generated.parent), str(source)], cwd=workspace
            )
            inputs = merge_compile_units((unit,), workspace_root=workspace, before_build={})
        self.assertEqual(inputs.sources, (source,))
        self.assertEqual(inputs.generated_sources, (generated,))

    def test_lombok_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            workspace = Path(temp_dir).resolve()
            lombok = workspace / "m2/org/projectlombok/lombok/1.18.34/lombok-1.18.34.jar"
            lombok.parent.mkdir(parents=True)
            lombok.write_bytes(b"")
            unit = parse_javac_invocation(["-classpath", str(lombok), "A.java"], cwd=workspace)
            with self.assertRaisesRegex(UnsupportedProjectError, "Lombok"):
                merge_compile_units((unit,), workspace_root=workspace / "ws", before_build={})

    def test_no_units_fails_closed(self) -> None:
        with self.assertRaisesRegex(UnsupportedProjectError, "compiled no Java sources"):
            merge_compile_units((), workspace_root=Path("/ws"), before_build={})


class AnalysisRootTest(unittest.TestCase):
    def test_common_directory_of_sources(self) -> None:
        self.assertEqual(
            analysis_root((Path("/ws/src/main/java/a/A.java"), Path("/ws/src/main/java/b/B.java"))),
            Path("/ws/src/main/java"),
        )

    def test_moves_up_so_no_relative_path_starts_with_src(self) -> None:
        # RLFixer strips a leading "src/" from its source list.
        self.assertEqual(
            analysis_root((Path("/ws/proj/src/A.java"), Path("/ws/proj/test/B.java"))),
            Path("/ws"),
        )


if __name__ == "__main__":
    unittest.main()
