#!/usr/bin/env bash
# Builds speclok.jar - the whole SDD harness (hooks, validators, gardener/observe/
# routing/telemetry, ops and the MCP server) as a standalone, dependency-free CLI.
# Only the JDK is required. Run from anywhere; outputs speclok.jar next to this script.
# Portable: works on Linux/Debian, macOS and Windows (Git Bash).
set -euo pipefail
cd "$(dirname "$0")"

OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

# Seed the compile with the CLI dispatcher + the sub-apps + the processor; javac pulls the rest
# via -sourcepath. --release 11 so the jar runs on any JDK 11+ (no newer language/API features).
javac --release 11 -d "$OUT" -sourcepath "src/core" \
  src/core/speclok/Cli.java \
  src/core/speclok/SddApp.java \
  src/core/speclok/SpecBookApp.java \
  src/core/speclok/SpecContextApp.java \
  src/core/speclok/SpecLintProcessor.java \
  src/core/speclok/Protected.java

# Register the compile-time spec-lint as a STANDARD annotation processor. With the jar on the
# annotation-processor path (and annotation processing enabled, as IDEs do), javac/the IDE run it
# automatically: an incomplete @Spec becomes a red compile error in the editor.
mkdir -p "$OUT/META-INF/services"
cat > "$OUT/META-INF/services/javax.annotation.processing.Processor" <<EOF
speclok.SpecLintProcessor
EOF

jar --create --file speclok.jar --main-class speclok.Cli -C "$OUT" .
echo "built $(pwd)/speclok.jar"
