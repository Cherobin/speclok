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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;


/**
 * The SDD harness, dissolved into speclok. One speclok app - {@code java -jar speclok.jar sdd <cmd>}
 * - that dispatches every piece that used to live as Python under {@code .sdd/}: the three hooks
 * (scope/compile/accept), the deterministic validators (trace/scopeci/docs), the full-version
 * extensions (garden/observe/route/telemetry), the harness operations (list/briefing/speclint/verify/
 * scaffold) and the MCP server (mcp). The spec stays the single source of truth - the
 * {@code @Spec}/{@code @AC} annotations in the code; everything here only reads them.
 */
public class SddApp {
	public String getAppName() {
		return "sdd";
	}

	public String getAppDescription() {
		return "SDD harness: hooks, validators, gardener/observe/routing/telemetry, ops and MCP server - all from @Spec/@AC.";
	}

	public int runApp(List<String> args) throws Exception {
		if (args.isEmpty()) { usage(); return 2; }
		String cmd = args.get(0);
		List<String> rest = args.subList(1, args.size());

		switch (cmd) {
			// ---- hooks (read the Claude Code event from stdin) ----
			case "scope":   return SddHooks.scope(SddCore.readStdin());
			case "compile": return SddHooks.compile(SddCore.readStdin());
			case "accept":  return SddHooks.accept(SddCore.readStdin());

			// ---- validators ----
			case "trace":   return need(rest, "trace <TASK_ID>") ? 2 : SddChecks.trace(rest.get(0));
			case "scopeci": return scopeci(rest);
			case "docs":    return SddChecks.docs();

			// ---- extensions ----
			case "garden":   return SddChecks.garden();
			case "observe":  return SddChecks.observe();
			case "route":    return need(rest, "route <TASK_ID>") ? 2 : SddChecks.route(rest.get(0));
			case "telemetry": return telemetry(rest);

			// ---- harness operations (print JSON) ----
			case "list":     print(SddOps.listTasks()); return 0;
			case "briefing": return need(rest, "briefing <TASK_ID>") ? 2 : printMap(SddOps.briefing(rest.get(0)));
			case "speclint": return need(rest, "speclint <TASK_ID>") ? 2 : printMap(SddOps.speclint(rest.get(0)));
			case "verify":   return need(rest, "verify <TASK_ID>") ? 2 : printMap(SddOps.verify(rest.get(0)));
			case "scaffold": return scaffold();

			// ---- setup + MCP server ----
			case "init":     return init();
			case "mcp":      return SddMcp.serve();

			case "help": case "-h": case "--help": usage(); return 0;
			default: System.err.println("sdd: unknown subcommand '" + cmd + "'"); usage(); return 2;
		}
	}

	// ---- helpers -------------------------------------------------------------

	private static boolean need(List<String> rest, String usage) {
		if (rest.isEmpty()) { System.err.println("usage: sdd " + usage); return true; }
		return false;
	}

	private int scopeci(List<String> rest) {
		String base = "origin/main";
		List<String> tasks = new ArrayList<String>();
		for (int i = 0; i < rest.size(); i++) {
			if ("--base".equals(rest.get(i)) && i + 1 < rest.size()) base = rest.get(++i);
			else tasks.add(rest.get(i));
		}
		return SddChecks.scopeCi(base, tasks);
	}

	private int telemetry(List<String> rest) {
		if (rest.isEmpty()) { System.err.println("usage: sdd telemetry <log|report> [flags]"); return 2; }
		String sub = rest.get(0);
		if ("report".equals(sub)) return SddChecks.telemetryReport();
		if ("log".equals(sub)) {
			Map<String, Object> flags = parseFlags(rest.subList(1, rest.size()));
			if (!flags.containsKey("task")) { System.err.println("usage: sdd telemetry log --task T-NNN [--model ... --tokens-in N ...]"); return 2; }
			Map<String, Object> rec = SddJson.map(
				"task", SddJson.str(flags.get("task")),
				"model", SddJson.str(flags.getOrDefault("model", "")),
				"tokens_in", Long.valueOf(SddJson.lng(flags.get("tokens-in"), 0)),
				"tokens_out", Long.valueOf(SddJson.lng(flags.get("tokens-out"), 0)),
				"cache_read", Long.valueOf(SddJson.lng(flags.get("cache-read"), 0)),
				"iters", Long.valueOf(SddJson.lng(flags.get("iters"), 0)),
				"blocks", splitCsv(SddJson.str(flags.getOrDefault("blocks", ""))));
			passedFirstTry(flags, rec);
			if (flags.containsKey("ts")) rec.put("ts", SddJson.str(flags.get("ts")));
			return SddChecks.telemetryLog(rec);
		}
		System.err.println("sdd telemetry: unknown sub '" + sub + "'");
		return 2;
	}

