# Ignite PR Checker

A small, focused tool for working with an [Apache Ignite](https://github.com/apache/ignite)-style
TeamCity setup. It does two things — and nothing else:

1. **Trigger suites** on TeamCity for a pull request.
2. **Show the tests that actually broke because of your change** — filtering out
   pre-existing failures and flaky tests, so you don't waste time on noise.

It's a deliberately lightweight, easy-to-update alternative to the legacy
[ignite-teamcity-bot](https://github.com/apache/ignite-teamcity-bot): no distributed
storage, no GitHub/JIRA integration, no notifications — just a thin layer over the
TeamCity REST API plus the one piece of logic that matters.

## The core idea: what is a "blocker"?

A test that failed in your PR run is treated as a **blocker** (i.e. broken *by your change*)
only if all of the following hold, judged against recent history of the **base branch**:

- it **failed in your PR** run, **and**
- its base-branch **fail rate is low** (below `fail-rate-blocker-threshold-percents`, default **4%**), **and**
- it is **not flaky** — where "flaky" means it flips pass↔fail on builds that had **no code changes**
  (at least `flakiness-status-change-border` times, default **1**).

Everything else (tests that fail often in master anyway, or that flip without code changes) is
reported separately as noise, with the reason it was filtered out. This mirrors the proven
classification used by the original bot, but computes the history on the fly from TeamCity
instead of maintaining a dedicated datastore.

## Architecture

Single Spring Boot app, no database:

| Component | Responsibility |
|---|---|
| `TcClient` | Thin wrapper over the TeamCity REST API (trigger build, get build, snapshot deps, failed tests, test history). |
| `ChainCollector` | Walks a composite RunAll build into its dependency suites and collects failed tests. |
| `BlockerAnalyzer` | The core classifier — fetches base-branch history per failed test and decides blocker vs noise. |
| `AnalyzeController` | Serves `/api/analyze` and `/api/trigger`; a single static page renders the result. |

## Authentication

There are **no credentials in config**. Every user logs in with their **own** TeamCity access
token (TeamCity: *Profile → Access Tokens*); the tool validates it and keeps it only in a
server-side session referenced by an HttpOnly cookie — it is never written to disk. All TeamCity
calls run under that user's own permissions.

## Configuration

Config holds only **non-secret, deployment-wide** settings — which TeamCity to talk to, which
chain counts as "RunAll", and the analysis thresholds. See
[`application.yml`](src/main/resources/application.yml). The defaults target Apache Ignite's CI;
override per deployment via environment variables if needed:

```bash
export TC_BASE_URL="https://your-teamcity-host/"     # default: https://ci2.ignite.apache.org/
export TC_RUN_ALL_BUILD_TYPE="IgniteTests24Java8_RunAll"
export SESSION_COOKIE_SECURE=true                    # set once behind HTTPS (Caddy)
```

## Install / update on a server (one line)

Debian/Ubuntu, as root. The same command installs and updates to the latest release
(your `/etc/ignite-pr-checker/env` config is preserved):

```bash
curl -fsSL https://raw.githubusercontent.com/anton-vinogradov/ignite-pr-checker/main/install.sh | sudo bash
```

It installs a JRE, a `prc` service user, a `systemd` unit, pulls the latest released jar,
and starts the service. No secrets to configure — open the site and log in with your own
TeamCity token. (Put the service behind HTTPS, e.g. Caddy, and set `SESSION_COOKIE_SECURE=true`.)

## Releases

Releases are built by CI. Cut one by pushing a tag:

```bash
git tag v0.1.0 && git push origin v0.1.0
```

The [`release`](.github/workflows/release.yml) workflow builds the fat jar and publishes it
as a GitHub Release asset named `ignite-pr-checker.jar`, which `install.sh` always fetches
from `/releases/latest/download/`.

## Build & run locally

Requires JDK 17+.

```bash
./gradlew bootRun
# then open http://localhost:8080
```

For fast iteration against your own test host during development, [`deploy.sh`](deploy.sh)
builds the jar locally and ships it over SSH (`build → scp → restart`) without cutting a release.

## Status

Early scaffold. Analysis and trigger endpoints are being built incrementally.

## License

Apache License 2.0 — see [LICENSE](LICENSE).
