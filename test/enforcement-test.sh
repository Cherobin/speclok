#!/usr/bin/env bash
# Adversarial tests for the enforcement gates: each case is something an agent could do to slip
# past the harness. Dependency-free (JDK + git + bash); works on Linux, macOS and Git Bash.
#   bash test/enforcement-test.sh            # builds the jar, runs every case
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
unset JAVA_TOOL_OPTIONS SDD_FAIL_CLOSED CLAUDE_PROJECT_DIR

bash "$ROOT/build-sdd.sh" >/dev/null || { echo "build failed"; exit 1; }
# On Windows Git Bash, java is the native Windows JVM and needs Windows-style paths for -Dsdd.root,
# the jar and the hook file_path; cygpath -m gives the C:/... form. Elsewhere there is no cygpath,
# so the path passes through unchanged.
winpath() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }
JAR="$(winpath "$ROOT/speclok.jar")"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
PASS=0; FAIL=0; P=""; OUT=""; CODE=0

FRETE=src/main/java/loja/Frete.java
FRETE_TEST=src/test/java/loja/FreteSpecTest.java
CUPOM=src/main/java/loja/Cupom.java

# A fresh, committed copy of the example project. $1 = name, $2 = optional sub-directory (the
# project then lives below the git top-level, as in a monorepo).
project() {
	local top="$WORK/$1"; P="$top${2:+/$2}"
	mkdir -p "$P"; cp -r "$ROOT/example/." "$P/"; rm -rf "$P/.sdd-state"
	printf '.sdd-state/\n' > "$top/.gitignore"
	git -C "$top" init -q
	git -C "$top" -c user.name=t -c user.email=t@t add -A
	git -C "$top" -c user.name=t -c user.email=t@t commit -q -m base
}
commit() { git -C "$P" -c user.name=t -c user.email=t@t commit -q -am "$1"; }
sdd()  { OUT="$(cd "$P" && java -Dsdd.root="$(winpath "$P")" -jar "$JAR" sdd "$@" 2>&1)"; CODE=$?; }
hook() { OUT="$(cd "$P" && printf '%s' "$2" | java -Dsdd.root="$(winpath "$P")" -jar "$JAR" sdd "$1" 2>&1)"; CODE=$?; }
edit() { hook scope "{\"tool_name\":\"Edit\",\"tool_input\":{\"file_path\":\"$(winpath "$P")/$1\"}}"; }
stop() { hook accept '{}'; }
task() { mkdir -p "$P/.sdd-state"; echo "$1" > "$P/.sdd-state/current-task"; }
replace() { # replace <file> <from> <to>   (literal, portable - no sed -i)
	F="$P/$1" FROM="$2" TO="$3" perl -0pi -e 's/\Q$ENV{FROM}\E/$ENV{TO}/' "$P/$1"
}

ok()   { PASS=$((PASS+1)); echo "  ok   $1"; }
bad()  { FAIL=$((FAIL+1)); echo "  FAIL $1"; echo "       exit=$CODE out=$OUT" | head -5; }
expect() { # expect <exit> <label> [substring]
	if [ "$CODE" = "$1" ] && { [ $# -lt 3 ] || printf '%s' "$OUT" | grep -qF -- "$3"; }; then ok "$2"; else bad "$2"; fi
}

echo "scope guard"
project scope
edit $FRETE;  expect 0 "in-scope edit is allowed (and arms the task)"
edit $CUPOM;  expect 2 "out-of-scope edit is blocked" "outside the scope of T-200"
edit .sdd-state/current-task; expect 2 "the active-task pointer cannot be switched mid-task" "outside the scope"
edit .sdd-state/scope-T-200.lock; expect 2 "harness state is not editable" "harness state"

echo "scope guard: the agent cannot widen its own scope"
replace $FRETE 'allowedFiles = {' 'allowedFiles = {"**", '
edit $CUPOM;  expect 2 "widened allowedFiles in the working tree is not in force" "not in force"
edit $FRETE;  expect 0 "the original scope still works"
replace $FRETE 'id = "T-200"' 'id = "T-900"'
task T-900
edit $CUPOM;  expect 2 "bumping the id does not reset the baseline" "not in force"
task T-900; stop
expect 0 "stopping with an uncommitted scope edit tells the user" '"systemMessage"'
case "$OUT" in *'T-900 (src/main/java/loja/Frete.java)'*'-> [**, '*) ok "...naming the spec and the new scope";; *) bad "...naming the spec and the new scope";; esac
commit "human approves the wider scope"
git -C "$P" branch -q -f base HEAD~1
sdd scopeci --base base T-900
expect 0 "scopeci flags a diff that changes allowedFiles" "this diff changes allowedFiles"
sdd scopeci --base base --strict-scope T-900
expect 1 "scopeci --strict-scope fails on it" "T-900 (src/main/java/loja/Frete.java)"
stop;         expect 0 "no message once the scope change is committed"
case "$OUT" in *systemMessage*) bad "no systemMessage after the commit";; *) ok "no systemMessage after the commit";; esac
edit $CUPOM;  expect 0 "a committed scope change takes effect"

