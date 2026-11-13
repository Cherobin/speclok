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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The Java build and acceptance gates, driven by the active task's manifest - the port of
 * {@code .sdd/java_build.py} and {@code .sdd/java_ac.py}. Compiles the minimal, self-contained target
 * ({@code @Spec}/{@code @AC} + the files in {@code allowedFiles}) to a temp dir; never builds all
 * of speclok. The acceptance gate also runs each JUnit-free test class via {@code java <Class> <AC>}.
 */
final class SddGates {
	private SddGates() {}

	static boolean hasBuildCommand() { return !SddCore.cfgStr("buildCommand").trim().isEmpty(); }
	static boolean hasTestCommand() { return !SddCore.cfgStr("testCommand").trim().isEmpty(); }

	/** Compiles the task's main sources. Returns true on success; appends javac output to {@code out}. */
	static boolean buildInto(String taskId, StringBuilder out) {
		if (hasBuildCommand()) return runCommand(SddCore.cfgStr("buildCommand"), out);
		Path[][] files = SddCore.taskJavaFiles(taskId);
		Path[] main = files[0];
		if (main.length == 0) return true;                // no java target resolved -> no-op
		Path tmp = null;
		try {
			tmp = Files.createTempDirectory("sdd-build");
			List<String> cmd = new ArrayList<String>();
			cmd.add("javac"); cmd.add("-d"); cmd.add(tmp.toString());
			Path jar = SddCore.sddJarPath();
			// The compile gate asks "does the code build?", not "is the spec complete?" (that is speclint's
			// job). So run the spec-lint processor in warn mode here, otherwise an in-progress @Spec breaks
			// the build and the agent spins in a write-reject-rewrite loop.
			if (jar != null) { cmd.add("-cp"); cmd.add(jar.toString()); cmd.add("-Aspeclok.severity=warn"); }
			for (Path p : main) cmd.add(p.toString());
			Proc r = run(cmd);
			if (r.code != 0) out.append(r.err.isEmpty() ? r.outText : r.err);
			return r.code == 0;
		} catch (Exception e) {
			out.append("build: could not run javac (").append(e.getMessage()).append(")");
			return true;                                  // fail-open: a harness bug never blocks the agent
		} finally {
			deleteTree(tmp);
		}
	}

	/** Compiles main+test, runs each test class with {@code which} (an AC id or "all"). Returns true if
	 * all pass (or there are no tests). Appends stdout/stderr to {@code out}. */
	static boolean acceptInto(String taskId, String which, StringBuilder out) {
		if (hasTestCommand()) {
			String cmd = SddCore.cfgStr("testCommand")
				.replace("{id}", "all".equals(which) ? "*" : which)
				.replace("{test}", "all".equals(which) ? "*" : which)
				.replace("{class}", "all".equals(which) ? "*" : which);
			return runCommand(cmd, out);
		}
		Path[][] files = SddCore.taskJavaFiles(taskId);
		Path[] main = files[0], test = files[1];
		if (test.length == 0) return true;                // nothing to enforce
		List<String> classes = SddCore.discoverTestClasses(test);
		Path tmp = null;
		try {
			tmp = Files.createTempDirectory("sdd-accept");
			Path jar = SddCore.sddJarPath();
			List<String> comp = new ArrayList<String>();
			comp.add("javac"); comp.add("-d"); comp.add(tmp.toString());
			if (jar != null) { comp.add("-cp"); comp.add(jar.toString()); comp.add("-Aspeclok.severity=warn"); }
			for (Path p : main) comp.add(p.toString());
			for (Path p : test) comp.add(p.toString());
			Proc c = run(comp);
			if (c.code != 0) { out.append(c.err.isEmpty() ? c.outText : c.err); return false; }
			String runCp = jar != null ? tmp + java.io.File.pathSeparator + jar : tmp.toString();
			int failed = 0;
			for (String fqn : classes) {
				Proc r = run(java.util.Arrays.asList("java", "-cp", runCp, fqn, which));
				out.append(r.outText);
				if (!r.err.isEmpty()) out.append(r.err);
				if (r.code != 0) failed++;
			}
			return failed == 0;
		} catch (Exception e) {
			out.append("accept: could not run javac/java (").append(e.getMessage()).append(")");
			return true;
		} finally {
			deleteTree(tmp);
		}
	}

	/** Runs a configured build-tool command through the shell; true on exit 0. Appends output. */
	private static boolean runCommand(String command, StringBuilder out) {
		try {
			boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
			List<String> cmd = win
				? java.util.Arrays.asList("cmd", "/c", command)
				: java.util.Arrays.asList("sh", "-c", command);
			Proc r = run(cmd);
			String text = (r.outText + (r.err.isEmpty() ? "" : "\n" + r.err)).trim();
			if (r.code != 0) out.append(text);
			else if (!text.isEmpty()) out.append(text);
			return r.code == 0;
		} catch (Exception e) {
			out.append("gate: could not run the command (").append(e.getMessage()).append(")");
			return true;                                  // fail-open
		}
	}

	// ---- process helper ------------------------------------------------------

	static final class Proc { int code; String outText = "", err = ""; }

	static Proc run(List<String> cmd) throws Exception {
		ProcessBuilder pb = new ProcessBuilder(cmd);
		pb.directory(SddCore.repoRoot().toFile());
		Process p = pb.start();
		String o = readAll(p.getInputStream());
		String e = readAll(p.getErrorStream());
		int code = p.waitFor();
		Proc r = new Proc();
		r.code = code; r.outText = o; r.err = e;
		return r;
	}

	private static String readAll(java.io.InputStream in) throws Exception {
		java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
		byte[] buf = new byte[4096];
		int n;
		while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
		return new String(bos.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
	}

	private static void deleteTree(Path dir) {
		if (dir == null) return;
		try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
			walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
				try { Files.deleteIfExists(p); } catch (Exception ignore) {}
			});
		} catch (Exception ignore) {}
	}
}
