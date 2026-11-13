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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The harness operations as pure functions returning plain JSON-shaped values - the port of
 * {@code .sdd/mcp/sdd_ops.py}. Both the {@code sdd} CLI subcommands and the in-process MCP server
 * ({@link SddMcp}) call these, so the five tools behave identically however they are invoked.
 */
final class SddOps {
	private SddOps() {}

	/** Index of every discovered task: id, objective, fonte, acs. */
	static List<Map<String, Object>> listTasks() {
		List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
		for (SpecBookApp.Spec s : SddCore.listSpecs()) {
			List<String> acs = new ArrayList<String>();
			for (String[] ac : s.acs) acs.add(ac[0]);
			out.add(SddJson.map("id", s.id, "objective", s.objective, "source", s.source, "acs", acs));
		}
		return out;
	}

	/** The agent briefing for a task: objective, scope, context-pack (symbol -> file:line span),
	 * out-of-scope, and acceptance criteria. */
	static Map<String, Object> briefing(String taskId) {
		SpecBookApp.Spec s = SddCore.specForTask(taskId);
		if (s == null) return SddJson.map("error", "task " + taskId + " not found (no @Spec)");
		List<Map<String, Object>> resolved = new ArrayList<Map<String, Object>>();
		List<String> missing = new ArrayList<String>();
		Map<String, String[]> index = rangedIndex(s.allowedFiles);
		for (String sym : s.symbols) {
			String[] hit = index.get(sym);
			if (hit != null) resolved.add(SddJson.map(
				"symbol", sym, "file", hit[0],
				"start", Long.valueOf(hit[1]), "end", Long.valueOf(hit[2])));
			else missing.add(sym);
		}
		List<Map<String, Object>> criteria = new ArrayList<Map<String, Object>>();
		for (String[] ac : s.acs)
			criteria.add(SddJson.map("id", ac[0], "given", ac[1], "when", ac[2], "then", ac[3]));
		return SddJson.map(
			"id", taskId,
			"objective", s.objective,
			"allowed_files", s.allowedFiles,
			"out_of_scope", s.outOfScope,
			"context_pack", resolved,
			"unresolved_symbols", missing,
			"criteria", criteria);
	}

	/** Deterministic spec-lint - same rules as {@code SpecLintProcessor}. */
	static Map<String, Object> speclint(String taskId) {
		SpecBookApp.Spec s = SddCore.specForTask(taskId);
		List<String> errors = new ArrayList<String>(), warnings = new ArrayList<String>();
		if (s == null) {
			errors.add("task " + taskId + " not found");
			return SddJson.map("ok", Boolean.FALSE, "errors", errors, "warnings", warnings);
		}
		if (s.objective.trim().isEmpty()) errors.add("objective is empty");
		if (s.allowedFiles.isEmpty()) errors.add("allowedFiles is empty");
		if (s.outOfScope.isEmpty()) warnings.add("outOfScope is empty - delimit what NOT to do");
		if (s.acs.isEmpty()) errors.add("no @AC - a spec needs criteria");
		Set<String> seen = new HashSet<String>();
		for (String[] ac : s.acs) {
			String cid = ac[0];
			if (cid.isEmpty()) errors.add("@AC without id");
			else if (!seen.add(cid)) errors.add(cid + ": duplicate @AC id");
			if (ac[1].trim().isEmpty()) errors.add((cid.isEmpty() ? "?" : cid) + ": 'given' is empty");
			if (ac[2].trim().isEmpty()) errors.add((cid.isEmpty() ? "?" : cid) + ": 'when' is empty");
			if (ac[3].trim().isEmpty()) errors.add((cid.isEmpty() ? "?" : cid) + ": 'then' is empty");
		}
		return SddJson.map("ok", Boolean.valueOf(errors.isEmpty()), "errors", errors, "warnings", warnings);
	}

	/** Runs the Java gates (build + acceptance) for a task without disturbing the active task. */
	static Map<String, Object> verify(String taskId) {
		String prev = SddCore.activeTaskId();
		try {
			SddCore.writeActiveTask(taskId);
			StringBuilder buildOut = new StringBuilder();
			boolean buildOk = SddGates.buildInto(taskId, buildOut);
			StringBuilder accOut = new StringBuilder();
			boolean accOk = SddGates.acceptInto(taskId, "all", accOut);
			return SddJson.map(
				"build_ok", Boolean.valueOf(buildOk),
				"acceptance_ok", Boolean.valueOf(accOk),
				"unverified_criteria", SddChecks.uncoveredCriteria(taskId),
				"build_out", trim(buildOut.toString(), 1200),
				"acceptance_out", trim(accOut.toString(), 1200));
		} finally {
			SddCore.writeActiveTask(prev);
		}
	}

	private static String trim(String s, int max) {
		s = s.trim();
		return s.length() > max ? s.substring(0, max) : s;
	}

	// ---- scaffold ------------------------------------------------------------

