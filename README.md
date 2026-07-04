# Ignite PR Checker

A small, focused tool for an [Apache Ignite](https://github.com/apache/ignite)-style TeamCity setup.
It answers one question well — **which tests did my pull request actually break?** — and lets you act
on it:

1. **Show the real blockers** for a PR: tests that failed in your run and look caused by *your change*,
   with pre-existing and flaky-on-master failures filtered out as noise.
2. **Trigger runs** from the same page: the whole RunAll chain, or a re-run of just the blocker suites
   (or one suite), at the head of the queue if you're in a hurry.

It's a deliberately lightweight, self-updating alternative to the legacy
[ignite-teamcity-bot](https://github.com/apache/ignite-teamcity-bot): no database, no GitHub/JIRA
integration, no notifications — a thin layer over the TeamCity REST API plus the one piece of logic
that matters. Every user works under their own TeamCity token.

## The core idea: what is a "blocker"?

A test that failed in your PR's latest **RunAll** is a **blocker** (broken *by your change*) only if
**all** of these hold:

- **Clean on master** — it does **not** fail in the last `MASTER_HISTORY_DEPTH` (default **100**) runs of
  that test on the base branch. Any failure there means it's pre-existing or flaky, not your fault →
  filtered out as noise (with the reason shown).
- **Still failing in the last finished run** on the PR branch — a later re-run that passed clears it
  (the failure wasn't reproducible). The verdict, its link and its history are always anchored to that
  **last finished run**, not to the RunAll it was first discovered in.
- If the test has **no master history at all**, it's kept as a blocker ("can't prove it's pre-existing").

Each blocker also shows a **pass/fail history strip** of its finished runs on the branch (green = passed,
red = failed, oldest → newest) — like the bot's, but computed on the fly.

Everything else is listed separately as filtered-out noise, each with the reason. History is read live
from the TeamCity REST API; there is no datastore to maintain.

## What's on the page

- A left pane lists the repo's **open PRs** (most-recently-updated first); click one to analyse it, or
  put any number in the URL (`?pr=12345`) to open it directly — even if it isn't in the list.
- Blockers are grouped **by suite** (the suite header links to its CI run); each test links straight to
  its failure **in the last finished run**.
- **Trigger** controls: `RunAll` and `Blockers` (rerun the blocker suites), each *plain* or *at the top
  of the queue*, plus a per-suite **Rerun / Rerun top**, a live list of your current runs, and **Cancel all**.
- A public **[status page](src/main/resources/static/status.html)** (`/status.html`) with service metrics:
  TeamCity/GitHub calls **by category over the last hour**, a per-minute chart, success rate and latency,
  JVM/cache internals, and a **health** indicator with recent WARN/ERROR from the log.

## Architecture

Single Spring Boot app, no database:

| Component | Responsibility |
|---|---|
| `TcClient` | Thin wrapper over the TeamCity REST API: find the latest finished RunAll, expand snapshot deps, failed tests, per-test master history, per-branch runs, trigger/cancel builds. Every call takes the caller's token. |
| `ChainCollector` | Walks a composite RunAll build into its dependency suites and collects the failed tests. |
| `BlockerAnalyzer` | The classifier: master-clean + still-failing-in-last-run → blocker vs noise; runs per-test lookups in parallel; caches results. |
| `AnalysisCache` / `TtlCache` | In-memory caches (per-build result, per-test master history), shared across users and PRs. |
| `Warmer` / `TokenPool` | Keeps the newest PRs pre-analysed in the background, spread across logged-in users' donated tokens; **cache-aware** (only recomputes PRs whose RunAll build changed). |
| `CacheStore` / `SnapshotCache` | Snapshots the caches to disk so a restart/redeploy starts warm instead of re-hammering TeamCity. |
| `GithubClient` | Lists the repo's open PRs for the nav pane (cached to stay within the API rate limit). |
| `Metrics` / `LogTracker` / `StatusController` | Powers the public status page (rolling last-hour call metrics + log health). |
| `UpdateService` | In-app self-update: checks GitHub releases and, on request, swaps the jar and restarts. |
| Web controllers | `/api/analyze`, `/api/refresh`, `/api/trigger`, `/api/rerun-blockers`, `/api/rerun-suite`, `/api/runs`, `/api/cancel-all`, `/api/prs`, `/api/config`, `/api/status`, `/api/version`, `/api/update`, `/api/login`·`/logout`·`/me`. |

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
site and log in with your own TeamCity token. Put it behind HTTPS (e.g. Caddy) with `SESSION_COOKIE_SECURE=true`.

Once running, a new release is picked up in the UI: an **Update to vX.Y.Z** button appears in the top bar;
any logged-in user can click it and the service swaps the jar and restarts itself — no server access needed.

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
