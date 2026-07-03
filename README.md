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

## Configuration

All connection details live in config only (never in code). See
[`application.yml`](src/main/resources/application.yml). Provide secrets via environment variables:

```bash
export TC_BASE_URL="https://your-teamcity-host/"
export TC_TOKEN="<personal access token>"          # Profile -> Access Tokens
export TC_RUN_ALL_BUILD_TYPE="IgniteTests24Java8_RunAll"
```

## Build & run

Requires JDK 17+.

```bash
./gradlew bootRun
# then open http://localhost:8080
```

## Status

Early scaffold. Analysis and trigger endpoints are being built incrementally.

## License

Apache License 2.0 — see [LICENSE](LICENSE).
