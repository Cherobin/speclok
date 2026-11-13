# Tuning speclok for AI agents

This page is grounded in a 180-run benchmark (6 conditions x 6 tasks x 5 reps, Claude Sonnet 5
headless, a Java brownfield fixture). The finding was blunt: **speclok with hooks alone was the worst
of the SDD tools** - most tokens, most turns, and it even induced its own scope violations. The
variants that beat it turned on MCP, scaffold, a pre-shipped config and a scope briefing.

So the rule is: **make MCP + scaffold + config + briefing the default, not optional add-ons.** Each
item below maps to that evidence.

## Now built into the jar (you get these for free)

Several of the recommendations below are no longer "do this" - they are the tool's default behavior:

- **`sdd init`** writes the hooks, the MCP server and a `sdd.config.json` in one command, so nothing
  is hand-wired and the agent never has to create a config.
- **The compile gate runs spec-lint in warn mode**, so an in-progress `@Spec` no longer breaks the
  build (kills the write-reject-rewrite loop).
- **The compile hook skips non-`.java` edits** - no wasted build on a doc or config change.
- **The scope guard auto-arms the active task** from the file being edited: touch a file that a
  `@Spec` governs and that task becomes active, so scope is enforced without a manual pointer.
- **`sdd scaffold` derives the test file name** (`<Class>Test.java` under `src/test`) when you omit
  it, so the agent stops inventing test names.
- **`sdd verify` reports `unverified_criteria`** - the `@AC`s that still have no test.

The rest is guidance for the prompt/workflow side, which lives outside the jar.

## 1. Load the MCP in headless / CI

`claude -p` (and most non-interactive runners) **ignore a project `.mcp.json` unless you pass it
explicitly.** In the benchmark, without the flag the `sdd` server never loaded and the agent reported
"no MCP tools". Pass it:

```bash
claude -p --mcp-config .mcp.json "implement T-200"
```

Or skip MCP entirely and call the **CLI directly** - the operations work without the server:

```bash
java -jar lib/speclok.jar sdd briefing T-200
java -jar lib/speclok.jar sdd scaffold   < new-spec.json
java -jar lib/speclok.jar sdd verify  T-200
```

## 2. Scaffold first (kill the gate-fight loop)

The single most expensive pattern was: the agent writes a `@Spec`, the compile-time check rejects it
as incomplete, it rewrites, recompiles, repeats - each cycle a turn, each turn re-reading the cached
context. One run hit 86 turns this way.

Always create a new spec with the scaffold, which emits a **valid** `@Spec`/`@AC` skeleton on the
first try:

```bash
java -jar lib/speclok.jar sdd scaffold < new-spec.json   # or the sdd_scaffold_task MCP tool
```

## 3. Use `severity=warn` for agent and CI runs

By default an incomplete `@Spec` is a compile **error** (great for a human in the IDE - immediate red
feedback). For a fast write-compile-rewrite agent loop, that same error is what fuels the gate fight.
Switch it to a warning so the spec-lint **guides** instead of breaking the build:

```
javac -Aspeclok.severity=warn ...
# Maven:    <compilerArgs><arg>-Aspeclok.severity=warn</arg></compilerArgs>
# Gradle:   options.compilerArgs << "-Aspeclok.severity=warn"
# IntelliJ: Settings > Build > Compiler > Annotation Processors > processor options
```
(Reading it from a project config file is a planned follow-up.)

Acceptance still gates on passing tests (`sdd verify` / the Stop hook), and you can keep `error` in CI
for strict checks. This is the robustness fix for the one transient build break seen in the benchmark.

## 4. Brief the agent on scope BEFORE it edits

speclok's scope guard only reacts **after** an edit attempt. The governance variant declared the
allowed and protected areas **up front**, so the agent never tried to edit outside - and its scope
violations were ~4x lower on the scope-heavy task.

- Run `sdd briefing <id>` (or the `sdd_briefing` tool) at the start and keep it in context. It lists
  `allowed_files` and the exact symbols, so the agent opens the right spot and stays in bounds.
- **Use the test file name from `allowedFiles`** - do not invent one. The benchmark agents kept
  guessing `OrderTotalSpecTest.java` / `OrderTotalNegativeTaxTest.java` when the scoped file was
  `OrderTotalTest.java`. The name is already in the spec; surface it, don't improvise.

## 5. Pre-ship a config, or stay truly configless

speclok runs configless (sensible baked defaults). The trap: with no config present, an agent may try
to "configure the tool" and create a `sdd.config.json` - which counts as an edit outside the task's
scope. In the benchmark this alone drove scope violations on one task to 8.4 (vs 0.6 for the variant
that shipped a config).

Either keep it configless and tell the agent not to create one, or drop a minimal config in the repo
up front:

```json
{
  "annotationRoots": ["src/main/java"],
  "scopeGuardOnNoTask": "warn"
}
```

## 6. Record the decision even when nothing changes

On the ambiguity task, the agents did the right thing - they did **not** invent behavior - but they
also left no `@Spec`, so there was no trace of the decision. Treat "we deliberately did not change X"
as a result worth recording: add a `@Spec`/`@AC` that states the ambiguity and the call made. "No
change" becomes a tracked decision instead of a gap.

---

## Validating a change

The benchmark harness can re-run this. Add a `speclok-v2` condition with these defaults (config
pre-shipped, MCP via `--mcp-config`, a scope briefing in the preamble, `severity=warn`) and run ~30
rounds. Target: match the governance variant's scope-violation rate while keeping tokens near the
MCP variant.
