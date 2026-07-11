# Feature tour

**English** · [Русский](features.ru.md)

What Ignite PR Checker can do, screen by screen. The pictures are schematic mockups of the real UI
(dark theme; there are four themes — Light, Dark, JetBrains and Terminal — the selector in the top bar).

## Finding your PR

![Home search](img/home-search.svg)

- The left pane lists the repo's **open PRs**, most-recently-updated first. Badges show each PR's
  last-known verdict: a green **✓** (no blockers), a red **count** of blockers, or nothing (not analysed yet).
- A blue **My?** chip flags PRs whose latest RunAll *you* triggered (matched by your TeamCity username).
- With no PR selected, a **search box** filters the list live by number or title; `Enter` opens the first
  match. A bare number opens **any** PR — even one not in the list (`?pr=12345` in the URL works too).
- The pane is **resizable** (drag the divider, double-click to reset) and **collapsible** (`‹` / `›`).

## Reading the verdict

![PR analysis](img/pr-analysis.svg)

The one question the tool answers: **which tests did this PR actually break?**

- **Blockers** — failed in the PR's latest RunAll, clean in the last ~100 master runs, **and failing
  consistently**: every one of the last N (default 3) finished branch runs failed, with no pass on the
  same code. Grouped by suite; every name links straight to the failure in TeamCity.
- **Recently started failing** — an amber card for tests failing the last 2+ runs but passing earlier:
  a fresh break to watch. If it keeps failing it becomes a blocker; if it flaps back it won't.
- The verdict is **live**: while a newer RunAll is running (or ended cancelled), failures from its
  already-finished suites are folded in — the *● includes an unfinished run* tag links to that chain.
  An aborted chain shows a red *RunAll interrupted* banner (N suites failed, M never ran).
- **Filtered out** — everything else, each with its reason (`pre-existing: fails 39/95 on master`,
  `passed on re-run`, …). Collapsed by default, so noise stays out of the way.
- **Broken suites** — a suite without a reliable run (compilation error, **execution timeout,
  out-of-memory, JVM crash**, failed dependency) is surfaced in its own red card instead of silently
  vanishing — even when it *does* have failed tests: those are hang cascade, and some tests never ran.
- Every test carries a **pass/fail strip** of its finished runs on the branch (oldest → newest). A
  fail→pass transition earns a **flaky?** tag; a steady `▮▮▮` means a solid break.
- **why?** expands the failure message inline as a copyable code block, prefixed with a rough triage:
  `♻ environment/timing — a re-run may pass` vs `⚖ assertion — likely a real logic failure`.
- The blockers card has two views: **Suites** (default) and **Root causes** — the same blockers
  regrouped by failure signature, each cause a collapsible with its suites and tests inside.
  Hundreds of tests usually collapse into a handful of causes; a suite broken by two different
  things simply appears under both.
- `IGNITE-XXXXX` in the PR title links to the ASF JIRA issue.

## Iterating on a fix

- **vs previous run: +2 new · −3 fixed · 5 persisting** — the delta against the PR's previous RunAll
  (test names in the tooltips), next to a **trend sparkline**: one bar per run, red while blockers
  remain, green at zero.
- **Re-runs without leaving the page**: the whole `RunAll`, any **section** (broken suites, blockers,
  recently-started, filtered) or one suite — each *plain* or *at the top of the queue*. Live
  **queued / running** chips appear on the affected suites and in the `runs:` row with **queue-aware
  finish estimates** (they account for the agent queue and each suite's actual progress). **Cancel all**
  kills everything you queued.
- **JIRA visa** — post the verdict to the PR's `IGNITE-XXXXX` ticket in the classic tcbot style:
  one click now, **Auto visa** (one-shot, fires when the current run finishes), or the settings (⚙)
  option *Auto-visa all my runs* — every RunAll you trigger gets its verdict posted automatically
  (only runs finished after you switch it on; each run is posted once).
- **Auto re-run blocker suites** (settings, independent of the visa) — when a RunAll you triggered
  finishes with blockers, their suites are re-run automatically, up to 2 attempts: ≤10 suites jump to
  the top of the queue, more go to the tail so they don't push others back, and a systemic breakage
  (30+) is left alone. Identical suites already waiting in the queue are cancelled first. A pass on
  re-run clears its blocker; with the visa also on, the visa waits until the re-runs settle.
- **GitHub PR comment** (settings, independent of the other two) — the same verdict, in GitHub
  markdown, posted on the `apache/ignite` pull request from your own GitHub account (a personal
  access token with the `public_repo` scope; stored encrypted at rest while the option is on).
  The whole run lives in **one comment**: it appears when the run finishes, and if auto re-run
  kicks in it **updates in place** (⏳ re-running → final verdict) instead of spawning new messages.
- **`/run-all` from the PR** — with the GitHub option on, comment `/run-all` on any pull request
  and the whole RunAll chain is queued under your own TeamCity token; the ack is a 🚀 reaction from
  your own account (😕 if TeamCity refused). Combined with auto re-run and the verdict comment, the
  entire cycle happens without leaving the PR. Commands are picked up within a minute — one
  repo-wide comments poll covers every PR.
- The freshness line shows the run's **composition** — `6 ran · 141 reused` — because a re-triggered
  chain on unchanged revisions reuses earlier suite builds (TeamCity substitutes suitable results).
- When your runs finish, the analysis **refreshes itself** — no F5.

## The fix-master queue (`/flaky.html`)

![Flaky tests](img/flaky-page.svg)

Tests the checker filters out because they **fail on master** — not any one PR's fault, but shared
noise. Ranked by master fail-rate (worst first) with the count of open PRs each one is currently
noising. The tally is accumulated and persisted, so it survives restarts and idle periods; a test
drops off ~14 days after it stops failing. Public — no login needed to read it.

## The status page (`/status.html`)

![Status page](img/status-page.svg)

Public service health: CPU/load/heap with **traffic-light** thresholds, the app's own endpoint
latency, TeamCity/GitHub call metrics by category with per-minute charts, and the **cache warmer**
block — whether it is warming right now (with live progress), how long the startup warm-up took,
and what the last cycle did. **Flush caches** (logged-in only) drops the analysis caches and
triggers a background re-warm.

## Everything else

- **Self-update**: when a new release is out, an **Update to vX.Y.Z** button appears — one click swaps
  the jar and restarts the service. After a deploy, open tabs show a **UI updated — reload** pill.
- The status page also has a **Users** tab (who's active now / everyone seen — visible to logged-in
  viewers only) and a **Restart service** button (danger-styled, confirm-guarded).
- **Per-user auth**: everyone logs in with their own TeamCity token (encrypted into an HttpOnly
  cookie; no server-side session store, no shared credentials).
- **Four themes** — Light, Dark, JetBrains (dense, status stripes) and Terminal (monospace, bracket
  buttons) — per-browser, with no flash on load.
- The heavy lifting is cached and pre-warmed in the background, so opening a PR is instant.
