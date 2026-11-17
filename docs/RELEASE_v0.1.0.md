# speclok v0.1.0 — first release

**spec-as-annotation for Java.** Your spec lives in the code, and the compiler enforces it. Just as `javac` rejects a bad `@Override`, speclok makes it reject an incomplete `@Spec`.

This is the first public release — a **prototype**, proven end-to-end, dependency-free (JDK only).

## What it does

From a single `@Spec`/`@AC` annotation on the class and method it governs, speclok:

1. **generates** the documentation, the per-task agent briefing and the task index,
2. **enforces** the contract at compile time — an incomplete spec is a `javac` error — and blocks edits outside the declared scope and "done" without a passing test,
3. **serves** the whole thing to an AI coding agent through hooks and a pure-Java MCP server.

No separate spec files, no `.md`/`.yaml` to drift. The spec is versioned with the code, in the same commit, because it *is* the code.

## What the benchmark shows

A 210-run benchmark (7 setups, same Java brownfield tasks, Claude Sonnet 5, hidden tests) measured how often each setup edits a file **outside the declared scope**:

| setup | out-of-scope edits (95% CI) |
|---|---|
| control (bare agent) | 93.3% [78.7–98.2] |
| spec-kit | 100% [88.6–100] |
| openspec | 96.7% [83.3–99.4] |
| SDD best-practice | 80.0% [62.7–90.5] |
| **speclok** | **3.3% [0.6–16.7]** |

Correctness is flat (~80–83%) across all setups — the gates keep the agent in its lane, they don't make it smarter. Re-measured on this hardened build: **0 out-of-scope edits in 29 runs**. Full methods and caveats: [`docs/benchmark.md`](docs/benchmark.md).

## Getting started

Download `speclok.jar` below (or build it yourself with `bash build-sdd.sh`), then:

```bash
# documentation, generated from the annotations
java -jar speclok.jar specbook src/main/java -o SPECBOOK.md

# the task index and a per-task agent briefing
java -jar speclok.jar sdd list
java -jar speclok.jar sdd briefing T-200

# the gates: build + the acceptance tests
java -jar speclok.jar sdd verify T-200

# wire up hooks + MCP + config for an AI agent, in one step
java -jar speclok.jar sdd init
```

See [`example/`](example/) for a full project, including a brownfield fix (`T-312`) where the acceptance gate blocks "done" until the criterion's test passes.

## Requirements

- **Java 11 or newer** (JDK). No other dependencies.
- The compile-time check, the MCP server and the `sdd` CLI are agent-agnostic and run on any OS (Linux/macOS/Windows). Only the live hooks are Claude-Code-specific; enforcement degrades gracefully to the compiler and CI elsewhere.

## Known limitations (it's a prototype)

- No published Maven/Gradle artifact yet — grab the jar here or build it.
- Cross-file trace in the processor is not there yet.
- Governance costs tokens (~2.7× a bare agent). If your pain is cost or correctness, this isn't the tool; if it's an agent straying into the wrong file, it is.

## Artifact

- `speclok.jar` — standalone CLI + annotation processor + MCP server. Main class `speclok.Cli`, built with `--release 11`.
