/*
 * Copyright (C) 2026 Ricardo Cherobin.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package speclok;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Shared helpers for the SDD harness, now living entirely inside speclok. Ports the Python
 * {@code .sdd/hooks/_common.py}, {@code java_common.py} and the config into a single, dependency-free
 * Java core that every {@code sdd} subcommand and the MCP server build on. The single source of truth
 * stays the {@code @Spec}/{@code @AC} annotations in the code; this class only reads them.
 */
final class SddCore {
	private SddCore() {}

	// ---- locations -----------------------------------------------------------

	/** Repo root: {@code -Dsdd.root}, else {@code CLAUDE_PROJECT_DIR}, else the process cwd. */
	static Path repoRoot() {
		String prop = System.getProperty("sdd.root");
		if (prop != null && !prop.isEmpty()) return Paths.get(prop).toAbsolutePath().normalize();
		String env = System.getenv("CLAUDE_PROJECT_DIR");
		if (env != null && !env.isEmpty()) return Paths.get(env).toAbsolutePath().normalize();
		return Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
	}

	/** Mutable runtime state lives here (current task, iteration counters, telemetry) - the only
	 * thing kept outside the source tree, because it is state, not harness logic. */
	static Path stateDir() {
		Path d = repoRoot().resolve(".sdd-state");
		try { Files.createDirectories(d); } catch (Exception ignore) {}
		return d;
	}

	/** Where the {@code @Spec}/{@code @AC} annotation sources live when running from a source tree
	 * (a dev fallback; a real project resolves the annotations from the speclok jar on its classpath). */
	static Path annotationDir() {
		return repoRoot().resolve("src").resolve("core").resolve("speclok");
	}

	// ---- active task ---------------------------------------------------------

	static String activeTaskId() {
		Path f = stateDir().resolve("current-task");
		if (!Files.exists(f)) {               // migration grace: honor an old .sdd/current-task
			Path legacy = repoRoot().resolve(".sdd").resolve("current-task");
			if (Files.exists(legacy)) f = legacy;
			else return null;
		}
		try {
			for (String line : new String(Files.readAllBytes(f), StandardCharsets.UTF_8).split("\n")) {
				String t = line.trim();
				if (!t.isEmpty() && !t.startsWith("#")) return t;
			}
		} catch (Exception ignore) {}
		return null;
	}

	static void writeActiveTask(String taskId) {
		try {
			Files.write(stateDir().resolve("current-task"),
				((taskId == null ? "# idle" : taskId) + "\n").getBytes(StandardCharsets.UTF_8));
		} catch (Exception ignore) {}
	}

	// ---- config (baked defaults; optional JSON override) ---------------------

	private static Map<String, Object> CONFIG;

	static Map<String, Object> config() {
		if (CONFIG != null) return CONFIG;
		Map<String, Object> c = defaultConfig();
		// A project self-describes with a sdd.config.json at its root, overriding the baked defaults.
		mergeOverride(c, repoRoot().resolve("sdd.config.json"));
		CONFIG = c;
		return c;
	}

