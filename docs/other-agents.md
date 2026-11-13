# Using speclok without Claude Code (other agents, any OS)

speclok is mostly agent- and OS-agnostic. Of its four parts, only the live hooks are tied to Claude
Code; everything else works anywhere, with any agent, on any operating system.

## What works everywhere, with no agent at all

The compile-time spec-lint is just a standard annotation processor. Any build that runs annotation
processing enforces it:

- `mvn compile`, `gradle build`, or plain `javac` fail when a `@Spec` is incomplete.
- Any IDE (IntelliJ, VS Code + the Java extension, Eclipse) shows it inline once annotation
  processing is enabled.
- Any OS: Linux/Debian, macOS, Windows. The jar is pure JDK.

This is the baseline: your spec is enforced by the compiler whether or not you use an AI agent.

## With any MCP-capable agent

`sdd mcp` is a standard MCP server (JSON-RPC 2.0 over stdio). Register it in your agent's MCP config;
the entry is the same everywhere:

```json
{ "command": "java", "args": ["-jar", "lib/speclok.jar", "sdd", "mcp"] }
```

Where that entry goes depends on the agent:

- **Claude Code** - `.mcp.json` (see [`using-with-an-agent.md`](using-with-an-agent.md)).
- **Cursor** - `.cursor/mcp.json`.
- **OpenCode, Cline, Continue, Zed**, and other MCP clients - their "MCP servers" config section; the
  `command`/`args` above are identical. Check your agent's MCP docs for the exact file.

Once registered, the agent gets the five tools: `sdd_list_tasks`, `sdd_briefing`, `sdd_speclint`,
`sdd_verify`, `sdd_scaffold_task`.

## With any agent that can run a shell command (no MCP needed)

The tools are also plain CLI subcommands. Any agent, or you, can run:

```bash
java -jar lib/speclok.jar sdd briefing T-200   # what to implement
java -jar lib/speclok.jar sdd verify  T-200    # run the gates
java -jar lib/speclok.jar sdd list             # every task
```

For an agent without MCP (for example Aider), point its test command at `sdd verify`, or just tell it
in your prompt/rules to run these before finishing.

## The one Claude-Code-specific part: the live hooks

The scope/compile/accept hooks use Claude Code's event mechanism (`PreToolUse` / `PostToolUse` /
`Stop` in `.claude/settings.json`) to block an edit or a premature "done" in real time. For other
agents:

- If your agent has its own hook / pre-edit / pre-commit mechanism, wire the same commands:
  `sdd scope`, `sdd compile`, `sdd accept`. They read the active task from `.sdd-state/current-task`
  and signal with exit code 2.
- If it does not, enforcement degrades gracefully to the agent-independent layers:
  1. **compile time** - an incomplete or violated spec fails the build;
  2. **`sdd verify`** - run as the agent's test step, it fails until the criteria pass;
  3. **`sdd scopeci --base origin/main`** - a CI check that fails a PR touching files outside
     `allowedFiles`, even if a human or a non-hooked agent made the edit.

So "the rules the model cannot disobey" still holds without Claude Code: the compiler and CI are the
backstop; the live in-session blocking is the Claude Code bonus.

## On Linux / Debian

Install a JDK (11 or newer) and you are set. The jar targets Java 11 bytecode, so it runs on any
JDK 11+; use a JDK (not just a JRE), because the internal acceptance gate compiles with `javac`.

```bash
sudo apt update && sudo apt install -y openjdk-17-jdk   # or any JDK 11+
java -jar lib/speclok.jar sdd list
```

Everything is `java -jar` plus shell, so the paths, the internal gate, and the build-tool gate work
the same as on macOS and Windows.
