# Feature tour

**English** · [Русский](features.ru.md)

What Ignite PR Checker can do, screen by screen. The pictures are schematic mockups of the real UI
(dark theme; there are four themes — Light, Dark, JetBrains and Terminal — the selector in the top bar).

## Verdict glossary

Every label the checker puts on a PR, a suite or a test: what it means and what to do. The **?** marks on the
page, the PR comment and the JIRA visa link here. The numbers are the defaults in the code; see
[Thresholds](#thresholds).

### Cards on the PR page

| Card or heading | What it means | What to do |
|---|---|---|
| **Blockers** | Tests this PR most likely broke: they fail in its runs, and neither master nor other PRs explain them. Each suite is judged on its own. The line under a test says why ([reasons](#reasons-under-a-test)). | Open **why?** or **TC**, fix the code, push, run RunAll again. |
| **Not confirmed: 1 run** | Blockers that failed the only time they ran on this branch. They still count as blockers: in the count, the PR list badge, the visa and the comment. | **Rerun** their suites. A second failure confirms the blocker, a pass drops it. |
| **Recently started failing** | Tests failing on the PR's current code, with too few runs of it to tell a break from a flake. Not blockers yet. | **Rerun** the suite. If it keeps failing, it becomes a blocker; if it passes, it drops out. |
| **Could not be checked** | Failed tests that TeamCity errors kept from being compared with master and the branch. Not counted as blockers. | Nothing: the checker tries them again on its own. |
| **Broken suites** | Suites with no reliable result ([causes](#broken-suite-causes)). A timed-out or crashed suite is listed here instead of being mined for blockers: its failures would be hang cascade, and some tests never ran. | Rerun them first: the verdict can't trust what they would have found. |
| **Fewer tests than master** | Suites that ran at least 10% fewer tests than the same suite on master; only suites with 20 or more tests on master are compared. Tests that never ran can't fail. | If the PR removes tests on purpose, nothing. Otherwise compare the test list with master and rerun. |
| **Crashed near the end of the run** | Suites that hit a timeout, out-of-memory error or JVM crash after running over 90% of master's tests, with no failure blamed on the PR. Their results stand. | Nothing for the PR. |
| **⚠ Suites that never ran** | The RunAll was interrupted: these suites were cancelled and never ran. The verdict says nothing about them. Suites a person cancelled are folded into one line per person. | **Rerun** them, or run RunAll again. |
| **This PR's tests** | How the test classes the PR adds (`new`) or changes (`changed`) ran in this RunAll. A test is listed when it failed, ran longer than 60 s, or, in a changed class, has no master history. | Check that the new tests ran and passed. A test with no master history has only this run to judge it by. |
| **Filtered out** | Failures not blamed on the PR, each with its reason: they fail on master, are flaky, or passed on a re-run. | Nothing for the PR. Tests that fail on master are on the flaky board (`/flaky.html`). |
| **No blockers 🎉** | The run covered the PR's current code and blamed nothing on it. The PR list shows **✓**. | Good to go. |
| **No test blockers** | No test was blamed, but the verdict can't call the PR clean. The red line under it says why ([caveats](#caveats)); with no red line, tests started failing on its code and are under **Recently started failing**. | Sort out what the red line lists, then rerun. With no red line, **Rerun** the suites under **Recently started failing**. |

### Reasons under a test

A reason has a master part and a branch part, joined by `;`. `N`, `F/R` and `abc1234` stand for numbers and a
revision. `on JDK 17` means only master runs on the PR's JDK were counted. A muted failure is never listed, and a
run where the test was ignored does not count as a run.

| Reason | What it means | What to do |
|---|---|---|
| `not seen failing in N master run(s)` | Master ran the test N times in this suite (its last 100 runs at most) and it never failed. | Read the branch part. |
| `not seen failing in only N master run(s) on JDK 17` | The same, with fewer than 10 master runs on the PR's JDK: thin evidence. | Read the branch part. |
| `no master history (can't prove pre-existing)` | Master has no run of the test on the PR's JDK: a new test, or one master does not run. Tagged `new test / no master history`. | Make sure the test is stable: this PR is all there is to judge it by. |
| `rare on master: fails F/R, passed the last G` | Master fails the test in at most 2% of its runs, none of the newest 10, and the PR's failures in a row are too many to be that chance. | Treat it as a blocker. |
| `fails F/R on master at TEST_SCALE_FACTOR=1.0, but f/r on other PR branches at 0.1` | Master fails the test in at least half its runs, but only at a scale factor this PR did not run at. Other PRs, run like this one, seldom fail it, and this PR's failures in a row are too many for their rate. | Treat it as a blocker. |
| `…; fails F/R in K other PRs` (on a blocker) | It fails in 3 or more other PRs too, but this PR's failures in a row are too many to be that flakiness. | Treat it as a blocker. |
| `failed all N runs on this branch`, `failed the last N of M runs on this branch` | The last 3 finished runs of the suite on the branch failed the test, or all of them when there were fewer. A blocker. | Fix the code. |
| `failed the only run on this branch` | The test ran once on the branch and failed. A blocker, tagged `1 run`. | **Rerun** the suite to confirm it. |
| `failed all N runs on revision abc1234` | Every run of the PR's current code failed the test, and there were at least 2. A blocker. | Fix the code. |
| `first failure on revision abc1234 — watch (nothing has passed on this code; …)` | The current code ran once and failed; the earlier runs were on older code. To watch. | **Rerun** the suite. |
| `started failing in the last N of M runs on revision abc1234 — watch (an earlier run on the same code passed)` | At least 2 failures in a row on the current code, after a pass on the same code. To watch. | **Rerun** the suite. |
| `… — watch (too few failures on this code yet to outweigh other PRs)` | The scale factor case above, without enough failures yet to beat the other PRs' rate. To watch. | **Rerun** the suite. |
| `pre-existing: fails F/R on master` | Master fails the test too. Filtered out. | Nothing for the PR. |
| `flaky on other PR branches: fails F/R in K other PRs; …` | It fails in 3 or more other PRs, and this PR's failures are not enough to outweigh that. Filtered out. | Nothing for the PR. |
| `flaky on branch: failed only the latest of N runs on revision abc1234 (an earlier run on the same code passed)` | It failed once after passing on the same code. Filtered out. | Nothing, or rerun to be sure. |
| `not failing in the last finished run (passed on re-run)` | The newest finished run of the test on the branch passed. Filtered out. | Nothing. |
| `could not verify (TeamCity error: …)` | A TeamCity error stopped the check. Listed under **Could not be checked**. | Nothing: it is tried again. |
| `(the N earlier branch runs ran on other code)` | Runs on an older revision don't count; they are dimmed in the strip. | — |
| `(passed just before, but TeamCity gave no revisions to prove that was the same code)`, `(… could not be placed on a revision)` | TeamCity gave no revision for some runs, so they could not be compared. | — |

### Caveats

The red line under **No test blockers**, and the list under "This run doesn't cover the PR fully" in the comment and
the visa.

| Caveat | What it means | What to do |
|---|---|---|
| `Build failed — N suites that need it did not run` | A run the other suites need failed, so they never ran. | Fix the build, push, run RunAll again. |
| `the RunAll was interrupted — N suite(s) never ran` | Suites were cancelled before they ran. | **Rerun** them, or run RunAll again. |
| `N suite(s) have no reliable result (…)` | Broken suites; their causes are in brackets. | See [broken suite causes](#broken-suite-causes). |
| `N suite(s) ran far fewer tests than the same suites on master` | See **Fewer tests than master**. | Check the test lists, rerun. |
| `N failed test(s) could not be checked (TeamCity errors)` | See **Could not be checked**. The visa, the PR comment and the auto re-run wait up to 20 minutes for a retry; then they go ahead and list those tests apart. | Wait. |
| `a newer run is still going — its unfinished suites can still fail` | The verdict folds in a RunAll that is still running. | Wait for it to finish. |
| `N commit(s) pushed since this run — it tested older code` | The PR's head has moved since the run. | Run RunAll again. |

### Broken suite causes

| Cause | What it means | What to do |
|---|---|---|
| `compilation error` | The suite's code did not compile. The page offers no **Rerun** for it: the same code fails the same way. | Fix the build, push, run RunAll again. |
| `execution timeout` | The suite ran out of time. Its failures are likely hang cascade, and some tests never ran. | Rerun. If it times out again, look at the thread dump in TeamCity. |
| `JVM crash / out of memory` | The JVM died mid-run. | Rerun. If it repeats, look for the crash or the leak in the build log. |
| `non-zero exit code` | The build exited with an error, and no unmuted test failed. | Open **TC** to see what failed. |
| `failed dependency` | A run this suite depends on failed. | Fix that run first. |
| `failed without running tests` | TeamCity named no problem, and no test failed. | Open **TC**, rerun. |
| `ci2 glitch: artifacts unavailable (N suites)` | ci2 could not hand these suites the artifacts of a run they need, though that run passed. | Rerun: a re-run usually gets them. |
| `Build failed — N suites that need it did not run; fix the build, then /run-all` | The build step failed, and the suites that need it never ran. `nothing else ran` when that was the whole chain. | Fix the build, push, `/run-all` or **Run**. |
| `Build failed in this run and passed on a re-run since — N suites that need it never ran; /run-all to run them` | The build passes now, but the suites that need it have no result. | `/run-all` or **Run**. |
| `— ran 33 of master's 67 tests` (after a cause) | How far the broken suite got. The cause explains the shortfall; it is not a missing-tests finding of its own. | — |
| anything else | The first line of TeamCity's own problem text. | Open **TC**. |

### Tags next to a test

| Tag | What it means | What to do |
|---|---|---|
| strip of bars | The finished runs of the test in its suite on the branch, oldest to newest: red failed, green passed. Dimmed bars ran on older code and don't count. | — |
| `flaky?` | The test failed and then passed on the same code. | Look for a race or a timing problem. |
| `1 run` | Only one failure on this branch backs the verdict. | **Rerun** the suite: it confirms or clears it. |
| `new test / no master history` | Master has no runs of the test on this JDK to compare with: a new test, or one master does not run. | Make sure the test is stable. |
| `unverified` | A TeamCity error kept part of the check from being made: other PRs' runs of the test. The checker tries again. | Wait. |
| `⚖ assertion — likely a real logic failure` (in **why?**) | A failed check. | Fix the code. |
| `♻ environment/timing — a re-run may pass` | Environment or timing trouble. | Rerun. |
| `♻ environment/timing — but it failed all N runs of this code, …` | The same, but every run of this code failed. | A re-run alone is unlikely to pass: fix it. |
| `⌛ hang — the test ran out of time; …` | The test hung. The test's thread from the thread dump is shown. | See where that thread waits. |
| **why?** / **ai** | The failure message / a fix prompt for a coding assistant, copied to the clipboard. | — |

### Badges in the PR list

| Badge | What it means | What to do |
|---|---|---|
| red number | Blockers in the PR's latest analysed RunAll. | Open the PR. |
| **!** | No blockers, but tests started failing on its code. | A rerun decides. |
| **?** | No blockers found, but the run can't prove the PR clean ([caveats](#caveats)), commits were pushed since the run, or it is not known whether the run tested the PR's current head. | Open the PR for the reason. |
| **✓** | A clean run of the PR's current head. | — |
| none | Not analysed yet. | Open the PR to analyse it. |
| **My?** | Your TeamCity user started its latest RunAll. | — |

### Runs, buttons and visas

| Label | What it means | What to do |
|---|---|---|
| `queued`, `running` | A build of this PR on ci2 now. The time next to it is how long it has waited or run so far; `~14m left` and `starts ~5m` are estimates from the queue and the suite's progress. | — |
| `rev ✓ head` | The run tests the PR's current head. | — |
| `⚠ rev abc1234` | Commits were pushed after the run started: it tests older code. | Run RunAll again when it matters. |
| `rev @start` | The run is still queued; the page names its revision once it runs. | — |
| `by alice` | Someone else started this run. **Cancel my runs** leaves it alone. | — |
| `● includes an unfinished run` | The verdict folds in the finished suites of a RunAll that is still running. It never reads green. | Wait for the run. |
| `suites: 6 fresh, 141 from earlier runs` | How many suites of the chain ran, and how many TeamCity reused from earlier runs on unchanged revisions. | — |
| `vs previous run: +2 new · −3 fixed · 5 persisting` | Blockers compared with the PR's previous RunAll, suite by suite. | — |
| `⚠ N new commits pushed since this run` | The verdict is for older code. | **Run RunAll**. |
| `♻️ Auto re-run #1 of up to 2 in progress`, `♻️ Deciding on auto re-runs…` | Auto re-runs are still settling this run: the verdict may change. | Wait. **🔔 Notify me** tells you when it is final. |
| **Run**, **Run at top** | Queue a new RunAll chain for the PR, at the end or at the top of the ci2 queue. Always asks first. | — |
| **Rerun**, **Rerun at top** | Re-run a suite, or every suite of a section (the number says how many). Five or more suites ask first. | — |
| **Cancel my runs** | Cancels the builds you started on this PR. | — |
| **JIRA visa** | Posts the verdict to the IGNITE ticket the PR title names, now. | — |
| **Auto visa** | Posts the verdict of the current run to the ticket when it finishes, once. `Auto visa ✓`: armed by you; `armed: bob`: armed by others, who get one visa between them. | — |
| **Auto visa: on in ⚙** | The standing auto-visa of whoever started the run will post it: nothing to arm. | — |
| **Auto-visa all my runs** (⚙) | When a RunAll you started finishes, its verdict goes to the IGNITE ticket the PR title names, once per run; with auto re-run on too, once the re-runs settle. The title needs an IGNITE-NNNN key: no key, no visa. No visa while a newer RunAll of the PR is going, and none that repeats the last one for the same revision. | — |

### Thresholds

| What | Default | Where it shows |
|---|---|---|
| Master runs of a test looked at, per suite | 100 (`MASTER_HISTORY_DEPTH`) | `pre-existing`, `not seen failing in N master run(s)` |
| Failed branch runs in a row for a blocker | 3, or every run when fewer (`BLOCKER_FAIL_STREAK`) | `failed all N runs on this branch` |
| Thin master history | fewer than 10 master runs on the PR's JDK | `only N master run(s)` |
| A rare master failure | at most 2% of master runs, and none of the newest 10 | `rare on master` |
| Too many failures for chance | at most 1 in 10,000: 2 in a row against a 1% rate, 3 against 2%, 5 against 12.5% | `rare on master`, the other PR and scale factor reasons |
| Flaky in other PRs | failing in 3 or more other PRs | `flaky on other PR branches` |
| Master's failures set aside as down to the scale factor | master fails at least half its runs, only at another scale factor, and other PRs have at least 10 runs | `fails F/R on master at TEST_SCALE_FACTOR=1.0` |
| Fewer tests than master | at least 10% fewer, for suites with 20 or more tests on master | **Fewer tests than master** |
| A crashed suite whose results stand | ran over 90% of master's tests | **Crashed near the end of the run** |
| An incomplete verdict held back | 20 minutes | **Could not be checked** |
| A slow test of the PR | longer than 60 s | **This PR's tests** |
| A rerun that asks first | 5 suites | **Rerun (N)** |

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

- **Blockers** — failed in the PR's latest RunAll, clean in the last ~100 master runs of the same
  suite, **and failing consistently**: every one of the last N (default 3) finished branch runs of that
  suite failed, with no pass on the same code. Grouped by suite; every name links straight to the
  failure in TeamCity.
  "The same code" is matched on the **VCS revision each run's build ran on**: a pass from before the
  breaking commits is discounted (dimmed in the history strip), never read as "it passed on this code".
  **Each suite is judged on its own.** One test can run in several suites of a chain (the C++ tests
  run on Windows, Linux and Clang): a pass on another platform is not a re-run of the failure, and
  another platform's master failures don't make it pre-existing. A test that failed in several
  suites is listed in each of them with that suite's own verdict, so a Windows re-run that passed
  doesn't hide a steady break on Linux.
  A master run where the test was **ignored** doesn't count: the test did not run there. The reason
  names real runs only (`not seen failing in 3 master run(s)`), and a test ignored in all of them
  gets *no master history (can't prove pre-existing)*.
- **Recently started failing** — an amber card for tests the run cannot yet call either way: the
  current revision has too few runs to tell a real break from a flake (typically its first failure,
  with only older-code passes behind it). The suite is re-run automatically; a second failure on that
  same revision makes it a blocker, a pass drops it out.
- **A cancelled — or still running — run counts.** When a PR has no clean finished RunAll, the
  checker falls back to the latest cancelled one, and failing that to the chain that is **still
  going**: its finished suites are real results, and on a PR's first RunAll they land hours before
  the chain ends. Until then the page said "no run at all" while a dozen suites were already red.
  Such a verdict is marked *● includes an unfinished run* and can never read green; a suite still
  running is never called broken, and its partial test count is never read as a shrink. A clean
  finished run always wins, so neither a fresh cancel nor a fresh start shadows a good verdict —
  and nothing that acts on a verdict (the visa, auto re-run) uses anything but a finished chain.
- The verdict is **live**: while a newer RunAll is running (or ended cancelled), failures from its
  already-finished suites are folded in — the *● includes an unfinished run* tag links to that chain.
  An aborted chain shows a red *RunAll interrupted* banner (N suites failed, M never ran).
- **A suite re-run on its own counts too.** A suite re-run outside any RunAll — by the auto re-run
  or by hand — can fail a test the chain passed, and on a newer revision that is a real break. Its
  failures join the verdict and are classified like any other; only a run newer than the chain's
  own run of that suite counts. A re-run that broke (timeout, crash, compilation error) is a broken
  suite by the same rules as a chain's suite, and a suite that broke again on its re-run stays one
  broken suite, shown with its newest run.
- **Filtered out** — everything else, each with its reason (`pre-existing: fails 39/95 on master`,
  `passed on re-run`, …). Collapsed by default, so noise stays out of the way.
- **Muted failures are skipped, in every suite.** A failure TeamCity recorded as muted is never a
  candidate, whether its suite went red for another reason or stayed green, and it is left out of the
  test's branch strip (the test's passes stay in): TeamCity does not fail a build on a muted test, and
  someone muted it on purpose. TeamCity's own *Tests failed: N* leaves muted failures out the same way.
  A red suite whose only failures are muted went red for some other reason, such as a non-zero exit
  code, so it is listed under broken suites with that reason.
- **Fewer tests than master** — a suite that ran noticeably fewer tests than the same suite runs on
  master gets its own card (`ran 57 tests · master runs 439 — −87%`). Tests that never ran can't
  fail, so a suite can look green while silently skipping coverage. The baseline is master's own
  latest chain, not TeamCity's built-in metric — that one compares against a pinned reference build
  and false-alarms on PR branches long after a legitimate test-count change. This card is for the
  **silent** case only: when the suite is broken as well, the count rides along under its cause
  (`execution timeout — ran 33 of master's 67 tests`) instead of becoming a finding of its own —
  a hung suite runs a fraction of its tests, and leading with the metric reads as "tests
  disappeared" while hiding the timeout that caused it. And like a broken suite, a shrunk one
  clears once a newer run of it got the count back.
- **"No blockers" is earned, not automatic.** The green all-clear appears only when the run behind
  it actually covered the PR. If the RunAll was interrupted, if suites have no reliable result, if
  suites ran far fewer tests than master, if a newer run is still going, or if commits were pushed
  since — the verdict reads *no blockers found, but this run can't prove the PR is clean* and lists
  the reasons. Same wording everywhere: the page, the PR comment and the JIRA visa; in the PR list
  such a PR gets a **?** badge instead of a tick.
- **Broken suites** — a suite without a reliable run (compilation error, **execution timeout,
  out-of-memory, JVM crash**, failed dependency) is surfaced in its own red card instead of silently
  vanishing — even when it *does* have failed tests: those are hang cascade, and some tests never ran.
- Every test carries a **pass/fail strip** of its finished runs in its suite on the branch (oldest →
  newest). A run where the test was **ignored** gets no bar: nothing passed or failed in it, so it
  neither clears a failure nor counts as one. A fail→pass transition earns a **flaky?** tag; a steady
  `▮▮▮` means a solid break.
- **ai** (next to *why?*) — copies a **paste-ready fix prompt for a coding assistant**: the PR link
  and branch, the suite with its failed-run TC link, the full test name, the checker's verdict with
  the branch run history, the triage tag, the complete failure output, and concrete repro/fix steps.
  A root cause gets its own **ai** button covering the whole cluster (shared signature, every
  affected suite/test, one exemplar output — "find the ONE cause, don't patch tests one by one");
  the flaky board's **ai** builds a stabilisation prompt (fail-rate, noised PRs, typical instability
  checklist, prove-with-20-runs instruction). Suite-level problems have their own **ai** too: a
  broken suite never reaches a stacktrace, so its prompt carries what TeamCity said about the run,
  which of its tests were seen failing, and how to tell a PR breakage from a master one; a shrunk
  suite's prompt asks for the test-list diff against master and walks the usual causes (a class
  dropped from the JUnit suite, a rename, an `@Ignore`, a setup failure, a run that ended early).
- **why?** expands the failure message inline as a copyable code block, prefixed with a rough triage:
  `♻ environment/timing — a re-run may pass` vs `⚖ assertion — likely a real logic failure`.
- The blockers card has two views: **Suites** (default) and **Root causes** — the same blockers
  regrouped by failure signature, each cause a collapsible with its suites and tests inside.
  Hundreds of tests usually collapse into a handful of causes; a suite broken by two different
  things simply appears under both, as does a test that fails one way on Linux and another on Windows.
- `IGNITE-XXXXX` in the PR title links to the ASF JIRA issue.

## Iterating on a fix

- **vs previous run: +2 new · −3 fixed · 5 persisting** — the delta against the PR's previous RunAll
  (test names in the tooltips), next to a **trend sparkline**: one bar per run, red while blockers
  remain, green at zero. A blocker is a test in a suite: a test fixed on Linux counts as fixed there
  even while Windows still fails it.
- **Re-runs without leaving the page**: the whole `RunAll`, any **section** (broken suites, blockers,
  recently-started, filtered) or one suite — each *plain* or *at the top of the queue*. Live
  **queued / running** chips appear on the affected suites and in the `runs:` row, each carrying
  **both halves of its timing** — what it has already cost (`running 4m`, `queued 16m` — measured)
  and what is left (`~14m left`, `starts ~5m` — queue-aware, accounting for the agent queue and each
  suite's actual progress); a running build's tooltip adds how long it waited before starting. The
  analysed run states its own in the freshness line: `ran 1h 36m · queued 50m`. Chips also carry a
  **revision tag**: `rev ✓ head` for a run on the PR's current head, `⚠ rev abc123` when commits
  were pushed after it started (it tests older code), `rev @start` for queued builds — TeamCity
  resolves their revision at start, so they pick up the head of that moment. **Cancel my runs**
  cancels the runs you started; other people's runs keep going.
- **JIRA visa** — post the verdict to the PR's `IGNITE-XXXXX` ticket in the classic tcbot style:
  one click now, **Auto visa** (one-shot, fires when the current run finishes and posts the verdict of
  the finished run, not one cached mid-run), or the settings (⚙) option *Auto-visa all my runs* —
  every RunAll you trigger gets its verdict posted automatically (only runs finished after you switch
  it on). Like the PR comment, the visa is **one living
  comment per run**: it appears when the run finishes and is edited in place as re-run waves start
  and settle — but only on stage changes (ticket watchers get mail on every edit), never on the
  10-minute ETA refreshes.
- **Auto re-run blocker suites** (settings, independent of the visa) — a suite of your RunAll is
  re-run **the moment it fails**, without waiting for the rest of the chain: hours of suites are
  still ahead at that point, so the re-run rides alongside them and the answer is usually in before
  the chain finishes. A chain started from the checker or with `/run-all` is watched from its start;
  one started straight from TeamCity is picked up by the 10-minute sweep, and a suite that failed
  before that is re-run then. Only suites the analysis blames are touched — a blocker, a
  **recently-started-failing** test or a **broken suite** (timeout, crash, compilation) — so one
  that failed on master's own flakes is left alone; each suite is re-run once per chain, and a chain
  producing more than 10 of them is left to the settled pass as systemic. Whatever is still
  outstanding when the chain finishes is re-run the same way, up to 2 attempts in total: ≤10 suites jump to the top of the queue, more go to the tail so
  they don't push others back, and a systemic breakage (30+) is left alone. Identical suites already
  waiting in the queue are cancelled first. The suites to re-run, the visa and the PR comment always
  come from a verdict computed after the chain finished and after every re-run that has finished
  since: a verdict cached mid-run is recomputed first, so the suite that failed last still gets both
  attempts. A pass on re-run clears its blocker — and a broken suite
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
- **An expired token switches its own options off.** When GitHub or JIRA refuses a stored token, the
  checker drops it, turns off exactly the options that needed it, and the settings panel says which
  credential to replace — a switch that promises work the checker can no longer do is worse than an
  off switch. Your linked GitHub login is kept (it is an identifier, not a credential), so PR
  commands keep working, narrated from the checker's own account until you paste a fresh PAT.
- **GitHub PR comment** (settings, independent of the other two) — the same verdict, in GitHub
  markdown, posted on the `apache/ignite` pull request from your own GitHub account (a personal
  access token with the `public_repo` scope; stored encrypted at rest while the option is on).
  The whole run lives in **one comment**: it appears when the run finishes, and if auto re-run
  kicks in it **updates in place** (⏳ re-running → final verdict) instead of spawning new messages.
- **Pending changes** — if new commits were pushed to the PR after the analysed RunAll, a banner
  says so (**"⚠ N new commits pushed since this run (abc123 → def456) — the verdict is for the older
  code"**) with a **Run RunAll** button, so a stale verdict is never mistaken for the current one.
- The freshness line shows the run's **composition** — `suites: 6 fresh, 141 from earlier runs` —
  because a re-triggered chain on unchanged revisions reuses earlier suite builds (TeamCity
  substitutes suitable results).
- When your runs finish — the chain or any re-run of its suites, in whatever order they end — the
  analysis **refreshes itself**, no F5.

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
noising. A test that runs in several suites (the C++ tests run on Windows, Linux and Clang) gets a
row per suite, labelled with the suite name: each row shows that suite's master fail-rate and links
that suite's failed master runs, never another platform's. The tally is accumulated and persisted,
so it survives restarts and idle periods; a test drops off ~14 days after it stops failing. Public —
no login needed to read it.

## The status page (`/status.html`)

![Status page](img/status-page.svg)

Public service health: CPU/load/heap with **traffic-light** thresholds, the app's own endpoint
latency, TeamCity/GitHub call metrics by category with per-minute charts, and the **cache warmer**
block — whether it is warming right now (with live progress), how long the startup warm-up took,
and what the last cycle did. **Flush caches** (logged-in only) drops the analysis caches and
triggers a background re-warm.

The **health dot** next to the title follows recent problems only: yellow for an hour after the
service's last warning, red for six hours after its last error, green otherwise. Requests Spring
turns away as the caller's mistake (wrong HTTP method, missing or malformed parameter, unreadable
body, wrong content type) are listed greyed out under *Logs & errors* and never colour it. The
counts since start stay on the page as information.

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
  donates one, and any **standing option** other than PR commands alone keeps yours in the pool
  while it is on, which is what keeps the background work (warming, the instant re-analysis of a
  finished run, live run states) going while nobody has the page open.