echo "scope guard: a spec that is not committed yet"
project fresh
cat > "$P/src/main/java/loja/Novo.java" <<'J'
package loja;
import speclok.Spec;
import speclok.AC;
@Spec(id = "T-777", objective = "new", allowedFiles = {"src/main/java/loja/Novo.java"}, outOfScope = {"x"})
public class Novo {
	@AC(id = "AC1", given = "g", when = "w", then = "t")
	public int f() { return 1; }
}
J
task T-777
edit src/main/java/loja/Novo.java; expect 0 "uncommitted spec: scope is snapshotted on first sight"
replace src/main/java/loja/Novo.java 'allowedFiles = {' 'allowedFiles = {"**", '
edit $CUPOM;  expect 2 "uncommitted spec: later widening is not in force" "not in force"

echo "scope guard: no active task"
project idle
edit .sdd-state/current-task; expect 0 "from idle, the agent may arm a task (documented flow)"
edit .sdd-state/baseline;     expect 2 "other harness state is blocked even with no task" "harness state"
replace src/main/java/loja/legado/Checkout.java 'package loja.legado;' 'package loja.legado;
// claims scope it was never given:
// @Spec(id = "T-666", objective = "x", allowedFiles = {"**"})'
sdd list;     case "$OUT" in *T-666*) bad "@Spec in a comment is not a spec";; *) ok "@Spec in a comment is not a spec";; esac

echo "@Protected"
project prot
printf 'package loja;\n// TODO maybe mark this @Protected(tier = "FORBIDDEN") later\npublic class A {}\n' > "$P/src/main/java/loja/A.java"
printf 'package loja;\npublic class B { String s = "@Protected(tier = \\"FORBIDDEN\\")"; }\n' > "$P/src/main/java/loja/B.java"
printf 'package loja;\nimport speclok.Protected;\n/* billing */ @Protected(tier = "FORBIDDEN")\npublic class C {}\n' > "$P/src/main/java/loja/C.java"
printf 'package loja;\nimport speclok.Protected;\n@Protected\npublic class D {}\n' > "$P/src/main/java/loja/D.java"
edit src/main/java/loja/A.java; expect 0 "@Protected in a comment does not protect the file"
edit src/main/java/loja/B.java; expect 0 "@Protected in a string does not protect the file"
edit src/main/java/loja/C.java; expect 2 "a real FORBIDDEN @Protected blocks" "FORBIDDEN"
edit src/main/java/loja/D.java; expect 0 "@Protected with no tier is a RESTRICTED checkpoint" "CHECKPOINT"

echo "stop gate: tests"
project accept; task T-200
stop;         expect 0 "clean task passes"
replace $FRETE_TEST 'static void AC2_freeAboveThreshold' 'static void renamed_freeAboveThreshold'
stop;         expect 2 "an @AC with no test blocks 'done'" "no test for: [AC2]"
replace $FRETE_TEST 'static void renamed_freeAboveThreshold' '// AC2_mentionedOnlyInAComment
	static void renamed_freeAboveThreshold'
