# speclok v0.1.1 — stop-gate fix

A correctness fix for the enforcement layer. **Recommended for everyone on v0.1.0.**

## The bug (present in v0.1.0)

The Stop gate decides whether a turn changed anything by comparing a per-turn baseline against `git status` — which only sees **uncommitted** files. So an agent that does the most ordinary thing — *"fix it and commit"* — ended the turn with a clean working tree, and the gate concluded "nothing changed" and let it stop. Two holes followed from that:

- A commit carrying a **failing `@AC` test** was accepted as "done" (the idle-turn shortcut skipped the gate entirely).
- A **shell edit outside the declared scope**, once committed, was not reported.

In short: on v0.1.0 an agent could ship a red or out-of-scope change as long as it committed it first. Since confinement is the whole point of speclok, this was worth a point release.

## The fix

The per-turn baseline now also pins `HEAD`, and "changed since the baseline" becomes **the uncommitted files plus the files touched by commits made during the turn** (`git diff baseline..HEAD`). A commit made *before* the turn started is still not blamed on the agent.

Covered by three new adversarial tests in `test/enforcement-test.sh`:

- a committed change with a failing `@AC` → still blocked (`missing ACs`)
- a committed out-of-scope shell edit → still caught
- a commit made before the turn → correctly **not** blocked

The full suite is green: **55/55**.

## Upgrade

Rebuild the jar (`bash build-sdd.sh`) or download `speclok.jar` below. No config or API changes — drop-in over v0.1.0.

## Requirements (unchanged)

- Java 11 or newer (JDK). No other dependencies.
- The Stop-gate scope check needs a git work tree (it already did in v0.1.0).

**Full changelog:** `c27ee3e` — *Stop gate: see changes the agent committed during the turn*.
