package utils;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ibm.wala.classLoader.JarFileModule;
import com.ibm.wala.classLoader.Module;
import com.ibm.wala.ipa.callgraph.AnalysisScope;

class ClassPathScopeTest {
	@TempDir
	Path temp;

	@Test
	void missingManifestClassPathEntriesAreSkippedAndExistingOnesFollowed() throws IOException {
		// Like a jar in a Maven repository: its manifest names sibling jars that are not there.
		Path lib = Files.createDirectories(temp.resolve("lib"));
		Path runtime = jar(lib.resolve("runtime.jar"), "missing-api.jar sibling.jar");
		jar(lib.resolve("sibling.jar"), null);
		Path other = jar(temp.resolve("other.jar"), null);

		String classPath = String.join(File.pathSeparator, runtime.toString(), temp.resolve("absent.jar").toString(),
				other.toString(), runtime.toString());
		AnalysisScope scope = ClassPathScope.make(classPath, null);

		List<String> jars = scope.getModules(scope.getLoader(AnalysisScope.APPLICATION)).stream()
				.map(ClassPathScopeTest::jarName).collect(Collectors.toList());
		assertEquals(List.of("runtime.jar", "other.jar", "sibling.jar"), jars);
	}

	private static String jarName(Module module) {
		return new File(((JarFileModule) module).getJarFile().getName()).getName();
	}

	private static Path jar(Path path, String manifestClassPath) throws IOException {
		Manifest manifest = new Manifest();
		manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
		if (manifestClassPath != null) {
			manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, manifestClassPath);
		}
		try (JarOutputStream out = new JarOutputStream(new FileOutputStream(path.toFile()), manifest)) {
			// A manifest is all the test needs.
		}
		return path;
	}
}
