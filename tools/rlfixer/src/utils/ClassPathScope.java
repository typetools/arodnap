package utils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import com.ibm.wala.classLoader.BinaryDirectoryTreeModule;
import com.ibm.wala.core.util.config.AnalysisScopeReader;
import com.ibm.wala.ipa.callgraph.AnalysisScope;
import com.ibm.wala.types.ClassLoaderReference;

/**
 * Builds the analysis scope for a class path the way javac reads it.
 *
 * <p>WALA's {@code makeJavaBinaryAnalysisScope} follows each jar's manifest {@code Class-Path}
 * and aborts when an entry there does not exist. Such entries are common: a jar in a Maven
 * repository names its dependencies as siblings, but Maven stores every jar in its own
 * directory. javac skips entries that do not exist, and so does this scope. Everything else is
 * the same: the JDK as primordial, then every directory, jar and class file on the class path
 * as application code, with manifest {@code Class-Path} entries followed.
 */
final class ClassPathScope {
	private ClassPathScope() {
	}

	static AnalysisScope make(String classPath, File exclusionsFile) throws IOException {
		AnalysisScope scope = AnalysisScopeReader.instance.makePrimordialScope(exclusionsFile);
		ClassLoaderReference application = scope.getLoader(AnalysisScope.APPLICATION);
		Set<String> added = new HashSet<>();
		Deque<File> pending = new ArrayDeque<>();
		for (String entry : classPath.split(File.pathSeparator)) {
			if (!entry.isEmpty()) {
				pending.add(new File(entry));
			}
		}
		while (!pending.isEmpty()) {
			File file = pending.removeFirst();
			if (!file.exists() || !added.add(file.getCanonicalPath())) {
				continue;
			}
			if (file.isDirectory()) {
				scope.addToScope(application, new BinaryDirectoryTreeModule(file));
			} else if (file.getName().endsWith(".class")) {
				try {
					scope.addClassFileToScope(application, file);
				} catch (Exception e) {
					throw new IOException("Cannot read class file " + file, e);
				}
			} else {
				JarFile jar = new JarFile(file, false);
				scope.addToScope(application, jar);
				Manifest manifest = jar.getManifest();
				String manifestClassPath = manifest == null ? null
						: manifest.getMainAttributes().getValue(Attributes.Name.CLASS_PATH);
				if (manifestClassPath != null) {
					for (String reference : manifestClassPath.trim().split("\\s+")) {
						if (!reference.isEmpty()) {
							pending.add(new File(file.getParentFile(), reference));
						}
					}
				}
			}
		}
		return scope;
	}
}
