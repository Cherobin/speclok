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
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The deterministic validators and the full-version extensions, ported from {@code .sdd/validate/*.py}
 * and the standalone pieces ({@code gardener.py}, {@code observe.py}, {@code routing/route.py},
 * {@code telemetry/*.py}). No LLM: every check is a pure function of (code, annotation, config), now
 * run as {@code sdd} subcommands inside speclok.
 */
final class SddChecks {
	private SddChecks() {}

	// ---- trace: every @AC has a test (name carries the AC id) ----------------

	private static final String[] TEST_DIRS = {"src/test", "tests", "test", "spec", "__tests__"};
	private static final String[] TEST_EXT = {".java", ".kt", ".py", ".js", ".ts", ".tsx", ".go", ".rb", ".cs"};

	/** The @AC ids of a task that have no matching test (by the {@code AC<id>_} name convention). */
	static List<String> uncoveredCriteria(String taskId) {
		SpecBookApp.Spec s = SddCore.specForTask(taskId);
		List<String> uncovered = new ArrayList<String>();
		if (s == null) return uncovered;
		String blob = allTestText();
		for (String[] ac : s.acs) {
			String cid = ac[0];
			if (cid.isEmpty()) continue;
			Pattern p = Pattern.compile("(?<![A-Za-z0-9])" + Pattern.quote(cid) + "_");
			if (!p.matcher(blob).find()) uncovered.add(cid);
		}
		return uncovered;
	}

	static int trace(String taskId) {
		SpecBookApp.Spec s = SddCore.specForTask(taskId);
		if (s == null) { System.err.println("trace: task " + taskId + " not found"); return 2; }
		int total = 0;
		for (String[] ac : s.acs) if (!ac[0].isEmpty()) total++;
		List<String> uncovered = uncoveredCriteria(taskId);
		if (!uncovered.isEmpty()) {
			System.out.println("✗ trace: " + uncovered.size() + "/" + total + " criterion(s) without a test in " + taskId + ": " + uncovered);
			System.out.println("  convention: name the test <AC>_description, e.g. AC1_expiresWhenEndDateYesterday");
			return 1;
		}
		System.out.println("✓ trace: " + total + " criterion(s) covered in " + taskId);
		return 0;
	}

	private static String allTestText() {
		StringBuilder b = new StringBuilder();
		Path root = SddCore.repoRoot();
		for (String d : TEST_DIRS) {
			Path base = root.resolve(d);
			if (!Files.exists(base)) continue;
			try (Stream<Path> walk = Files.walk(base)) {
				for (Path p : (Iterable<Path>) walk::iterator) {
					if (!Files.isRegularFile(p)) continue;
					String name = p.toString().toLowerCase();
					boolean ok = false;
					for (String ext : TEST_EXT) if (name.endsWith(ext)) { ok = true; break; }
					if (!ok) continue;
					try { b.append(new String(Files.readAllBytes(p), StandardCharsets.UTF_8)).append("\n"); }
					catch (Exception ignore) {}
				}
			} catch (Exception ignore) {}
		}
		return b.toString();
	}

	// ---- scope-CI: the PR diff vs allowedFiles -------------------------

	static int scopeCi(String base, List<String> taskIds) {
		if (taskIds.isEmpty()) {
			String active = SddCore.activeTaskId();
			if (active != null) taskIds = java.util.Collections.singletonList(active);
		}
		if (taskIds.isEmpty()) {
			System.err.println("no tasks to check (pass ids or set the active task)");
			return 2;
		}
		List<String> globs = new ArrayList<String>();
		for (String tid : taskIds) {
			SpecBookApp.Spec s = SddCore.specForTask(tid);
			if (s != null) globs.addAll(s.allowedFiles);
		}
		List<String> infra = Arrays.asList(".sdd/**", ".sdd-state/**", "docs/sdd/**");
		List<String> files = changedFiles(base);
		List<String> offenders = new ArrayList<String>();
		for (String f : files) {
			boolean allowed = false;
			for (String g : globs) if (SddCore.globMatch(f, g)) { allowed = true; break; }
			if (!allowed) for (String g : infra) if (SddCore.globMatch(f, g)) { allowed = true; break; }
			if (!allowed) offenders.add(f);
		}
		if (!offenders.isEmpty()) {
			System.out.println("✗ scope-CI: " + offenders.size() + " file(s) outside the scope of " + taskIds + ":");
			for (String f : offenders) System.out.println("  - " + f);
			System.out.println("  allowed: " + globs);
			return 1;
		}
		System.out.println("✓ scope-CI: " + files.size() + " changed file(s), all within the scope of " + taskIds);
		return 0;
	}

	private static List<String> changedFiles(String base) {
		List<String> out = new ArrayList<String>();
		try {
			SddGates.Proc r = SddGates.run(Arrays.asList("git", "diff", "--name-only", base + "...HEAD"));
			if (r.code != 0) r = SddGates.run(Arrays.asList("git", "diff", "--name-only", base));
			for (String l : r.outText.split("\n")) if (!l.trim().isEmpty()) out.add(l.trim().replace('\\', '/'));
		} catch (Exception e) {
			SddCore.warn("scope_check_ci: git unavailable (" + e.getMessage() + ")");
		}
		return out;
	}

	// ---- docs linter ---------------------------------------------------------

	private static final Pattern LINK = Pattern.compile("\\[[^\\]]*\\]\\(([^)]+)\\)");
	private static final Set<String> SKIP_DIRS = new LinkedHashSet<String>(Arrays.asList(
		"node_modules", "__pycache__", ".git", "assets"));

	static int docs() {
		List<String> problems = new ArrayList<String>();
		Path root = SddCore.repoRoot();
		List<String> roots = SddCore.cfgList("docsRoots");
		if (roots.isEmpty()) roots = java.util.Collections.singletonList(".");
		Set<Path> seen = new LinkedHashSet<Path>();
		for (String r : roots) {
			Path base = root.resolve(r);
			if (!Files.exists(base)) continue;
			try (Stream<Path> walk = Files.walk(base)) {
				for (Path p : (Iterable<Path>) walk::iterator) {
					if (!p.toString().endsWith(".md")) continue;
					boolean skip = false;
					for (Path part : p) if (SKIP_DIRS.contains(part.toString())) { skip = true; break; }
					if (skip || !seen.add(p)) continue;
					lintFile(p, root, problems);
				}
			} catch (Exception ignore) {}
		}
		String entry = SddCore.cfgStr("docsEntryPoint");
		int maxLines = (int) SddCore.cfgLong("docsEntryMaxLines", 120);
		if (!entry.isEmpty()) {
			Path ep = root.resolve(entry);
			if (!Files.exists(ep)) problems.add("entry_point '" + entry + "' does not exist");
			else {
				try {
					int nlines = new String(Files.readAllBytes(ep), StandardCharsets.UTF_8).split("\n", -1).length;
					if (nlines > maxLines) problems.add(entry + ": " + nlines + " lines (> " + maxLines
						+ ") - entry-point should be short and point onward (progressive disclosure)");
				} catch (Exception ignore) {}
			}
		}
		if (!problems.isEmpty()) {
			System.out.println("✗ docs-linter: " + problems.size() + " problem(s):");
			for (String p : problems) System.out.println("  - " + p);
			return 1;
		}
		System.out.println("✓ docs-linter: links, anchors and structure ok");
		return 0;
	}

	private static void lintFile(Path md, Path root, List<String> problems) {
		String rel = root.relativize(md).toString().replace('\\', '/');
		String text;
		try { text = new String(Files.readAllBytes(md), StandardCharsets.UTF_8); }
		catch (Exception e) { return; }
		Matcher m = LINK.matcher(text);
		while (m.find()) {
			String target = m.group(1).trim().split("\\s+")[0];
			if (target.startsWith("http://") || target.startsWith("https://")
				|| target.startsWith("mailto:") || target.startsWith("#")) continue;
			int hash = target.indexOf('#');
			String pathPart = hash >= 0 ? target.substring(0, hash) : target;
			String anchor = hash >= 0 ? target.substring(hash + 1) : "";
			if (pathPart.isEmpty()) continue;
			Path tgt = md.getParent().resolve(pathPart).normalize();
			if (!Files.exists(tgt)) { problems.add(rel + ": broken link -> " + target); continue; }
			if (!anchor.isEmpty() && tgt.toString().toLowerCase().endsWith(".md")) {
				try {
					Set<String> slugs = slugs(new String(Files.readAllBytes(tgt), StandardCharsets.UTF_8));
					if (!slugs.contains(anchor.toLowerCase())) problems.add(rel + ": nonexistent anchor -> " + target);
				} catch (Exception ignore) {}
			}
		}
	}

	private static Set<String> slugs(String md) {
		Set<String> out = new LinkedHashSet<String>();
		for (String line : md.split("\n")) {
			String t = line.replaceFirst("^\\s*", "");
			if (t.startsWith("#")) {
				String h = t.replaceFirst("^#+\\s*", "").trim().toLowerCase();
				h = Normalizer.normalize(h, Normalizer.Form.NFKD).replaceAll("[^\\p{ASCII}]", "");
				h = h.replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
				out.add(h);
			}
		}
		return out;
	}

	// ---- gardener: layer direction + file size -------------------------------

	private static final Map<String, Integer> LAYER = new LinkedHashMap<String, Integer>();
	static { LAYER.put("models", 0); LAYER.put("repository", 1); }

	private static Integer layerOf(String stem) {
		if (LAYER.containsKey(stem)) return LAYER.get(stem);
		if (stem.endsWith("_job") || stem.equals("job")) return 2;
		return null;
	}

	static int garden() {
		int maxLines = (int) SddCore.cfgLong("gardenerMaxFileLines", 80);
		List<String[]> findings = gardenScan(maxLines);  // [rule, file, detail, fix]
		if (findings.isEmpty()) { System.out.println("gardener: 0 violations - garden in order."); return 0; }
		System.out.println("gardener: " + findings.size() + " invariant violation(s):");
		for (String[] f : findings) {
			System.out.println("  ✗ [" + f[0] + "] " + f[1]);
			System.out.println("      " + f[2]);
			System.out.println("      proposed PR: " + f[3]);
		}
		return 1;
	}

	private static List<String[]> gardenScan(int maxLines) {
		List<String[]> findings = new ArrayList<String[]>();
		Path src = SddCore.repoRoot().resolve("src");
		if (!Files.exists(src)) return findings;
		try (Stream<Path> walk = Files.walk(src)) {
			for (Path p : (Iterable<Path>) walk::iterator) {
				if (!p.toString().endsWith(".py")) continue;
				String rel = SddCore.repoRoot().relativize(p).toString().replace('\\', '/');
				String text;
				try { text = new String(Files.readAllBytes(p), StandardCharsets.UTF_8); }
				catch (Exception e) { continue; }
				int nlines = text.split("\n", -1).length;
				if (nlines > maxLines)
					findings.add(new String[] {"size", rel, nlines + " lines (> " + maxLines + ")",
						"break " + rel + " into smaller units (one responsibility per module)."});
				String stem = stem(p);
				Integer myLayer = layerOf(stem);
				if (myLayer == null) continue;
				for (String mod : pyImports(text)) {
					Integer tl = layerOf(mod);
					if (tl != null && tl > myLayer)
						findings.add(new String[] {"layers", rel,
							stem + " (layer " + myLayer + ") imports " + mod + " (layer " + tl + ") - inverted direction",
							"remove the import of '" + mod + "' in " + rel + "; the dependency must point from the outer layer to the inner one."});
				}
			}
		} catch (Exception ignore) {}
		return findings;
	}

	private static final Pattern PY_FROM = Pattern.compile("(?m)^\\s*from\\s+([\\w.]+)\\s+import\\b");
	private static final Pattern PY_IMPORT = Pattern.compile("(?m)^\\s*import\\s+([\\w.]+)");

	private static List<String> pyImports(String text) {
		List<String> out = new ArrayList<String>();
		Matcher a = PY_FROM.matcher(text);
		while (a.find()) { String[] parts = a.group(1).split("\\."); out.add(parts[parts.length - 1]); }
		Matcher b = PY_IMPORT.matcher(text);
		while (b.find()) { String[] parts = b.group(1).split("\\."); out.add(parts[parts.length - 1]); }
		return out;
	}

	private static String stem(Path p) {
		String name = p.getFileName().toString();
		int dot = name.lastIndexOf('.');
		return dot >= 0 ? name.substring(0, dot) : name;
	}

	// ---- observe: run instrumented target, compare behavior ------------------

	static int observe() {
		String cmd = SddCore.cfgStr("observeCommand");
		if (cmd.trim().isEmpty()) { SddCore.warn("observe.command is empty - observability inactive (no-op)."); return 0; }
		Map<String, Object> expect = SddJson.obj(SddCore.config().get("observeExpect"));
		SddGates.Proc proc;
		try { proc = SddGates.run(shellCmd(cmd)); }
		catch (Exception e) { SddCore.warn("could not run observe.command (" + e.getMessage() + ")."); return 0; }

		if (proc.code != 0) {
			String head = (proc.err + proc.outText).trim();
			System.err.println("OBSERVE: the instrumented target failed:\n" + head.substring(0, Math.min(400, head.length())));
			return 2;
		}
		Map<String, Object> metrics = lastJson(proc.outText);
		if (metrics == null) {
			System.err.println("OBSERVE: no JSON metric in the target's output (emit {\"domain.metric\": value}).");
			return 2;
		}
		System.out.println("observe · metrics of the running system:");
		for (Map.Entry<String, Object> e : metrics.entrySet()) System.out.println("  " + e.getKey() + " = " + SddJson.str(e.getValue()));
		List<String> diffs = new ArrayList<String>();
		for (Map.Entry<String, Object> e : expect.entrySet()) {
			Object got = metrics.get(e.getKey());
			if (!String.valueOf(got).equals(String.valueOf(e.getValue())))
				diffs.add(e.getKey() + ": expected " + SddJson.str(e.getValue()) + ", observed " + SddJson.str(got));
		}
		if (!diffs.isEmpty()) {
			System.err.println("OBSERVE: behavior outside expectations:");
			for (String d : diffs) System.err.println("  ✗ " + d);
			return 1;
		}
		if (!expect.isEmpty()) System.out.println("  ✓ " + expect.size() + " behavior expectation(s) match.");
		return 0;
	}

	private static Map<String, Object> lastJson(String text) {
		Map<String, Object> obj = null;
		for (String line : text.split("\n")) {
			String t = line.trim();
			if (t.startsWith("{") && t.endsWith("}")) {
				try { obj = SddJson.obj(SddJson.parse(t)); } catch (RuntimeException ignore) {}
			}
		}
		return obj;
	}

	// ---- routing: manifest signals -> tier -----------------------------------

	static int route(String taskId) {
		SpecBookApp.Spec s = SddCore.specForTask(taskId);
		if (s == null) { System.err.println("route: task " + taskId + " not found"); return 2; }
		int pastFailures = pastFailures(taskId);
		String[] tierWhy = computeTier(s, pastFailures);
		System.out.println(tierWhy[0]);
		System.err.println("[sdd] " + taskId + " -> " + tierWhy[0] + " (" + tierWhy[1] + ")");
		return 0;
	}

	static String[] computeTier(SpecBookApp.Spec s, int pastFailures) {
		int smallMax = (int) SddCore.cfgLong("routingSmallMaxFiles", 2);
		int escalateAfter = (int) SddCore.cfgLong("routingEscalateAfter", 2);
		List<String> strongKw = new ArrayList<String>();
		for (String k : SddCore.cfgList("routingStrongKeywords")) strongKw.add(k.toLowerCase());

		StringBuilder blob = new StringBuilder();
		blob.append(s.objective).append(' ').append(s.allowedFiles).append(' ').append(s.symbols).append(' ').append(s.outOfScope);
		for (String[] ac : s.acs) blob.append(' ').append(ac[1]).append(' ').append(ac[2]).append(' ').append(ac[3]);
		String text = blob.toString().toLowerCase();

		if (pastFailures >= escalateAfter) return new String[] {"strong", "escalated: " + pastFailures + " prior failure(s)"};
		for (String k : strongKw) if (text.contains(k)) return new String[] {"strong", "risk keyword (security/concurrency/legacy)"};
		if (s.symbols.isEmpty()) return new String[] {"strong", "no existing symbol - likely creation/design"};
		if (s.allowedFiles.size() <= smallMax && !s.symbols.isEmpty())
			return new String[] {"small", "few files, symbols already exist, clear criteria"};
		return new String[] {"medium", "medium-sized change"};
	}

	private static int pastFailures(String taskId) {
		Path p = SddCore.repoRoot().resolve(SddCore.cfgStr("telemetryPath"));
		if (!Files.exists(p)) return 0;
		int n = 0;
		try {
			for (String line : new String(Files.readAllBytes(p), StandardCharsets.UTF_8).split("\n")) {
				if (line.trim().isEmpty()) continue;
				Map<String, Object> rec = SddJson.obj(SddJson.parse(line));
				if (taskId.equals(SddJson.str(rec.get("task"))) && Boolean.FALSE.equals(rec.get("passed_first_try"))) n++;
			}
		} catch (Exception ignore) {}
		return n;
	}

	// ---- telemetry: append-only log + report ---------------------------------

	static int telemetryLog(Map<String, Object> rec) {
		Path p = SddCore.repoRoot().resolve(SddCore.cfgStr("telemetryPath"));
		try {
			Files.createDirectories(p.getParent());
			String line = SddJson.write(rec) + "\n";
			Files.write(p, line.getBytes(StandardCharsets.UTF_8),
				java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
			System.out.println("✓ telemetry: " + SddJson.str(rec.get("task")) + " logged at " + p);
			return 0;
		} catch (Exception e) {
			System.err.println("telemetry failed: " + e.getMessage());
			return 1;
		}
	}

	static int telemetryReport() {
		Path p = SddCore.repoRoot().resolve(SddCore.cfgStr("telemetryPath"));
		if (!Files.exists(p)) { System.out.println("no telemetry yet - run some tasks and log them."); return 0; }
		List<Map<String, Object>> recs = new ArrayList<Map<String, Object>>();
		try {
			for (String line : new String(Files.readAllBytes(p), StandardCharsets.UTF_8).split("\n")) {
				if (line.trim().isEmpty()) continue;
				try { recs.add(SddJson.obj(SddJson.parse(line))); } catch (RuntimeException ignore) {}
			}
		} catch (Exception ignore) {}
		if (recs.isEmpty()) { System.out.println("telemetry empty."); return 0; }

		int n = recs.size(), first = 0;
		long tin = 0, tout = 0, tcache = 0;
		Map<String, long[]> byModel = new LinkedHashMap<String, long[]>(); // [n, in, out, first]
		Map<String, Integer> blocks = new LinkedHashMap<String, Integer>();
		for (Map<String, Object> r : recs) {
			tin += SddJson.lng(r.get("tokens_in"), 0);
			tout += SddJson.lng(r.get("tokens_out"), 0);
			tcache += SddJson.lng(r.get("cache_read"), 0);
			boolean ft = Boolean.TRUE.equals(r.get("passed_first_try"));
			if (ft) first++;
			String m = SddJson.str(r.get("model")); if (m.isEmpty()) m = "?";
			long[] mv = byModel.computeIfAbsent(m, k -> new long[4]);
			mv[0]++; mv[1] += SddJson.lng(r.get("tokens_in"), 0); mv[2] += SddJson.lng(r.get("tokens_out"), 0);
			if (ft) mv[3]++;
			for (String b : SddJson.strList(r.get("blocks"))) blocks.merge(b, 1, Integer::sum);
		}
		long cacheRate = tin > 0 ? (tcache * 100 / tin) : 0;
		System.out.println("== SDD Telemetry · " + n + " task(s) ==");
		System.out.println("tokens  in=" + tin + "  out=" + tout + "  cache_read=" + tcache + "  (cache hit ~" + cacheRate + "%)");
		System.out.println("passed first try: " + first + "/" + n + " (" + (n > 0 ? first * 100 / n : 0) + "%)");
		System.out.println("\nby model:");
		for (Map.Entry<String, long[]> e : byModel.entrySet()) {
			long[] v = e.getValue();
			System.out.printf("  %-8s tasks=%3d  in=%8d  out=%7d  1st=%d/%d%n", e.getKey(), v[0], v[1], v[2], v[3], v[0]);
		}
		System.out.println("\nhooks that blocked the most:");
		blocks.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue())
			.forEach(e -> System.out.printf("  %-12s %d%n", e.getKey(), e.getValue()));
		return 0;
	}

	// ---- misc ----------------------------------------------------------------

	private static List<String> shellCmd(String cmd) {
		boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
		return win ? Arrays.asList("cmd", "/c", cmd) : Arrays.asList("sh", "-c", cmd);
	}
}
