# Feature tour

**English** · [Русский](features.ru.md)

What Ignite PR Checker can do, screen by screen. The screenshots are of the production instance in the Dark theme; the
selector in the top bar has four themes: Light, Dark, JetBrains and Terminal.

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

- The left pane lists the repo's open PRs, the most recently updated first. Each has a badge with its last known
  verdict ([badges](#badges-in-the-pr-list)), and the legend under the filter repeats them. **My?** marks the PRs
  whose latest RunAll you started.
- The filter (**Filter, or a PR number**) narrows the list by number or title. A number that is not in the list is
  offered as **Open PR #N →**. `Enter` opens it or the first match, `Escape` clears the filter, and `/` jumps to it
  from anywhere on the page.
- Each PR in the list is a link (`?pr=13655`), so a middle click opens it in a new tab. Any PR number works in the
  address, also one that is not in the list.
- Drag the divider to resize the pane, double-click it to reset, and fold the pane with `‹` / `›`. On a screen
  narrower than 768 px the list folds away when a PR opens.

## Reading the verdict

![The PR page of #13655: 62 broken suites in three rows, then blockers that rest on one run each](img/pr-page.jpg)

The page answers one question: which tests did this PR break? The [cards](#cards-on-the-pr-page) go from what makes
the run unreliable (suites that never ran, broken suites, fewer tests than master) to the blockers, the tests to watch,
and the filtered-out noise.

- **The head** names the PR, links it on GitHub and its IGNITE ticket in JIRA, and has the RunAll buttons. The line
  under it starts with ↻, which recomputes the verdict now, then the analysed build with its TC link, when the run
  finished (amber when it is over a week old), when the verdict was computed, and what the run is made of:
  `suites: 147 fresh, 0 from earlier runs`.
- **Banners** under the head say when commits were pushed since the run, when auto re-runs still settle it, and, for a
  merged PR, that this is the verdict as it stood at the merge. A merged PR's verdict is kept and not recomputed:
  master's history now holds the PR's own runs.
- **Which run.** The checker analyses the PR's newest finished RunAll that was not cancelled; without one, the newest
  cancelled one; without that, the one still running. The finished suites of a running chain are real results, so a
  PR's first RunAll shows failures hours before it ends. While a newer RunAll goes, its finished suites are folded
  into the verdict, marked `● includes an unfinished run`. Such a verdict never reads green, and visas and PR comments
  wait for a finished chain.
- **Re-runs count.** A suite re-run on its own, by hand or by auto re-run, joins the verdict when it is newer than the
  chain's run of that suite. A pass on re-run clears a blocker; a broken suite whose full re-run did not break is
  judged by that run.
- **Each suite on its own.** One test can run in several suites (the C++ tests run on Windows, Linux and Clang). It is
  listed in each suite it failed in, with that suite's own verdict, so a pass on Windows does not hide a steady break
  on Linux.
- **The same code, the same JDK.** Every run is matched to the revision it built: a pass on older code does not clear
  a failure, and such runs are dimmed in the test's strip. Master runs are counted only on the JDK of the PR's run. A
  run where the test was ignored is not a run, and a failure TeamCity muted is never listed.
- **The strip** next to a test shows its finished runs in that suite on the branch, oldest to newest. A pass after a
  failure on the same code earns the `flaky?` tag.
- **Broken suites** are grouped by cause. A failed Build comes first, with the suites that need it and did not run.
  Then ci2's artifact glitch, then the rest by TeamCity's problem text. A group has one **Rerun** for all its suites;
  a suite that failed to compile gets none, since the same code fails the same way.
- **Blockers** have two views: **Suites**, and **Root causes**, the same blockers grouped by failure signature. The
  cause count in the header shows up once someone has opened Root causes for these blockers. Blockers whose failure
  message TeamCity no longer keeps are listed there apart, with a hint to re-run their suites.
- **This PR's tests** shows how the test classes the PR adds or changes ran: each class with its passed, failed and
  ignored tests and its longest test, and the tests that failed, ran over 60 s, or, in a changed class, have no master
  history.
- **No blockers 🎉** shows only for the verdict the PR list ticks: a run of the PR's current head that covered
  everything and blamed nothing on it. Otherwise the page says **No test blockers** and the red line under it lists
  the caveats, in the same words as the PR comment and the visa.
- **why?** opens the failure message and the stack trace, with a rough triage label: `⚖ assertion …`,
  `♻ environment/timing …` or `⌛ hang …`. Output over 32,000 characters is cut; the whole of it stays in TeamCity.
- **ai** copies a prompt for a coding assistant: the PR, the suite and its run, the test, the checker's verdict with
  its reason, the triage label, the failure output, and how to reproduce it on the commit the run tested, with steps
  for Java, .NET or C++ by suite. Everything quoted from the PR is marked as data, not instructions, and the prompt
  asks to run the PR's code in an isolated environment. Root causes, broken suites and suites with fewer tests than
  master have their own **ai** prompts.
- **The tab** shows how the run stands in its title and icon: `⏱ ~1h 05m left`, `♻️ re-run 1/2`, then
  `❌ 3 blockers`, `✅ no blockers` or `⚠ no test blockers`. **🔔 Notify me** sends one desktop notification when the
  verdict is final; the tab has to stay open.

## Iterating on a fix

- **vs previous run**: `vs previous run: +2 new · −3 fixed · 5 persisting`, the blockers compared with the PR's
  previous RunAll (test names in the tooltips), next to a trend: one bar per run, red while blockers remain. A blocker
  is a test in a suite, so a test fixed on Linux counts as fixed there while Windows still fails it.
- **Run** and **Run at top** queue a new RunAll for the PR, at the end or at the top of the ci2 queue. They always ask
  first and say how big the chain is. If your own RunAll of the PR is still going, OK cancels it and queues the new
  one; someone else's RunAll stays.
- **Rerun** and **Rerun at top** re-run one suite, every suite of a section, or a group of broken suites; a section's
  buttons say how many suites. From 5 suites on they ask first. Your own identical re-run still in the queue is
  replaced; someone else's stays.
- **The runs row** lists the PR's queued and running builds: how long each has waited or run, an estimate
  (`~14m left`, `starts ~5m`), a revision tag (`rev ✓ head`, `⚠ rev abc1234`, `rev @start`) and `by alice` for
  someone else's run. The same chips appear on the suites they re-run.
- **Cancel my runs** shows only while you have a run on the PR. It lists your runs, asks, and cancels only them.
- **Commits pushed since the run**: a banner says
  `⚠ 7 new commits pushed since this run (0013263 → b52666a) — the verdict is for the older code.`, with
  **Run RunAll** next to it.
- When your chain or a re-run finishes, the page shows the new verdict without a reload.

## Standing options

The ⚙ panel holds options that act while the page is closed, on the RunAll chains you start: on the page, with a
`/run-all` comment, or in TeamCity. An option does not reach back: runs that finished before it was switched on are
left alone. Each stores the tokens it needs, encrypted, while it is on.

- **Auto re-run failed suites on my runs** re-runs the suites the verdict blames: blockers, tests to watch and broken
  suites. A suite that fails while the chain still runs is re-run at once, at the top of the queue, up to 10 per
  chain. After the chain finishes, the suites still blamed are re-run: up to 2 waves per run, the mid-run re-runs
  counting as the first; up to 10 suites go to the top of the queue, more to the tail. With more than 30 such suites
  and no re-run yet, nothing is re-run: that looks systemic. Identical suites already in the queue are cancelled
  first. A failed Build is re-run alone; suites that failed to compile, and the suites a failed Build kept from
  running, are never re-run.
- **Auto-visa all my runs** posts the verdict to the IGNITE ticket the PR title names, once per run, after the
  re-runs settle. The title needs a key like `IGNITE-12345`, in any case: no key, no visa. No visa goes out while a
  newer RunAll of the PR is going, and none that repeats the last visa for the same revision. Needs your JIRA token.
- **Comment my runs' verdicts on the GitHub PR** posts the same verdict as a comment from your own GitHub account,
  once per run, after the re-runs settle. Needs a classic GitHub token with the `public_repo` scope.
- **PR commands** lets you start RunAll from a PR comment ([commands](#working-from-the-pr-commands)). It stores only
  your GitHub login and your TeamCity token; on its own, it lends the token to no background work. Linking your
  GitHub login switches it on.
- **Auto-fix checkstyle on my runs**: when you command a run on your own PR, the mechanically fixable violations in its
  changed files (imports, whitespace, modifier order…) are fixed and pushed before the run starts, as one commit from
  your account titled `Checkstyle autofix by Ignite PR Checker (requested via PR command)`. The command comment names
  the commit, reminds you to `git pull`, and lists what is left for a human (javadoc, naming, wrapping). It uses the
  same GitHub token and works on a PR with up to 50 changed Java files; a file over 400 KB, or past 5 MB in all, is
  not checked. It never touches anyone else's PR and never force-pushes.
- **A token that stops working.** When GitHub or JIRA refuses a stored token, the checker drops it and switches off
  the options that need it; the panel says which token to replace. When TeamCity refuses yours, your options pause
  until you log in with a fresh token; nobody else's stop.

## Visas and PR comments

- **JIRA visa**, over the blockers, posts the verdict to the IGNITE ticket now. **Auto visa** posts the verdict of the
  run under way to the ticket when it finishes, once. Each user arms their own, and several users armed on one ticket
  get one visa between them. When the run's starter has **Auto-visa all my runs** on, the button reads
  **Auto visa: on in ⚙**.
- The JIRA token for these buttons rides in your session cookie; Auto visa also has the server store it, encrypted,
  until the visa is posted.
- A visa and a PR comment say the same. The head line names the RunAll build, the commit it tested, and how many
  suites ran and how many were reused. Broken suites come grouped by cause.
- The lists of blockers, tests to watch and unchecked tests are grouped by suite and class, the biggest group first.
  Each line has the test's tags (`1 run`, `new test / no master history`, `unverified`) and a TC link, and a line
  under the lists explains the tags. The PR comment shows 5 groups and folds the rest; the visa shows 10.
- A verdict that can't call the PR clean lists the caveats. A verdict of code that was pushed over says which revision
  it tested and what the PR head is now. A red PR comment ends with a **Next:** line: fix, push, and `/run-all`.
- Every verdict ends with a link to the [verdict glossary](#verdict-glossary).
- Once a newer RunAll of the PR has finished, each older verdict comment in the PR gets a last line saying it is
  superseded and where the newer verdict is.

## Working from the PR (commands)

The whole cycle, from the start to the verdict, can happen in the pull request. Switch on **PR commands** in ⚙ and
link your GitHub login; a GitHub token of your own is optional.

| Command | What it does |
|---|---|
| `/run-all` (also `/runall`) | Queues the whole RunAll chain under your own TeamCity account. |
| `/run-all top` (also `--top`) | The same, at the top of the ci2 queue. |
| `/top` | Moves the RunAll your `/run-all` started on this PR to the top of the queue, while it still waits. |

- The command has to be the first word of the comment. Commands are picked up within a minute, on any pull request.
  A comment written more than an hour ago is not run, even if it is edited later.
- 🚀 means accepted, 😕 refused. The details are edited into your command comment: the queued build, then
  `⏱ Queued — expected to finish ≈ 21:05 MSK` in the time zone of your JIRA profile, UTC without one. The comment is
  edited when the stage changes or the estimate moves by 10 minutes or more.
- When the checker holds your GitHub token (for the PR comment or the autofix), the reactions and the story come
  from your own GitHub account; otherwise from the app account.
- After the chain finishes, the story shows the re-run waves (`♻️ Auto re-run #2 — 3 broken suite(s), ≈ settled by 22:21`)
  and ends with `🏁 Run finished — see the verdict`, a link to your verdict comment or to the checker's page. If the
  app account narrates for you, it also mentions you in a new comment when the verdict is ready.
- A command that is not the first word ("Please /run-all") gets 😕 and a hint saying how to write it. A refused
  `/top` or a `/run-all` that failed says why, in words.
- A new `/run-all` supersedes your previous one: your queued or running chain on the PR and its re-runs are cancelled
  first, nobody else's. The old story ends with `🛑 Superseded by a newer /run-all`, and the ack says what was
  cancelled.
- A command from someone whose PR commands are off gets 😕 and a short reply from the app account on how to switch
  them on: once per person, once per PR, at most 5 replies a day.

## The fix-master queue (`/flaky.html`)

![The flaky board: tests ranked by their master fail rate, each with its failed master runs](img/flaky-board.jpg)

- Tests the checker filters out because they fail on master: not any one PR's fault, yet they fail in PRs that did
  not break them. **Flaky on master** comes first: tests that fail some master runs and pass others. **Broken on
  master** follows: tests that failed each of their newest 10 master runs, or every run when there were fewer.
- Each group is ranked by master fail rate, then by how many PRs the test failed in during the last 14 days, and shows
  40 rows at most.
- A test that runs in several suites gets a row per suite, with that suite's fail rate and its failed master runs.
- Tests muted on TeamCity are left out, and the intro says how many.
- The tally survives restarts; a test drops off 14 days after it was last seen failing.
- Anyone can read the board; **why?** needs a login. **ai** builds a prompt to stabilise a flaky test, or to find the
  commit that broke a broken one. A suite being re-run shows its chip: the PR, the state and the estimate.

## The status page (`/status.html`)

![The status page: version and commit, CPU, heap, process memory, snapshots, requests per endpoint](img/status-page.jpg)

- Anyone can see the version and its commit, uptime, CPU and load, the heap with its old-generation peak, the
  process's memory, the snapshots, the latency of each endpoint, the cache warmer, the build watcher, and the
  TeamCity, GitHub and JIRA calls by category over the last hour.
- The health dot next to the title turns red for 6 hours after an error and amber for an hour after a warning. It
  also turns red or amber when a background job stops (the standing sweep, the PR command poll, the warm cycle), a
  state file could not be read, or a setting is out of range; such problems are listed at the top. Requests Spring
  turns away as the caller's mistake are listed greyed out and never colour it.
- The GitHub section shows the app account: whose `GITHUB_TOKEN` it is, with a warning if that account can push to
  the repo.
- Signed-in viewers also see the log messages, the settings in effect, and who restarted or flushed last. Those who
  may operate the service (`PRC_ADMINS`, or anyone logged in when it is not set) also get **Restart service**,
  **Flush caches** and the list on the **Users** tab.

## Everything else

- **Update**: when a newer release is out, the top bar shows **Update to vX.Y.Z** and a **what's new** link to its
  notes. After a deploy, open tabs show **UI updated — reload**.
- **Your own login**: everyone logs in with their own TeamCity token, kept encrypted in an HttpOnly cookie. There is
  no server-side session store and no shared TeamCity account. A token TeamCity has revoked sends you back to the
  login form.
- **Four themes**: Light, Dark, JetBrains (dense, with status stripes) and Terminal (monospace, bracket buttons). The
  choice is kept per browser, with no flash on load.
- **Warming**: the heavy lifting is cached and pre-warmed in the background, so opening a PR is usually instant. There
  is no service account: warming runs on real users' TeamCity tokens. Every logged-in request donates one, and any
  **standing option** other than PR commands alone keeps yours in the pool while it is on, which keeps the background
  work (warming, the re-analysis of a finished run, live run states) going while nobody has the page open.
