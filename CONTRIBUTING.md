# Contributing

Thanks for taking a look. This repo is the focused contribution: the `speclok` package plus an
example project. It is meant to be read, tried, and adopted in your own Java projects.

## Layout

```
src/core/speclok/         the 14 harness classes, in the `speclok` package
dist/speclok.jar       prebuilt harness (JDK-only) so the example runs with no build
build-sdd.sh              builds the jar - JDK only, no external dependencies
example/                  a full project: @Spec/@AC, tests, config, and a brownfield fix (T-312)
docs/                     how-it-works and the agent guides
```

## The source files

All 14 files are plain Java, JDK-only (no external dependencies). They fall in four groups:

- **Annotations** - `Spec`, `AC`, `ACs` (`@Retention(SOURCE)`, like the standard `@Override`).
- **Generators** (CLI apps) - `SpecBookApp` (spec book), `SpecContextApp` (agent briefing).
- **Enforcement** - `SpecLintProcessor` (standard annotation processor: spec-lint as a compile error),
  and the hooks inside `SddApp`/`SddHooks`.
- **The `sdd` app** - `SddApp` (dispatch), `SddCore`, `SddJson`, `SddOps`, `SddGates`, `SddHooks`,
  `SddChecks`, `SddMcp` (the MCP server).

## Building from source

The build is JDK-only - there are no external dependencies. Run `build-sdd.sh` from the repo root to
compile `src/core/speclok/*.java` and package `dist/speclok.jar`.

## Style

Match the project's conventions: 4-space tabs, the standard MIT header on every file, English Javadoc.
Keep the harness dependency-free - JDK only.
