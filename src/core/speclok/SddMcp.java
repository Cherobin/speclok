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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The SDD harness MCP server, now in pure Java - the port of {@code .sdd/mcp/server.py}, which
 * no longer exists. Speaks JSON-RPC 2.0 over stdio (newline-delimited messages) and exposes the five
 * tools backed by {@link SddOps}. Launched as {@code java -jar speclok.jar sdd mcp} and registered
 * in {@code .mcp.json}; the whole harness is now inside speclok, zero Python.
 */
final class SddMcp {
	private SddMcp() {}

	private static final String SERVER_VERSION = "1.0.0";
	private static final String DEFAULT_PROTOCOL = "2024-11-05";

	static int serve() {
		// stdout is the wire; route anything accidental to stderr.
		PrintStream wire = System.out;
		BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
		try {
			String line;
			while ((line = in.readLine()) != null) {
				line = line.trim();
				if (line.isEmpty()) continue;
				Map<String, Object> msg;
				try { msg = SddJson.obj(SddJson.parse(line)); }
				catch (RuntimeException e) { continue; }   // ignore unparseable frames
				handle(msg, wire);
			}
		} catch (Exception e) {
			SddCore.warn("mcp: " + e.getMessage());
			return 1;
		}
		return 0;
	}

	private static void handle(Map<String, Object> msg, PrintStream wire) {
		Object id = msg.get("id");
		String method = SddJson.str(msg.get("method"));
		boolean isRequest = id != null;

		if ("initialize".equals(method)) {
			Map<String, Object> params = SddJson.obj(msg.get("params"));
			String proto = SddJson.str(params.get("protocolVersion"));
			if (proto.isEmpty()) proto = DEFAULT_PROTOCOL;
			Map<String, Object> result = SddJson.map(
				"protocolVersion", proto,
				"capabilities", SddJson.map("tools", SddJson.map()),
				"serverInfo", SddJson.map("name", "sdd", "version", SERVER_VERSION),
				"instructions", "SDD/speclok harness. The spec lives as a @Spec/@AC annotation in the code (single source of truth). "
					+ "Call sdd_briefing before implementing; use sdd_verify when done; sdd_scaffold_task creates a new spec.");
			respond(wire, id, result);
			return;
		}
		if (method.startsWith("notifications/")) return;    // no response to notifications
		if ("ping".equals(method)) { respond(wire, id, SddJson.map()); return; }
		if ("tools/list".equals(method)) { respond(wire, id, SddJson.map("tools", toolList())); return; }
		if ("tools/call".equals(method)) { respond(wire, id, callTool(SddJson.obj(msg.get("params")))); return; }

		if (isRequest) error(wire, id, -32601, "Method not found: " + method);
	}

	// ---- tools ---------------------------------------------------------------

	private static List<Object> toolList() {
		List<Object> tools = new ArrayList<Object>();
		tools.add(SddJson.map(
			"name", "sdd_list_tasks",
			"description", "Lists all discovered SDD tasks (@Spec): id, objective, source and ACs.",
			"inputSchema", SddJson.map("type", "object", "properties", SddJson.map())));
		tools.add(SddJson.map(
			"name", "sdd_briefing",
			"description", "Agent briefing for a task: objective, scope (allowed_files), "
				+ "context-pack (each symbol resolved to file:line), out-of-scope and acceptance criteria.",
			"inputSchema", schemaTaskId()));
		tools.add(SddJson.map(
			"name", "sdd_speclint",
			"description", "Deterministic spec-lint of a task (@Spec/@AC complete?). Returns {ok, erros, avisos}.",
			"inputSchema", schemaTaskId()));
		tools.add(SddJson.map(
			"name", "sdd_verify",
			"description", "Runs the Java task gates (build + acceptance) and returns {build_ok, acceptance_ok, build_out, acceptance_out}.",
			"inputSchema", schemaTaskId()));
		Map<String, Object> scaffoldProps = SddJson.map(
			"task_id", strProp("task id, e.g. T-200"),
			"target_file", strProp("target file of the @Spec"),
			"objective", strProp("what and why"),
			"symbols", arrProp("symbols (Class.method) the task touches"),
			"out_of_scope", arrProp("what NOT to do"),
			"acs", SddJson.map("type", "array", "description", "list of {id, given, when, then}",
				"items", SddJson.map("type", "object")),
			"test_file", strProp("test file to generate (optional; derived as <Class>Test.java under src/test if omitted)"),
			"import_fqn", strProp("FQN of the target class for the test"),
			"apply", SddJson.map("type", "boolean", "description", "true writes the test; false is dry-run"));
		tools.add(SddJson.map(
			"name", "sdd_scaffold_task",
			"description", "Creates a NEW spec. Generates the @Spec + @AC block and the dependency-free test file; with apply=True, writes the test. test_file is derived from target_file if omitted.",
			"inputSchema", SddJson.map("type", "object", "properties", scaffoldProps,
				"required", java.util.Arrays.asList("task_id", "target_file", "objective", "symbols",
					"out_of_scope", "acs", "import_fqn"))));
		return tools;
	}

