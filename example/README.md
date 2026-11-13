# example - a Java project with the SDD harness via speclok

A fresh Java project that **just adds speclok** (`lib/speclok.jar`) and gets the SDD harness for
free: the spec becomes a `@Spec`/`@AC` annotation on the code, and speclok **generates** the docs,
**enforces** the rules, and **serves** the tools to an AI agent.

## The spec lives in the code

`src/main/java/loja/Frete.java` carries the spec on the class and the method - there is no spec
`.md` or `.yaml`. The annotation is the single source of truth; everything else is derived.

## The cycle (all via `java -jar lib/speclok.jar ...`)

| I want | Command |
|--------|---------|
| The documentation (Spec Book) | `specbook src/main/java -o SPECBOOK.md` |
| The task index (tracking) | `sdd list` |
| The agent briefing | `sdd briefing T-200` (or the MCP tool `sdd_briefing`) |
| Validate the spec | `sdd speclint T-200` |
| Run the gates (build + AC tests via **Maven**) | `sdd verify T-200` |
| Traceability (does every AC have a test?) | `sdd trace T-200` |

> There is also a **brownfield fix** in `loja/legado/` - task `T-312` fixes a negative-shipping bug
> in previously unannotated legacy code; the acceptance gate blocks "done" until the criterion passes.

## What the harness enforces when an agent works here

Wired by `.claude/settings.json` (hooks) and `.mcp.json` (tools):

- **scope**: the agent only edits `allowedFiles`; everything else is blocked before it happens;
- **build**: an edit that does not compile fails immediately;
- **acceptance**: "done" is accepted only when every `@AC`'s test passes.

All of this is the `sdd` app inside `lib/speclok.jar`. Zero Python.

## In IntelliJ (the nicest way)

1. **File → Open** → point at `pom.xml`. IntelliJ imports the project with its bundled Maven (no
   `mvn` on the PATH needed for the IDE to build).
2. **Settings → Build → Compiler → Annotation Processors → Enable annotation processing** (the same
   requirement for any annotation processor). `lib/speclok.jar` already ships the registered `SpecLintProcessor`.
3. That's it: write an incomplete `@Spec` (empty `objective`, an `@AC` missing `then`) and IntelliJ
   **underlines it in red** immediately - `spec-lint [T-xxx]: objective is empty` - like any compile
   error. That gate needs no agent at all.

The hooks and MCP tools (scope/build/acceptance + `sdd_briefing` etc.) come in via the Claude Code
plugin for JetBrains - `.claude/settings.json` and `.mcp.json` already point at the jar. The
acceptance gate runs `mvn test`; in IntelliJ, use `mvn` on the PATH or switch the `testCommand` in
`sdd.config.json` to `./mvnw` (the Maven Wrapper).

## Maven / IntelliJ, and the acceptance gate

`pom.xml` imports cleanly into IntelliJ and puts `lib/speclok.jar` on the classpath so the
`SpecLintProcessor` runs (the red-squiggle story above).

The acceptance gate is **build-tool-agnostic**. By default it uses a self-contained `javac` gate and
the bundled **dependency-free** tests, so `sdd verify` works on any machine with just a JDK - no
Maven, no JUnit. To use a real build tool instead, set `buildCommand`/`testCommand` in
`sdd.config.json`:

```json
"buildCommand": "mvn -q -o -DskipTests compile",
"testCommand":  "mvn -q -o -DfailIfNoTests=false test"
```

Then `sdd verify T-200` runs `mvn test` underneath; the Stop hook uses the same command. Swap in
`gradle test` and it works the same - the harness just ties the build tool's exit code to the verdict.