stop;         expect 2 "an AC id in a comment is not a test" "no test for: [AC2]"
project nodelete; task T-200
rm "$P/$FRETE_TEST"
stop;         expect 2 "deleting the test file blocks 'done'" "no test for"
sdd verify T-200; expect 0 "verify reports the missing tests" '"acceptance_ok":false'
project glob; task T-200
replace $FRETE 'allowedFiles = {"src/main/java/loja/Frete.java", "src/test/java/loja/FreteSpecTest.java"}' 'allowedFiles = {"src/**/Frete*.java"}'
replace $FRETE_TEST 'static void AC2_freeAboveThreshold' 'static void renamed_freeAboveThreshold'
commit "scope as a glob"
stop;         expect 2 "with a glob scope, another task's AC2_ test does not cover this AC2" "no test for: [AC2]"
project red; task T-200
replace $FRETE 'return Math.max(frete, 0);' 'return frete - 100;'
stop;         expect 2 "a failing @AC test blocks 'done'" "missing ACs: [AC1]"

hook accept '{"stop_hook_active":true}'
expect 2 "a second 'done' after a block is still gated" "missing ACs: [AC1]"
for i in 3 4 5; do stop; done
expect 2 "...up to the iteration ceiling" "iter 5/5"
stop;         expect 0 "...and then the agent may stop, with an honest failure" "ceiling of 5 iterations"

echo "stop gate: scope measured on the tree (shell edits)"
project shell
edit $FRETE                                   # arms T-200
echo "// touched by a shell command" >> "$P/$CUPOM"
stop;         expect 2 "a shell edit outside the scope blocks 'done'" "$CUPOM"
git -C "$P" checkout -q -- "$CUPOM"
echo "x" > "$P/notes.txt"
stop;         expect 2 "a new file outside the scope blocks 'done'" "notes.txt"
rm "$P/notes.txt"
mkdir -p "$P/target/classes" && echo x > "$P/target/classes/A.class"
echo "// in scope" >> "$P/$FRETE"
stop;         expect 0 "in-scope changes and build output pass"

project wip
echo "// a human's work in progress" >> "$P/$CUPOM"
edit $FRETE                                   # arms T-200 with Cupom already dirty
stop;         expect 0 "files already dirty before the task are not blamed on it"
echo "// and now the agent touches it too" >> "$P/$CUPOM"
stop;         expect 2 "...but changing them further is" "$CUPOM"

project turns
edit $FRETE; stop                             # first turn ends clean
echo "// the human edits between turns" >> "$P/$CUPOM"
sdd arm
if [ "$CODE" = 0 ] && [ -z "$OUT" ]; then ok "sdd arm succeeds and prints nothing (its stdout goes to the model)"; else bad "sdd arm succeeds and prints nothing"; fi
stop;         expect 0 "changes made before the turn started are not blamed on it"
echo "// shell edit during the next turn, no edit tool involved" >> "$P/$CUPOM"
stop;         expect 2 "a turn that only uses the shell is still checked" "$CUPOM"

project locale
edit $FRETE
mkdir -p "$P/docs novos" && echo x > "$P/docs novos/ação.md"
OUT="$(cd "$P" && printf '{}' | LC_ALL=C java -Dsdd.root="$(winpath "$P")" -jar "$JAR" sdd accept 2>&1)"; CODE=$?
expect 2 "a non-ASCII file name under a C locale does not disable the check" "docs novos/"

project nogit; rm -rf "$P/.git" "$WORK/nogit/.git"; task T-200
edit $CUPOM;  expect 2 "outside git: the scope guard still blocks"
stop;         expect 0 "outside git: the stop check is skipped with a warning" "git status unavailable"
SDD_FAIL_CLOSED=1; export SDD_FAIL_CLOSED
stop;         expect 2 "outside git with failClosed: the stop check blocks" "failClosed"
unset SDD_FAIL_CLOSED

project mono app                              # project below the git top-level
edit $FRETE
echo "// shell" >> "$P/$CUPOM"
stop;         expect 2 "works when the project is a sub-directory of the repo" "[$CUPOM]"

echo "fail-open / fail-closed"
project closed; task T-200
hook scope 'not json';                 expect 0 "a harness failure lets the edit through by default"
export SDD_FAIL_CLOSED=1
hook scope 'not json';                 expect 2 "SDD_FAIL_CLOSED=1 blocks on a harness failure" "failClosed"
unset SDD_FAIL_CLOSED
printf '{"failClosed": true, "annotationRoots": ["src/main/java"]}' > "$P/sdd.config.json"
hook scope 'not json';                 expect 2 "\"failClosed\": true in the config does the same" "failClosed"

echo
echo "$PASS passed, $FAIL failed"
[ "$FAIL" = 0 ]
