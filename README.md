# Ignite PR Checker

**English** · [Русский](README.ru.md)

Ignite PR Checker answers one question for an [Apache Ignite](https://github.com/apache/ignite) contributor: which
test failures in my pull request's run did my change cause? It reads the project's TeamCity, ci2, compares each failure
with master and with other PRs, and filters out what they explain, with the reason for each. From the same page you
start RunAll, re-run suites, and post the verdict to the JIRA ticket or the pull request.

There is no database and no shared TeamCity account. Every user works under their own ci2 token, and the state is a
few JSON files on disk.

➡ **Live instance: <https://ignite-pr-checker.is-a.dev>**. Log in with your ci2 access token.

➡ **[Feature tour](docs/features.md)**. It starts with the [verdict glossary](docs/features.md#verdict-glossary): what
each label means and what to do about it.

![The PR page: the verdict comes first, then the broken suites, the blockers and the tests that recently started failing](docs/img/pr-page.jpg)

## What it does

- **The verdict.** The PR page splits the failures of the PR's latest RunAll into blockers, tests that recently
  started failing, broken suites and failures not blamed on the PR. A line under each test gives its reason. When
  tests of a suite have the same reason, the line appears once above them. See
  [Reading the verdict](docs/features.md#reading-the-verdict).
- **Runs.** Run RunAll or re-run suites from the page, at the top of the ci2 queue if you are in a hurry. The runs row
  shows what is queued and running, with estimates; **Cancel my runs** stops yours. See
  [Iterating on a fix](docs/features.md#iterating-on-a-fix).
- **Standing options.** ⚙ groups them by what they need. Auto re-run of failed suites and a PR comment for each of
  your runs need only your TeamCity login. A JIRA visa needs a JIRA token, PR commands need your linked GitHub
  account, and a checkstyle fix and tests added to suites need a GitHub token for commits. The options work while the
  page is closed. When something is missing, ⚙ and the PR replies say what to do. See
  [Standing options](docs/features.md#standing-options).
- **In Russian.** Choose Русский under **Language** in ⚙ and press **Save**: every page of the checker then shows in
  Russian in every browser you log in from. PR comments and visas stay in English. See
  [Language](docs/features.md#language).
- **PR commands.** With **PR commands** on and your GitHub account linked once with **Link GitHub**, a `/run-all`
  comment on any pull request queues RunAll under your TeamCity account. The checker's GitHub account answers: a
  comment of its own tells how the run goes, and a new one mentions you when the verdict is ready. See
  [Working from the PR](docs/features.md#working-from-the-pr-commands).
- **The flaky board** at `/flaky.html`: tests that fail on master, flaky ones first, then the ones that broke. See
  [The flaky board](docs/features.md#the-flaky-board-flakyhtml).
- **The status page**: health, memory, the warmer, TeamCity, GitHub and JIRA calls, the settings in effect. Live:
  <https://ignite-pr-checker.is-a.dev/status.html>.

## How a failure becomes a blocker

Each failed test is judged in each suite on its own. The same test can run in several suites (the C++ tests run on
Windows, Linux and Clang), and a pass on one platform says nothing about another.

A failed test is a **blocker** when all of these hold:

- **The branch keeps failing it.** It failed in each of its last 3 finished runs in that suite on the PR branch, or in
  every run when there were fewer: on a PR's first RunAll one failure is enough. Failing every run of the PR's current
  commit, two or more, counts too. Each run is matched to the commit it built, so a pass on older code does not clear
  a failure.
- **Master does not explain it.** It did not fail in the last 100 runs of that suite on master on the PR's JDK. A
  master failure makes the test pre-existing, unless that failure is rare and old (at most 2% of the runs, none of the
  newest 10) and the PR's failures in a row are too many to be chance, or master fails it only at a
  `TEST_SCALE_FACTOR` the PR did not run at.
- **Other PRs do not explain it.** It does not fail on the branches of 3 or more other PRs, unless the PR's failures in
  a row outweigh their rate.

A test that master and other PRs do not explain, but that the branch fails too little to block, goes to **Recently
started failing** when it failed the only run of newly pushed code after passing on the branch before, or its last two
or more runs on the same code after passing on that code. A re-run decides. Everything else is not blamed on the PR and
is listed with its reason. Suites with no reliable result (a compilation error, a timeout, a crash, a failed build step)
are listed apart as broken suites. The [glossary](docs/features.md#verdict-glossary) has every reason and threshold.

## Accounts and tokens

- **Users** log in with their own ci2 access token. It travels encrypted (AES-GCM) in an HttpOnly session cookie, and
  the runs a user starts or cancels on the page go under their account. Until the user logs out, but no longer than
  about an hour after their last request, the server also holds the token in memory. It uses the token only to read
  from TeamCity, to pre-analyse open PRs for everyone. Any standing option keeps the token there while the option is
  on, unless PR commands is the only option on. When the host moves the server's memory to swap, the token goes to the
  disk with it, in plain text unless the swap is encrypted. Each browser the user logs in from is a login of its own,
  and the server keeps a list of them without tokens. **Log out** ends the login of the browser where the user clicks
  it. ⚙ lists the user's logins and can end any of the others. A login left unused for 400 days ends too, and a
  user's 51st login ends an older one. A copy of a browser's session cookie is the same login: it works until that
  login ends. The options in ⚙ belong to the account: they are the same in every login, and no logout changes them.
  TeamCity may reject 3 login attempts from one network address in 10 minutes. For all users together, the checker
  checks the tokens of at most 30 logins a minute with TeamCity. Each check is a call from the server's address, and if
  ci2 blocks that address, the checker stops for everyone. Past either limit, the login form says how long to wait.
  Each attempt with a rejected token counts against its address, the same token sent again too. A login that works,
  one that TeamCity could not check, and one that the shared limit stops do not count.
- **Standing options** store the tokens they need while they are on, encrypted with `SESSION_SECRET`: the TeamCity
  token with any option, the user's JIRA token for visas, and the user's GitHub token for the checkstyle autofix and
  adding tests to suites, the two options that commit. Visas come only from **Visa my runs in JIRA**. The page sends
  the JIRA token in the request that switches this option on. No cookie keeps the token. Copies of these tokens stay
  in the backups of the [cache directory](#the-cache-directory) for 2 days; backups older than 2 days keep the rest of
  the state, without the tokens.
- **GitHub account.** PR commands act on the comments of the GitHub account the user linked: with **Link GitHub** in
  ⚙, or with the GitHub token kept for the commits. **Link GitHub** goes through the checker's OAuth App, with no
  scope.
  GitHub asks the user to let the app see their public profile and sends them back with a code. The checker trades the
  code for a token, asks GitHub whose account it is, and revokes the token at once; it keeps the token nowhere. A link
  counts only in the login that began it, within 10 minutes, once. The checker keeps the login and the account's id,
  and takes a comment for a command only when its author has that id. The app account cannot be linked. **Unlink**
  forgets the account: PR commands go off, and so do the options that commit, with the GitHub token of that account.
- **The user's GitHub token** commits, and only that: the autofix's commit and the one adding tests to suites, to the
  branch of the user's own PR. It writes no comments, edits or reactions, and commits to `apache/ignite` only for a
  PR from a branch of `apache/ignite` itself. Best is a fine-grained token for the user's fork alone, with Contents:
  read and write. It cannot reach a PR from a branch of `apache/ignite` itself or of
  someone else's fork: those need a classic token with `public_repo`, which pushes to every public repository the user
  can push to, `apache/ignite` too for a committer. The checker refuses a classic token with `admin:*`, `delete_repo`
  or `workflow`, takes one with `repo` with a warning, and keeps none in a cookie: the page sends it with the switch.
- **Expiry.** The JIRA token acts with all the rights of the user's JIRA account (ASF JIRA has no token scopes). Give it
  and the GitHub token an expiry, say 90 days. When one expires, the options that use it switch off with a note in ⚙;
  once the user pastes a new one and switches them back on, the visas held back meanwhile go out, for runs no newer
  RunAll replaced. ⚙ shows how long each token has left, including the TeamCity token the user logged in with. A
  token's line turns amber from `expires in 14 days` on. TeamCity and JIRA list a user's tokens without their values.
  So ⚙ names an expiry only when all the user's working tokens expire at the same time. Otherwise it says the expiry
  is unknown. GitHub names the expiry of the token itself. The checker accepts a token without an expiry too. The
  checker asks each service about a token at most once an hour, when the user opens ⚙ or saves a token.
- **The app account** is the GitHub account of `GITHUB_TOKEN`. The checker reads GitHub under it, and everything it
  writes in a PR comes from it, for every user alike: verdict comments, which mention the run's starter, the stories of
  `/run-all`, reactions, replies and the reports of tests added to suites. It edits and deletes only its own comments.
  Use a separate account with no rights in `apache/*` and a classic token with the `public_repo` scope: a leaked token
  then cannot push to Apache Ignite. Give the token an expiry, say 90 days: a copy of a token without one works until
  someone revokes it. When GitHub makes its writes wait (a secondary rate limit) they go out later, and
  so do the writes past 40 an hour to one PR or 60 for one user; the status page shows writes that wait or that GitHub
  refuses. The PR page checks whether a running build tests the PR's head and whether the head moved since a run.
  Once GitHub names a PR's head, the checker reuses it for 30 seconds. While fewer than a fifth of the token's hourly
  calls are left, these checks do not ask GitHub. The checker's own work, such as PR commands, comments and visas,
  needs the rest. Without the token the options that write to GitHub are off. The checker checks the token at the start and every 6 hours, and the status page shows signed-in viewers
  what it found: whose token it is, whether it can write to the repo (a fine-grained token cannot, nor a classic one
  without `public_repo`), whether it expires within 14 days or never expires (a warning for each), and whether the
  account can push to the repo or to the checker's own, where its releases come from. What GitHub does not say shows
  as unknown. Only operators see the day the token expires and how many days it has left.

There is no shared JIRA account: visas go out under the JIRA token of the user they are for.

## Quick start

**Use it.** Open <https://ignite-pr-checker.is-a.dev> and log in with a ci2 access token (TeamCity: Profile → Access
Tokens). Pick a PR on the left, or type its number in the filter.

**Run your own instance** on Debian or Ubuntu, as root:

```bash
curl -fsSL https://raw.githubusercontent.com/anton-vinogradov/ignite-pr-checker/main/install.sh | sudo bash
```

Then point a DNS name at the host, serve the service through an HTTPS proxy, set `APP_PUBLIC_URL` in
`/etc/ignite-pr-checker/env` and restart the service; the installer prints these steps. Bind the service to loopback
(`SERVER_ADDRESS=127.0.0.1`, as the env template has it) and serve it through the proxy only: users paste tokens into
its login form. A minimal `Caddyfile`, which also compresses the large JSON answers:

```
{
	admin unix//run/caddy/admin.sock
}

prc.example.org {
	encode zstd gzip
	reverse_proxy 127.0.0.1:8080
}
```

The `admin` line moves Caddy's admin API off port 2019, which every local process can reach. So someone who uses a
vulnerability in the checker cannot reconfigure the proxy in front of it. These commands add a systemd drop-in to
Caddy's unit, so systemd creates the socket's directory when Caddy starts:

```bash
sudo mkdir -p /etc/systemd/system/caddy.service.d
printf '[Service]\nRuntimeDirectory=caddy\n' | sudo tee /etc/systemd/system/caddy.service.d/admin-socket.conf
sudo systemctl daemon-reload && sudo systemctl restart caddy
```

**Link GitHub** in ⚙ needs a GitHub OAuth App. Register it under the app account: GitHub → Settings → Developer
settings → OAuth Apps → New OAuth App. Application name: what users see on GitHub, e.g. `Ignite PR Checker`. Homepage
URL: the `APP_PUBLIC_URL`. Authorization callback URL: the `APP_PUBLIC_URL` with `/api/github/callback` at the end,
e.g. `https://prc.example.org/api/github/callback`. Leave Device Flow off. Then generate a client secret, put the
client ID and the secret into `GITHUB_OAUTH_CLIENT_ID` and `GITHUB_OAUTH_CLIENT_SECRET` in the env file, and restart
the service. Without them, or with an empty `APP_PUBLIC_URL`, ⚙ has no **Link GitHub**, and only a GitHub token for
commits links an account.

**Develop** with JDK 17:

```bash
./gradlew bootRun   # http://localhost:8080, the dev profile
./gradlew test
```

## Configuration

Settings are environment variables in `/etc/ignite-pr-checker/env`; restart the service after a change. The defaults
target Apache Ignite's ci2. Each start logs the settings in effect (`effective config: …`), and the status page lists
them for signed-in viewers. A value out of range falls back to its default, and the status page warns about it.
`install.sh` writes the env file from a template once and never touches it again: on an existing install, add new
settings by hand.

| Variable | Default | What for |
|---|---|---|
| `APP_PUBLIC_URL` | `https://ignite-pr-checker.is-a.dev`; empty in the env template | The https address users open. Links in PR comments, JIRA visas and replies point here. Set it on any other instance: while it is empty, the status page warns. |
| `SERVER_ADDRESS` | all interfaces; `127.0.0.1` in the env template | Where the service listens. Keep `127.0.0.1` behind an HTTPS proxy. |
| `SERVER_PORT` | `8080` | The port. |
| `SESSION_SECRET` | a random key per start; `install.sh` writes a stable one | Encrypts the session cookies and the stored tokens. Read [The cache directory](#the-cache-directory) before you change it. |
| `SESSION_COOKIE_SECURE` | `false`; `true` in the env template | Send the session cookie over HTTPS only. Set `false` only for a test install on plain HTTP. |
| `GITHUB_TOKEN` | none | Token of the app account, which writes every comment of the checker: a separate GitHub account with no rights in `apache/*`, a classic token with the `public_repo` scope. Without it GitHub answers the service at most 60 calls an hour, and the checker has no account to write with: the PR comment, PR commands, the checkstyle autofix and tests to suites are off. |
| `GITHUB_OAUTH_CLIENT_ID`, `GITHUB_OAUTH_CLIENT_SECRET` | none | The client ID and a client secret of the checker's GitHub OAuth App, for **Link GitHub** in ⚙. See [Quick start](#quick-start). Without them users link a GitHub account only with a GitHub token for commits. |
| `PRC_ADMINS` | none | TeamCity usernames of the operators, comma-separated. See [Operators](#operators). |
| `JAVA_OPTS` | none | Extra JVM flags; `run.sh` puts them after its defaults, e.g. `-Xmx768m`. See [Memory](#memory). |
| `TC_BASE_URL` | `https://ci2.ignite.apache.org/` | The TeamCity to read, with a slash at the end. |
| `TC_RUN_ALL_BUILD_TYPE` | `IgniteTests24Java8_RunAll` | The build type of the RunAll chain. |
| `GITHUB_REPO` | `apache/ignite` | Whose PRs are listed and checked. |
| `JIRA_BASE_URL` | `https://issues.apache.org/jira` | Where visas go and IGNITE ticket links point. |
| `MASTER_HISTORY_DEPTH` | `100` | Master runs of a test looked at, per suite. |
| `BLOCKER_FAIL_STREAK` | `3` | Failed branch runs in a row for a blocker. |
| `ANALYSIS_BASE_BRANCH` | `refs/heads/master` | The branch PR failures are compared with. |
| `ANALYSIS_CONCURRENCY` | `8` | TeamCity calls one analysis makes in parallel. |
| `ANALYSIS_CACHE_TTL_MINUTES` | `120` | How long a test's history and a verdict stay cached. |
| `ANALYSIS_REFRESH_AFTER_SECONDS` | `120` | A viewed verdict older than this asks TeamCity whether anything finished on the branch, and is recomputed if so. |
| `WARM_ENABLED` | `true` | Keep the newest open PRs analysed in the background. |
| `WARM_COUNT` | `50` | How many PRs. |
| `WARM_INTERVAL_MINUTES` | `10` | How often. |
| `WARM_TOKEN_TTL_MINUTES` | `60` | How long a user's token stays in the pool after their last request. |
| `AUTOMATION_ENABLED` | `true` | Let standing options and PR commands act in the background. |
| `PRC_PERSIST_ENABLED` | `true` | Keep state and caches on disk. |
| `PRC_CACHE_DIR` | `/opt/ignite-pr-checker/cache` | Where. |
| `PERSIST_INTERVAL_MINUTES` | `5` | How often the caches are written; state is written within a second of a change. |
| `UPDATE_ENABLED` | `true` | Offer the **Update** button. |
| `UPDATE_JAR_PATH` | `/opt/ignite-pr-checker/app.jar` | The running jar; the update request and `update-failed` sit beside it. |
| `PRC_LOG_FILE` | none, console only; the unit sets `/opt/ignite-pr-checker/logs/ignite-pr-checker.log` | The service's own log: a file a day, 30 days, at most 500 MB of old files. |
| `GITHUB_CACHE_SECONDS` | `300` | How long the list of open PRs is cached. |
| `TEAMCITY_READ_TIMEOUT`, `GITHUB_READ_TIMEOUT`, `JIRA_READ_TIMEOUT` | `60s` | How long a call may wait without data. |
| `GITHUB_API_URL` | `https://api.github.com` | For tests only. |
| `PRC_JAVA` | set in the unit by `install.sh` | The java `run.sh` starts. |

## Operation

### Install and update

`install.sh`, the one-liner above, installs the latest release or updates an existing install to it. Re-run it to get
new versions of the scripts and the systemd unit, not only of the jar. It:

- uses the `java` on the PATH if it is 17 or newer, and installs OpenJDK 17 otherwise;
- creates the `prc` service user. `/opt/ignite-pr-checker` with the jar and the scripts belongs to root, so the service
  cannot change the code it runs; it writes only to `cache/`, `update/`, `dumps/` and `logs/` there;
- writes `/etc/ignite-pr-checker/env` from the template, with a generated `SESSION_SECRET`, if the file is not there;
- writes `/etc/tmpfiles.d/ignite-pr-checker.conf`: while the service is stopped, systemd-tmpfiles deletes a heap dump
  older than 2 days at its daily clean;
- writes `update.sh`, `run.sh` and the unit `ignite-pr-checker`, installs the latest release through `update.sh`,
  starts the service and waits up to 60 s for it to answer.

**Update from the page.** When a newer release is out, the top bar shows **Update to vX.Y.Z** and a **what's new** link
to its release notes, or to the list of releases when it is not the next patch release. The button restarts the
service. Before the start, systemd runs `update.sh` as root, which:

- downloads exactly the release the button offered;
- installs it only if it is newer than the release that runs, so someone who uses a vulnerability in the service
  cannot roll the service back to an older release. `update.sh` finds the running release by the jar's sha256. On
  install, `update.sh` and `deploy.sh` record the jar's sha256 and its release number in `installed-jars`. If the jar
  was copied by hand, an update from the page fails;
- installs it only if its sha256 equals the digest GitHub lists for the release's `ignite-pr-checker.jar`;
- keeps the jar it replaces as `app.jar.prev`, unless it is the same jar;
- on a failure keeps the current jar and writes the reason to `/opt/ignite-pr-checker/update-failed`. The button then
  reads **Retry update to vX.Y.Z**, with the reason in its tooltip. The service starts either way.

**Install a given version.** `install.sh` always takes the latest release; `update.sh` installs the one you name, an
older one too:

```bash
sudo /opt/ignite-pr-checker/update.sh 1.23.1 && sudo systemctl restart ignite-pr-checker
```

**Roll back** to the jar that ran before the last update, install or deploy:

```bash
cd /opt/ignite-pr-checker && sudo install -m 644 app.jar.prev app.jar && sudo systemctl restart ignite-pr-checker
```

Use `install`, not `cp`: `cp` writes into the jar the running service still reads, and the service then fails to save
its state as it stops. A jar installed before v1.35.0 is not in `installed-jars`, so after a rollback to it an update
from the page fails. Install the next release with `update.sh` as shown above.

A jar without **Your logins** in ⚙ keeps no list of logins. After a rollback to it, a login ended in ⚙ or with
**Log out** works again where a copy of its cookie is left. Once such a copy is a day old, that jar issues it again
without the login. That copy also works after the update back. **Log out** in that jar logs out every browser of its
user, but after the update back the cookies this version issued before the rollback work again. In ⚙,
**Log out everywhere else** ends both. After the update back, ask the users who ended a login before the rollback, or
logged out during it, to press it.

The first start of a new build copies each state file to `<file>.before-<version>-built-<time>`. To undo what a bad
release wrote, stop the service, copy that file over the state file, and start the old jar. A copy older than 2 days
holds no tokens. A jar of v1.37.0 or older started on such a copy switches off the visa and the options that commit, as
if JIRA or GitHub had rejected the token. Each user switches them on again with their token.

### Service and logs

```bash
sudo systemctl status ignite-pr-checker
sudo systemctl restart ignite-pr-checker   # after a change in /etc/ignite-pr-checker/env
journalctl -u ignite-pr-checker -f
sudo tail -f /opt/ignite-pr-checker/logs/ignite-pr-checker.log
```

The service's own log keeps 30 days, a file a day. The status page keeps the recent warnings and errors across
restarts, for the [operators](#operators) to read. At the top it lists what is wrong now: a background job that
stopped, a state file it could not read, a setting out of range.

`application.yml` keeps the loggers below at INFO, whatever level the env file sets for their package or for root. At
DEBUG or TRACE they write tokens:

- Spring's: the text of a request body the service could not read, and the arguments of a request's handler
- httpclient's: the headers of GitHub calls
- the JDK's `HttpURLConnection`: the headers of TeamCity and JIRA calls
- Tomcat's: each request as it comes in, and the session cookie

### Memory

The JVM gets `-Xmx512m`. A JVM out of memory writes a heap dump to `/opt/ignite-pr-checker/dumps/` and exits, and
systemd starts it again; `run.sh` keeps only the newest dump. A dump holds decrypted tokens and the key made from
`SESSION_SECRET`, so only the service can read that directory. The service deletes the dump 2 days after the JVM wrote
it. While the service is stopped, systemd-tmpfiles deletes the dump instead, at its first daily clean after the dump is
2 days old. The rule for that needs systemd 249 or newer, as in Ubuntu 22.04: an older systemd refuses it. Until the
dump is deleted, the status page shows the **Out of memory** card: when the JVM ran out of memory and when the dump is
deleted. For more heap, set `JAVA_OPTS=-Xmx768m` in the env file and restart. The status page shows the heap with the
peak of its old generation, and the process's resident memory with its peak.

The JVM's memory holds decrypted tokens too, and the host may move it to swap. Encrypt the swap with a random key at
each boot, or the tokens can stay in the swap file.

### The cache directory

`/opt/ignite-pr-checker/cache` (`PRC_CACHE_DIR`) belongs to `prc`, mode 700:

| File | What it holds |
|---|---|
| `standing-visas.json` | Each user's standing options, their stored TeamCity, JIRA and GitHub tokens (encrypted; for a TeamCity token that TeamCity refused, only its SHA-256, to recognize that token again), GitHub logins and account ids, when and from which browser each account was linked, the comments and visas the options follow, the visas and comments a refused token held back, the ticket and comment of the visa of each of the last 2000 settled runs, the re-run waves, and a fingerprint of the key that encrypted the tokens. |
| `pr-commands.json` | The PR command poll: handled comments, run stories, who got the onboarding reply, the reactions and replies GitHub has not taken yet. |
| `suite-fixes.json` | Adding PRs' tests to suites: each head commit tried once, the commit pushed and the comment that reports it. |
| `reruns.json` | Queued and running builds the page and the options follow. |
| `admin-actions.json` | The last restart, update and flush: who and when. |
| `problems.json` | Recent warnings and errors for the status page. |
| `users.json` | Who used the service and when, when each user last ended all their other logins, and the language each chose for the pages. The server refuses session cookies that an earlier version of the checker issued before that moment. Names only, no tokens. |
| `logins.json` | Each user's logins: the browser and its system, when it logged in and when it was last seen. No tokens and no IP addresses. If the file loses a browser's login, that browser goes to the login form. |
| `journal.json` | Each user's journal in ⚙: each change of their settings and the browser it came from, each token a service refused or the checker could not read, each visa posted or edited with their JIRA token. The last 100 switches of options, the last 100 saved tokens and the last 100 other entries of each user. No tokens. |
| `analysis.json`, `flaky.json`, `suite-baseline.json`, `delta.json`, `github.json`, `metrics.json` | Caches: verdicts and what TeamCity said, the flaky board, master's test counts, run-to-run deltas, the open PRs, the status page's counters. Without them the service starts slower. |
| `merged/<pr>.json` | The verdict of a merged PR as it stood at the merge. Written once, never removed. |
| `backups/cache-YYYY-MM-DD.zip` | All snapshot files, zipped once a day, the newest 7 kept. `merged/` is not in them. |
| `<file>.bad-<time>` | A file that could not be read at the start, set aside; that part started empty. |
| `<file>.before-<version>-built-<time>` | A file as it was before a new build first ran; the newest 5 kept per file. |

State is written within a second of a change, the caches every 5 minutes, and everything at shutdown.

Backups and the `.before-` and `.bad-` files keep their tokens for 2 days after they are made. Then the service takes
the tokens out of them and keeps the rest; it looks for such copies every 10 minutes. In a state restored from such a
copy, each user's options stay on but pause until the user brings the missing tokens. The TeamCity token comes back
when the user opens the checker or logs in. Until then the reply to a `/run-all` and the story of a running `/run-all`
say that the state was restored from a backup. ⚙ asks the user to save the JIRA token and the GitHub token for commits
again. Until the user saves the JIRA token, the comment that brings the verdict says so too. Until the user saves the
GitHub token, so does the story of a `/run-all` whose checkstyle fix waits for it. A `.bad-` file that is not JSON
keeps its tokens: the log names it, so delete it once you no longer need it.

The tokens in these files, the backups included, are encrypted with `SESSION_SECRET`. Next to them
`standing-visas.json` keeps a fingerprint of the key: an HMAC of a fixed text, which reveals nothing of the key.
Changing the secret logs everyone out. At the next start the fingerprint does not match the new key, so the checker
drops each stored token it can no longer read. The options stay on and wait for new tokens. ⚙ and the journal of each
such user say that the server key was changed and which tokens to save again. Logging in again stores a new TeamCity
token; a new JIRA or GitHub token goes into the form ⚙ opens for it. Until the user logs in again, the checker replies
to a `/run-all` that the key was changed, and the story of a running `/run-all` waits and says so. Until the user saves
a new JIRA token, the comment that brings the verdict says why no visa came. The status page shows the operators how
many users still wait. The first command from each linked GitHub account after the change gets the line
`ℹ️ First command from @x for ci2 user y` in the PR again. When the key did not change but the checker cannot read a
stored token, it drops that token the same way. ⚙ then says that the checker could not read it. A state restored from
a copy without tokens under the same key is another case: ⚙ says that the state was restored from a backup, and the
status page does not count those users. A copy made before the key change reads as a key change: its users store new
tokens, and the status page counts them. Change the secret only if it leaked.

### If a secret leaked

Act on any sign that someone could read the service's secrets: a stranger on the host, a copy of the env file, a heap
dump or the swap file in other hands. Whoever has `SESSION_SECRET` decrypts every stored token, the backups' too, and
can make a session cookie for any user.

1. Replace `SESSION_SECRET`, `GITHUB_TOKEN` and `GITHUB_OAUTH_CLIENT_SECRET` in the env file and restart the service
   once. Revoke the old app token and the old client secret on GitHub. Restart Caddy too: it then runs the `Caddyfile`
   again, not a configuration someone pushed through its admin API.
2. Ask every user in `users.json` to make new TeamCity, JIRA and GitHub tokens and to revoke the old ones in ci2, JIRA
   and GitHub. Changing the secret logs everyone out; [The cache directory](#the-cache-directory) says what it does to
   the stored tokens. The **New tokens wanted** card on the status page counts the users whose options still wait for
   new tokens. Ask the users to check the line of their GitHub account and the journal in ⚙, and to press **Unlink**
   if the account there is not theirs: with the secret, anyone could link their own account to any user.
3. If you like, delete the copies of the state whose modification time is within the last 2 days: the files in
   `cache/backups/`, `*.before-*` and `*.bad-*`. They hold the old tokens, encrypted with the old `SESSION_SECRET`.
   Also delete each copy the log names after "could not take the tokens out of": it may still hold its tokens. The
   other older copies hold no tokens, and the state can still be rolled back to them.

### Operators

Operators are the TeamCity users that `PRC_ADMINS` names, comma-separated. Only they see the log messages on the
status page, who restarted and who flushed last, the operators' names in the settings, the day `GITHUB_TOKEN` expires,
and the list on the **Users** tab. Only they can press **Restart service**, **Update** and **Flush caches**. The
server checks this itself: it sends these to nobody else and refuses anyone else's press. The PR page shows
**operator** next to an operator's name. Other logged-in users see how many warnings and errors were logged, but not
the messages. They also see how many operators there are, but not who. Every press is logged with the operator's name.

Without `PRC_ADMINS`, nobody is an operator. Nobody can restart the service, update it or flush its caches from the
pages. Nobody sees the log messages on the status page either: read them on the server with
`journalctl -u ignite-pr-checker` or in the service's own log.
To name operators, set `PRC_ADMINS` in `/etc/ignite-pr-checker/env` to their TeamCity usernames, comma-separated, and
restart the service.

### Releases

CI builds a release from a tag:

```bash
git tag v1.24.0 && git push origin v1.24.0
```

The release workflow runs all the tests and publishes the jar as `ignite-pr-checker.jar`. Its notes are made of the
**What changes for users** section of each PR merged since the previous tag, with a warning when `TestVerdict.RULES`
changed: every verdict is then computed again after the update.

After **What changes for users**, the notes have a **Dependencies to update** section, and GitHub's list of the merged
PRs comes last. The section names the platforms and libraries of the checker whose free support has ended or ends within
90 days. The support end dates come from endoflife.date. It checks Spring Boot, Spring Framework, Gradle, Java (Eclipse
Temurin), Tomcat and Log4j. It also lists the open Dependabot PRs. When endoflife.date or GitHub does not answer, the
section says "could not check", and the release still comes out. Updates skipped on purpose are under `ignore` in
`.github/dependabot.yml`, each with its reason. For example, Dependabot offers no new major version of checkstyle, since
the checker keeps the version Ignite's own build runs.

For the maintainer: fill **What changes for users** in each PR (the template has it), release a change of the verdict
rules as a minor version, and put small PRs into one release.

### Shipping a local build

`deploy.sh` builds and tests the jar on your machine, copies it over SSH (`PRC_SSH_HOST`, by default `ignite-prc`) and
restarts the service. It refuses uncommitted changes to what goes into the jar (`--force` ships a build versioned
`…-dirty`). It keeps the replaced jar as `app.jar.prev` only if that jar was answering, waits up to 60 s for the new
version, and prints the rollback command if it does not come up.

## Architecture

One Spring Boot application; the packages under `com.github.igniteprchecker`:

| Package | What it does |
|---|---|
| `tc` | The TeamCity REST client, and `RerunTracker`, which follows queued and running builds. What a user does goes under their own token; background reads borrow other users' tokens and only read with them. |
| `analysis` | The verdict: `ChainCollector` walks the RunAll chain, `BlockerAnalyzer` judges each failure, plus caveats, root causes, the flaky board, merged PRs' verdicts, and the warmer with its token pool. |
| `jira` | Verdict texts (`VisaService`), and the standing options that settle runs with re-runs, comments and visas (`StandingVisas`). |
| `github` | The GitHub client (PR list, comments, reactions, commits) and the PR command poll (`PrCommands`). |
| `style` | The checkstyle autofix of the author's own PR. |
| `web` | The HTTP API, login, operator actions, security headers. |
| `persist` | Snapshots of state and caches on disk, and their backups. |
| `health`, `metrics` | The status page: recent problems, health of the background jobs, call counters, memory. |
| `update` | The in-app update, which asks `update.sh` for a release. |
| `config`, `session` | Settings, and the encrypted session cookie. |

The pages are static files, `index.html`, `flaky.html` and `status.html`, with their code in `static/*.js` and the
shared helpers in `static/common.js`.

## Development

- `./gradlew bootRun` uses the `dev` profile: it listens on `127.0.0.1:8080`, keeps its state in `./build/prc-cache`,
  and neither warms PRs, nor acts on PRs in the background, nor updates itself. Opening a PR still analyses it on ci2
  under your token. To try the warmer: `WARM_ENABLED=true WARM_COUNT=3 ./gradlew bootRun`.
- `./gradlew test` runs the tests, the page scripts under node included (skipped without node), and writes a coverage
  report to `build/reports/jacoco/test/html`. CI runs `./gradlew build` and `node --check` on every page script.
- The docs come in pairs, English and Russian. Change both: `DocParityTest` compares them section by section,
  counting paragraphs, list items and table rows, and matching the numbers, the quoted code and the bold names the
  Russian keeps in English. It does not read what the sentences say.

## License

Apache License 2.0, see [LICENSE](LICENSE).
