# Feature tour

**English** · [Русский](features.ru.md)

What Ignite PR Checker can do, screen by screen. The pictures are schematic mockups of the real UI
(dark theme; there is a light theme too — the ☀/🌙 toggle in the top bar).

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

- **Blockers** — failed in the PR's latest RunAll **and** clean in the last ~100 master runs **and** still
  failing in the last finished run on the branch. Grouped by suite; every name links straight to the
  failure in TeamCity.
- **Filtered out** — everything else, each with its reason (`pre-existing: fails 39/95 on master`,
  `passed on re-run`, …). Collapsed by default, so noise stays out of the way.
- **Broken suites** — a suite that failed *without running its tests* (compilation error, timeout, agent
  crash, failed dependency) is surfaced in its own red card instead of silently vanishing; suites that
  failed only because a dependency failed collapse into one line.
- Every test carries a **pass/fail strip** of its finished runs on the branch (oldest → newest). A
  fail→pass transition earns a **flaky?** tag; a steady `▮▮▮` means a solid break.
- **why?** expands the failure message inline, prefixed with a rough triage:
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
- **Re-runs without leaving the page**: the whole `RunAll`, all blocker suites at once, or one suite —
  each *plain* or *at the top of the queue*. Live **queued / running** chips appear on the affected
  suites and in the `runs:` row, each linking to that very build; a running RunAll chain expands into
  per-suite states. **Cancel all** kills everything you queued.
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
  the jar and restarts the service.
- **Per-user auth**: everyone logs in with their own TeamCity token (encrypted into an HttpOnly
  cookie; no server-side session store, no shared credentials).
- **Dark / light theme**, per-browser, with no flash on load.
- The heavy lifting is cached and pre-warmed in the background, so opening a PR is instant.
