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
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The scope the hooks enforce, taken from a place the agent's own edits cannot move.
 * <p>
 * The {@code @Spec} lives in a file the agent is allowed to edit, so reading {@code allowedFiles}
 * from the working tree lets the agent widen its own scope. This class resolves the <b>baseline</b>
 * scope instead: the {@code @Spec} as committed at {@code HEAD} for that source file, or - for a spec
 * that is not committed yet - a snapshot taken the first time the harness sees the task. A widened
 * {@code allowedFiles} only takes effect once a human commits it (or removes the snapshot).
 * <p>
 * It also backs the end-of-turn check: every file that changed since the turn started, by whatever
 * means (an edit tool, a shell command, a generated file), compared against that baseline scope.
 */
final class SddScope {
	private SddScope() {}

	/** Harness-owned paths: never part of a task, never editable through the agent's edit tools. */
	static final List<String> STATE = Arrays.asList(".sdd-state/**");

	// ---- baseline scope ------------------------------------------------------

	/** The scope in force for {@code task}: the committed one, else the armed snapshot, else (first
	 * sight of an uncommitted spec) the working-tree value, which is snapshotted right away. */
	static List<String> locked(SpecBookApp.Spec task) {
		if ("worktree".equals(SddCore.cfgStr("scopeBaseline"))) return task.allowedFiles;
		List<String> head = committedScope(task.source);
		if (head != null) return head;
		Path lock = lockFile(task.id);
		if (Files.exists(lock)) return readLines(lock);
		writeLines(lock, task.allowedFiles);
		return task.allowedFiles;
	}

	/** True when the working-tree {@code allowedFiles} is not the baseline (someone edited the scope). */
	static boolean scopeChanged(SpecBookApp.Spec task) {
		return !new ArrayList<String>(task.allowedFiles).equals(new ArrayList<String>(locked(task)));
	}

	/** {@code allowedFiles} of the {@code @Spec} committed at HEAD in {@code source}; {@code null} if the
	 * file is not committed, has no {@code @Spec} there, or this is not a git work tree. The baseline is
	 * per source file (one {@code @Spec} per class), so bumping the id does not reset it. */
	private static List<String> committedScope(String source) {
		if (source == null || source.isEmpty()) return null;
		// Read on every call - no cache: `sdd mcp` is a long-lived process and has to see a scope
		// change the moment a human commits it.
		try {
			SddGates.Proc r = SddGates.run(Arrays.asList("git", "show", "HEAD:./" + source));
			if (r.code != 0) return null;
			SpecBookApp.Spec s = SpecBookApp.manifestFromText(r.outText, source);
			return s == null ? null : s.allowedFiles;
		} catch (Exception e) {
			return null;
		}
	}

	/** {@code allowedFiles} of the {@code @Spec} in {@code source} as of git {@code ref}; {@code null}
	 * if there is none there. */
	static List<String> scopeAt(String ref, String source) {
		try {
			SddGates.Proc r = SddGates.run(Arrays.asList("git", "show", ref + ":./" + source));
			if (r.code != 0) return null;
			SpecBookApp.Spec s = SpecBookApp.manifestFromText(r.outText, source);
			return s == null ? null : s.allowedFiles;
		} catch (Exception e) {
			return null;
		}
	}

	/** Specs whose {@code allowedFiles} in the working tree is not the baseline, one line each
	 * ({@code T-200 (path): [old] -> [new]}). Only looks at specs whose source file is dirty, and
	 * never creates a snapshot - this is a read-only report for a human. */
	static List<String> pendingScopeChanges() {
		List<String> out = new ArrayList<String>();
		Map<String, String> dirty = dirtyFiles();
		if (dirty == null) return out;
		for (SpecBookApp.Spec s : SddCore.listSpecs()) {
			if (!dirty.containsKey(s.source)) continue;
			List<String> base = committedScope(s.source);
			if (base == null && Files.exists(lockFile(s.id))) base = readLines(lockFile(s.id));
			if (base != null && !base.equals(s.allowedFiles))
				out.add(s.id + " (" + s.source + "): " + base + " -> " + s.allowedFiles);
		}
		return out;
	}

