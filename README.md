# Ignite PR Checker

**English** · [Русский](README.ru.md)

A small, focused tool for an [Apache Ignite](https://github.com/apache/ignite)-style TeamCity setup.
It answers one question well — **which tests did my pull request actually break?** — and lets you act
on it:

1. **Show the real blockers** for a PR: tests that failed in your run and look caused by *your change*,
   with pre-existing and flaky-on-master failures filtered out as noise.
2. **Trigger runs** from the same page: the whole RunAll chain, or a re-run of just the blocker suites
   (or one suite), at the head of the queue if you're in a hurry.

➡ **Live instance for Apache Ignite contributors: <https://ignite-pr-checker.is-a.dev>** — log in with your ci2 token.

➡ **[Feature tour with screenshots](docs/features.md)**

It's a deliberately lightweight, self-updating alternative to the legacy
[ignite-teamcity-bot](https://github.com/apache/ignite-teamcity-bot): no database, no state to babysit —
a thin layer over the TeamCity REST API plus the one piece of logic that matters. It can also post the
verdict (a "visa") to the PR's JIRA ticket — manually, once when the run finishes, or automatically for
every run you trigger. Every user works under their own TeamCity token.

You can drive it without leaving the pull request: comment **`/run-all`** (or `/run-all top`, or
`/top` to promote your queued run) — the chain runs under your own accounts, your comment carries a
live ETA, and the verdict lands in the PR when the run settles. See
[Working from the PR](docs/features.md#working-from-the-pr-commands).

## The core idea: what is a "blocker"?

A test that failed in your PR's latest **RunAll** is a **blocker** (broken *by your change*) only if
**all** of these hold:

- **Clean on master** — it does **not** fail in the last `MASTER_HISTORY_DEPTH` (default **100**) runs of
  that test on the base branch. Any failure there means it's pre-existing or flaky, not your fault →
  filtered out as noise (with the reason shown).
- **Fails consistently on the branch** — it fails in all of the last `BLOCKER_FAIL_STREAK` (default **3**)
  finished runs of its suite on the PR branch, with no pass in that window. A pass *on the same code*
  means the failure isn't caused by the change:
  - failed only the latest run but passed just before → filtered as *flaky on branch*;
  - failing the last 2+ runs but passed earlier → a separate **"Recently started failing"** card — a
    fresh break to watch, not yet a hard blocker.

  "The same code" is taken literally: every run is matched against the **VCS revision its build ran
  on**, so only runs of the revision under review can clear a failure. A green run from before the
  commits that broke the test proves nothing and is discounted (dimmed in the history strip). That
  makes the first failure on a fresh revision a *watch* item — the suite is re-run automatically, and
  a second failure on that same revision makes it a blocker.

  The verdict, its links and its history strips are always anchored to the **newest finished run**.
- If the test has **no master history at all**, it's kept as a blocker ("can't prove it's pre-existing").

Suites that didn't produce a reliable run — compilation failure, **execution timeout, out-of-memory,
JVM crash**, or a chain interrupted/cancelled mid-way — are surfaced as **broken suites** instead of
being mined for cascade failures, and an aborted chain gets an explicit *RunAll interrupted* banner.
While a newer RunAll is still running (or ended cancelled), results of its finished suites are **folded
into the verdict live** — no waiting for the whole ~4-hour chain.

Each blocker also shows a **pass/fail history strip** of its finished runs on the branch (green = passed,
red = failed, oldest → newest) — like the bot's, but computed on the fly.

Everything else is listed separately as filtered-out noise, each with the reason. History is read live
from the TeamCity REST API; there is no datastore to maintain.

## What's on the page

- A left pane lists the repo's **open PRs** (most-recently-updated first); click one to analyse it, or
  put any number in the URL (`?pr=12345`) to open it directly — even if it isn't in the list.
- Blockers are grouped **by suite** (the suite header links to its CI run); each test links straight to
  its failure **in the last finished run**.
- **Trigger** controls: `RunAll` (*plain* or *at the top of the queue*), a **Rerun / Rerun top** pair on
  every section (broken suites, blockers, recently-started, filtered) and on each individual suite, a live
  list of your current runs with queue-aware ETAs, and **Cancel all**.
- **JIRA visa**: post the verdict as a comment to the PR's `IGNITE-XXXXX` ticket — one click, armed
  one-shot for when the current run finishes (**Auto visa**), or a standing option in settings (⚙) that
  visas **every** run you trigger. Your JIRA PAT travels in the encrypted session cookie; for the
  deferred variants it is stored encrypted only until the visa is posted / while the option is on.
- A public **[status page](src/main/resources/static/status.html)** (`/status.html`) with service metrics:
  TeamCity/GitHub calls **by category over the last hour**, a per-minute chart, success rate and latency,
  JVM/cache internals, and a **health** indicator with recent WARN/ERROR from the log.

## Architecture

Single Spring Boot app, no database:

| Component | Responsibility |
|---|---|
| `TcClient` | Thin wrapper over the TeamCity REST API: find the latest finished RunAll, expand snapshot deps, failed tests, per-test master history, per-branch runs, trigger/cancel builds. Every call takes the caller's token. |
| `ChainCollector` | Walks a composite RunAll build into its dependency suites and collects the failed tests (plus broken suites that failed without running tests, and the run's ran/reused composition). |
| `BlockerAnalyzer` | The classifier: master-clean + consistent failure over the last N branch runs → blocker / recently-started (watch) / noise; runs per-test lookups in parallel; caches results. |
| `AnalysisCache` / `TtlCache` | In-memory caches (per-build result, per-test master history), shared across users and PRs. |
| `Warmer` / `TokenPool` | Keeps the newest PRs pre-analysed in the background, spread across logged-in users' donated tokens; **cache-aware** (only recomputes PRs whose RunAll build changed). |
| `RunDeltaStore` / `FlakyStats` / `RerunTracker` | Blocker delta & trend between runs; the persistent fix-master queue; live queued/running states of re-runs. |
| `CacheStore` / `SnapshotCache` | Snapshots the caches to disk so a restart/redeploy starts warm instead of re-hammering TeamCity. |
| `GithubClient` | Lists the repo's open PRs for the nav pane (cached to stay within the API rate limit). |
| `Metrics` / `LogTracker` / `StatusController` | Powers the public status page (rolling last-hour call metrics + log health). |
| `UpdateService` | In-app self-update: checks GitHub releases and, on request, swaps the jar and restarts. |
| `JiraClient` / `VisaService` / `VisaSubscriptions` / `StandingVisas` | The JIRA "visa": composes the verdict in wiki markup and posts it to the ticket — manually, one-shot when a tracked run finishes, or via a periodic sweep for every enrolled user's runs (tokens encrypted at rest only while needed). |
| `UserDirectory` | Who has used the tool (name, activity, logins) — backs the status page's **Users** tab. |
| Web controllers | `/api/analyze`, `/api/refresh`, `/api/causes`, `/api/delta`, `/api/progress`, `/api/trigger`, `/api/rerun-suite(s)`, `/api/runs`, `/api/cancel-all`, `/api/jira-visa`, `/api/auto-visa(-all)`, `/api/users`, `/api/restart`, `/api/prs`, `/api/config`, `/api/status`, `/api/version`, `/api/update`, `/api/login`·`/logout`·`/me`, … |

## Authentication

There are **no credentials in config**. Every user logs in with their **own** TeamCity access token
(TeamCity: *Profile → Access Tokens*). The token is validated and then encrypted (AES-GCM) into an
**HttpOnly session cookie** — the server keeps no session store, so logins survive restarts/redeploys,
and the login lasts until you log out. All TeamCity calls run under that user's own permissions. Serve
the app over HTTPS (the token travels in the cookie) and set `SESSION_COOKIE_SECURE=true`.

## Configuration

Config holds only **non-secret, deployment-wide** settings; see
[`application.yml`](src/main/resources/application.yml). Defaults target Apache Ignite's CI. Override per
deployment via environment variables:

```bash
export TC_BASE_URL="https://your-teamcity-host/"        # default: https://ci2.ignite.apache.org/
export TC_RUN_ALL_BUILD_TYPE="IgniteTests24Java8_RunAll"
export MASTER_HISTORY_DEPTH=100                         # master runs checked per test for the blocker rule
export BLOCKER_FAIL_STREAK=3                            # consecutive branch-run failures required for a blocker
export APP_PUBLIC_URL="https://your.host"               # absolute links in posted JIRA visas
export GITHUB_REPO="apache/ignite"                      # whose open PRs populate the nav pane
export GITHUB_TOKEN=...                                 # optional: raise the GitHub API limit (60 -> 5000/h)
export SESSION_SECRET=...                               # stable secret so logins survive restarts (install.sh generates one)
export SESSION_COOKIE_SECURE=true                       # set once behind HTTPS (e.g. Caddy)
```

## Install / update on a server (one line)

Debian/Ubuntu, as root. The same command installs and updates to the latest release (your
`/etc/ignite-pr-checker/env` config is preserved):

```bash
curl -fsSL https://raw.githubusercontent.com/anton-vinogradov/ignite-pr-checker/main/install.sh | sudo bash
```

It installs a JRE, a `prc` service user, a `systemd` unit (via a small `run.sh` wrapper that also performs
self-updates), pulls the latest released jar, and starts the service. No secrets to configure — open the
site and log in with your own TeamCity token. Put it behind HTTPS (e.g. Caddy) with `SESSION_COOKIE_SECURE=true`,
and let the proxy compress responses — the analysis JSON is sizeable (a few hundred KB) and compresses ~10×, so
this is the difference between an instant and a sluggish load on a small box. A minimal `Caddyfile`:

```
your.host {
	encode zstd gzip
	reverse_proxy localhost:8080
}
```

Once running, a new release is picked up in the UI: an **Update to vX.Y.Z** button appears in the top bar;
any logged-in user can click it and the service swaps the jar and restarts itself — no server access needed.

## Running the installed service

`install.sh` registers a `systemd` service named **`ignite-pr-checker`**, enables it (so it starts on
boot), and runs it as the `prc` user on port **8080**. Manage it with the usual `systemctl`:

```bash
sudo systemctl status  ignite-pr-checker    # is it running?
sudo systemctl start   ignite-pr-checker    # start it
sudo systemctl stop    ignite-pr-checker    # stop it
sudo systemctl restart ignite-pr-checker    # restart (e.g. after editing config)
```

Follow the logs with:

```bash
journalctl -u ignite-pr-checker -f
```

Configuration lives in **`/etc/ignite-pr-checker/env`** (restart the service after editing it). The
app itself is at `/opt/ignite-pr-checker` (`app.jar` launched by `run.sh`). To update the binary,
click **Update to vX.Y.Z** in the UI or re-run the install one-liner — both preserve your config.

## Releases

Releases are built by CI. Cut one by pushing a tag:

```bash
git tag v0.1.0 && git push origin v0.1.0
```

The [`release`](.github/workflows/release.yml) workflow builds the fat jar (embedding the tag as the app
version) and publishes it as a GitHub Release asset named `ignite-pr-checker.jar`, which `install.sh` and
the self-updater always fetch from `/releases/latest/download/`.

## Build & run locally

Requires JDK 17.

```bash
./gradlew bootRun
# then open http://localhost:8080
```

For fast iteration against your own test host during development, [`deploy.sh`](deploy.sh) builds the jar
locally and ships it over SSH (`build → scp → restart`) without cutting a release.

## License

Apache License 2.0 — see [LICENSE](LICENSE).
