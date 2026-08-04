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
- **A cancelled run still counts.** When a PR has no clean finished RunAll but a cancelled one that
  got through most of its suites, the checker analyses that (partial) run — the *RunAll interrupted*
  banner plus its real failures — instead of "no run at all". A clean finished run always wins, so a
  fresh cancel never shadows a good verdict.
- The verdict is **live**: while a newer RunAll is running (or ended cancelled), failures from its
  already-finished suites are folded in — the *● includes an unfinished run* tag links to that chain.
  An aborted chain shows a red *RunAll interrupted* banner (N suites failed, M never ran).
- **Filtered out** — everything else, each with its reason (`pre-existing: fails 39/95 on master`,
  `passed on re-run`, …). Collapsed by default, so noise stays out of the way.
- **Fewer tests than master** — a suite that ran noticeably fewer tests than the same suite runs on
  master gets its own card (`ran 57 tests · master runs 439 — −87%`). Tests that never ran can't
  fail, so a suite can look green while silently skipping coverage. The baseline is master's own
  latest chain, not TeamCity's built-in metric — that one compares against a pinned reference build
  and false-alarms on PR branches long after a legitimate test-count change.
- **Broken suites** — a suite without a reliable run (compilation error, **execution timeout,
  out-of-memory, JVM crash**, failed dependency) is surfaced in its own red card instead of silently
  vanishing — even when it *does* have failed tests: those are hang cascade, and some tests never ran.
- Every test carries a **pass/fail strip** of its finished runs on the branch (oldest → newest). A
  fail→pass transition earns a **flaky?** tag; a steady `▮▮▮` means a solid break.
- **ai** (next to *why?*) — copies a **paste-ready fix prompt for a coding assistant**: the PR link
  and branch, the suite with its failed-run TC link, the full test name, the checker's verdict with
  the branch run history, the triage tag, the complete failure output, and concrete repro/fix steps.
  A root cause gets its own **ai** button covering the whole cluster (shared signature, every
  affected suite/test, one exemplar output — "find the ONE cause, don't patch tests one by one");
  the flaky board's **ai** builds a stabilisation prompt (fail-rate, noised PRs, typical instability
  checklist, prove-with-20-runs instruction).
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
  finish estimates** (they account for the agent queue and each suite's actual progress) and a
  **revision tag**: `rev ✓ head` for a run on the PR's current head, `⚠ rev abc123` when commits
  were pushed after it started (it tests older code), `rev @start` for queued builds — TeamCity
  resolves their revision at start, so they pick up the head of that moment. **Cancel all**
  kills everything you queued.
- **JIRA visa** — post the verdict to the PR's `IGNITE-XXXXX` ticket in the classic tcbot style:
  one click now, **Auto visa** (one-shot, fires when the current run finishes), or the settings (⚙)
  option *Auto-visa all my runs* — every RunAll you trigger gets its verdict posted automatically
  (only runs finished after you switch it on). Like the PR comment, the visa is **one living
  comment per run**: it appears when the run finishes and is edited in place as re-run waves start
  and settle — but only on stage changes (ticket watchers get mail on every edit), never on the
  10-minute ETA refreshes.
- **Auto re-run blocker suites** (settings, independent of the visa) — when a RunAll you triggered
  finishes with blockers **or broken suites** (timeout, crash, compilation), those suites are re-run
  automatically, up to 2 attempts: ≤10 suites jump to the top of the queue, more go to the tail so
  they don't push others back, and a systemic breakage (30+) is left alone. Identical suites already
  waiting in the queue are cancelled first. A pass on re-run clears its blocker — and a broken suite
  whose newer run passed stops being broken. With the visa also on, the visa waits until the re-runs
  settle; the living PR comment's ⏳ line carries a queue-aware **"≈ settled by HH:MM"** estimate,
  refreshed every sweep.
- **Auto-fix checkstyle on my runs** (settings; uses the GitHub token) — when you command a run on
  your **own** PR and the changed files violate checkstyle, the mechanically fixable part (imports,
  whitespace, tabs, modifier order, empty lines…) is fixed and pushed as one clearly-labelled commit
  from your account **before** the run starts — a trivial style failure can't waste a four-hour
  RunAll. The repo's own `checkstyle.xml` is used; what can't be fixed mechanically (javadoc,
  naming, wrapping) is reported in the command comment. Never touches anyone else's PR, never
  force-pushes.
- **GitHub PR comment** (settings, independent of the other two) — the same verdict, in GitHub
  markdown, posted on the `apache/ignite` pull request from your own GitHub account (a personal
  access token with the `public_repo` scope; stored encrypted at rest while the option is on).
  The whole run lives in **one comment**: it appears when the run finishes, and if auto re-run
  kicks in it **updates in place** (⏳ re-running → final verdict) instead of spawning new messages.
- **Pending changes** — if new commits were pushed to the PR after the analysed RunAll, a banner
  says so (**"⚠ N new commits pushed since this run (abc123 → def456) — the verdict is for the older
  code"**) with a **Re-run RunAll** button, so a stale verdict is never mistaken for the current one.
- The freshness line shows the run's **composition** — `6 ran · 141 reused` — because a re-triggered
  chain on unchanged revisions reuses earlier suite builds (TeamCity substitutes suitable results).
- When your runs finish, the analysis **refreshes itself** — no F5.

## Working from the PR (commands)

The whole cycle — trigger → progress → verdict — happens in the pull request, in comments. A
GitHub PAT is **optional**: with the GitHub option on, acks and the live status come from your own
account (and checkstyle autofix works); without one, just link your **GitHub login** in settings
(any standing option keeps your TC token stored) and the checker acks and narrates from its own
account instead.

| Command | Effect |
|---|---|
| `/run-all` (or `/runall`) | queues the whole RunAll chain under **your own** TeamCity token |
| `/run-all top` | same, but the chain enters the build queue **at the top** (native `queueAtTop`) |
| `/top` | promotes **the run your command started** to the top of the queue — only while it is still queued |

The semantics, fixed:

- **Top belongs to the command.** Both forms act on the run *your* command started; other people's
  builds on the same PR are never touched.
- **Ack, not chatter**: 🚀 reaction = accepted, 😕 = refused or nothing to act on. The details — the
  queued-build TC link, then a live **"~Xh Ym remaining — ≈ 21:05 MSK"** line (queue-aware, the
  wall-clock stamp in your JIRA-profile timezone, refreshed every minute) — are edited **into
  your command comment**, which narrates the whole story: run finished → **"♻️ Auto re-run #2 —
  3 broken suite(s), ≈ settled by 22:21"** while the waves settle → closes when the verdict lands.
  The verdict itself is **one** living comment that updates in place through the auto re-runs.
  Two messages per run, total.
- Commands are picked up **within a minute** (one repo-wide comments poll covers every PR) and work
  on **any** pull request — commanding a PR means running it under your accounts.
- **A new `/run-all` supersedes your previous one**: your own queued/running chain on the PR is
  cancelled first (nobody else's), its narration closes with 🛑 *Superseded*, and the ack says so.
  On unchanged revisions the new chain reuses the finished suites, so nothing useful is lost.
- A command from someone **not enrolled yet** gets a one-time reply explaining where to log in and
  which switch to flip — the command is its own onboarding path.

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
- The heavy lifting is cached and pre-warmed in the background, so opening a PR is instant. There
  is no service account: warming runs on real users' TeamCity tokens — every logged-in request
  donates one, and any **standing option** keeps yours in the pool permanently, which is what
  keeps the background work (warming, the instant re-analysis of a finished run, live run states)
  going while nobody has the page open.