	static Path lockFile(String taskId) { return SddCore.stateDir().resolve("scope-" + safe(taskId) + ".lock"); }
	private static Path baselineFile() { return SddCore.stateDir().resolve("baseline"); }
	private static String safe(String id) { return id.replaceAll("[^A-Za-z0-9._-]", "_"); }

	// ---- baseline: what was already dirty before the agent's turn --------------

	/** Idempotent. Pins the task's scope and, if there is no baseline yet, takes one. */
	static void arm(SpecBookApp.Spec task) {
		locked(task);
		if (!Files.exists(baselineFile())) rebaseline();
	}

	/** Records the files that are modified/untracked right now, with their content hash, so the
	 * end-of-turn check only reports what changed <i>after</i> this point (a human's work in progress
	 * is not blamed on the agent). Taken at the start of each turn ({@code sdd arm}, the
	 * UserPromptSubmit hook) and again whenever the Stop gate lets the agent stop. */
	static void rebaseline() {
		Map<String, String> dirty = dirtyFiles();
		if (dirty == null) return;                      // not a git work tree: nothing to baseline
		List<String> lines = new ArrayList<String>();
		String head = headSha();
		if (head != null) lines.add(HEAD_MARK + head);  // no tab: the "hash<TAB>path" readers skip it
		for (Map.Entry<String, String> e : dirty.entrySet()) lines.add(e.getValue() + "\t" + e.getKey());
		writeLines(baselineFile(), lines);
	}

	// The baseline also pins HEAD. `git status` only sees what is uncommitted, so an agent that
	// commits its work (an ordinary "...and commit" request) would otherwise end the turn with a clean
	// tree: nothing out of scope to report, and nothing "changed" at all.
	private static final String HEAD_MARK = "#head ";

	private static String headSha() {
		try {
			SddGates.Proc r = SddGates.run(Arrays.asList("git", "rev-parse", "--verify", "-q", "HEAD"));
			String sha = r.outText.trim();
			return r.code == 0 && !sha.isEmpty() ? sha : null;
		} catch (Exception e) {
			return null;
		}
	}

	private static String baselineHead() {
		for (String l : readLines(baselineFile())) if (l.startsWith(HEAD_MARK)) return l.substring(HEAD_MARK.length()).trim();
		return null;
	}

	/** Files changed by commits made since the baseline -> current content hash. Empty when HEAD has
	 * not moved, the baseline predates this marker, or the old HEAD is no longer reachable. */
	private static Map<String, String> committedSinceBaseline() {
		Map<String, String> out = new LinkedHashMap<String, String>();
		try {
			String base = baselineHead(), now = headSha();
			if (base == null || now == null || base.equals(now)) return out;
			SddGates.Proc r = SddGates.run(Arrays.asList(
				"git", "diff", "--name-only", "-z", "--no-renames", "--relative", base, "HEAD", "--", "."));
			if (r.code != 0) return out;
			for (String path : r.outText.split("\0")) {
				path = path.replace('\\', '/');
				if (!path.isEmpty()) out.put(path, hash(path));
			}
		} catch (Exception ignore) {}
		return out;
	}

	/** Everything that may have changed since the baseline: what was committed since then plus what
	 * is uncommitted now. {@code null} if this is not a git work tree. */
	private static Map<String, String> changedNow() {
		Map<String, String> dirty = dirtyFiles();
		if (dirty == null) return null;
		Map<String, String> all = committedSinceBaseline();
		all.putAll(dirty);
		return all;
	}

	// ---- end-of-turn check ---------------------------------------------------

	/** Files changed since {@link #arm} that are outside the baseline scope - whatever wrote them,
	 * committed or not.
	 * {@code null} when git is unavailable (the caller decides whether that blocks). */
	static List<String> outOfScopeChanges(SpecBookApp.Spec task) {
		Map<String, String> dirty = changedNow();
		if (dirty == null) return null;
		Map<String, String> before = new LinkedHashMap<String, String>();
		for (String l : readLines(baselineFile())) {
			int tab = l.indexOf('\t');
			if (tab > 0) before.put(l.substring(tab + 1), l.substring(0, tab));
		}
		List<String> allowed = locked(task);
		List<String> ignore = SddCore.cfgList("stopScopeIgnore");
		List<String> out = new ArrayList<String>();
		for (Map.Entry<String, String> e : dirty.entrySet()) {
			String rel = e.getKey();
			if (SddCore.pathAllowed(rel, allowed) || SddCore.pathAllowed(rel, STATE)
				|| SddCore.pathAllowed(rel, ignore)) continue;
			if (e.getValue().equals(before.get(rel))) continue;   // already like this before the task
			out.add(rel);
		}
		return out;
	}