	private static Map<String, Object> passedFirstTry(Map<String, Object> flags, Map<String, Object> rec) {
		if (flags.containsKey("passed-first-try")) {
			String v = SddJson.str(flags.get("passed-first-try")).trim().toLowerCase();
			boolean tru = v.equals("1") || v.equals("true") || v.equals("yes") || v.equals("sim") || v.equals("y");
			rec.put("passed_first_try", Boolean.valueOf(tru));
		} else {
			rec.put("passed_first_try", null);
		}
		return rec;
	}

	private static List<String> splitCsv(String s) {
		List<String> out = new ArrayList<String>();
		for (String p : s.split(",")) if (!p.trim().isEmpty()) out.add(p.trim());
		return out;
	}

	/** Parses {@code --key value} / {@code --flag} pairs into a map. */
	private static Map<String, Object> parseFlags(List<String> args) {
		Map<String, Object> out = new java.util.LinkedHashMap<String, Object>();
		for (int i = 0; i < args.size(); i++) {
			String a = args.get(i);
			if (a.startsWith("--")) {
				String key = a.substring(2);
				if (i + 1 < args.size() && !args.get(i + 1).startsWith("--")) out.put(key, args.get(++i));
				else out.put(key, "true");
			}
		}
		return out;
	}

	/** scaffold reads a JSON object from stdin: {task_id, target_file, objective, symbols, out_of_scope, acs, test_file, import_fqn, apply}. */
	private int scaffold() {
		Map<String, Object> in = SddJson.parseObject(SddCore.readStdin());
		if (!in.containsKey("task_id")) {
			System.err.println("usage: sdd scaffold  (pass a JSON on stdin with task_id, target_file, objective, symbols, out_of_scope, acs, test_file, import_fqn, apply)");
			return 2;
		}
		List<Map<String, Object>> acs = new ArrayList<Map<String, Object>>();
		for (Object o : SddJson.arr(in.get("acs"))) acs.add(SddJson.obj(o));
		Map<String, Object> res = SddOps.scaffoldTask(
			SddJson.str(in.get("task_id")), SddJson.str(in.get("target_file")), SddJson.str(in.get("objective")),
			SddJson.strList(in.get("symbols")), SddJson.strList(in.get("out_of_scope")), acs,
			SddJson.str(in.get("test_file")), SddJson.str(in.get("import_fqn")), Boolean.TRUE.equals(in.get("apply")));
		return printMap(res);
	}

	private static int printMap(Map<String, Object> m) { System.out.println(SddJson.write(m)); return 0; }
	private static void print(Object v) { System.out.println(SddJson.write(v)); }

	/** Bootstraps the project for an AI agent: writes the hook, MCP and config files pointing at this
	 * jar, plus an idle active-task pointer. Never clobbers existing files. This is the "make the good
	 * stuff the default" command - one step instead of hand-wiring four files. */
	private static final String AGENTS_STARTER =
		"# Working agreements (speclok)\n\n"
		+ "The spec lives in the code as @Spec/@AC. The code is the source of truth, not the ticket.\n\n"
		+ "1. Read before you write, and say which @Spec or code informed the change (cite file:line).\n"
		+ "2. Spec before change: propose or scaffold a @Spec/@AC before altering behavior (sdd scaffold).\n"
		+ "3. Stay in scope: edit only the task's allowedFiles. The scope guard blocks the rest and\n"
		+ "   auto-arms the task from the file you touch.\n"
		+ "4. Checkpoint and WAIT before: a destructive or irreversible change; editing a @Protected area\n"
		+ "   (FORBIDDEN = refuse, RESTRICTED = get human review); or when the spec is ambiguous (surface\n"
		+ "   2-3 readings, do not guess).\n"
		+ "5. Tests are part of the change: name each test by its @AC id (AC1_...); a green run is not\n"
		+ "   proof - the test must exercise what the @AC describes. \"Done\" is only when sdd verify passes.\n"
		+ "6. Honest uncertainty: rate your confidence; if unsure, say so instead of asserting.\n"
		+ "7. No stowaway edits: keep the diff to the objective; no opportunistic refactors.\n\n"
		+ "Subagents (if present under .claude/agents/): spec-writer -> planner -> implementer -> reviewer.\n";

