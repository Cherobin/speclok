# Using speclok with an AI agent (Claude Code)

speclok works for a human on its own (the compile-time spec-lint is just `javac`). This guide is the
other half: wiring it to an AI coding agent so the agent is scoped, gated, and can create specs for
you. Examples use Claude Code; Cursor, Copilot and others have equivalent hooks/MCP.

## 1. Wire it up (one time)

Drop two files at your project root (copy them from [`../example/`](../example/)):

**`.mcp.json`** - registers the MCP server, which gives the agent five tools:

```json
{
  "mcpServers": {
    "sdd": {
      "command": "java",
      "args": ["-jar", "lib/speclok.jar", "sdd", "mcp"]
    }
  }
}
```

**`.claude/settings.json`** - the hooks (the rules the model cannot disobey):

```json
{
  "hooks": {
    "PreToolUse":  [{ "matcher": "Edit|Write|MultiEdit|NotebookEdit",
      "hooks": [{ "type": "command", "command": "java -jar \"$CLAUDE_PROJECT_DIR/lib/speclok.jar\" sdd scope" }] }],
    "PostToolUse": [{ "matcher": "Edit|Write|MultiEdit|NotebookEdit",
      "hooks": [{ "type": "command", "command": "java -jar \"$CLAUDE_PROJECT_DIR/lib/speclok.jar\" sdd compile" }] }],
    "Stop":        [{ "hooks": [{ "type": "command", "command": "java -jar \"$CLAUDE_PROJECT_DIR/lib/speclok.jar\" sdd accept" }] }]
  }
}
```

Point both at wherever `speclok.jar` lives (here `lib/speclok.jar`). Then **restart the
agent** so it picks up the MCP server. You will know it connected when the tools show up as
`mcp__sdd__sdd_list_tasks`, `mcp__sdd__sdd_briefing`, and so on.

## 2. The five tools the agent gets

| Tool | What the agent uses it for |
|------|----------------------------|
| `sdd_list_tasks` | see every task (`@Spec`) that exists |
| `sdd_briefing <id>` | get everything needed to implement one task: objective, scope, the symbol to open (`file:line`), and the criteria |
| `sdd_speclint <id>` | check a spec is complete before starting |
| `sdd_verify <id>` | run the gates (build + the `@AC` tests) |
| `sdd_scaffold_task ...` | generate a brand-new `@Spec`/`@AC` block plus its test |

## 3. Creating a spec

### Option A - by hand

Write the annotation above the class and method. That is the whole "registration" - the next
`sdd list` finds it by scanning. See any file in [`../example/`](../example/).

### Option B - ask the agent to scaffold it

You give the intent; the agent calls `sdd_scaffold_task`, which generates the `@Spec`/`@AC` block and
a dependency-free test. A prompt like:

> "Create a speclok spec `T-400` for `OrderService.total`. Objective: the order total must include tax
> and never be negative. Allowed files: the service and its test. Acceptance criteria: (1) an order of
> 100 with 10% tax returns 110; (2) an empty order returns 0, never negative. Use the
> `sdd_scaffold_task` tool, then paste the annotation onto the class and write the test."

The agent runs the tool, pastes the block, and you review. The scaffold is the one step that needs
judgment (deciding the contract); everything after it is deterministic.

## 4. The brownfield flow - what to actually type

You usually do not know which file to touch. That is fine - reads are never blocked, so the agent
scouts first. A prompt like:

> "Customers say shipping is sometimes wrong - it comes out negative on big orders. Find where
> shipping is calculated (read-only), then propose a speclok `@Spec`/`@AC` for the fix with the bug as
> a criterion (shipping is never negative). Show me the spec before changing any code."

The agent greps the repo, finds the method, and proposes the spec. You approve, then:

> "Good. Apply that spec, set it as the active task, and implement the fix. Stay inside the allowed
> files."

Now the hooks take over (next section).

## 5. Setting the active task (how the hooks know what you are working on)

The scope and acceptance hooks act on the **active task**, read from `.sdd-state/current-task`. Set it
when work starts:

```bash
echo T-400 > .sdd-state/current-task     # arm the task
echo "# idle" > .sdd-state/current-task  # back to rest (hooks go quiet)
```

(The agent can do this itself, or you can.) With a task armed:

- editing a file outside its `allowedFiles` is **blocked** before it happens;
- an edit that breaks the build comes back with the first error lines;
- saying "done" is **refused** until every `@AC` test passes (with an iteration ceiling, so an
  impossible criterion cannot loop forever).

With no active task, the hooks stay quiet - speclok is invisible until you arm a task.

## 6. A typical session, end to end

1. You describe the task in plain words.
2. The agent scouts (read-only) and proposes a `@Spec`/`@AC` - or you scaffold it.
3. You approve; the task is armed in `.sdd-state/current-task`.
4. The agent implements. The scope guard keeps it in the allowed files; the compile gate catches
   breaks; the acceptance gate will not let it finish until the criterion's test is green.
5. You commit - the spec, the fix, and the test travel together in one commit, because the spec is
   part of the code.