	private static void mergeOverride(Map<String, Object> c, Path file) {
		if (!Files.exists(file)) return;
		try {
			Map<String, Object> user = SddJson.parseObject(
				new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
			for (Map.Entry<String, Object> e : user.entrySet()) c.put(e.getKey(), e.getValue());
		} catch (Exception ignore) {}
	}

	/** Path to the running harness jar (for the compile classpath), or {@code null} if running
	 * from a classes dir. Lets the gates resolve {@code @Spec}/{@code @AC} from the jar exactly as a
	 * real project resolves them from the speclok dependency on its classpath. */
	static Path sddJarPath() {
		try {
			java.net.URL u = SddCore.class.getProtectionDomain().getCodeSource().getLocation();
			Path p = Paths.get(u.toURI());
			if (Files.isRegularFile(p) && p.toString().endsWith(".jar")) return p;
		} catch (Exception ignore) {}
		String cfg = cfgStr("sddJar");
		if (!cfg.isEmpty()) {
			Path p = repoRoot().resolve(cfg);
			if (Files.exists(p)) return p;
		}
		return null;
	}

	private static Map<String, Object> defaultConfig() {
		Map<String, Object> c = new LinkedHashMap<String, Object>();
		// where @Spec/@AC annotations are discovered (roots scanned for *.java)
		c.put("annotationRoots", new ArrayList<String>(java.util.Arrays.asList(
			"src/main/java",
			"src/test/java")));
		c.put("scopeGuardOnNoTask", "allow");     // allow | warn | block
		c.put("maxErrorLines", Long.valueOf(3));
		c.put("maxIters", Long.valueOf(5));
		c.put("gardenerMaxFileLines", Long.valueOf(80));
		c.put("docsRoots", new ArrayList<String>(Collections.singletonList(".")));
		c.put("docsEntryPoint", "AGENTS.md");
		c.put("docsEntryMaxLines", Long.valueOf(120));
		c.put("routingSmallMaxFiles", Long.valueOf(2));
		c.put("routingStrongKeywords", new ArrayList<String>(java.util.Arrays.asList(
			"security", "concurrency", "migration", "legacy", "auth", "crypto")));
		c.put("routingEscalateAfter", Long.valueOf(2));
		c.put("telemetryPath", ".sdd-state/telemetry/runs.jsonl");
		c.put("observeCommand", "");               // empty -> observe is a no-op
		c.put("observeExpect", new LinkedHashMap<String, Object>());
		// Build-tool gates: when set, the gates run these shell commands instead of calling javac
		// directly. Placeholders in testCommand: {id} (the AC id), {test}/{class} (the test name).
		// Empty -> fall back to the self-contained javac/java path (the speclok prototype default).
		c.put("buildCommand", "");                 // e.g. "mvn -q -o compile" or "gradle -q classes"
		c.put("testCommand", "");                  // e.g. "mvn -q -o -Dtest=*#{id}_* -DfailIfNoTests=false test"
		return c;
	}

	static String cfgStr(String key) { return SddJson.str(config().get(key)); }
	static long cfgLong(String key, long dflt) { return SddJson.lng(config().get(key), dflt); }
	static List<String> cfgList(String key) { return SddJson.strList(config().get(key)); }

	// ---- @Spec discovery -----------------------------------------------------

	/** Every {@code @Spec} found under the configured annotation roots, sorted by id. The
	 * {@code source} field is repo-relative. */
	static List<SpecBookApp.Spec> listSpecs() {
		List<SpecBookApp.Spec> out = new ArrayList<SpecBookApp.Spec>();
		Path root = repoRoot();
		for (String r : cfgList("annotationRoots")) {
			Path base = root.resolve(r);
			if (!Files.exists(base)) continue;
			try (Stream<Path> walk = Files.walk(base)) {
				for (Path p : (Iterable<Path>) walk::iterator) {
					if (!p.toString().endsWith(".java")) continue;
					String text = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
					if (!text.contains("@Spec")) continue;
					String rel = root.relativize(p).toString().replace('\\', '/');
					SpecBookApp.Spec s = SpecBookApp.manifestFromText(text, rel);
					if (s != null && !s.id.isEmpty()) out.add(s);
				}
			} catch (Exception ignore) {}
		}
		out.sort(java.util.Comparator.comparing(s -> s.id));
		return out;
	}

	static SpecBookApp.Spec specForTask(String taskId) {
		if (taskId == null) return null;
		for (SpecBookApp.Spec s : listSpecs()) if (taskId.equals(s.id)) return s;
		return null;
	}

	/** Every {@code @Spec} whose {@code allowedFiles} matches the repo-relative {@code rel} - used to
	 * auto-arm the active task from the file being edited, so scope works without a manual pointer. */
	static List<SpecBookApp.Spec> specsGoverning(String rel) {
		List<SpecBookApp.Spec> out = new ArrayList<SpecBookApp.Spec>();
		if (rel == null) return out;
		for (SpecBookApp.Spec s : listSpecs()) if (pathAllowed(rel, s.allowedFiles)) out.add(s);
		return out;
	}

	// ---- glob (port of _common._glob_to_re, real ** support) -----------------

	static Pattern globToRegex(String pattern) {
		String pat = pattern.replace('\\', '/');
		StringBuilder out = new StringBuilder("^");
		int i = 0, n = pat.length();
		while (i < n) {
			char c = pat.charAt(i);
			if (c == '*') {
				if (pat.startsWith("**/", i)) { out.append("(?:.*/)?"); i += 3; continue; }
				if (pat.startsWith("**", i)) { out.append(".*"); i += 2; continue; }
				out.append("[^/]*"); i++;
			} else if (c == '?') { out.append("[^/]"); i++; }
			else { out.append(Pattern.quote(String.valueOf(c))); i++; }
		}
		out.append("$");
		return Pattern.compile(out.toString());
	}

	static boolean globMatch(String relPath, String pattern) {
		if (relPath == null) return false;
		return globToRegex(pattern).matcher(relPath.replace('\\', '/')).matches();
	}

	static boolean pathAllowed(String relPath, List<String> patterns) {
		if (patterns == null) return false;
		for (String p : patterns) if (globMatch(relPath, p)) return true;
		return false;
	}

	/** Normalize a tool path to a repo-relative POSIX path (robust to absolute/cwd/Windows). */
	static String relToRepo(String path) {
		if (path == null) return null;
		try {
			Path p = Paths.get(path.replace('\\', '/'));
			if (!p.isAbsolute()) p = repoRoot().resolve(p);
			return repoRoot().relativize(p.toAbsolutePath().normalize()).toString().replace('\\', '/');
		} catch (Exception e) {
			return path.replace('\\', '/');
		}
	}

	// ---- task java files (port of java_common.py) ----------------------------

	static List<Path> annotationSourceFiles() {
		List<Path> out = new ArrayList<Path>();
		for (String n : new String[] {"Spec.java", "AC.java", "ACs.java"}) {
			Path p = annotationDir().resolve(n);
			if (Files.exists(p)) out.add(p);
		}
		return out;
	}

	static boolean isTest(Path p) { return p.toString().replace('\\', '/').contains("/test/"); }

	static List<Path> resolveGlobs(List<String> patterns) {
		List<Path> out = new ArrayList<Path>();
		Path root = repoRoot();
		for (String pat : patterns) {
			// walk the shallowest fixed prefix of the glob to keep it cheap
			Path base = root;
			if (!Files.exists(base)) continue;
			try (Stream<Path> walk = Files.walk(base)) {
				for (Path p : (Iterable<Path>) walk::iterator) {
					if (!Files.isRegularFile(p) || !p.toString().endsWith(".java")) continue;
					String rel = root.relativize(p).toString().replace('\\', '/');
					if (globMatch(rel, pat)) out.add(p);
				}
			} catch (Exception ignore) {}
		}
		return out;
	}

	/** (mainSources, testSources) for the task - annotations always in the main compile set. */
	static Path[][] taskJavaFiles(String taskId) {
		SpecBookApp.Spec s = specForTask(taskId);
		List<String> allowed = s == null ? new ArrayList<String>() : s.allowedFiles;
		List<Path> files = resolveGlobs(allowed);
		TreeSet<Path> main = new TreeSet<Path>(), test = new TreeSet<Path>();
		for (Path p : files) (isTest(p) ? test : main).add(p);
		main.addAll(annotationSourceFiles());
		return new Path[][] { main.toArray(new Path[0]), test.toArray(new Path[0]) };
	}

	/** Fully-qualified names (package.Class) for each test source file. */
	static List<String> discoverTestClasses(Path[] testFiles) {
		List<String> classes = new ArrayList<String>();
		for (Path p : testFiles) {
			try {
				String text = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
				java.util.regex.Matcher pm = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;").matcher(text);
				String pkg = pm.find() ? pm.group(1) : "";
				java.util.regex.Matcher cm = Pattern.compile("\\b(?:public\\s+)?class\\s+(\\w+)").matcher(text);
				if (!cm.find()) continue;
				classes.add(pkg.isEmpty() ? cm.group(1) : pkg + "." + cm.group(1));
			} catch (Exception ignore) {}
		}
		return classes;
	}

	// ---- symbol index for the context-pack (scoped to the task's files) ------

	static Map<String, String> symbolIndex(SpecBookApp.Spec s) {
		Map<String, String> index = new LinkedHashMap<String, String>();
		Path root = repoRoot();
		for (Path p : resolveGlobs(s.allowedFiles)) {
			try {
				String text = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
				String rel = root.relativize(p).toString().replace('\\', '/');
				SpecContextApp.indexJava(text, rel, index);
			} catch (Exception ignore) {}
		}
		return index;
	}

	// ---- misc ----------------------------------------------------------------

	static String readStdin() {
		try {
			InputStream in = System.in;
			ByteArrayOutputStream bos = new ByteArrayOutputStream();
			byte[] buf = new byte[4096];
			int r;
			if (in.available() == 0) {
				// block for at most a short read; hook events are tiny and already buffered
			}
			while ((r = in.read(buf)) != -1) bos.write(buf, 0, r);
			return new String(bos.toByteArray(), StandardCharsets.UTF_8);
		} catch (Exception e) {
			return "";
		}
	}

	static void warn(String msg) { System.err.println("[sdd] " + msg); }
}
