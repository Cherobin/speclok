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
import java.util.List;
import java.util.Map;

/**
 * The three Claude Code hooks, ported from {@code .sdd/hooks/*.py} into speclok. Each reads the hook
 * event JSON on stdin and signals the model through the exit code: {@code 2} blocks the edit / forces
 * the agent to keep going, {@code 0} lets it proceed / stop. These are the rules the model cannot
 * disobey - now a speclok app invoked by the harness ({@code java -jar speclok.jar sdd <hook>}).
 * Every hook fails open: a bug in the harness never wedges the agent.
 */
final class SddHooks {
	private SddHooks() {}

	/** PreToolUse: block an edit outside the active task's {@code allowedFiles} before it happens. */
	static int scope(String stdin) {
		try {
			Map<String, Object> evt = SddJson.obj(SddJson.parse(stdin.isEmpty() ? "{}" : stdin));
			Map<String, Object> ti = SddJson.obj(evt.get("tool_input"));
			String path = firstNonEmpty(SddJson.str(ti.get("file_path")),
				SddJson.str(ti.get("notebook_path")), SddJson.str(ti.get("path")));
			if (path.isEmpty()) return 0;              // tool with no path (e.g. Bash) - not ours

			String rel = SddCore.relToRepo(path);

			// Protected-area policy (independent of the task scope): FORBIDDEN blocks the edit,
			// RESTRICTED surfaces a checkpoint but lets it through.
			String ptier = protectedTierOf(rel);
			if ("FORBIDDEN".equalsIgnoreCase(ptier)) {
				System.err.println("BLOCKED: " + rel + " is a FORBIDDEN @Protected area - do not edit without human sign-off.");
				return 2;
			}
			if ("RESTRICTED".equalsIgnoreCase(ptier))
				SddCore.warn("CHECKPOINT: " + rel + " is a RESTRICTED @Protected area - state what and why, and get human review before relying on this edit.");

			String taskId = SddCore.activeTaskId();
			String policy = SddCore.cfgStr("scopeGuardOnNoTask");
			SpecBookApp.Spec task = SddCore.specForTask(taskId);
			if (task == null) {
				// No active task: auto-arm from the @Spec that governs this file, so scope enforcement
				// works without the manual current-task dance. Only when exactly one spec governs it.
				List<SpecBookApp.Spec> governing = SddCore.specsGoverning(rel);
				if (governing.size() == 1) {
					SddCore.writeActiveTask(governing.get(0).id);
					SddCore.warn("auto-armed task " + governing.get(0).id + " (it governs " + rel + ").");
					return 0;
				}
				if ("block".equals(policy)) {
					System.err.println("BLOCKED: no active task in .sdd-state/current-task - I don't know the scope of '" + rel + "'.");
					return 2;
				}
				if ("warn".equals(policy))
					SddCore.warn("no active task; edit of '" + rel + "' allowed without scope check.");
				return 0;
			}
			if (SddCore.pathAllowed(rel, task.allowedFiles)) return 0;
			System.err.println("BLOCKED: " + rel + " is outside the scope of " + task.id + ".\n"
				+ "Allowed: " + task.allowedFiles + "\n"
				+ "If the change is truly necessary, stop and ask to widen the manifest - don't edit outside.");
			return 2;
		} catch (Exception e) {
			SddCore.warn("scope_guard failed (" + e.getMessage() + "); edit allowed to be safe.");
			return 0;
		}
	}

	/** PostToolUse: compile after each edit; on failure return the first N error lines to the model.
	 * Skips edits that are not {@code .java} (a doc/config change has nothing to compile). */
	static int compile(String stdin) {
		try {
			Map<String, Object> evt = SddJson.obj(SddJson.parse(stdin.isEmpty() ? "{}" : stdin));
			Map<String, Object> ti = SddJson.obj(evt.get("tool_input"));
			String path = firstNonEmpty(SddJson.str(ti.get("file_path")),
				SddJson.str(ti.get("notebook_path")), SddJson.str(ti.get("path")));
			if (!path.isEmpty() && !path.endsWith(".java")) return 0;   // non-Java edit: nothing to compile

			String taskId = SddCore.activeTaskId();
			if (taskId == null) return 0;
			StringBuilder out = new StringBuilder();
			boolean ok = SddGates.buildInto(taskId, out);
			if (ok) return 0;
			int maxLines = (int) SddCore.cfgLong("maxErrorLines", 3);
			List<String> lines = new ArrayList<String>();
			for (String l : out.toString().split("\n")) if (!l.trim().isEmpty()) lines.add(l);
			StringBuilder head = new StringBuilder();
			for (int i = 0; i < Math.min(maxLines, lines.size()); i++) {
				if (i > 0) head.append("\n");
				head.append(lines.get(i));
			}
			System.err.println("BUILD FAILED after the edit. First errors:\n" + head);
			return 2;
		} catch (Exception e) {
			SddCore.warn("compile_check failed (" + e.getMessage() + "); continuing without blocking.");
			return 0;
		}
	}

