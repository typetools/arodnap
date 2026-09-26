# Testing

## Layers

Each layer proves something the one below cannot. A bug fix gets a test at the lowest layer
that reproduces it, and test names say what behavior they check.

| Layer | Proves | Where | Runs |
|---|---|---|---|
| Unit | parsers, patches, leak tracking, compile-unit merging, report building | `*/src/test`, on real tool outputs recorded as test resources | `mvn verify` |
| Pipeline | step order, when analysis reruns, a crashing tool fails the run, report assembly | `RepairPipelineTest`, with every tool faked (`FakeTools`) | `mvn verify` |
| Snapshots | the shape of `report.json`, `manifest.json`, `stage_result.json` and the patch bundle | `RepairPipelineTest.theOutputFilesKeepTheirShape` | `mvn verify` |
| Tool contract | each real tool still produces what the engine reads | `ToolContractIT` (distribution) | `ARODNAP_E2E=1` |
| End-to-end | real repairs work on each supported kind of build | `RealRepairIT` (distribution) | `ARODNAP_E2E=1` |
| Plugins | the plugins read the right inputs from a real build | Maven: `arodnap-maven-plugin/src/it`; Gradle: `ArodnapPluginTest` (always), `ArodnapPluginFunctionalTest` | `ARODNAP_E2E=1` for the real runs |
| Real projects | nothing regressed on real code | `scripts/real_projects.py` | quick tier on every pull request, all weekly |

```bash
mvn verify                                           # everything that needs no real tools
ARODNAP_E2E=1 mvn install                            # plus the end-to-end, contract and Maven plugin tests
ARODNAP_E2E=1 gradle -p arodnap-gradle-plugin build  # the Gradle plugin (after mvn install)
```

The end-to-end layers need JDK 17 or newer and `gradle`, `mvn` and `ant` on `PATH` (a test
whose tool is missing is skipped; CI treats a skip as a failure), and network access or warm
caches for the projects' dependencies. `RealRepairIT` runs the distribution's `bin/arodnap`
exactly as users do; set `ARODNAP_CHECKER_FRAMEWORK` to run it against another Checker
Framework distribution.

## Recorded Tool Outputs

Unit tests read what the real tools produced instead of hand-written imitations:
`arodnap-engine/src/test/resources/recorded/coverage/` holds the diagnostics, RLFixer report
and debug table, and RLPatcher manifest of a real run on `gradle-pipeline-coverage`, with the
run's directories replaced by placeholders (see `Recorded`), and what an earlier release
made of them (`expected-*.json`). `recorded/<project>/` holds excerpts from real projects'
runs that exposed a bug. `patch/difflib-cases.json` holds diffs made by Python's difflib,
whose output Arodnap's patches keep.

## Snapshots

Approved copies of output files are in `arodnap-engine/src/test/resources/snapshots`. The
test replaces what differs between runs (directories, times, durations, Arodnap's version)
and compares the rest exactly. When an output changes on purpose, rewrite the copies and
review the diff with the change:

```bash
mvn test -pl arodnap-model,arodnap-engine -Darodnap.updateSnapshots=true
```

A change to a contract listed in [`architecture.md`](architecture.md) also updates that
document.

## Test Projects

Small projects in [`test-projects/`](../test-projects), shared by the end-to-end and plugin
tests:

- `gradle-pipeline-baseline/`: wrappers, owning fields, direct and try/catch leaks
- `gradle-pipeline-coverage/`: every stage fixes something; exact counts per stage
- `gradle-dependency-leak/`: a leak through a Maven Central dependency
- `gradle-multimodule/`: two Gradle projects with a leak in each
- `maven-dependency-leak/`: Maven with a Maven Central dependency
- `ant-vendored-jar/`: Ant compiling against a jar checked into `lib/`
- `javac-script/`: no build tool; captured with `-- ./build.sh`
- `javac-return-cycle/`: a leak returned through a cycle of callers, which RLFixer leaves
- `javac-field-transformations/`: fields made final or local before analysis
- `javac-latin1/`: ISO-8859-1 sources compiled for Java 8; `infer` must use the build's
  encoding and release, and `repair` must stop with a clear message

Keep them small: one project per behavior. Add a project and a test for every newly
supported project shape.

## Continuous Integration

[`.github/workflows/ci.yml`](../.github/workflows/ci.yml) runs on every pull request and every
push to `master`:

- `build`: `mvn verify` on JDK 17, 21 and 25 (Ubuntu) and JDK 21 (macOS)
- `e2e`: the end-to-end, tool contract, Maven plugin and Gradle plugin tests on JDK 17, 21
  and 25 with Gradle, Maven and Ant installed; a skipped test fails the job
- `real-projects-quick`: the real projects marked `"tier": "quick"` (minutes each)

[`.github/workflows/real-projects.yml`](../.github/workflows/real-projects.yml) runs every
project weekly, on demand, and on pull requests that change the project list; the large
ones take one to three hours. It repairs real open-source projects listed in
[`scripts/real_projects.json`](../scripts/real_projects.json), each cloned fresh from its own
repository at a pinned release, and checks that `repair` succeeds and that the leak counts
match the recorded ones. Run the same locally, after `mvn package`:

```bash
python3 scripts/real_projects.py run commons-csv   # or --all
```

When a change is meant to change the results, rerun with `--record` and commit the new
counts. To test a newer release of a project, change its `ref` and record again.

## What To Check

- the original project is not changed by `analyze`, `infer`, `repair` or `doctor`
- outputs are where the output contract says, and patch bundles are consumable by `apply`
- a stage normalizes its tool's output before it leaves the stage, and a crash fails it
- unstable values are replaced before anything is compared exactly
- nothing builds on the paper's scripts in `legacy/`