	static Map<String, Object> scaffoldTask(String taskId, String targetFile, String objective,
			List<String> symbols, List<String> outOfScope, List<Map<String, Object>> acs,
			String testFile, String importFqn, boolean apply) {
		if (testFile == null || testFile.trim().isEmpty()) testFile = deriveTestFile(targetFile);
		List<String> arquivos = Arrays.asList(targetFile, testFile);
		String spec = specBlock(taskId, objective, arquivos, symbols, outOfScope);
		String ac = acBlock(acs);
		String testClass = stem(testFile);
		String testSrc = testFile(testClass, importFqn, acs);

		List<String> written = new ArrayList<String>();
		if (apply) {
			try {
				Path tf = SddCore.repoRoot().resolve(testFile);
				Files.createDirectories(tf.getParent());
				Files.write(tf, testSrc.getBytes(StandardCharsets.UTF_8));
				written.add(SddCore.repoRoot().relativize(tf).toString().replace('\\', '/'));
			} catch (Exception e) {
				return SddJson.map("error", "failed to write test: " + e.getMessage());
			}
		}
		return SddJson.map(
			"task_id", taskId,
			"spec_annotation", spec,
			"ac_annotations", ac,
			"import_needed", "import speclok.AC;\nimport speclok.Spec;",
			"test_file", testFile,
			"test_source", testSrc,
			"written", written,
			"note", apply
				? "paste import_needed + spec_annotation above the class and ac_annotations above the method; the test was written"
				: "dry-run: nothing written (use apply=True)");
	}

	private static String arr(List<String> xs) {
		StringBuilder b = new StringBuilder("{");
		for (int i = 0; i < xs.size(); i++) { if (i > 0) b.append(", "); b.append('"').append(xs.get(i)).append('"'); }
		return b.append("}").toString();
	}

	private static String specBlock(String id, String objective, List<String> arquivos,
			List<String> symbols, List<String> outOfScope) {
		return "@Spec(\n"
			+ "\tid = \"" + id + "\",\n"
			+ "\tobjective = \"" + objective + "\",\n"
			+ "\tallowedFiles = " + arr(arquivos) + ",\n"
			+ "\tsymbols = " + arr(symbols) + ",\n"
			+ "\toutOfScope = " + arr(outOfScope) + "\n"
			+ ")";
	}

	private static String acBlock(List<Map<String, Object>> acs) {
		List<String> out = new ArrayList<String>();
		for (Map<String, Object> c : acs)
			out.add("\t@AC(id = \"" + SddJson.str(c.get("id")) + "\", given = \"" + SddJson.str(c.get("given")) + "\",\n"
				+ "\t\twhen = \"" + SddJson.str(c.get("when")) + "\",\n"
				+ "\t\tthen = \"" + SddJson.str(c.get("then")) + "\")");
		return String.join("\n", out);
	}

	private static String stem(String path) {
		String name = path.replace('\\', '/');
		int slash = name.lastIndexOf('/');
		if (slash >= 0) name = name.substring(slash + 1);
		int dot = name.lastIndexOf('.');
		return dot >= 0 ? name.substring(0, dot) : name;
	}

	/** Conventional test path from the target: {@code src/main/...} -> {@code src/test/...} and
	 * {@code <Class>.java} -> {@code <Class>Test.java}. Fixes the "agent invents a test name" problem -
	 * the name is derived from the target, not improvised. */
	static String deriveTestFile(String targetFile) {
		String p = targetFile.replace('\\', '/');
		String dir = p.contains("/") ? p.substring(0, p.lastIndexOf('/')) : "";
		String cls = stem(p);
		if (dir.contains("src/main/")) dir = dir.replace("src/main/", "src/test/");
		return (dir.isEmpty() ? "" : dir + "/") + cls + "Test.java";
	}

	private static String testFile(String testClass, String importFqn, List<Map<String, Object>> acs) {
		StringBuilder methods = new StringBuilder();
		for (int i = 0; i < acs.size(); i++) {
			Map<String, Object> c = acs.get(i);
			if (i > 0) methods.append("\n\n");
			methods.append("\t/** ").append(SddJson.str(c.get("id"))).append(" · ").append(SddJson.str(c.get("then"))).append(" */\n")
				.append("\tstatic void ").append(SddJson.str(c.get("id"))).append("_describe() {\n")
				.append("\t\t// TODO: exercise the target and throw AssertionError if ").append(SddJson.str(c.get("then"))).append(" fails\n")
				.append("\t\tthrow new AssertionError(\"").append(SddJson.str(c.get("id"))).append(": test not implemented\");\n")
				.append("\t}");
		}
		return "package sdd;\n\n"
			+ "import " + importFqn + ";\n\n"
			+ "/** Acceptance tests for " + testClass.replace("SpecTest", "") + " - no JUnit:\n"
			+ " * {@code java sdd." + testClass + " <AC-id>}. Exit 0 = pass, 1 = fail. */\n"
			+ "public class " + testClass + " {\n\n"
			+ methods + "\n\n"
			+ "\tpublic static void main(String[] args) throws Exception {\n"
			+ "\t\tString target = args.length > 0 ? args[0] : \"all\";\n"
			+ "\t\tint failed = 0;\n"
			+ "\t\tfor (java.lang.reflect.Method m : " + testClass + ".class.getDeclaredMethods()) {\n"
			+ "\t\t\tif (!m.getName().startsWith(\"AC\")) continue;\n"
			+ "\t\t\tif (!\"all\".equals(target) && !m.getName().equals(target) && !m.getName().startsWith(target + \"_\")) continue;\n"
			+ "\t\t\ttry { m.invoke(null); System.out.println(\"PASS \" + m.getName()); }\n"
			+ "\t\t\tcatch (Throwable t) { Throwable c = t.getCause() != null ? t.getCause() : t;\n"
			+ "\t\t\t\tSystem.out.println(\"FAIL \" + m.getName() + \" :: \" + c.getMessage()); failed++; }\n"
			+ "\t\t}\n"
			+ "\t\tSystem.exit(failed == 0 ? 0 : 1);\n"
			+ "\t}\n"
			+ "}\n";
	}

