package main;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ibm.wala.core.util.config.AnalysisScopeReader;
import com.ibm.wala.ipa.callgraph.AnalysisScope;
import com.ibm.wala.ipa.summaries.MethodSummary;
import com.ibm.wala.ipa.summaries.XMLMethodSummaryReader;
import com.ibm.wala.ssa.SSAGetInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAReturnInstruction;
import com.ibm.wala.types.ClassLoaderReference;
import com.ibm.wala.types.MethodReference;

import utils.ProgramInfo;

/**
 * RLFixer must reach the same result on every supported JDK. Since JDK 21.0.13, 25.0.5 and 26,
 * {@code Reference.get()} returns through a native {@code get0()} (JDK-8352565), which WALA does
 * not model; without RLFixer's own model it lost every resource read back from a SoftReference.
 */
class JdkNativeModelsTest {
	private static final String FIXTURE = String.join("\n",
			"package fixture;",
			"",
			"import java.io.Closeable;",
			"import java.lang.ref.SoftReference;",
			"",
			"class Stream implements Closeable {",
			"	public void close() {",
			"	}",
			"}",
			"",
			"class Holder {",
			"	private final Stream stream = new Stream();",
			"",
			"	Stream getStream() {",
			"		return stream;",
			"	}",
			"}",
			"",
			"class Cache {",
			"	private SoftReference<Holder> entry;",
			"",
			"	void put(Holder holder) {",
			"		entry = new SoftReference<>(holder);",
			"	}",
			"",
			"	Holder get() {",
			"		return entry.get();",
			"	}",
			"}",
			"",
			"public class Page {",
			"	private final Cache cache = new Cache();",
			"",
			"	void add() {",
			"		cache.put(new Holder());",
			"	}",
			"",
			"	String describe() {",
			"		Holder holder = cache.get();",
			"		Stream stream = holder.getStream(); // the warning",
			"		return stream.toString();",
			"	}",
			"}",
			"");

	@TempDir
	Path temp;

	@Test
	void summaryFileModelsReferenceGet0AsReadingTheReferent() throws IOException {
		AnalysisScope scope = AnalysisScopeReader.instance.makePrimordialScope(null);
		MethodSummary summary;
		try (InputStream xml = Objects.requireNonNull(
				ProgramInfo.class.getClassLoader().getResourceAsStream(ProgramInfo.JDK_SUMMARIES))) {
			summary = new XMLMethodSummaryReader(xml, scope).getSummaries().get(MethodReference.findOrCreate(
					ClassLoaderReference.Primordial, "Ljava/lang/ref/Reference", "get0", "()Ljava/lang/Object;"));
		}
		assertNotNull(summary, "no model for Reference.get0()");
		List<SSAInstruction> statements = Arrays.stream(summary.getStatements()).filter(Objects::nonNull)
				.collect(Collectors.toList());
		assertEquals(2, statements.size(), statements.toString());
		SSAGetInstruction read = (SSAGetInstruction) statements.get(0);
		assertEquals("referent", read.getDeclaredField().getName().toString());
		assertEquals(1, read.getRef(), "reads this.referent");
		assertEquals(read.getDef(), ((SSAReturnInstruction) statements.get(1)).getResult());
	}

	@Test
	void resourceReadBackFromSoftReferenceKeepsItsFieldSource() throws Exception {
		System.out.println("Reference.get() on this JDK (" + System.getProperty("java.version") + ") "
				+ (referenceGetIsNative() ? "returns through the native get0()" : "reads the referent directly"));
		Path sources = Files.createDirectories(temp.resolve("src/fixture"));
		Files.writeString(sources.resolve("Page.java"), FIXTURE);
		Path classes = Files.createDirectories(temp.resolve("classes"));
		JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
		assertEquals(0, javac.run(null, null, null, "-g", "--release", "17", "-d", classes.toString(),
				sources.resolve("Page.java").toString()));

		int line = Arrays.asList(FIXTURE.split("\n")).indexOf("		Stream stream = holder.getStream(); // the warning") + 1;
		Path warnings = Files.writeString(temp.resolve("warnings.txt"), "fixture/Page.java," + line + ",describe,False\n");
		Path appClasses = Files.writeString(temp.resolve("app-classes.txt"),
				String.join("\n", "fixture.Stream", "fixture.Holder", "fixture.Cache", "fixture.Page") + "\n");
		Path sourceList = Files.writeString(temp.resolve("sources.txt"), "fixture/Page.java\n");
		Path debug = temp.resolve("debug.txt");

		Main.main(new String[] { "-classpath", classes.toString(), "-warningsFile", warnings.toString(), "-appClasses",
				appClasses.toString(), "-projectDir", temp.resolve("src").toString(), "-srcFiles",
				sourceList.toString(), "-debugOutput", debug.toString() });

		// Index^Source File^Line Number^Matched Method^MatchedInstruction^Aliases^Classification^Duplicate^Unfixable^Comments
		List<String> rows = Files.readAllLines(debug, StandardCharsets.UTF_8);
		assertEquals(2, rows.size(), String.join("\n", rows));
		String[] row = rows.get(1).split("\\^", -1);
		assertEquals(String.valueOf(line), row[2]);
		assertTrue(row[3].contains("describe"), row[3]);
		// The stream belongs to the Holder that the cache returns, so it must not be closed here.
		assertEquals("true", row[8], "unfixable: " + rows.get(1));
		assertTrue(row[6].contains("FIELD"), "classification: " + rows.get(1));
	}

	private static boolean referenceGetIsNative() {
		for (Method method : java.lang.ref.Reference.class.getDeclaredMethods()) {
			if (method.getName().equals("get0") && Modifier.isNative(method.getModifiers())) {
				return true;
			}
		}
		return false;
	}
}