	private int init() {
		java.nio.file.Path root = SddCore.repoRoot();
		java.nio.file.Path jar = SddCore.sddJarPath();
		String jarRef = jar != null ? jar.toString().replace('\\', '/') : "lib/speclok.jar";
		List<String> wrote = new ArrayList<String>(), skipped = new ArrayList<String>();

		Map<String, Object> settings = SddJson.map("hooks", SddJson.map(
			"PreToolUse",  Arrays.asList(hookEntry("Edit|Write|MultiEdit|NotebookEdit", jarRef, "scope")),
			"PostToolUse", Arrays.asList(hookEntry("Edit|Write|MultiEdit|NotebookEdit", jarRef, "compile")),
			"Stop",        Arrays.asList(SddJson.map("hooks", Arrays.asList(cmdHook(jarRef, "accept"))))));
		writeIfAbsent(root.resolve(".claude").resolve("settings.json"), SddJson.write(settings), root, wrote, skipped);

		Map<String, Object> mcp = SddJson.map("mcpServers", SddJson.map("sdd",
			SddJson.map("command", "java", "args", Arrays.asList("-jar", jarRef, "sdd", "mcp"))));
		writeIfAbsent(root.resolve(".mcp.json"), SddJson.write(mcp), root, wrote, skipped);

		Map<String, Object> cfg = SddJson.map("annotationRoots", Arrays.asList("src/main/java"), "scopeGuardOnNoTask", "warn");
		writeIfAbsent(root.resolve("sdd.config.json"), SddJson.write(cfg), root, wrote, skipped);

		writeIfAbsent(SddCore.stateDir().resolve("current-task"), "# idle\n", root, wrote, skipped);
		writeIfAbsent(root.resolve("AGENTS.md"), AGENTS_STARTER, root, wrote, skipped);

		System.out.println("speclok init -> jar: " + jarRef);
		if (!wrote.isEmpty())   System.out.println("  wrote:   " + wrote);
		if (!skipped.isEmpty()) System.out.println("  skipped (already present): " + skipped);
		System.out.println("Next: restart your agent so the MCP server loads (headless: pass --mcp-config .mcp.json).");
		return 0;
	}

	private static Map<String, Object> hookEntry(String matcher, String jarRef, String sub) {
		return SddJson.map("matcher", matcher, "hooks", Arrays.asList(cmdHook(jarRef, sub)));
	}
	private static Map<String, Object> cmdHook(String jarRef, String sub) {
		return SddJson.map("type", "command", "command", "java -jar \"" + jarRef + "\" sdd " + sub);
	}
	private static void writeIfAbsent(java.nio.file.Path p, String content, java.nio.file.Path root,
			List<String> wrote, List<String> skipped) {
		try {
			String rel = root.relativize(p).toString().replace('\\', '/');
			if (java.nio.file.Files.exists(p)) { skipped.add(rel); return; }
			if (p.getParent() != null) java.nio.file.Files.createDirectories(p.getParent());
			java.nio.file.Files.write(p, content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			wrote.add(rel);
		} catch (Exception e) {
			SddCore.warn("init: could not write " + p + " (" + e.getMessage() + ")");
		}
	}

	private void usage() {
		System.err.println("usage: sdd <subcommand> [args]");
		System.err.println("  setup:       init                            (bootstrap hooks + MCP + config for this project)");
		System.err.println("  hooks:       scope | compile | accept        (read the Claude Code event on stdin)");
		System.err.println("  validators:  trace <TASK> | scopeci [--base B] [TASK...] | docs");
		System.err.println("  extensions:  garden | observe | route <TASK> | telemetry <log|report> [flags]");
		System.err.println("  operations:  list | briefing <TASK> | speclint <TASK> | verify <TASK> | scaffold (JSON on stdin)");
		System.err.println("  server:      mcp                             (JSON-RPC stdio for .mcp.json)");
	}

	/** Standalone for testing: {@code java speclok.SddApp <subcommand> ...}. */
	public static void main(String[] args) throws Exception {
		System.exit(new SddApp().runApp(Arrays.asList(args)));
	}
}
