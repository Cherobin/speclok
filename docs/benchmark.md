# speclok benchmark - results

Re-measured on the current build (commit `c27ee3e`) with **every setup running in a git workspace** -
which speclok's Stop gate requires - at **n=30 per setup**: the current speclok build edits an
out-of-scope file in **0 of 30 runs** (95% CI 0.0-11.4); every non-speclok setup does so in 83-100%,
their confidence intervals non-overlapping with it. The three speclok builds trace the hardening:
20% (hooks only) -> 6.7% (+MCP) -> 0% (current). No setup differs measurably in task correctness
(~70-83%, intervals overlap). Governance costs tokens (~3x the ungoverned agent); in the git
environment speclok is among the more token-heavy governed setups, not the cheapest.

> Design: 6 setups x 6 tasks x 5 repetitions = 180 runs on the current build, all in a git workspace.
> Agent: Claude Sonnet 5, headless. All figures are computed directly from the raw data, committed
> here: [`benchmark-data/results-rerun-git-n30.csv`](benchmark-data/results-rerun-git-n30.csv) (one
> row per run).

## Methods

| | |
|---|---|
| **Setups** | control (bare agent), spec-kit, openspec, and three speclok builds: v1 (hooks only), v1 +MCP, and the current build. Every setup runs inside a git work tree. |
| **Fixture** | one Java brownfield project; 6 tasks (T01-T06); hidden tests the agent never sees. |
| **Primary outcome** | **out-of-scope edit rate** = share of runs that changed a file outside the task's declared allowed set. |
| **Secondary** | correctness (hidden-test pass), input-token cost. |
| **Statistics** | proportions as 95% Wilson score intervals (n=30 per setup, z=1.96); token cost as median input tokens. Every figure is recomputed directly from the committed CSV. |

## Results

| setup | out-of-scope (95% CI) | correctness (95% CI) | tokens, median |
|---|---|---|---|
| control (bare agent) | 83.3% [66.4-92.7] | 83.3% [66.4-92.7] | 354k |
| spec-kit | 100% [88.6-100] | 83.3% [66.4-92.7] | 767k |
| openspec | 100% [88.6-100] | 83.3% [66.4-92.7] | 930k |
| speclok v1 (hooks only) | 20.0% [9.5-37.3] | 70.0% [52.1-83.3] | 1065k |
| speclok v1 + MCP | 6.7% [1.8-21.3] | 83.3% [66.4-92.7] | 976k |
| **speclok (current build)** | **0.0% [0.0-11.4]** | **80.0% [62.7-90.5]** | 1009k |

## Interpretation

- **Scope.** The current speclok build is the only setup with 0 out-of-scope edits; its 95% interval
  [0-11.4%] does not overlap any non-speclok setup (83-100%). The three speclok builds trace the
  hardening cleanly: hooks-only 20% -> +MCP 6.7% -> current 0%.
- **The git workspace is load-bearing for speclok.** speclok reads scope from the `@Spec` committed at
  HEAD and tracks shell edits via `git status`; without a git work tree its Stop gate cannot catch an
  out-of-scope change that a shell command wrote or that the agent committed. An earlier first-pass
  that ran the speclok conditions *without* git put the earlier builds far higher. This run puts every
  setup in a git workspace, so the comparison is apples-to-apples.
- **Correctness.** Roughly flat (~70-83%, intervals overlap): the gates keep the agent in its lane,
  they do not make it smarter.
- **Cost.** The governed setups run ~3x the bare agent's median tokens. The current speclok build
  (1009k) is on the heavier end of the governed setups, not the cheapest - a correction to an earlier,
  non-git measurement that read it as second-cheapest.

## Limitations

- **Environment deviates from the original protocol.** This run executed on Windows 11 / JDK 21; the
  original pins specified Ubuntu 22.04 / JDK 17. Only portability patches were applied.
- **Harness flakiness required re-runs.** On Windows, parallel execution left 51 of the 180 cells
  blocked at setup (git-init / tool-bootstrap aborting under concurrent load, *before* the agent ran).
  Each was cleared and re-run until it completed, so the final table has n=30 valid runs for every
  setup. Because the blocks happen before the agent acts, re-running them does not bias the
  out-of-scope outcome.
- **Small per-cell n.** 5 repetitions per task; setup-level n=30. Intervals are wide for the mid-range
  setups - treat their ordering as indistinct.
- **Scope of generalization.** A single agent model and a single fixture family; results may not
  transfer to other models, languages, or task shapes.
- **The harness is not committed here** (fixture, tasks, prompts), so the per-run numbers are
  reproducible only from the committed CSV.

## Reproduce it

Every number above comes from
[`benchmark-data/results-rerun-git-n30.csv`](benchmark-data/results-rerun-git-n30.csv). The condition
column maps to the labels as: `control`, `spec-kit`, `openspec`, `speclok` (v1 hooks), `speclok-mcp`
(v1 +MCP), `speclok-v2` (current). Out-of-scope = `forbidden_files_changed`; correctness =
`hidden_tests_pass`; tokens = `input_tokens`.

```python
import csv, math, statistics as st
rows = list(csv.DictReader(open("docs/benchmark-data/results-rerun-git-n30.csv", encoding="utf-8")))
def wilson(k, n, z=1.96):
    p = k / n; c = (p + z*z/(2*n)) / (1 + z*z/n)
    h = z*math.sqrt(p*(1-p)/n + z*z/(4*n*n)) / (1 + z*z/n)
    return round(p*100, 1), round(max(0, (c - h)) * 100, 1), round((c + h) * 100, 1)
def rate(rs, field): return sum(r[field].strip().lower() == "true" for r in rs)
for cond in ["control", "spec-kit", "openspec", "speclok", "speclok-mcp", "speclok-v2"]:
    rs = [r for r in rows if r["condition"] == cond]; n = len(rs)
    tok = sorted(int(r["input_tokens"]) for r in rs)
    print(cond, "oos", wilson(rate(rs, "forbidden_files_changed"), n),
          "correct", wilson(rate(rs, "hidden_tests_pass"), n), f"{st.median(tok)/1e3:.0f}k")
```

---

Wilson score intervals, z=1.96. "speclok (current)" is the published build; the "v1" rows are earlier
iterations. An earlier first-pass benchmark (pre-hardening, and run without a git workspace for the
speclok conditions) is superseded by this table.
