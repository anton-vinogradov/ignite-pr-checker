# Feature tour

**English** · [Русский](features.ru.md)

What Ignite PR Checker can do, screen by screen. The screenshots use the Dark theme; the selector in the top bar
has four themes: Light, Dark, JetBrains and Terminal.

## Verdict glossary

Every label the checker puts on a PR, a suite or a test: what it means and what to do. The **?** buttons in verdict
cards explain the result on the page. Hover for a tooltip, or press the button to read it above the verdict.
The PR comment and the JIRA visa link here. The numbers are the defaults in the code; see
[Thresholds](#thresholds).

### Cards on the PR page

| Card or heading | What it means | What to do |
|---|---|---|
| **Blockers** | Tests this PR most likely broke: they fail in its runs, and neither master nor other PRs explain them. Each suite is judged on its own. The line under a test says why ([reasons](#reasons-under-a-test)). When tests of a suite have the same reason, the line stands once above them. | Open **failure ▸** or **TC**, fix the code, push, run RunAll again. |
| **Not confirmed: 1 run** | Blockers that failed the only time they ran on this branch. They still count as blockers: in the count, the PR list badge, the visa and the comment. The heading says that each of these tests failed its only run on this branch. So their rows leave out `failed the only run on this branch`, the `1 run` tag and a strip of one bar. If every blocker failed its only run on this branch, the line **Not confirmed yet** above the Blockers list says so, and this group is not shown. | **Rerun** their suites. A second failure confirms the blocker, a pass drops it. |
| **Recently started failing** | Tests failing on the code this run tested, with too few runs of it to tell a break from a flake. Not blockers yet. | **Rerun** the suite. If it keeps failing, it becomes a blocker; if it passes, it drops out. |
| **Could not be checked** | Failed tests that TeamCity errors kept from being compared with master and the branch. Not counted as blockers. | Nothing: the checker tries them again on its own. |
| **Run in progress — waiting for results** | The first run is still going; its empty blocker list is not a completed verdict. Partial results remain on the page. | Wait for the run's results. The activity line says whether automatic re-runs are still pending. |
| **Broken suites** | Suites with no reliable result ([causes](#broken-suite-causes)). A timed-out or crashed suite is listed here, and the checker does not look for blockers in it. Its failures would come from the timeout or crash, and some tests never ran. | Check the latest master result shown beside each suite before rerunning. The verdict can't see their tests. A suite that failed to compile has no **Rerun**: it needs a code fix. |
| **Fewer tests than master** | Suites that ran at least 10% fewer tests than the same suite on master; only suites with 20 or more tests on master are compared. Tests that never ran can't fail. | If the PR removes tests on purpose, nothing. Otherwise compare the test list with master and re-run. |
| **Crashed near the end of the run** | Suites that hit a timeout, out-of-memory error or JVM crash after running over 90% of master's tests, with no failure blamed on the PR. Their results stand. Shown folded, with the count in its heading. | Nothing for the PR. |
| **⚠ Suites that never ran** | The RunAll was interrupted: these suites were cancelled and never ran. The verdict says nothing about them. Suites a person cancelled are folded into one line per person. | **Rerun** them, or run RunAll again. |
| **This PR's tests** | How the test classes the PR adds (`new`) or changes (`changed`) ran in this RunAll. Each class lists its tests that failed or ran longer than 60 s. The **all N tests** button opens every test of the class with its status and time. A test's name opens its run in TeamCity; a suite's name opens the suite's run. The line under a failed test says what the verdict did with it: `blocks this PR`, `recently started failing: …`, `could not be checked` or `not blamed: …`. Each links to the test's row in the matching card: **Blockers**, **Recently started failing**, **Could not be checked** or **Not blamed on this PR**. The verdict does not judge a test of a suite that timed out or crashed (`not checked: its suite broke`). It also does not judge a failure that TeamCity muted (`not checked: TeamCity muted it`). A failure the verdict has not judged reads `not checked yet` while the verdict can still change: a RunAll is still going, or the checker tries again after TeamCity errors. In a final verdict and in a merged PR's verdict, it reads `not checked`. The class line counts what the verdict did with its failures, for example `3 failed (none blamed on this PR)` or `2 failed (2 not checked yet)`. A class that Ignite's own abandoned-tests check finds in no test suite is tagged `not in any suite`. A class with no runs says why, when Ignite's abandoned-tests check and the files changed since the run show the reason: a commit after the run added it or put it in a suite, its suite did not run or broke, or it is a base or `@Ignore` class. When they show no reason, a note above the list names the possible reasons once for all such classes. The abandoned-tests check may still be running or be waiting for a maintainer to approve its run (a push by someone outside the project). It may also be skipped, or its result can't be read. Then a note above the list says so and how many classes did not run, if any. The card does not say why these classes did not run: without the check, nobody can tell. | Check that the new tests ran and passed. Fix a failed test that `blocks this PR`. Add a `not in any suite` class to a test suite or mark it `@Ignore`. |
| **Not blamed on this PR** | Failures not blamed on the PR, each with its reason: they fail on master too, are flaky, or passed in a later run. Shown folded. | Nothing for this PR. Re-run only to double-check the flaky ones. Tests that fail on master are on the flaky board (`/flaky.html`). |
| **✓ No blockers** | The run covered the PR's current code and blamed nothing on it. The PR list shows **✓**. | You can merge the PR. |
| **No proven blockers yet** | No test was blamed, but tests started failing on the code this run tested: they are under **Recently started failing**. The PR list shows **!**. | **Rerun** their suites: a re-run shows whether these tests are blockers. |
| **No blockers found — not proven clean** | No test was blamed, but the verdict can't call the PR clean. The amber lines under the heading say why ([caveats](#caveats)). The PR list shows **?**. While the page still checks that the run tested the PR's latest commit, the heading reads **No blockers found**. | Handle what the lines list, then re-run. |

### Reasons under a test

A reason has a master part and a branch part, joined by `;`. `N`, `F/R` and `abc1234` stand for numbers and a
commit. `on JDK 17` means only master runs on the PR's JDK were counted. A muted failure is never listed, and a
run where the test was ignored does not count as a run.

| Reason | What it means | What to do |
|---|---|---|
| `not seen failing in N master runs` | Master ran the test N times in this suite (its last 100 runs at most) and it never failed. | Read the branch part. |
| `not seen failing in only N master runs on JDK 17` | The same, with fewer than 10 master runs on the PR's JDK: thin evidence. | Read the branch part. |
| `no master history (can't prove pre-existing)` | Master has no run of the test on the PR's JDK: a new test, or one master does not run. Tagged `new test / no master history`. | Make sure the test is stable: this PR is all there is to judge it by. |
| `rare on master: fails F/R, passed the last G` | Master fails the test in at most 2% of its runs, none of the newest 10, and the PR's failures in a row are too many to be that chance. | Treat it as a blocker. |
| `fails F/R on master at TEST_SCALE_FACTOR=1.0, but f/r on other PR branches at 0.1` | Master fails the test in at least half its runs, but only at a scale factor this PR did not run at. Other PRs, run like this one, seldom fail it, and this PR's failures in a row are too many for their rate. | Treat it as a blocker. |
| `…; fails F/R in K other PRs` (on a blocker) | It fails in 3 or more other PRs too, but this PR's failures in a row are too many to be that flakiness. | Treat it as a blocker. |
| `failed all N runs on this branch`, `failed the last N of M runs on this branch` | The last 3 finished runs of the suite on the branch failed the test, or all of them when there were fewer. A blocker. | Fix the code. |
| `failed the only run on this branch` | The test ran once on the branch and failed. A blocker that is not confirmed yet. The line under the test leaves this part out, because **Not confirmed: 1 run** or **Not confirmed yet** above the test already says so. | **Rerun** the suite to confirm it. |
| `failed all N runs on commit abc1234` | Every run of the PR's current code failed the test, and there were at least 2. A blocker. | Fix the code. |
| `failed the only run on commit abc1234; the N earlier runs were on other code` | The current code ran once and failed; the earlier runs were on older code. Listed under **Recently started failing**. | **Rerun** the suite. |
| `failed the latest run; TeamCity gave no commit for it, so the N earlier runs do not count` | The latest run failed, and TeamCity gave no commit for it. The earlier runs may have tested other code, so only the latest run counts. Listed under **Recently started failing**. | **Rerun** the suite. |
| `failed the last N of M runs on commit abc1234, after a pass on the same commit` | At least 2 failures in a row on the current code, after a pass on the same code. Listed under **Recently started failing**. | **Rerun** the suite. |
| `…; too few failures on this code yet to outweigh other PRs` | The scale factor case above, without enough failures yet to beat the other PRs' rate. Listed under **Recently started failing**. | **Rerun** the suite. |
| `pre-existing: fails F/R on master` | Master fails the test too. Listed under **Not blamed on this PR**. | Nothing for the PR. |
| `flaky on other PR branches: fails F/R in K other PRs; …` | It fails in 3 or more other PRs, and this PR's failures are not enough to outweigh that. Listed under **Not blamed on this PR**. | Nothing for the PR. |
| `flaky on branch: failed only the latest of N runs on commit abc1234 (an earlier run on the same code passed)` | It failed once after passing on the same code. Listed under **Not blamed on this PR**. | Nothing, or re-run to be sure. |
| `not failing in the last finished run (passed on re-run)` | The newest finished run of the test on the branch passed. Listed under **Not blamed on this PR**. | Nothing. |
| `could not verify (TeamCity error: …)` | A TeamCity error stopped the check. Listed under **Could not be checked**. | Nothing: it is tried again. |
| `the N earlier runs were on other code` | Runs on an older commit don't count; they are dimmed in the strip. | — |
| `passed just before, but TeamCity gave no commits to prove that was the same code`, `after a pass; TeamCity gave no commits to prove that was the same code` | TeamCity gave no commit for any run of the test on the branch, so the pass may have been on other code. | — |
| `revision`, `run(s)`, `— watch` in a merged PR's verdict | A merged PR keeps the verdict it had at the merge, in the words of the checker release that computed it. | — |

### Caveats

The amber lines under the verdict heading, and the list under "This run doesn't cover the PR fully" in the comment
and the visa.

| Caveat | What it means | What to do |
|---|---|---|
| `Build failed — N suites that need it did not run` | A run the other suites need failed, so they never ran. | Fix the build, push, run RunAll again. |
| `the RunAll was interrupted — N suites never ran` | Suites were cancelled before they ran. | **Rerun** them, or run RunAll again. |
| `N suites have no reliable result (…)` | Broken suites; their causes are in brackets. | See [broken suite causes](#broken-suite-causes). |
| `N suites ran fewer tests than the same suites on master` | See **Fewer tests than master**. | Check the test lists, re-run. |
| `N failed tests could not be checked (TeamCity errors)` | See **Could not be checked**. The visa, the PR comment and the auto re-run wait up to 20 minutes for a retry; then they go ahead and list those tests apart. | Wait. |
| `a newer run is still going — its unfinished suites can still fail` | The verdict folds in a RunAll that is still running. | Wait for it to finish. |
| `N commits pushed since this run — it tested older code`, `new commits pushed since this run — it tested older code` | The PR's head has moved since the run. The comment and the visa use these words; the page says `it tested older code (see the note at the top)`, and the line at the top names both commits, with their count once GitHub answers. | Run RunAll again. |
| `mixed run — Cache 2 re-ran on abc1234, the rest of the RunAll ran on def5678; only a RunAll of the PR's head can prove it clean` | A mixed run: the verdict counts runs of some suites on another commit than the rest of the RunAll, so no single commit is checked in full. The comment and the visa use these words instead of the caveat about commits pushed since. The page says `it is a mixed run (see the note at the top)`. The line at the top says which commit is the PR's head. | Run RunAll on the PR's head. |
| `could not check whether the PR changed since this run` | On the page only: GitHub or ci2 did not say whether the PR's head is still the commit the run tested, so the page does not call the verdict clean. The same can happen while fewer than a fifth of the checker's hourly GitHub calls are left: the page then does not ask GitHub about it. | Reload the page in a minute. If the **GitHub API** card on the status page shows fewer than a fifth of the calls left, wait until GitHub resets the hourly limit. |

### Broken suite causes

For each suite with its own failure, the card shows its latest completed master run, finish time and **TC** link.
Suites kept from running by a failed dependency are listed under that failure; the comparison belongs to the failed
dependency. The comparison is fetched when you open or refresh the verdict. **Same reported problems** means TeamCity reported exactly the same nonempty
set of problem types. It does not prove a shared cause or give the PR a reliable test result. A passing master run,
missing history and a failed lookup are shown separately.

| Cause | What it means | What to do |
|---|---|---|
| `compilation error` | The suite's code did not compile. The page offers no **Rerun** for it: the same code fails the same way. | Fix the build, push, run RunAll again. |
| `execution timeout` | The suite ran out of time. Its failures are likely hang cascade, and some tests never ran. | Re-run. If it times out again, look at the thread dump in TeamCity. |
| `JVM crash / out of memory` | The JVM died mid-run. | Re-run. If it repeats, look for the crash or the leak in the build log. |
| `non-zero exit code` | The build exited with an error, and no unmuted test failed. | Open **TC** to see what failed. |
| `failed dependency` | A run this suite depends on failed. | Fix that run first. |
| `failed without running tests` | TeamCity named no problem, and no test failed. | Open **TC**, re-run. |
| `ci2 glitch: artifacts unavailable (N suites)` | ci2 could not hand these suites the artifacts of a run they need, though that run passed. | Re-run them: a new run usually gets them. |
| `Build failed — N suites that need it did not run; fix the build, then /run-all` | The build step failed, and the suites that need it never ran. `nothing else ran` when that was the whole chain. | Fix the build, push, `/run-all` or **Run**. |
| `Build failed in this run and passed on a re-run since — N suites that need it never ran; /run-all to run them` | The build passes now, but the suites that need it have no result. | `/run-all` or **Run**. |
| `— ran 33 of master's 67 tests` (after a cause), `ran 33 of master's 67 tests` (alone), `ran fewer tests than on master` (a group) | How far the broken suite got. The cause explains the shortfall; it is not a missing-tests finding of its own. When TeamCity reported nothing but its own count of this shortfall, the line says only how far the suite got. Several such suites with different counts share the line `ran fewer tests than on master`, and each suite keeps its own count. When TeamCity's count has other numbers, the line keeps it: TeamCity compared with a run it chose itself. | — |
| anything else | The first line of TeamCity's own problem text. | Open **TC**. |

### Tags next to a test

| Tag | What it means | What to do |
|---|---|---|
| strip of bars | The finished runs of the test in its suite on the branch, oldest to newest: red failed, green passed. Dimmed bars ran on older code; the bright ones ran on the code of the newest run. Under **Not confirmed: 1 run** and **Not confirmed yet**, the page leaves out a strip of one bar: these already say that each test there failed its only run on this branch. | — |
| `flaky?` | The test failed and then passed on the same code. | Look for a race or a timing problem. |
| `1 run` | The test ran once on this code and failed. The tag is not shown under **Not confirmed: 1 run** and **Not confirmed yet**. Both say that each test there failed its only run on this branch. | **Rerun** the suite: it confirms or clears it. |
| `new test / no master history` | Master has no runs of the test on this JDK to compare with: a new test, or one master does not run. | Make sure the test is stable. |
| `not in any suite` (on a test class) | Ignite's own check (GitHub Actions job Check java code, step "Run abandoned tests checks.") finds the class in no test suite, so CI never runs it. Shown in **This PR's tests**; the PR comment and the visa say it too, and the verdict stays as it is. | Add it to a test suite or mark it `@Ignore`, or switch on **Add my PR's tests that no suite runs** in ⚙. |
| `unverified` | A TeamCity error kept part of the check from being made: other PRs' runs of the test. The checker tries again. | Wait. |
| `⚖ assertion — likely a real logic failure` (in **failure ▸**) | A failed check. | Fix the code. |
| `environment/timing — a re-run may pass` | Environment or timing trouble. | Re-run. |
| `environment/timing — but it failed all N runs of this code, …` | The same, but every run of this code failed. | A re-run alone is unlikely to pass: fix it. |
| `⌛ hang — the test ran out of time; …` | The test hung. The test's thread from the thread dump is shown. | See where that thread waits. |
| **failure ▸** / **copy failure** / **copy AI prompt** | **failure ▸** opens a panel with the failure message and the stack trace. In the panel, **copy failure** copies the message and **copy AI prompt** copies a fix prompt for a coding assistant. If there is no failure message to open, **copy AI prompt** is on the row itself. | — |

### Badges in the PR list

| Badge | What it means | What to do |
|---|---|---|
| red number | Blockers in the PR's latest analysed RunAll. | Open the PR. |
| **!** | No blockers yet, but tests started failing on the code the run tested. | A re-run shows whether these tests are blockers. |
| **?** | No blockers found, but the PR is not proven clean, for one or more of these reasons. The run can't prove it ([caveats](#caveats)): it was interrupted, suites broke or ran fewer tests than on master, failed tests could not be checked, suites re-ran on other commits, or a newer run is still going. Commits were pushed after the run. It is not known whether the run tested the PR's latest commit. | Open the PR for the reason. |
| **✓** | A clean run of the PR's current code. | — |
| **…** | Only for the PR open on the page: no blockers found, and the page is checking that the run tested the PR's latest commit. Once it knows, the badge turns into **✓** or **?**. | Wait. |
| none | No verdict yet. | Open the PR to analyse it. |
| `2/4 · ~55m` after a small ring | A run of the PR is under way. The first number is the step the run is at, of the four the PR's page shows: `Queue`, `RunAll`, `Auto re-run` and `Final verdict`. The time is how long that step still takes by TeamCity's estimate; it is left out when there is no estimate. The tooltip names the step. The checker tells this from the builds it already follows and asks TeamCity nothing more for the list. So a run it has not seen yet shows nothing, and neither does a suite someone re-runs by hand. A thin client PR shows nothing either: its run is one suite. A **?** is left out while the run goes: the step already says that the PR is not proven clean yet. | Wait, or open the PR. |
| `outdated` | Stands after **?** when commits were pushed after the run. While a run of the PR is under way, the row shows that run's step instead. | Open the PR and run RunAll again. |
| `by me`, `by alice` | Who started the PR's latest RunAll, or for a thin client PR, its latest run of the suite. It stands at the right end of the row's first line, and only where the tab does not say it already. On **All** and **Involved**, `by me` marks a run you started, and a run someone else started is not marked. On **Mine**, the name marks a run someone else started, and your own run is not marked. The PR may be someone else's. | — |

### Runs, buttons and visas

| Label | What it means | What to do |
|---|---|---|
| `Merged`, `Closed` | The PR was merged, or closed without a merge. The tag stands next to the PR's number, and its tooltip says when and at which commit. The checker keeps a merged Ignite PR's verdict if it computed that verdict before the merge or within the hour after it. Then `Merged` has a **?** that opens this row. The checker does not compute a kept verdict again. After the merge, master's history holds the PR's own runs. So a new verdict would take the PR's failures for failures of master. New runs do not change it, so no button is filled and no line says that a re-run would change it. Runs queued before the merge that this verdict did not count are the one exception: they were still going at the merge, or ended after the verdict was computed. When the last of them ends, the checker computes the verdict once more. That verdict counts only the PR's runs queued before the merge, and runs of master and other PRs that finished before the merge. When some of the PR's runs tested a commit other than the RunAll's, the `⚠ Mixed run` line names them. The checker keeps no verdict of other merged PRs, of thin client PRs or of PRs closed without a merge: it computes their verdict as for an open PR. | — |
| `queued`, `running` | A build of this PR on TeamCity now: among the builds a step opens, or in the **On TeamCity** row of a PR that has no steps. The time next to it is how long it has waited or run so far; `~14m left` and `starts ~5m` are estimates from the queue and the suite's progress. | — |
| `on latest commit` | The run tests the PR's latest commit. | — |
| `⚠ old commit abc1234` | Commits were pushed after the run started: it tests older code. | Run RunAll again when it matters. |
| `takes latest commit at start` | The run is still queued; TeamCity picks the PR's latest commit when it starts. | — |
| `by alice` | Someone else started this run. **Cancel my runs** leaves it alone. | — |
| `● includes an unfinished run` | The verdict folds in the finished suites of a RunAll that is still running. It never reads green. | Wait for the run. |
| `6 suites ran, 141 reused from earlier runs` | How many suites of the chain ran, and how many TeamCity reused from earlier runs of the same commit. Shown when some were reused, or while the run still goes. | — |
| `Blockers since the previous RunAll: 2 new · 3 dropped out · 5 still blocking` | Blockers compared with the PR's previous RunAll, suite by suite. `dropped out`: no longer a blocker, because it passed, moved to another list or did not run. Zero new reads `no new`. A zero `dropped out` or `still blocking` is left out. With all three zero, there is no line. From three RunAlls on, a bar per run stands beside it. | — |
| `⚠ The verdict is for older code` | The run tested an older commit than the PR's head; the line names both commits. While a new RunAll is queued or running, it says so instead of offering the button. On a merged PR the line only names the commit the run tested and the commit the PR was merged at, with no button. | **Run RunAll**. |
| `⚠ Mixed run` | The verdict counts runs of some suites on another commit than the rest of the RunAll. The line says which suites re-ran on which commit, which commit is the PR's head, and that only a RunAll of the PR's head can prove it clean. It stands in place of `⚠ The verdict is for older code`. While a new RunAll is queued or running, it says so instead of offering the button. On a merged PR it names the commit the PR was merged at, with no button. | **Run RunAll**. |
| `Auto re-run`: `wave 1 of 2`, `deciding` | The step of auto re-runs is under way. Auto re-runs are still settling this run: the verdict may change. A client's PR has no steps, and its status line says `♻︎ Auto re-run 1/1` or `The checker is processing the results` instead. | Wait. **🔔 Notify me** tells you when it is final. |
| **Run**, **Run at top** | Queue a new RunAll chain for the PR, at the end or at the top of the ci2 queue. Always asks first. | — |
| **Run anyway** | **Run**, **Run at top** and **Run RunAll** queue nothing on a PR that GitHub reports merge conflicts for. The status line says so, and this button appears next to it. It asks first, then queues that RunAll anyway. | Resolve the conflicts and run RunAll again. |
| **Rerun**, **Rerun at top** | Re-run a suite, or every suite of a section: the section's button says how many suites, and a suite row has its own pair of small text buttons. A pair that would re-run the same suites as the pair above it is left out. A suite the checker is re-running right now, in a wave of auto re-runs, has no pair until the wave ends, and neither has a section while the checker re-runs all of its suites: a manual re-run would only replace the automatic one. One button is filled as the next step: **Run** for a PR with no run to analyse and none queued or running, **Run RunAll** for older code, else the broken or never-ran suites, else the blockers on one run. On a merged PR no button is filled. Five or more suites ask first. A line under the buttons says why, when the suites are not queued or are queued at the end, not at the top. | — |
| **Cancel my runs** | Cancels the builds you started on this PR. | — |
| `Verdict to JIRA:`, `to PR:` | Two groups at the end of the verdict card say where the verdict of this run stands: in the IGNITE ticket the PR title names, as the run's visa, and in the PR, as the checker's verdict comment. A group shows a state in a word, a button, or both: `✓ posted`, `posted, verdict changed`, `auto when final`, `retrying`, `waits`, **Post**, **Update**. The **?** of a group says the rest, as a tooltip. A click on the **?** puts that text into a line under the groups, where a keyboard and a phone reach it, and a second click takes the line away. Where the text names Settings, the word opens ⚙. The rows below that begin with `Visa:` quote what the **?** of the first group says. For a PR that is not in the left pane, the first group appears once the page gets the PR's title. | — |
| `✓ posted` | The visa is in the ticket, or the verdict comment is in the PR, and it says what the verdict says now: the group has no button. The mark links to the comment when the checker knows where it is. Next to the mark stands **Post** when the visa in the ticket is someone else's, and a press adds yours. **Update** stands there when the checker cannot tell whether the comment is up to date: it kept the comment without what its verdict says, as v1.52.0 and before did, or it holds no verdict of the run at the moment. | — |
| `posted, verdict changed` | The comment a press would edit is in, and the verdict of the run changed after it: it blames other tests, or has other caveats. The checker compares what the verdict blames and its caveats with what it kept of the comment. Commits pushed after the run do not make the group say so. | Press **Update** to put the verdict as it is now into the comment. |
| `auto when final` | An option of whoever started the run posts it once the run and its auto re-runs settle: **Visa my runs in JIRA** for the ticket, **Post my runs' verdicts in the PR** for the PR. Until the verdict is final, the group has no button. The **?** says whose option it is. On a thin client's PR the groups never show this state: there the button waits for the final verdict. | Wait. |
| `retrying`, `waits` | The visa of your run is still to come, and something holds it. `retrying`: JIRA did not take it. `waits`: it waits for your login or for your JIRA token. The **?** says which, in one of the `Visa:` texts below. | See the row of that text. |
| `Visa: posted to IGNITE-12345` | The visa of this run is in the ticket, and the group says `✓ posted`. The key links to the comment. | — |
| `Visa: will be posted to IGNITE-12345 when the run settles` | You started the run, and your **Visa my runs in JIRA** is on. The visa goes out once the run and its auto re-runs settle. Until the verdict is final, the group says `auto when final`. | Wait. |
| `Visa: JIRA did not take it, the checker tries again every 10 minutes` | You started the run. It settled, but JIRA did not take the visa: the group says `retrying`. The checker keeps trying while Visa my runs in JIRA stays on and no newer RunAll replaces the run. | Wait, or press **Post to JIRA**. |
| `Visa: bob, who started this run, has Visa my runs in JIRA on` | Only the option of whoever started a run posts its automatic visa, and bob's is on. The **?** says nothing more about another person's option. Until the verdict is final, the group says `auto when final`. | — |
| `Visa: Visa my runs in JIRA is off in Settings` | You started this run, and your option is off. When the text ends with `because JIRA rejected your token`, JIRA refused your stored token, and that switched the option off. | Switch it on in ⚙ with your JIRA token. Then press **Post to JIRA** to post the visa of this run. |
| `Visa: none, bob started this run and has Visa my runs in JIRA off` | Only the option of whoever started a run posts its automatic visa, and bob's is off. | Press **Post to JIRA** to post it yourself, or start a RunAll yourself with the option on. |
| `Visa: waits until you log in again` | You started the run, and TeamCity refused the token your options use, so they pause. The group says `waits`. The visa goes out after a login with a fresh token, unless a newer RunAll replaced the run. | Log in again. |
| `Visa: waits until you save your JIRA token in Settings again` | You started the run, and the checker's state was restored from a backup older than 2 days, which keeps no tokens. Your option stays on and waits for your JIRA token: the group says `waits`. The visa goes out once you save it, unless a newer RunAll replaced the run. | Save your JIRA token in ⚙. |
| `Visa: waits for a new JIRA token in Settings` | You started the run, and the checker could not read the JIRA token your Visa my runs in JIRA stored: the server key was changed, or the token got damaged. The option stays on, and the group says `waits`. The visa goes out once you save a new token, unless a newer RunAll replaced the run. | Save a new JIRA token in ⚙. |
| `Visa: none, this run finished before you switched on Visa my runs in JIRA` | The option acts only on runs that finish after it goes on. | Press **Post to JIRA**, or run RunAll again. |
| `Visa: none, the PR title named no IGNITE ticket when the run settled` | Adding the key to the title after the run settled does not post this run's visa. | Press **Post to JIRA**, or run RunAll again. |
| `Visa: none, this run was cancelled` | A cancelled run gets no visa. The page shows a cancelled run only when every finished run of the PR was cancelled. | Run RunAll again. |
| `Visa: none for this run, a newer RunAll replaces it` | The newer RunAll of the PR gets the visa instead. | — |
| `Visa: none, the PR is merged` | The PR was merged before the visa of this run went out. | To post it now, press **Post to JIRA**. |
| `Visa: none, the PR is closed` | The PR was closed without a merge before the visa of this run went out. | To post it now, press **Post to JIRA**. |
| `Visa: unknown, TeamCity does not say who started this run` | Only the starter's option posts an automatic visa, and TeamCity named no starter. | To post it yourself, press **Post to JIRA**. |
| `The PR title names no IGNITE ticket` | What the **?** of the first group says next to a grey **Post**. A visa goes to the IGNITE ticket the PR title names. The key may be in any case. | Put the key in the PR title. |
| **Post to JIRA** | The button of the `Verdict to JIRA:` group: **Post**, or **Update** once the verdict changed after your visa of the run went into the ticket. It posts the visa of the run on the page now, to the IGNITE ticket the PR title names, as your comment in JIRA. It uses the JIRA token you saved in ⚙ for Visa my runs in JIRA; the checker keeps your JIRA token nowhere else. It asks first, in a dialog: the question names the run and the ticket, a line says whose comment the visa is, and under it stands the visa as it will be posted. **Cancel**, Esc and a click outside the dialog post nothing. The visa has the text of the run's automatic visa for the verdict as it is now, without the line about auto re-run waves. Its last line names you by your ci2 user name, and by your GitHub login when it is proven. Pressed again for the same run, it updates your comment instead of adding one. The button is grey until the verdict is final, the PR title names a ticket and you have a JIRA token saved. When TeamCity errors left part of the verdict unchecked, the button also waits up to 20 minutes while the checker checks that part again. A click on the grey button says why in the line under the groups, and the **?** of the group says it too. | — |
| **Post to PR** | The button of the `to PR:` group: **Post**, or **Update** once the verdict changed after the checker's verdict comment of the run went into the PR. It posts the verdict of the run on the page to the PR now, as a comment of the checker's GitHub account. It asks first, in a dialog: the question names the run and the PR, a line says whose comment it is, and under it stands the comment as it will be posted. **Cancel**, Esc and a click outside the dialog post nothing. The comment has the text of the run's automatic verdict comment, with the verdict as it is now. It leaves out the lines about the starter's visa and auto re-run waves. It mentions whoever started the run when their GitHub login is proven. Its last line names you by your ci2 user name, and by your GitHub login when it is proven. A run has one verdict comment. The checker's verdict comment of a run comes from **Post my runs' verdicts in the PR**, a `/run-all` or an earlier press. When the PR already has this comment for this run, the press updates it, and its last line says who updated it. Otherwise the press posts a new one. The button is grey until the verdict is final. It is also grey while the checker cannot write to GitHub: the checker has no `GITHUB_TOKEN`, GitHub refuses that token, or GitHub asked the checker to wait. When TeamCity errors left part of the verdict unchecked, the button also waits up to 20 minutes while the checker checks that part again. A click on the grey button says why in the line under the groups, and the **?** of the group says it too. | — |
| **Visa my runs in JIRA** (⚙) | When a RunAll you started finishes, its verdict goes to the IGNITE ticket the PR title names, once per run; with auto re-run on too, once the re-runs settle. The title needs an IGNITE-NNNN key: no key, no visa. No visa while a newer RunAll of the PR is going, and none that repeats the last one for the same commit. | — |

### Thresholds

| What | Default | Where it shows |
|---|---|---|
| Master runs of a test looked at, per suite | 100 (`MASTER_HISTORY_DEPTH`) | `pre-existing`, `not seen failing in N master runs` |
| Failed branch runs in a row for a blocker | 3, or every run when fewer (`BLOCKER_FAIL_STREAK`) | `failed all N runs on this branch` |
| Thin master history | fewer than 10 master runs on the PR's JDK | `only N master runs` |
| A rare master failure | at most 2% of master runs, and none of the newest 10 | `rare on master` |
| Too many failures for chance | at most 1 in 10,000: 2 in a row against a 1% rate, 3 against 2%, 5 against 12.5% | `rare on master`, the other PR and scale factor reasons |
| Flaky in other PRs | failing in 3 or more other PRs | `flaky on other PR branches` |
| Master's failures set aside as down to the scale factor | master fails at least half its runs, only at another scale factor, and other PRs have at least 10 runs | `fails F/R on master at TEST_SCALE_FACTOR=1.0` |
| Fewer tests than master | at least 10% fewer, for suites with 20 or more tests on master | **Fewer tests than master** |
| A crashed suite whose results stand | ran over 90% of master's tests | **Crashed near the end of the run** |
| An incomplete verdict held back | 20 minutes | **Could not be checked** |
| A slow test of the PR | longer than 60 s | **This PR's tests** |
| A re-run that asks first | 5 suites | **Rerun N suites** |

## Finding your PR

![The PHP login page with the Ignite, Python, PHP and Node.js project tabs](img/project-tabs.jpg)

*Local development instance: the PHP login page and project tabs.*

- The project tabs **Ignite**, **Python**, **PHP**, **Node.js** and **Extensions** open `/ignite`, `/python`, `/php`,
  `/nodejs` and `/extensions`.
  The left pane lists open PRs of the selected project, the most recently updated first. A row has the PR's number, its ticket key
  and a badge with its last known verdict ([badges](#badges-in-the-pr-list)). Each badge has a tooltip.
  A PR whose run is under way says how far it is next to the badge, for example `2/4 · ~55m`. The right end of the
  line names who started the PR's latest RunAll, client-suite run or Extensions chain, where the tab does not say it:
  `by me` on **All** and **Involved**, and a name such as `by alice` on **Mine**, where another person started a run
  of your PR. The list is loaded when the page opens. While the page is visible, it asks the checker every minute how
  far the runs under way are, and only the rows of those runs change.
- Three tabs over the list choose which PRs it shows. **All** shows the 50 most recently updated open PRs. **Mine**
  shows your open PRs, also the ones that are not among those 50. **Involved** shows other people's open PRs where
  someone asked you to review, you reviewed or you commented. The checker finds Mine and Involved with GitHub's search,
  at most once a minute for each user. A PR that is not among the 50 gets its badge once someone opens it here.
- **Mine** and **Involved** need your GitHub account linked in ⚙ ([GitHub account](#github-account)). Without the link
  they look disabled, and a click on one opens ⚙. The list opens on the tab you last chose in this browser. Until you
  choose one, it opens on **Mine** if your GitHub account is linked, else on **All**.
- The filter (**Filter, or a PR number**) narrows the list on the chosen tab by number or title. A number that is not in
  the list is offered as **Open PR #N →**. `Enter` opens it or the first match, `Escape` clears the filter, and `/`
  jumps to it from anywhere on the page.
- The **my runs** box next to the filter keeps only the PRs of the chosen tab whose latest run you started. It works
  together with the filter. When the box leaves no PR on the tab, the list says `No runs by you here.` When the filter
  matches none of the PRs the box keeps, the list says `No open PR matches.`
- Each PR in the list is a link (`/ignite?pr=13655`), so a middle click opens it in a new tab. Any PR number works in the
  address, also one that is not in the list.
- A number in the filter belongs to the selected project. Client PR links include its path, for example `/php?pr=42`.
  **All**, **Mine** and **Involved** stay within that project. See [Thin client PRs](#thin-client-prs).
- Drag the divider to resize the pane, double-click it to reset, and fold the pane with `‹` / `›`. On a screen
  narrower than 768 px the list folds away when a PR opens.

## Reading the verdict

![The PR page of #13835 in the dark theme while auto re-runs settle its run. The verdict card says "3 blockers in 2 suites, all on 1 run" and shows the four steps with the builds of "Auto re-run" open. Below it are the "Blockers" and "This PR's tests" cards.](img/pr-page.jpg)

The page answers one question: which tests did this PR break? The verdict card answers it first, in the words of the
PR comment and the visa. The [cards](#cards-on-the-pr-page) under it go from what makes the run unreliable (suites that
never ran, broken suites, fewer tests than master) to the blockers, the tests that recently started failing, the PR's
own tests, and what was not blamed on the PR.

- **The head** names the PR, its number a link to GitHub and its IGNITE ticket a link to JIRA, and has the RunAll
  buttons. A merged PR has the tag `Merged` next to its number, and a PR closed without a merge has `Closed`. The
  tag's tooltip says when and at which commit. When the checker keeps the verdict a merged PR had at the merge,
  `Merged` has a **?**: the [glossary](#runs-buttons-and-visas) says when and how the checker keeps it. The line under
  the head names the RunAll build with its TeamCity link, when the run finished and when the verdict was computed. The
  finish time turns amber when the run is over a week old. The ↻ button computes the verdict again. When TeamCity
  reused suites, the line also says `135 suites ran, 12 reused from earlier runs`.
- **Lines under the head** say when the verdict is for older code or a mixed run.
- **The status line** in the verdict card, under its heading, says whether to wait. A **preliminary verdict**
  explicitly asks you to wait while builds, automatic re-runs or analysis still need time. This includes a newer
  RunAll while the page shows an earlier result: the line counts the PR's other runs and names the run the verdict
  is for. If TeamCity errors leave the analysis incomplete, it says how long the checker will keep retrying it. An
  unconfirmed status is shown as unknown.
- A **final verdict** means the checker expects no further automatic re-runs or analysis of this run. The line says
  why there was no automatic re-run when the steps do not, for example that the run was cancelled. A final verdict
  may still have blockers or missing results: fix the failures or re-run manually. GitHub comments and JIRA visas
  have their own delivery status. A merged PR keeps its retained verdict.
- **The steps** under the status line show how far the run is from its final verdict: `Queue`, `RunAll`,
  `Auto re-run` and `Final verdict`. A done step has a tick. The step under way is highlighted and says what it waits
  for: when the chain starts, how long RunAll still takes, or which wave of auto re-runs goes, with its running and
  queued builds. A suite that fails while the chain runs is re-run at once, so `RunAll` and `Auto re-run` can be under
  way together. A done `Auto re-run` says how many waves went: `2 waves`, or `none started`. It says nothing when
  that is not known: unknown history is not treated as zero. A skipped step is dashed and says why, such as
  `off for this run`. The last step of a final verdict says how it stands: `no blockers`, `1 blocker`,
  `started failing` or `not proven clean`. A step that has builds of the PR on TeamCity now opens them: see
  [Iterating on a fix](#iterating-on-a-fix). There are no steps for a merged PR, a client's PR, or while the page
  could not read the PR's runs. There the status line says all of this in words, and the **On TeamCity** row under
  the head lists the builds.
- **Which run.** The checker analyses the PR's newest finished RunAll that was not cancelled; without one, the newest
  cancelled one; without that, the one still running. The finished suites of a running chain are real results, so a
  PR's first RunAll shows failures hours before it ends. While a newer RunAll goes, its finished suites are folded
  into the verdict, marked `● includes an unfinished run`. Such a verdict never reads green, and visas and PR comments
  wait for a finished chain.
- **Re-runs count.** A suite re-run on its own, by hand or by auto re-run, joins the verdict when it is newer than the
  chain's run of that suite. A pass on re-run clears a blocker; a broken suite whose full re-run did not break is
  judged by that run. A re-run on another commit than the RunAll makes the verdict a mixed run, which never reads
  green: its other suites did not test that commit.
- **Each suite on its own.** One test can run in several suites (the C++ tests run on Windows, Linux and Clang). It is
  listed in each suite it failed in, with that suite's own verdict, so a pass on Windows does not hide a steady break
  on Linux.
- **The same code, the same JDK.** Every run is matched to the commit it built: a pass on older code does not clear
  a failure, and such runs are dimmed in the test's strip. Master runs are counted only on the JDK of the PR's run. A
  run where the test was ignored is not a run, and a failure TeamCity muted is never listed.
- **Grouped by suite.** The lists of tests group the rows by suite. The suite's **TC** opens its newest run, and a row's
  **TC** opens the run the test failed in. If all tests of a suite share one reason, the list shows it once above them.
  If they have several reasons, each reason shared by two or more tests gets its own line above those tests. The
  biggest group comes first. If a reason belongs to one test only, it stands on a line under that test. The test name
  is plain text, `Class.method` with the parameters, with the full name in its tooltip. The name leaves out the work
  directory of a TeamCity agent inside the parameters.
- **The strip** next to a test shows its finished runs in that suite on the branch, oldest to newest. A pass after a
  failure on the same code earns the `flaky?` tag.
- **Broken suites** are grouped by cause. A failed Build comes first, with the suites that need it and did not run.
  Then ci2's artifact glitch, then the rest by TeamCity's problem text. A group has one **Rerun** for all its suites;
  a suite that failed to compile gets none, since the same code fails the same way.
- **Blockers** have two views, **Suites** and **Root causes**, the same blockers grouped by failure signature. The
  Root causes tab shows the cause count once someone has opened it for these blockers. A cause names how many tests
  share the signature, and the suites when there are more than one. Blockers whose failure message TeamCity no longer
  keeps are listed there apart, with a hint to re-run their suites.
- **This PR's tests** shows how the test classes the PR adds or changes ran: each class with its passed, failed and
  ignored tests and its slowest test, and the tests that failed or ran over 60 s. **all N tests** opens the whole list
  of a class: the failed tests first, then the slow ones, then the rest by name. Test and suite names open their runs
  in TeamCity. Under a failed test, a line says what the verdict did with it. Its link leads to the test's row in the
  card that lists it and opens that card if it is folded. The card can appear a moment after the verdict. Two cards
  are under it: **Crashed near the end of the run** and **Not blamed on this PR**. They wait for the server's answer
  about the PR's tests, so the card does not push them down. If the answer does not come in 20 s, they appear without
  it. When the verdict of a newer RunAll comes, the card keeps its place with the tests it had until the new ones
  come. It says they are the tests of an earlier RunAll and leaves out what the old verdict did with them. If the new
  tests do not come, the page hides the card.
- **✓ No blockers** heads the verdict card only for the verdict the PR list ticks: a run of the PR's current head that
  covered everything and blamed nothing on it. Otherwise, when tests started failing on the code the run tested, the
  heading reads **No proven blockers yet**. In other cases it reads **No blockers found — not proven clean**. The amber
  lines under the heading list the caveats. They use the same words as the PR comment and the visa. The page asks
  GitHub whether the run tested the PR's head. Until GitHub answers, a clean verdict reads **No blockers found** with
  `Checking that this run tested the PR's latest commit…` and the PR's badge in the list is **…**; after that, the
  badge says what the page says.
- **failure ▸** opens the failure message and the stack trace, with a rough triage label: `⚖ assertion …`,
  `environment/timing …` or `⌛ hang …`. Output over 32,000 characters is cut; the whole of it stays in TeamCity.
  **copy failure** in the open panel copies the message.
- **copy AI prompt** copies a prompt for a coding assistant. The button is in the open panel, or on the row when
  TeamCity kept no failure message. The prompt includes the PR, the suite and its run, the test, the checker's verdict
  with its reason, the triage label and the failure output. It also says how to reproduce the failure on the commit the
  run tested, with steps for Java, .NET, C++, Python, Node.js or PHP by suite. Everything quoted from the PR is marked as
  data, not instructions, and the prompt asks to run the PR's code in an isolated environment. On the Root causes tab,
  **copy AI prompt** copies one prompt that covers all tests of a cause; broken suites and suites with fewer tests than
  master have their own **copy AI prompt**.
- **The tab** shows how the run stands in its title and icon: `⏱ ~1h 05m left`, `♻️ re-run 1/2`, then
  `❌ 3 blockers`, `✅ no blockers`, `⚠ started failing` or `⚠ not proven clean`. **🔔 Notify me** sends one desktop
  notification when the verdict is final; the tab has to stay open. While the button is on, it is filled and reads
  `🔔 Will notify`.

## Iterating on a fix

- **Blockers since the previous RunAll**: `2 new · 3 dropped out · 5 still blocking` in the verdict card, the blockers
  compared with the PR's previous RunAll (test names in the tooltips); from three runs on, a bar per RunAll beside it,
  red while blockers remain. A blocker is a test in a suite, so a test that dropped out on Linux counts as dropped out
  there while Windows still fails it.
- **Run** and **Run at top** queue a new RunAll for the PR, at the end or at the top of the ci2 queue. They always ask
  first and say how big the chain is. If your own RunAll of the PR is still going, OK cancels it and queues the new
  one; someone else's RunAll stays. ci2 lets only some accounts reorder its queue: for anyone else TeamCity queues the
  chain at the end, and the page says so.
- On a PR that GitHub reports merge conflicts for, **Run**, **Run at top** and **Run RunAll** queue nothing and say
  why. **Run anyway** next to that answer asks again and queues the RunAll. **Run**, **Run at top** and **Run RunAll**
  queue RunAll as usual until GitHub has worked out whether the PR has conflicts. While fewer than a fifth of the
  checker's hourly GitHub calls are left, the page does not ask GitHub about conflicts and queues RunAll as usual.
  Merge conflicts do not stop **Rerun**: it re-runs suites of a run the PR already has.
- **Rerun** and **Rerun at top** re-run one suite, every suite of a section or a group of broken suites. Each suite
  row has its own pair of small text buttons. A section's button says how many suites it re-runs. A pair that would
  re-run what the pair above it re-runs is left out, and one button is filled as the next step, except on a merged
  PR. On a PR with no run to analyse and none queued or running, that button is **Run**. While the checker re-runs a
  suite in a wave of auto re-runs, the suite has no pair, and a section or a group has none while the checker re-runs
  all of its suites: a manual re-run would only replace the automatic one. The pairs come back when the wave ends.
  From 5 suites on they
  ask first. Your own identical re-run still in the queue is replaced; someone else's stays. At the top works as for
  Run. A line under the buttons says why, when the suites are not queued or are queued at the end, not at the top.
  The queued chip appears on the suite at once.
- **The builds of a step.** A step that has builds of the PR on TeamCity is a button. A click opens them under the
  steps, one step at a time, and another click closes them. A queued RunAll stands under `Queue`, a running one under
  `RunAll`, the re-runs of the wave under `Auto re-run`, and any other build, such as a re-run you started, under
  `Final verdict`. Each build shows how long it has waited or run, an estimate (`~14m left`, `starts ~5m`), the
  commit it tests (`on latest commit`, `⚠ old commit abc1234`, `takes latest commit at start`) and `by alice` for
  someone else's run. The same chips appear on the suites they re-run. A PR without steps lists its builds in the
  **On TeamCity** row.
- **Cancel my runs** shows only while you have a run on the PR. It lists your runs, asks, and cancels only them. It
  stands at the end of the status line, or of the **On TeamCity** row.
- **Older code**: a line says
  `⚠ The verdict is for older code: this run tested 0013263, and the PR is now at b52666a, 7 commits later.` with
  **Run RunAll** right after it. After a rebase or force-push it says so instead of a count; while a new RunAll is
  queued or running, it says that instead of showing the button. A mixed run has its own line instead, such as
  `⚠ Mixed run: Control Utility 5 re-ran on 9b02561, the PR's head; the rest of the RunAll ran on c577b18.`
- When your chain or a re-run finishes, the page shows the new verdict without a reload.

### When TeamCity uses the Git proxy

An operator can enable the checker's [public Git proxy](../README.md#git-proxy-for-teamcity) when TeamCity cannot
reach GitHub. Keep using the PR page and `/run-all` comments. Caddy forwards fetches to the matching public
GitHub repository without storing code or refs. No extra account, token or branch publication is needed.

A new PR launch selects the fresh GitHub head. Each launch or re-run stays pinned to the commit chosen for that
action. Suites queued by one action use the same commit. If GitHub no longer serves the selected SHA, checkout fails.
The proxy does not retain force-pushed commits or substitute another commit. TeamCity may use its own cached VCS state.

## Standing options

**⚙ Settings** opens `/settings`, a separate page with a link back to the project or PR you came from. Linking GitHub
returns to Settings with that same context. These options act while the page is closed, on the RunAll chains you start: on the page, with a
`/run-all` comment, or in TeamCity. Automatic re-runs, PR comments and JIRA visas act on the runs you start in
[thin client PRs](#thin-client-prs) and [Extensions PRs](#extensions-prs) too. Options that push commits apply to your own Ignite PRs. An option does not reach back: runs that finished
before it was switched on are left alone. ⚙ puts the options in four groups, by what they need, and each group starts
with the state of that access. While any of them is on, the server stores your TeamCity token, encrypted, and each also
stores the JIRA or GitHub token it needs while it is on.

Detailed explanations are under **Details** in each group. Reading verdicts and starting runs on the page need no
standing options. When you choose an option without what it needs, ⚙ opens the relevant token form or shows
**Link GitHub**. Saved tokens keep their **Replace** action; missing or rejected tokens keep their recovery action.

The state line of each token also says how long the token has left: `expires in 43 days`, `never expires` or
`expiry unknown`. The TeamCity line is about the token you logged in with. From `expires in 14 days` on, the line turns
amber and says what to do before then. TeamCity and JIRA list your tokens without their values, so the checker cannot
tell which one it holds. It names an expiry only when all your tokens that still work expire at the same time.
Otherwise the line says `expiry unknown` and why. GitHub names the expiry of the token itself. If JIRA or GitHub
refuses the saved token, the line says so in amber. A token without an expiry works as well, and its line suggests an
expiry for the next one, say 90 days. The checker asks each service about a token at most once an hour: when you open
⚙ or save a token. A JIRA or GitHub token that the server no longer keeps, after a change of the server key or a
restore from an old copy, has no such line until you save it again.

When the operator changes the server key, the checker can no longer read the tokens it stored, and drops them. Your
options stay on and wait for new tokens. ⚙ says that the server key was changed and opens the form of each token to save
again; a login stores the TeamCity token. Until you log in again, a `/run-all` gets a reply that says the server key was
changed, and the story of a running `/run-all` waits and says so. After the login, the run's story says what the
checkstyle fix waits for, and the comment that brings the verdict says why no visa came. A stored token that got
damaged is dropped the same way, and ⚙ says that the checker could not read it.

When the operator restores the checker's state from a copy older than 2 days, the copy has no tokens. Your options stay
on and wait for the tokens too. ⚙, the reply to a `/run-all`, the run's story and the verdict comment then say that the
state was restored from a backup, not that the key was changed. Opening the page while logged in gives the checker your
TeamCity token again. A copy made before a change of the server key reads as that change: save new tokens, not the old
ones.

### TeamCity login

Your login is all these two options need.

- **Re-run failed suites of my runs** re-runs the suites the verdict blames: suites with blockers, suites with tests
  that recently started failing, and broken suites. It also re-runs the suites TeamCity cancelled by itself, but not
  those a person cancelled.
  A suite that fails while the
  chain still runs is re-run at once, at the top of the queue, up to 10 per chain. After the chain finishes, the suites
  still blamed are re-run: up to 2 waves per run, the mid-run re-runs counting as the first. All automatic re-runs go to the
  top of the queue. With more than 30 such suites and no re-run yet, nothing is re-run: that looks
  systemic. If ci2 does not let your account reorder its queue, the re-runs go to its end. Your own identical re-runs
  still in the queue are cancelled first; someone else's stay. A failed Build is re-run alone; suites that failed to
  compile, and the suites a failed Build kept from running, are never re-run.
- **Post my runs' verdicts in the PR**: the checker's GitHub account posts the verdict as a comment, once per run,
  after the re-runs settle, and mentions you when your GitHub account is linked. Needs no GitHub token of yours.

When TeamCity refuses the token your options use, they pause, and ⚙ shows **Log in again**. Log in with a fresh
token: the options resume on it. Nobody else's options stop.

### JIRA token

The state reads not saved, saved or rejected. Choose **Visa my runs in JIRA** to open the token form. Create the token
in your ASF JIRA profile. It acts with all the rights of your JIRA account (ASF JIRA has no token scopes), so give it an
expiry, say 90 days. **Save** switches on the option with this token. The server stores the token encrypted while the option is on. No cookie keeps it.
A visa comes from this option, or from **Post to JIRA** on the PR page, which uses the same token.

- **Visa my runs in JIRA** posts the verdict to the IGNITE ticket the PR title names, once per run, after the
  re-runs settle. The title needs a key like `IGNITE-12345`, in any case: no key, no visa. No visa goes out while a
  newer RunAll of the PR is going, and none that repeats the last visa for the same commit.

When JIRA refuses the stored token itself, the checker drops it and switches the visa off; ⚙ says so. A rate limit,
an outage or a ticket you may not comment on keeps the token, and the visa is tried again at the next sweeps. A visa a
refused token held back goes out once you save a fresh token and switch the option back on, unless a newer RunAll
replaced the run. A new token that JIRA refuses or cannot check replaces nothing: ⚙ says so at the form, and the
saved token stays in use.

The checker does not use a JIRA token from your login cookie. If one was there and your option is off, ⚙ says so for
30 days and asks you to switch the option on with the token.

### GitHub account

The state reads not linked, the login, or not proven. If the checker knows when a linked account was linked, its line
says so, such as `@alice-gh, linked 19 Oct 14:02 from Firefox on Linux`. The line names the browser only if the checker
knows it.

- **Link GitHub** links your account: GitHub asks you to let the checker see your public profile, then sends you
  back. The checker keeps the login and the account's id; the token GitHub gave for it is revoked at once. A link
  counts only in the login that began it, within 10 minutes. Linking switches on **PR commands**. A GitHub token for
  commits links the account as well, and on a checker whose operator did not set up **Link GitHub** it is the only way.
  The checker's own account cannot be linked.
- **Unlink** forgets the account: **PR commands** go off, and so do the options that commit, with your GitHub token.
  Switching off your last option forgets the account too, and the journal says so.
- **PR commands** lets you start RunAll from a PR comment ([commands](#working-from-the-pr-commands)). It stores only
  your GitHub login, your GitHub account's id and your TeamCity token; on its own, it lends the token to no background
  work. A comment is your command only when it comes from the linked account, by its id, so a login you give up is no
  one else's way in.

A login an earlier version kept counts as proven only if your saved GitHub token names it. Any other is not proven: it
runs no command, and verdict comments do not mention it, until you link the account.

### GitHub token for commits

The state reads not saved, saved with the token's kind (fine-grained or classic), or rejected; a classic token also
gets a note on what it can push to. Choose an option below to open its token form.

- **The token** only commits, to the branch of your own PR: it writes no comments, and it commits to
  `apache/ignite` only for a PR from a branch there. Best is a fine-grained token for your fork alone, with Contents:
  read and write. It cannot reach a PR from a branch of
  `apache/ignite` itself or from someone else's fork; those need a classic token with `public_repo`, which pushes to
  every public repository you can push to, `apache/ignite` too if you are a committer. A classic token with `admin:*`,
  `delete_repo` or `workflow` is refused, one with `repo` is taken with a warning. The page sends the token with the
  switch, and no cookie keeps it. Give it an expiry, say 90 days.
- **Fix checkstyle before my commanded runs**: when you command a run on your own PR, the mechanically fixable
  violations in its changed files (imports, whitespace, modifier order…) are fixed and pushed before the run starts, as
  one commit from your account titled `Checkstyle autofix by Ignite PR Checker (requested via PR command)`. The run's
  story names the commit, reminds you to `git pull`, and lists what is left for a human (javadoc, naming, wrapping).
  It uses your GitHub token, which the checker keeps only for such commits, and works on a PR with up to 50 changed
  Java files; a file over 400 KB, or past 5 MB in all, is not checked. It never touches anyone else's PR and never
  force-pushes. It runs only from a command, so it switches on only with **PR commands** on and goes off with them.
- **Add my PR's tests that no suite runs**: when Ignite's own abandoned-tests check on the head of your PR finds test
  classes of the PR in no test suite, each goes next to the class of its module nearest to it: a class of the same
  package first, else one of the longest package in common, then the longest start of the name in common; when several
  suites list a class as near, the one with more such classes wins. One commit from your account lists the new class in
  that class's suite, on a copy of its line, with an import if needed. A module with no suite listing a class of a
  package near the new one gets no commit, and the comment says so. Once per head commit, on your own PRs only. A
  comment of the checker's account in the PR says what went where and why, then what Ignite's check says of the
  commit. It uses your GitHub token, as the autofix does.

When GitHub refuses the stored token itself, the checker drops it and switches off both options; ⚙ says so. A PR
comment GitHub did not take from the checker's account goes at the next sweeps; what that account is refused never
touches your token. A new token that GitHub refuses or cannot check replaces nothing: ⚙ says so at the form, and the
saved token stays in use.

### Your logins

Below the options, expand **Your logins** to see each browser you are logged in from, this browser first. Each line names the
browser and its system, as the browser's User-Agent names them. It also says when the browser logged in. The line of
another browser adds when the checker last saw it. That time can be up to 5 minutes old. A login that began before the
checker kept this list says so instead of a date. The server keeps the list without tokens and without IP addresses.

- **Log out** in the top bar ends the login of this browser only. Your logins in other browsers stay, and the line
  under the login form says how many there are.
- **Log out selected** ends the logins you ticked. **Log out everywhere else** ends every login except this
  browser's. It also ends a login that began before the checker kept this list and is not in the list yet. A browser
  whose login ended goes to the login form at its next request, and the form says that its login has ended.
- Before either button ends a login, the checker asks TeamCity whether this browser's token still works. With a
  revoked token, nothing ends, and this browser goes to the login form.
- A copy of a browser's session cookie is the same login: the list does not show it as a separate line, and it works
  until that login ends. **Log out everywhere else** keeps a copy of this browser's cookie logged in. To end it, use
  **Log out** in this browser, then log in again.
- A copy of a cookie taken before the update to v1.40.0, the first version with this list, can be a login of its own.
  Earlier versions issued a new cookie each day, so a copy of an older one is not the cookie of this browser. Such a
  copy is not in the list until its first request. **Log out** in this browser does not end it, but
  **Log out everywhere else** does.
- A login also ends after 400 days without use: the browser then drops its cookie.
- You have at most 50 logins. A new login past that ends the one with the same browser and system that was seen
  longest ago. So a script that logs in with curl on every run ends its own earlier login, not a browser's. When no
  other login has the same browser and system, the new login ends the one seen longest ago.

Your options belong to your account: ⚙ shows the same options in every login, and no logout changes them. When a
login ends, background warming stops using its TeamCity token, unless a standing option keeps that token in use.

### Journal

Expand **Journal** at the end of ⚙ to see what happened to your settings, the newest first. It shows 10 entries, and **Show all** shows
the rest. The checker keeps your last 100 switches of options, your last 100 saved tokens and your last 100 other
entries, such as links and visas. So switching options many times in a row does not push a link or a visa out of the
journal.

- Each change of your settings is an entry: an option switched on or off, a JIRA or GitHub token saved, a GitHub account
  linked or unlinked, your options switched to the TeamCity token of a login. It says when, and from which browser: this
  browser, or another one by its label, as in **Your logins**.
- A browser is named as its User-Agent names it. A copy of this browser's cookie is the same login as this browser. An
  entry made in this login by a browser with another name says `in the login of this browser`.
- What the checker changes itself is an entry by the checker: a token JIRA, GitHub or TeamCity refused, a token the
  checker could not read, your GitHub account that another ci2 user linked.
- Each visa the checker posts or edits with your JIRA token is an entry: the PR, the ticket, the RunAll and when. The
  ticket links to the comment in JIRA. The entry of a visa in a thin client PR names the client's repo,
  and the suite's build instead of the RunAll. A RunAll someone started in TeamCity with your ci2 token gets a visa from
  your **Visa my runs in JIRA** too, and the journal shows it. A visa you post or update with **Post to JIRA** is an
  entry from your browser and names the button.
- Each verdict you post or update in a PR with **Post to PR** is an entry from your browser: the PR, the RunAll or the
  client's build, and the button. `PR #…` in it links to the comment on GitHub.
- An entry names options, services and GitHub accounts, never a token or a part of one.

### Language

⚙ starts with **Language**: pick English or Русский and press **Save**. The page then opens again in that language.
The server keeps the choice for your account: every page of the checker shows in that language in every browser you
log in from. The journal lists each change. A page that opens without a login is in English.

- On the Russian page, the cards, buttons, reasons and server answers are in Russian. They use the same words as the
  [Russian tour](features.ru.md). The **?** links lead to the Russian tour.
- PR comments, visas in JIRA, the copied AI prompts, the logs and the API stay in English: the Ignite community and
  scripts read them.
- The status page and the flaky board have no login form: they use your account's language if you are logged in to
  the checker in this browser.
- A page that last opened in Russian waits up to 2 s, until it knows the language, so it does not show English first.
- If you log in on a page in another language, the page opens again in your account's language.

## Visas and PR comments

- **The visa** of a run comes from **Visa my runs in JIRA** of whoever started it, or from **Post to JIRA**. The
  group `Verdict to JIRA:` in the verdict card says where it stands: `✓ posted`, with a link to the comment;
  `auto when final`, still to come once the run settles; or a button to post it yourself. The **?** of the group says
  the rest: why no visa comes, or what the button waits for. For someone else's run it names whose option it is and
  whether it is on, and nothing more about their options. The [glossary](#runs-buttons-and-visas) lists every state.
- **Post to JIRA**, the button of that group, posts the visa of the run on the page now, with your own JIRA token
  from ⚙, whoever started the run. Use it when no option posted the visa, or when the verdict changed after the visa
  went out. For example, a suite with a blocker re-ran on a newer commit and passed: the run became a mixed run, and
  you want the ticket to show that. The visa says what the run's automatic visa would say now, the mixed-run caveat
  included. The last line of the visa names who posted it. Before anything is posted, a dialog shows the visa as it
  will go: read it, then confirm or cancel. Only the text the dialog showed is posted: when the verdict changed while
  you read it, nothing goes out, the page says so, and the next press shows the new text. Opening another PR closes
  the dialog without posting. While your visa says what the verdict says, the group reads `✓ posted` and has no
  button. Once the verdict changes, it reads `posted, verdict changed`, and **Update** puts the new verdict into that
  comment. On a merged PR's page the button posts the verdict kept at the merge.
- **Post to PR**, the button of the group `to PR:` next to it, does the same in the PR: the checker's account posts
  the verdict of the run on the page as a comment, after the same dialog. A run has one verdict comment in the PR.
  The group links it with `✓ posted`, and says `auto when final` while **Post my runs' verdicts in the PR** of whoever
  started the run still posts it. Once the verdict changes after the comment, the group reads
  `posted, verdict changed` and has **Update**. The press updates the checker's verdict comment of the run, or posts
  one when there is none. The last line of the comment names who posted or updated it. The press counts among the writes of the
  checker's account: at most 40 an hour to one PR and 60 for one user. The checker takes at most 5 presses on one PR
  in an hour. The rest of those 40 writes stay for the stories of the PR's runs and their verdict comments. When a
  limit is reached, the page says so; press again later.
- A visa and a PR comment say the same. The head line names the RunAll build, the commit it tested, and how many
  suites ran and how many were reused. Broken suites come grouped by cause.
- The lists of blockers, tests that recently started failing and unchecked tests are grouped by suite and class, the
  biggest group first.
  Each line has the test's tags (`1 run`, `new test / no master history`, `unverified`) and a TC link, and a line
  under the lists explains the tags. The PR comment shows 5 groups and folds the rest; the visa shows 10.
- Test names and the reasons TeamCity gives for a broken suite can have links, web addresses, mentions, images and
  HTML. The comment and the visa show all of it as written. `#123` and `GH-123` in them do not link to any GitHub
  issue or PR.
- A verdict that can't call the PR clean lists the caveats. A verdict of code that was pushed over says which commit
  it tested and which commit the PR is at now. A red PR comment ends with a **Next:** line: fix, push, and `/run-all`.
- Every verdict carries a link to the [verdict glossary](#verdict-glossary) right under its lists; the lines about the
  tested commit and the **Next:** line come after it.
- The PR comment comes from the checker's account and opens with a mention of the run's starter when their GitHub
  login is proven, so GitHub tells them. When the PR title names no IGNITE ticket and the visa option is on, it says
  why no visa came.
- Once a newer RunAll of the PR has finished, each older verdict comment in the PR gets a last line saying it is
  superseded and where the newer verdict is.

## Working from the PR (commands)

The whole cycle, from the start to the verdict, can happen in the pull request. In ⚙, press **Link GitHub** under
GitHub account: that switches on **PR commands**, and no GitHub token of yours is kept for them. The checker answers
from its own GitHub account, the app account.

| Command | What it does |
|---|---|
| `/run-all` (also `/runall`) | Queues the whole RunAll chain under your own TeamCity account. |
| `/run-all top` (also `--top`) | The same, at the top of the ci2 queue; if ci2 does not let your account reorder the queue, at its end, and the ack says so. |
| `/top` | Moves the RunAll your `/run-all` started on this PR to the top of the queue, while it still waits; the run's story says so. |
| `/run-all force`, `/run-all top force` (also `--force`) | The same as `/run-all` and `/run-all top`, also on a PR that GitHub reports merge conflicts for. |

- The command has to be the first word of the comment. Commands are picked up within a minute, on any pull request.
  A comment written more than an hour ago is not run. An edited comment is not run either: anyone with write access
  may edit your comment. The checker answers 😕 and asks you to post the command in a new comment.
- The first command from a GitHub account since its link gets one line from the app account, such as
  `ℹ️ First command from @alice-gh for ci2 user alice: Ignite PR Checker runs the commands of @alice-gh under that ci2 account.`
  So the thread shows whose ci2 account the commands of that GitHub account run under, and anyone who reads the thread
  sees a link nobody expected. One line per link: after you press **Link GitHub** again for the account already linked,
  its next command does not get the line again. After a change of the server key the first command of each linked
  account gets the line again: whoever had the old key could link an account to any user.
- 🚀 means accepted, 😕 refused. Then a comment of the app account, which mentions you, tells the story of the run:
  the queued build, then `⏱ Queued — expected to finish ≈ 21:05 MSK` in your browser's time zone, which ⚙ sends with
  every change, and in UTC until it has. It is edited when the stage changes or the estimate moves by 10 minutes or
  more; GitHub tells nobody of an edit.
- After the chain finishes, the story shows the re-run waves (`♻️ Auto re-run #2 — 3 broken suites, ≈ settled by 22:21`).
  When the verdict is in, a new comment mentions you: the verdict comment, when **Post my runs' verdicts in the PR**
  is on, else one saying the verdict is ready, with a link to it. The autofix's note moves into that
  comment, and the story's comment is deleted, so the run leaves one comment of the checker in the PR.
- A command that is not the first word ("Please /run-all") gets 😕 and a reply with a hint saying how to write it. A
  `/run-all` that failed is answered why, in words.
- On a PR that GitHub reports merge conflicts for, `/run-all` and `/run-all top` queue nothing. Such a PR has to
  change before it can be merged. The command gets 😕 and a reply that says what to do: resolve the conflicts and post
  it again, or add `force`. Until GitHub has worked out whether the PR has conflicts, the command runs as usual. A
  word that differs from `force` by one character, such as `forse` or `-force`, does not count as `force`. The reply
  names such a word.
- A new `/run-all` supersedes your previous one: your queued or running chain on the PR and its re-runs are cancelled
  first, nobody else's. The old story ends with `🛑 Superseded by a newer /run-all`, and the ack says what was
  cancelled.
- A command that cannot run gets 😕 and a short reply from the app account that says what is missing and what to
  press in ⚙: a GitHub account nobody linked, **PR commands** off, a stored TeamCity token that no longer works, that
  the checker could not read after a change of the server key, or that a restored copy of the state lacked, a login an
  earlier version kept without proof. A `/top` with no waiting run of your command, or one ci2 does not let your account move, is answered the same way. The same reply goes once per person and PR, at most 3 replies a day to one
  person and 5 a day to all the accounts nobody linked; a repeat gets only the 😕. A reply quotes nothing of the
  comment.
- When GitHub makes the app account wait, or its writes reach 40 an hour to one PR or 60 for one user, reactions,
  replies and story edits go later, not lost; a reaction or a reply that waited a day goes no more.

### Extensions PRs

The **Extensions** tab lists PRs of [apache/ignite-extensions](https://github.com/apache/ignite-extensions).
Its home is `/extensions`, and a PR opens at `/extensions?pr=42`. The list and its filters stay in this repository.

**Run** and `/run-all` queue the whole **Extensions: All Tests** build chain, which contains several suites.
It tests the PR's selected head against the selected Ignite master commit. The page shows each suite's failures
and links to its TeamCity results. **Run** starts a new whole chain; it does not launch an individual leaf suite.
The command rules, including `top` and `force`, apply as in the other projects.

The starter's standing options can re-run the whole chain on its original two commits and post the final verdict.
The page also offers **Post to JIRA** and **Post to PR** for the run on screen. A PR number, navigation link or
comment always keeps the Extensions repository; it never selects an Ignite or thin client PR with the same number.

### Thin client PRs

The commands also work in the three thin client projects, under the same conditions: **PR commands** on and your
GitHub account linked. There `/run-all` runs one client suite instead of RunAll:

| Project | Repository | PR page example | Suite |
|---|---|---|---|
| Python | [apache/ignite-python-thin-client](https://github.com/apache/ignite-python-thin-client) | `/python?pr=69` | **Thin client: Python** |
| PHP | [apache/ignite-php-thin-client](https://github.com/apache/ignite-php-thin-client) | `/php?pr=42` | **Thin client: PHP** |
| Node.js | [apache/ignite-nodejs-thin-client](https://github.com/apache/ignite-nodejs-thin-client) | `/nodejs?pr=42` | **Thin client: Node.js** |

- `/run-all` queues the suite under your TeamCity account. The run tests the PR's head commit against the commit
  Ignite master has at the moment of the command. The checker gives TeamCity both commits when it queues the run, so
  a run that waits in the queue still tests them. `/run-all top` and `/top` work as in Ignite's PRs.
- When GitHub reports merge conflicts between the PR and its base branch, `/run-all` and `/run-all top` queue
  nothing, as in Ignite's PRs. To queue the suite anyway, add `force`, as in `/run-all force` or `/run-all top force`.
  Merge conflicts do not stop the auto re-run: it re-runs a run the PR already has.
- The checker judges the failed tests the same way as in a RunAll: by the suite's runs on master, its runs in
  other Ignite PRs, and its runs in this PR. Two runs of this PR count as the same code only when both commits match,
  the client's and Ignite's. The suite takes the client's code from its parameter `OVERRIDDEN_BRANCH`, and `/run-all`
  sets it to the PR's commit. A run that does not set it tests the client's master. So the checker does not count a
  run someone started in this PR from TeamCity without it.
- As with a RunAll, a comment of the checker tells how the run goes. When the run finishes, a new comment mentions you
  and brings the verdict: the two commits, the blockers and the tests that started failing, each with its reason and a
  link to TeamCity. The comment gives only the number of tests not blamed on the PR. The PR's page on the checker
  lists these tests. Both comments link that page.
- The pages in the table show the verdict of the newest of the PR's runs that
  tested the PR's own commit. When the run before the newest one tested the same two commits, the verdict counts the
  failed tests of both runs. For example, an auto re-run tests the same two commits as the run it re-runs. The page
  names the suite's build and both commits above the verdict. It has the verdict's cards, but not **This PR's tests**:
  the client's tests are not Java classes. The cards have no **Rerun** buttons and no AI prompts. **Run** and
  **Run at top** queue the suite as `/run-all` and `/run-all top` do. On a PR with merge conflicts they queue nothing
  and offer **Run anyway**. The checker computes the verdicts of the client's open PRs in advance, in the background,
  as it does for Ignite's PRs.
- Your options act on a run you start with **Run** or **Run at top** as on a run of `/run-all`. An option you switch
  on before the run finishes acts on it too. **Re-run failed suites of my runs** and **Visa my runs in JIRA** work on
  it as the next bullets say. With **Post my runs' verdicts in the PR** on, the verdict comes in a comment in the PR.
  The comment mentions you when your GitHub account is linked. With that option off, the run leaves no comment in the
  PR. No comment tells how such a run goes. The same is true for a RunAll you start on the page of an Ignite PR.
- **Re-run failed suites of my runs** works here too, once per run. When the verdict has a blocker, a test that started
  failing or a broken suite, the checker queues the suite again at the top of the queue. The re-run tests the same two
  commits with the same `OVERRIDDEN_BRANCH`, even after Ignite master or the PR moved on. For a `/run-all`, the
  checker's comment on the run also tells how the re-run goes. The verdict comes after the re-run and counts both runs.
  One of its lines says how the re-run went. A suite that failed to compile is not re-run, and neither is a run that
  finished before you switched the option on. A run TeamCity cancelled by itself is re-run as well, and the verdict is
  that re-run's. A run that a person cancelled is not re-run, nor one that TeamCity cancelled because a failed Build
  kept it from running. When the re-run of a cancelled run gives no result either, there is no verdict, and the
  checker's comment on the run says why.
- **Visa my runs in JIRA** posts the verdict to the IGNITE ticket in the title of the client's PR, as for a RunAll,
  after the re-run if there is one. The first line of the visa says `one suite only, not a RunAll`, so nobody takes it
  for the visa of a RunAll. It names the suite, its build, the PR's commit and Ignite master's commit. Then it lists the
  failed tests, up to 20 in each list, and links the rest in TeamCity. A closed PR or a title without a ticket gets no
  visa. Nor does a run whose verdict repeats your last visa of this PR in that ticket for the same commits. When JIRA
  fails, or GitHub does not give the PR's title, the checker tries again every minute for 10 minutes, and the verdict
  waits. Meanwhile the checker's comment on the run says that it tries the visa again. After that the verdict comes
  without the visa. The verdict comment says where the visa went, or why none did. The journal in ⚙ lists the visa with
  the client's repo.
- **Post to JIRA** and **Post to PR** work on the client PR's page too: they post the visa and the verdict comment of
  the run the page shows. When the verdict counts a later run of the same two commits, both name that run too.
- Not tested: a client change together with an Ignite change it needs, since the run always takes Ignite master. Run
  `/run-all` again once the Ignite change is in master.
- A new `/run-all` cancels your run of the suite in this PR that is queued or running, and its auto re-run. **Run**
  on the page does the same once you confirm. When TeamCity has no such suite or refuses the run, the reply says so.
- The **PR commands** card on the status page names Ignite and all three client repositories.

## The flaky board (`/flaky.html`)

![The flaky board: tests ranked by how often they fail on master](img/flaky-board.jpg)

- Tests that fail on master: the checker does not blame them on any PR, yet they fail in PRs that did not break them.
  **Failing after a pass** comes first: the latest two or more completed, non-cancelled master runs in the
  observed window failed consecutively, with a pass before that streak. These are candidates to investigate, not
  proof of a regression. The other tests form **Flaky on master** (some runs fail,
  others pass) and **Broken on master** (each of the newest 10 runs failed, or every run when there were fewer).
- Each group is ranked by master fail rate, then by how many PRs the test failed in during the last 14 days, and shows
  40 rows at most. The two columns on the right show both: **Master fail rate** (for a broken test, how many runs in a
  row it failed) and **PRs, last 14 days**.
- The board collects tests seen failing in cached PR analyses; it does not scan every test on master.
- A test that runs in several suites gets a row per suite, with that suite's fail rate and its failed master runs.
- Each row shows a mini history of completed master runs, oldest to newest. **P** and **F** link to the passed or
  failed run; their tooltips give the finish dates. The preview shows the latest 12 dated runs; **Show all** opens
  the available window of up to 50. The fail-rate column uses its own sample, which can be larger.
- The attention group gives the last observed pass, the first failure after it and the current failure streak.
  These dates bound the observed change; they do not establish when or why the test broke. Without an earlier pass
  or readable finish dates, the page says the transition is unknown. History is checked at most once per 24 hours;
  each row shows when. A history not yet checked gets a placeholder until the normal background cycle fills it.
- Tests muted on TeamCity are left out, and the intro says how many.
- The tally survives restarts; a test drops off 14 days after it was last seen failing.
- Anyone can read the board. **failure ▸** opens a panel with the test's older failed master runs and the failure
  message from its latest failure in a PR. The message needs a login. **copy AI prompt** copies a prompt to make a flaky
  test stable, or to find the commit that broke a broken one. It works without a login. The button is in that panel, or
  on the row when there is no panel. A suite being re-run shows its chip: the PR, the state and the estimate.

## The status page (`/status.html`)

![The status page: version and commit, CPU, heap, process memory, snapshots, requests per endpoint](img/status-page.jpg)

![Caddy Git proxy status with six configured public repositories](img/git-proxy-status.jpg)

*The Git proxy block shows Caddy's configured read routes for six public repositories.*

- Anyone can see the version and its commit, uptime, CPU and load, the heap with its old-generation peak, the
  process's memory, the snapshots, the latency of each endpoint, the cache warmer, the build watcher, and the
  TeamCity, GitHub and JIRA calls by category over the last hour.
- The health dot next to **Status** in the menu turns red for 6 hours after an error and amber for an hour after a
  warning. It also turns red or amber when a background job stops (the standing sweep, the PR command poll, the warm
  cycle), a state file could not be read, or a setting is out of range; such problems are listed at the top. Requests
  that the service rejects as the caller's mistake are listed in grey and never colour the dot.
- The GitHub section shows the app account: whose `GITHUB_TOKEN` it is, or that GitHub refuses it, and then the health
  dot is red until a call under it works again. Calls under users' own tokens are counted apart from the app's.
- **Git proxy** shows whether Caddy's Git route is enabled and lists its configured public repositories.
  It reports no live request statistics or GitHub availability. Caddy controls HTTP routing;
  `GIT_PROXY_ENABLED` controls whether Ignite PR launches stay pinned to the chosen exact SHA.
  TeamCity's VCS root fetch URL determines whether it uses this proxy. The proxy keeps no repository cache
  and runs no background sync.
- The **Out of memory** card is there while a heap dump is on disk: it says when the JVM ran out of memory and when
  the dump is deleted. The server deletes a dump 2 days after the JVM wrote it, since it holds decrypted tokens.
- The **App writes** card shows writes of the app account that GitHub makes wait, or that reached their hourly cap,
  in amber, and a write GitHub refused for another reason (a block, a locked PR) in red until one goes in.
- The **Tests added to suites** card counts the users who have **Add my PR's tests that no suite runs** on. If the
  last sweep did not act for some of them, the card turns amber and says how many are paused. A user is paused when
  TeamCity or GitHub refuses their token, or a GitHub account or token is missing. A user who switched the option on
  after the last sweep also counts as paused until the next sweep. Without a `GITHUB_TOKEN`, all of them are paused.
- The token is checked at the start and every 6 hours. A token that cannot write to the repo (a fine-grained one, or a
  classic one without `public_repo`) turns the dot red; one that expires within 14 days or never expires, scopes
  beyond `public_repo`, and an account that can push to the repo or to the checker's own, where its releases come from,
  turn it amber. What GitHub does not say is shown as unknown, not as fine.
- The **Standing options**, **PR commands** and **Tests added to suites** cards say when their job last started a
  pass. **Last refresh** says the same for the build watcher. Before the first pass after the service starts, a card
  says when that pass is due, for example "first sweep in 3m". The **Warmer** card says when the first warm cycle is
  due. If the pass has not started a minute after that, the card says "never" in amber: the job may be stuck.
- Signed-in viewers also see what the check found and the settings in effect. Only operators see when `GITHUB_TOKEN`
  expires and in how many days. Other signed-in viewers see only whether it expires within 14 days.
- Operators are the users `PRC_ADMINS` names. They also see the log messages, who restarted or flushed last, the
  operators' names in the settings, **Restart service**, **Flush caches** and the **Users** tab. After a change of the
  server key, they see the **New tokens wanted** card: how many users' options still wait for new tokens. Other
  viewers see only how many warnings and errors were logged. Signed-in viewers among them also see how many operators
  there are.
  Without `PRC_ADMINS` nobody is an operator. The status page then says how to name operators.

## Everything else

- **The top bar**: every page has the same menu with **PRs**, **Flaky tests** and **Status**. The page you are on is
  underlined. The theme selector is on every page too. Once you are logged in, the PR page shows your name with
  **Log out** beside it. **⚙ Settings** is at the top right and opens the separate account settings page. An operator's name has
  **operator** next to it.
- **Update**: when a newer release is out, operators see **Update to vX.Y.Z** in the top bar, and a **what's new** link
  to its notes. After a deploy, open tabs show **UI updated — reload**.
- **Your own login**: everyone logs in with their own TeamCity token, kept encrypted in an HttpOnly cookie. The
  server keeps a list of your logins without tokens, and there is no shared TeamCity account. A token TeamCity has
  revoked sends you back to the login form. The checker asks TeamCity whether your token still works before these
  actions: **Link GitHub**, **Unlink**, a switch in ⚙, ending your other logins, and the operator's buttons. With a
  revoked token, the action does not happen. **Log out** ends your login in this browser, copies of its cookie
  included. [Your logins](#your-logins) in ⚙ lists the others, and you can end them there. A login left unused for
  400 days ends too, and your 51st login ends an older one. TeamCity may reject 3 login attempts from one network
  address in 10 minutes; past that, the login form says how long to wait. Each attempt with a rejected token counts,
  even when the same token is sent again. A login that works does not count.
- **Four themes**: Light, Dark, JetBrains (dense, with status stripes) and Terminal (monospace, bracket buttons). The
  choice is kept per browser, with no flash on load.
- **Warming**: the heavy lifting is cached and pre-warmed in the background, so opening a PR is usually instant. There
  is no service account: warming runs on real users' TeamCity tokens. Every logged-in request donates one, and any
  **standing option** other than PR commands alone keeps yours in the pool while it is on, which keeps the background
  work (warming, the re-analysis of a finished run, live run states) going while nobody has the page open.