	/** Stop: don't let the agent say "done" while any @AC test fails, with an iteration ceiling. */
	static int accept(String stdin) {
		try {
			Map<String, Object> evt = SddJson.obj(SddJson.parse(stdin.isEmpty() ? "{}" : stdin));
			if (Boolean.TRUE.equals(evt.get("stop_hook_active"))) return 0;

			String taskId = SddCore.activeTaskId();
			SpecBookApp.Spec task = SddCore.specForTask(taskId);
			if (task == null) return 0;
			List<String> ids = new ArrayList<String>();
			for (String[] ac : task.acs) if (!ac[0].isEmpty()) ids.add(ac[0]);
			if (ids.isEmpty()) return 0;

			int maxIters = (int) SddCore.cfgLong("maxIters", 5);
			int n = bumpIter(taskId);
			if (n > maxIters) {
				resetIter(taskId);
				System.err.println("GATE: ceiling of " + maxIters + " iterations reached on " + taskId
					+ " without passing all ACs. Stopping. Honestly report what's missing.");
				return 0;                               // let it stop - honest failure
			}

			if (SddGates.hasTestCommand()) {
				// build-tool mode: one run of the whole suite (surefire/junit reports which test failed)
				StringBuilder out = new StringBuilder();
				if (!SddGates.acceptInto(taskId, "all", out)) {
					System.err.println("Stop blocked - the test suite still fails (iter " + n + "/" + maxIters + "). "
						+ "Make the @AC tests pass and only then finish.\n" + tail(out.toString(), 12));
					return 2;
				}
			} else {
				List<String> failing = new ArrayList<String>();
				for (String cid : ids) {
					StringBuilder out = new StringBuilder();
					if (!SddGates.acceptInto(taskId, cid, out)) failing.add(cid);
				}
				if (!failing.isEmpty()) {
					System.err.println("Stop blocked - missing ACs: " + failing + " (iter " + n + "/" + maxIters + "). "
						+ "Go back, make those criteria pass and only then finish.");
					return 2;                           // force the agent to continue
				}
			}
			resetIter(taskId);
			return 0;
		} catch (Exception e) {
			SddCore.warn("acceptance_gate failed (" + e.getMessage() + "); letting it stop to avoid wedging.");
			return 0;
		}
	}

	// ---- iteration state -----------------------------------------------------

	private static Path iterFile(String taskId) { return SddCore.stateDir().resolve("iter-" + taskId); }

	private static int bumpIter(String taskId) {
		Path f = iterFile(taskId);
		int n = 0;
		try { if (Files.exists(f)) n = Integer.parseInt(new String(Files.readAllBytes(f), StandardCharsets.UTF_8).trim()); }
		catch (Exception ignore) {}
		n++;
		try { Files.write(f, String.valueOf(n).getBytes(StandardCharsets.UTF_8)); } catch (Exception ignore) {}
		return n;
	}

	private static void resetIter(String taskId) {
		try { Files.deleteIfExists(iterFile(taskId)); } catch (Exception ignore) {}
	}

	private static String firstNonEmpty(String... xs) {
		for (String x : xs) if (x != null && !x.isEmpty()) return x;
		return "";
	}

	private static final java.util.regex.Pattern PROTECTED_TIER =
		java.util.regex.Pattern.compile("@Protected\\s*\\([^)]*tier\\s*=\\s*\"([A-Za-z]+)\"");

	/** The {@code @Protected} tier declared in the target file ({@code "FORBIDDEN"}/{@code "RESTRICTED"}),
	 * or {@code ""} if the file has no {@code @Protected} (or does not exist yet). */
	private static String protectedTierOf(String rel) {
		try {
			java.nio.file.Path p = SddCore.repoRoot().resolve(rel);
			if (!java.nio.file.Files.exists(p)) return "";
			String text = new String(java.nio.file.Files.readAllBytes(p), java.nio.charset.StandardCharsets.UTF_8);
			if (!text.contains("@Protected")) return "";
			java.util.regex.Matcher m = PROTECTED_TIER.matcher(text);
			return m.find() ? m.group(1) : "RESTRICTED";   // @Protected with no tier -> default RESTRICTED
		} catch (Exception e) {
			return "";
		}
	}

	/** Last {@code n} non-empty lines of the build output (full logs waste the model's tokens). */
	private static String tail(String text, int n) {
		List<String> lines = new ArrayList<String>();
		for (String l : text.split("\n")) if (!l.trim().isEmpty()) lines.add(l);
		int from = Math.max(0, lines.size() - n);
		StringBuilder b = new StringBuilder();
		for (int i = from; i < lines.size(); i++) { if (i > from) b.append("\n"); b.append(lines.get(i)); }
		return b.toString();
	}
}