	private static Map<String, Object> schemaTaskId() {
		return SddJson.map("type", "object",
			"properties", SddJson.map("task_id", strProp("task id, e.g. T-105")),
			"required", java.util.Arrays.asList("task_id"));
	}

	private static Map<String, Object> strProp(String desc) { return SddJson.map("type", "string", "description", desc); }
	private static Map<String, Object> arrProp(String desc) {
		return SddJson.map("type", "array", "description", desc, "items", SddJson.map("type", "string"));
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> callTool(Map<String, Object> params) {
		String name = SddJson.str(params.get("name"));
		Map<String, Object> args = SddJson.obj(params.get("arguments"));
		Object payload;
		try {
			if ("sdd_list_tasks".equals(name)) payload = SddOps.listTasks();
			else if ("sdd_briefing".equals(name)) payload = SddOps.briefing(SddJson.str(args.get("task_id")));
			else if ("sdd_speclint".equals(name)) payload = SddOps.speclint(SddJson.str(args.get("task_id")));
			else if ("sdd_verify".equals(name)) payload = SddOps.verify(SddJson.str(args.get("task_id")));
			else if ("sdd_scaffold_task".equals(name)) {
				List<Map<String, Object>> acs = new ArrayList<Map<String, Object>>();
				for (Object o : SddJson.arr(args.get("acs"))) acs.add(SddJson.obj(o));
				boolean apply = Boolean.TRUE.equals(args.get("apply"));
				payload = SddOps.scaffoldTask(
					SddJson.str(args.get("task_id")), SddJson.str(args.get("target_file")),
					SddJson.str(args.get("objective")), SddJson.strList(args.get("symbols")),
					SddJson.strList(args.get("out_of_scope")), acs,
					SddJson.str(args.get("test_file")), SddJson.str(args.get("import_fqn")), apply);
			} else {
				return toolError("unknown tool: " + name);
			}
		} catch (Exception e) {
			return toolError(name + " failed: " + e.getMessage());
		}
		// MCP tool result: text content carrying the JSON payload.
		List<Object> content = new ArrayList<Object>();
		content.add(SddJson.map("type", "text", "text", SddJson.write(payload)));
		return SddJson.map("content", content, "isError", Boolean.FALSE);
	}

	private static Map<String, Object> toolError(String message) {
		List<Object> content = new ArrayList<Object>();
		content.add(SddJson.map("type", "text", "text", message));
		return SddJson.map("content", content, "isError", Boolean.TRUE);
	}

	// ---- wire ----------------------------------------------------------------

	private static void respond(PrintStream wire, Object id, Object result) {
		if (id == null) return;
		send(wire, SddJson.map("jsonrpc", "2.0", "id", id, "result", result));
	}

	private static void error(PrintStream wire, Object id, int code, String message) {
		send(wire, SddJson.map("jsonrpc", "2.0", "id", id, "error", SddJson.map("code", Long.valueOf(code), "message", message)));
	}

	private static void send(PrintStream wire, Map<String, Object> msg) {
		wire.print(SddJson.write(msg));
		wire.print("\n");
		wire.flush();
	}
}
