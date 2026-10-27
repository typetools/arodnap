package org.arodnap.engine.inputs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.arodnap.engine.files.FilePaths;
import org.arodnap.model.CompileUnit;
import org.arodnap.model.ProjectInputs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CompileUnitsTest {
    @TempDir
    Path temp;
    Path root;

    @BeforeEach
    void realRoot() {
        root = FilePaths.real(temp);
    }

    private Path file(String relative, String text) throws IOException {
        Path path = root.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, text);
        return path;
    }

    private Path jar(String relative, String... classes) throws IOException {
        Path path = root.resolve(relative);
        Files.createDirectories(path.getParent());
        try (OutputStream out = Files.newOutputStream(path); JarOutputStream jar = new JarOutputStream(out)) {
            for (String name : classes) {
                jar.putNextEntry(new JarEntry(name));
                jar.closeEntry();
            }
        }
        return path;
    }

    private static CompileUnit unit(Path cwd, List<Path> sources, List<Path> classpath, Path output, Integer release) {
        return new CompileUnit(cwd, sources, classpath, Optional.ofNullable(output), Optional.empty(), Optional.ofNullable(release),
                Optional.of("UTF-8"), List.of(), List.of(), Optional.empty());
    }

    @Test
    void readsAJavacCommandLine() {
        Optional<CompileUnit> unit = JavacArguments.parse(List.of("-d", "out", "--release=11", "-encoding", "ISO-8859-1", "-cp", "lib/a.jar:lib/b.jar",
                "-processor", "p.One,p.Two", "-Xlint:all", "src/A.java", "-sourcepath", "src", "B.java"), root, Optional.of(":compileJava"));

        assertThat(unit).isPresent();
        CompileUnit parsed = unit.get();
        assertThat(parsed.sources()).containsExactly(root.resolve("src/A.java"), root.resolve("B.java"));
        assertThat(parsed.classpath()).containsExactly(root.resolve("lib/a.jar"), root.resolve("lib/b.jar"));
        assertThat(parsed.outputDirectory()).contains(root.resolve("out"));
        assertThat(parsed.release()).contains(11);
        assertThat(parsed.encoding()).contains("ISO-8859-1");
        assertThat(parsed.processors()).containsExactly("p.One", "p.Two");
        assertThat(parsed.label()).contains(":compileJava");
    }

    @Test
    void aCommandLineWithoutSourcesIsNoCompileUnit() {
        assertThat(JavacArguments.parse(List.of("-version"), root, Optional.empty())).isEmpty();
    }

    @Test
    void oldLanguageLevelsReadAsTheirFeatureRelease() {
        assertThat(JavacArguments.javaLevel("1.8")).contains(8);
        assertThat(JavacArguments.javaLevel("17")).contains(17);
        assertThat(JavacArguments.javaLevel("seventeen")).isEmpty();
    }

    @Test
    void aClassPathWildcardMeansEveryJarInTheDirectory() throws Exception {
        jar("lib/b.jar");
        jar("lib/a.jar");
        file("lib/notes.txt", "");

        assertThat(JavacArguments.pathList("lib/*", root)).containsExactly(root.resolve("lib/a.jar"), root.resolve("lib/b.jar"));
    }

    @Test
    void mergesUnitsAndDropsWhatTheBuildCompiledItself() throws Exception {
        Path a = file("core/src/main/java/p/A.java", "package p; class A {}");
        Path b = file("app/src/main/java/q/B.java", "package q; class B {}");
        Path coreClasses = root.resolve("core/build/classes");
        file("core/build/classes/p/A.class", "");
        // The app module compiles against core's jar; its classes all came from core's sources.
        Path coreJar = jar("core/build/libs/core.jar", "p/A.class");
        // A dependency the build downloaded into the project stays.
        Path dependency = jar("lib/dependency.jar", "d/D.class");

        ProjectInputs inputs = CompileUnits.merge(List.of(
                unit(root.resolve("core"), List.of(a), List.of(dependency), coreClasses, 8),
                unit(root.resolve("app"), List.of(b), List.of(coreClasses, coreJar, dependency, root.resolve("missing.jar")),
                        root.resolve("app/build/classes"), 11)));

        assertThat(inputs.sources()).containsExactly(a, b);
        assertThat(inputs.classpath()).containsExactly(dependency);
        assertThat(inputs.release()).contains(11);
        assertThat(inputs.encoding()).contains("UTF-8");
        assertThat(inputs.sourceRoot()).isEqualTo(root);
    }

    @Test
    void releasesBelowEightAreAnalyzedAsEight() throws Exception {
        Path a = file("src/A.java", "class A {}");
        assertThat(CompileUnits.merge(List.of(unit(root, List.of(a), List.of(), null, 6))).release()).contains(8);
    }

    @Test
    void generatedSourcesAreAnalyzedButKeptApart() throws Exception {
        Path a = file("src/A.java", "class A {}");
        Path generated = file("build/generated/G.java", "class G {}");
        file("build/generated/module-info.java", "module m {}");
        CompileUnit withGenerated = new CompileUnit(root, List.of(a), List.of(), Optional.empty(), Optional.of(root.resolve("build/generated")),
                Optional.empty(), Optional.empty(), List.of(), List.of(), Optional.empty());

        ProjectInputs inputs = CompileUnits.merge(List.of(withGenerated));

        assertThat(inputs.sources()).containsExactly(a);
        assertThat(inputs.generatedSources()).containsExactly(generated);
    }

    @Test
    void theSourceRootMovesAboveASrcDirectoryBecauseRLFixerStripsIt() {
        // The nearest common directory of the sources...
        assertThat(CompileUnits.analysisRoot(List.of(root.resolve("src/main/java/p/A.java")))).isEqualTo(root.resolve("src/main/java/p"));
        assertThat(CompileUnits.analysisRoot(List.of(root.resolve("x/p/A.java"), root.resolve("x/q/B.java")))).isEqualTo(root.resolve("x"));
        // ...unless a source would then be named "src/...": RLFixer strips that prefix, so go up.
        assertThat(CompileUnits.analysisRoot(List.of(root.resolve("src/A.java"), root.resolve("test/B.java")))).isEqualTo(root.getParent());
    }

    @Test
    void noCompiledSourcesIsAnUnsupportedProject() {
        assertThatThrownBy(() -> CompileUnits.merge(List.of())).isInstanceOf(UnsupportedProjectException.class)
                .hasMessageContaining("compiled no Java sources");
    }

    @Test
    void lombokIsRejectedWithAnExplanation() throws Exception {
        Path a = file("src/A.java", "class A {}");
        Path lombok = jar("repo/org/projectlombok/lombok/1.18/lombok-1.18.jar", "lombok/Getter.class");

        assertThatThrownBy(() -> CompileUnits.merge(List.of(unit(root, List.of(a), List.of(lombok), null, null))))
                .isInstanceOf(UnsupportedProjectException.class).hasMessageContaining("Lombok");
    }

    @Test
    void readsARecordedCaptureFileOnceperDistinctLine() throws Exception {
        Path a = file("src/A.java", "class A {}");
        String line = "{\"cwd\": \"" + root + "\", \"task\": \":compileJava\", \"args\": [\"-d\", \"out\", \"" + a + "\"]}";
        Path capture = file("capture.jsonl", line + "\n" + line + "\n{\"cwd\": \"" + root + "\", \"args\": [\"-version\"]}\n");

        List<CompileUnit> units = CompileUnits.load(capture);

        assertThat(units).hasSize(1);
        assertThat(units.get(0).label()).contains(":compileJava");
    }
}