	/** Files whose content differs from the per-turn baseline, in or out of scope (harness state
	 * excluded) - what this turn actually touched, by whatever means. {@code null} if there is no
	 * baseline yet or git is unavailable (the caller cannot then assume the turn was idle); an empty
	 * list means nothing changed since the turn started. */
	static List<String> changedSinceBaseline() {
		if (!Files.exists(baselineFile())) return null;
		Map<String, String> dirty = changedNow();
		if (dirty == null) return null;
		Map<String, String> before = new LinkedHashMap<String, String>();
		for (String l : readLines(baselineFile())) {
			int tab = l.indexOf('\t');
			if (tab > 0) before.put(l.substring(tab + 1), l.substring(0, tab));
		}
		java.util.LinkedHashSet<String> changed = new java.util.LinkedHashSet<String>();
		for (Map.Entry<String, String> e : dirty.entrySet()) {
			if (SddCore.pathAllowed(e.getKey(), STATE)) continue;
			if (!e.getValue().equals(before.get(e.getKey()))) changed.add(e.getKey());
		}
		for (String rel : before.keySet())                       // dirty at baseline, now gone (reverted or committed)
			if (!dirty.containsKey(rel) && !SddCore.pathAllowed(rel, STATE)) changed.add(rel);
		return new ArrayList<String>(changed);
	}

	/** Every modified, deleted or untracked file under the project root -> content hash. {@code null}
	 * if this is not a git work tree. Paths are relative to the project root, POSIX separators. */
	private static Map<String, String> dirtyFiles() {
		try {
			SddGates.Proc pre = SddGates.run(Arrays.asList("git", "rev-parse", "--show-prefix"));
			if (pre.code != 0) return null;
			String prefix = pre.outText.trim();
			SddGates.Proc r = SddGates.run(Arrays.asList(
				"git", "status", "--porcelain", "-z", "-uall", "--no-renames", "--", "."));
			if (r.code != 0) return null;
			Map<String, String> out = new LinkedHashMap<String, String>();
			for (String entry : r.outText.split("\0")) {
				if (entry.length() < 4) continue;       // "XY <path>"
				String path = entry.substring(3).replace('\\', '/');
				if (!prefix.isEmpty() && path.startsWith(prefix)) path = path.substring(prefix.length());
				out.put(path, hash(path));
			}
			return out;
		} catch (Exception e) {
			return null;
		}
	}

	/** Content hash of a changed file. Never throws: a path this JVM cannot represent (a non-ASCII
	 * name under a POSIX/C locale) is still reported as changed, just without a hash. */
	private static String hash(String rel) {
		try {
			Path p = SddCore.repoRoot().resolve(rel);
			if (!Files.isRegularFile(p)) return "absent";
			byte[] d = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p));
			StringBuilder b = new StringBuilder();
			for (byte x : d) b.append(String.format("%02x", x & 0xff));
			return b.toString();
		} catch (Exception e) {
			return "unreadable";
		}
	}

	// ---- tiny file helpers ---------------------------------------------------

	private static List<String> readLines(Path f) {
		List<String> out = new ArrayList<String>();
		try {
			if (!Files.exists(f)) return out;
			for (String l : new String(Files.readAllBytes(f), StandardCharsets.UTF_8).split("\n"))
				if (!l.trim().isEmpty()) out.add(l.replace("\r", ""));
		} catch (Exception ignore) {}
		return out;
	}

	private static void writeLines(Path f, List<String> lines) {
		try {
			StringBuilder b = new StringBuilder();
			for (String l : lines) b.append(l).append("\n");
			Files.write(f, b.toString().getBytes(StandardCharsets.UTF_8));
		} catch (Exception ignore) {}
	}
}