	// ---- ranged symbol index (port of symbol_locate._index_java_file) --------

	private static final Pattern TYPE = Pattern.compile("\\b(?:class|enum|interface)\\s+(\\w+)");
	private static final Pattern METHOD = Pattern.compile(
		"\\s*(?:@\\w+\\s*(?:\\([^)]*\\))?\\s*)*"
		+ "(?:(?:public|private|protected|static|final|abstract|default|synchronized|native|strictfp)\\s+)*"
		+ "(?:<[^>]+>\\s+)?[\\w\\[\\]<>,.?\\s]+?\\s+(\\w+)\\s*\\(");
	private static final Set<String> NOT_METHOD = new HashSet<String>(Arrays.asList(
		"if", "for", "while", "switch", "catch", "return", "new", "synchronized"));

	/** symbol -> {relPath, startLine, endLine} across the task's scoped files. */
	static Map<String, String[]> rangedIndex(List<String> allowed) {
		Map<String, String[]> index = new LinkedHashMap<String, String[]>();
		Path root = SddCore.repoRoot();
		for (Path p : SddCore.resolveGlobs(allowed)) {
			try {
				String raw = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
				String rel = root.relativize(p).toString().replace('\\', '/');
				indexJavaRanged(raw, rel, index);
			} catch (Exception ignore) {}
		}
		return index;
	}

	private static int matchBraceEnd(String[] lines, int start) {
		int depth = 0; boolean started = false;
		for (int j = start; j < lines.length; j++) {
			for (int k = 0; k < lines[j].length(); k++) {
				char c = lines[j].charAt(k);
				if (c == '{') { depth++; started = true; }
				else if (c == '}') depth--;
			}
			if (started && depth <= 0) return j + 1;
		}
		return start + 1;
	}

	private static void indexJavaRanged(String raw, String rel, Map<String, String[]> out) {
		String[] lines = SpecContextApp.blankJava(raw).split("\n", -1);
		int depth = 0;
		// stack entries: [name, startLine, openDepth, placeholder-index-into-out]
		java.util.Deque<Object[]> stack = new java.util.ArrayDeque<Object[]>();
		for (int i = 0; i < lines.length; i++) {
			String line = lines[i];
			int before = depth;
			Matcher tm = TYPE.matcher(line);
			if (tm.find() && before <= 1) {
				String name = tm.group(1);
				if (!out.containsKey(name)) out.put(name, new String[] {rel, String.valueOf(i + 1), null});
				stack.push(new Object[] {name, Integer.valueOf(i + 1), Integer.valueOf(before)});
			}
			if (!stack.isEmpty()) {
				Object[] top = stack.peek();
				String tname = (String) top[0];
				int tdepth = ((Integer) top[2]).intValue();
				if (before == tdepth + 1) {
					Matcher mm = METHOD.matcher(line);
					if (mm.lookingAt() && (line.indexOf('{') >= 0 || line.indexOf(';') >= 0 || line.contains("throws"))) {
						String mname = mm.group(1);
						if (!NOT_METHOD.contains(mname) && !mname.equals(tname)) {
							int end = line.indexOf('{') >= 0 ? matchBraceEnd(lines, i) : i + 1;
							String key = tname + "." + mname;
							if (!out.containsKey(key)) out.put(key, new String[] {rel, String.valueOf(i + 1), String.valueOf(end)});
						}
					}
				}
			}
			for (int k = 0; k < line.length(); k++) {
				char c = line.charAt(k);
				if (c == '{') depth++;
				else if (c == '}') depth--;
			}
			while (!stack.isEmpty() && depth <= ((Integer) stack.peek()[2]).intValue()) {
				Object[] popped = stack.pop();
				String tname = (String) popped[0];
				String[] slot = out.get(tname);
				if (slot != null && slot[2] == null) slot[2] = String.valueOf(i + 1);
			}
		}
		// close any still-open types at EOF
		for (String[] slot : out.values()) if (slot[2] == null) slot[2] = slot[1];
	}
}
