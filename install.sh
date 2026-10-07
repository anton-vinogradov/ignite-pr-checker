#!/usr/bin/env bash
#
# Install or update Ignite PR Checker from the latest GitHub release.
# One-liner (Debian/Ubuntu, run as root):
#
#   curl -fsSL https://raw.githubusercontent.com/anton-vinogradov/ignite-pr-checker/main/install.sh | sudo bash
#
# Re-running the same command updates an existing install to the latest release
# (the config in /etc/ignite-pr-checker/env is preserved).
#
set -euo pipefail

REPO="anton-vinogradov/ignite-pr-checker"
SERVICE="ignite-pr-checker"
APP_DIR="/opt/ignite-pr-checker"
ETC_DIR="/etc/ignite-pr-checker"

log() { printf '>> %s\n' "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

[ "$(id -u)" = 0 ] || die "run as root (e.g. pipe into 'sudo bash')"
command -v apt-get >/dev/null 2>&1 || die "this installer targets Debian/Ubuntu (apt-get not found)"

# 1. Java 17 or newer: on an older one the service fails at start, and systemd restarts it forever.
java_major() { "$1" -version 2>&1 | sed -n 's/.* version "\([0-9]*\).*/\1/p' | head -n 1; }
java_ok() {
    local major
    major="$(java_major "$1")"
    [ -n "$major" ] && [ "$major" -ge 17 ]
}

JAVA="$(command -v java || true)"
if [ -z "$JAVA" ] || ! java_ok "$JAVA"; then
    log "installing JRE 17 (found: ${JAVA:-no java}${JAVA:+, Java $(java_major "$JAVA")}) ..."
    export DEBIAN_FRONTEND=noninteractive
    apt-get update -qq
    apt-get install -y -qq openjdk-17-jre-headless >/dev/null
    JAVA=""
    for candidate in /usr/lib/jvm/java-17-openjdk-*/bin/java; do
        [ -x "$candidate" ] && JAVA="$candidate"
    done
    [ -n "$JAVA" ] && java_ok "$JAVA" || die "Java 17 did not install"
fi
log "using $JAVA (Java $(java_major "$JAVA"))"

# 2. service user + directories. The directory with the jar and the scripts belongs to root, so the service account
# cannot change the code it runs: a hole in the service does not outlive a restart.
id prc >/dev/null 2>&1 || useradd --system --no-create-home --shell /usr/sbin/nologin prc
install -d -o root -g root -m 755 "$APP_DIR"
install -d -o prc  -g prc  -m 700 "$APP_DIR/cache"
# Where the service asks for an update; update.sh reads it as root.
install -d -o prc  -g prc  -m 700 "$APP_DIR/update"
# Heap dumps hold whatever the JVM had in memory, decrypted tokens included: readable by the service only.
install -d -o prc  -g prc  -m 700 "$APP_DIR/dumps"
# The service's own log names users and PRs.
install -d -o prc  -g prc  -m 750 "$APP_DIR/logs"
install -d -o root -g prc  -m 750 "$ETC_DIR"

# 3. config (created once; never overwritten on update). Users log in with their own TeamCity
# token via the web UI; these are the instance's settings plus the session-cookie secret.
if [ ! -f "$ETC_DIR/env" ]; then
    log "writing config template at $ETC_DIR/env"
    cat > "$ETC_DIR/env" <<'ENV'
# Settings of this instance. install.sh writes this file once and never overwrites it; a change takes effect at
# "systemctl restart ignite-pr-checker". Each start logs the settings in effect; the status page lists them.

# The https address users open: links in PR comments, JIRA visas and onboarding replies point here. Required: while
# it is empty the status page reports it.
APP_PUBLIC_URL=
# Users paste tokens into the login form: listen on this machine only and serve it through an HTTPS proxy (Caddy).
SERVER_ADDRESS=127.0.0.1
#SERVER_PORT=8080
# Send the session cookie over HTTPS only. false only for a test install reached over plain HTTP.
SESSION_COOKIE_SECURE=true
# Token of the GitHub account the checker acts as: it lists the PRs and reads their comments for /run-all commands,
# and posts the replies, run narration and reactions. A classic token with the public_repo scope, of an account with
# no rights in apache/*. Without it the checker posts nothing to GitHub and may read only 60 times an hour.
#GITHUB_TOKEN=
# TeamCity usernames (comma-separated) of who operates this instance: only they may restart, update
# and flush it and see its users. Unset: any logged-in user may, but restart/update at most once per
# 10 minutes and flush once per hour.
#PRC_ADMINS=
# Optional overrides of the built-in defaults; uncomment to change.
#TC_BASE_URL=https://ci2.ignite.apache.org/
#TC_RUN_ALL_BUILD_TYPE=IgniteTests24Java8_RunAll
#JIRA_BASE_URL=https://issues.apache.org/jira
# How many recent master runs of a test to check: a PR failure is a blocker unless the test also
# fails at least once in these (default 100).
#MASTER_HISTORY_DEPTH=100
# Extra JVM flags, after the defaults (-Xmx512m -XX:+ExitOnOutOfMemoryError, heap dump on OOM), so a
# repeated flag wins, e.g. -Xmx768m. The status page shows the heap and the process's peak memory.
#JAVA_OPTS=
ENV
fi
# Ensure a stable session secret exists (so logins survive restarts/updates). Generated once.
if ! grep -q '^SESSION_SECRET=' "$ETC_DIR/env"; then
    log "generating SESSION_SECRET"
    printf 'SESSION_SECRET=%s\n' "$(head -c 32 /dev/urandom | base64)" >> "$ETC_DIR/env"
fi
chown root:prc "$ETC_DIR/env"
chmod 640 "$ETC_DIR/env"

# 4. update.sh: installs a release as root before each start, the one the service asked for (POST /api/update writes
# it into update/requested) or the one given as the argument. The download must match the sha256 GitHub records for
# the release's jar; the jar it replaces stays as app.jar.prev for a rollback. A failure keeps the current jar and
# leaves its reason in update-failed, which the page shows. Run by systemd it never fails the start.
cat > "$APP_DIR/update.sh" <<'UPDATE'
#!/usr/bin/env bash
set -uo pipefail
umask 022
APP_DIR=/opt/ignite-pr-checker
REPO=anton-vinogradov/ignite-pr-checker
JAR="$APP_DIR/app.jar"
NEW="$APP_DIR/app.jar.new"
REQUEST="$APP_DIR/update/requested"
FAILED="$APP_DIR/update-failed"

if [ $# -gt 0 ]; then
    version="$1"
    strict=true
else
    # update/ belongs to the service account: read a plain file only, never a link planted there.
    if [ ! -f "$REQUEST" ] || [ -L "$REQUEST" ]; then
        exit 0
    fi
    version="$(head -c 64 "$REQUEST" | tr -d '[:space:]')"
    rm -f "$REQUEST"
    strict=false
fi
version="${version#v}"

fail() {
    rm -f "$NEW"
    echo "update to v$version failed: $*" >&2
    printf '%s\t%s\t%s\n' "$version" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*" > "$FAILED"
    [ "$strict" = true ] && exit 1
    exit 0
}

if ! [[ "$version" =~ ^[0-9]+(\.[0-9]+){1,3}$ ]]; then
    version="?"
    fail "the request does not name a release"
fi

release="$(curl -fsSL -m 30 -H 'Accept: application/vnd.github+json' \
    "https://api.github.com/repos/$REPO/releases/tags/v$version")" || fail "GitHub did not describe release v$version"
expected="$(printf '%s' "$release" | tr -d '\n' \
    | grep -oE '"name": *"ignite-pr-checker\.jar"([^{}]|\{[^{}]*\})*"digest": *"sha256:[0-9a-f]{64}"' \
    | grep -oE '[0-9a-f]{64}' | head -n 1)"
[ -n "$expected" ] || fail "release v$version lists no sha256 for ignite-pr-checker.jar"

curl -fsSL -m 120 -o "$NEW" "https://github.com/$REPO/releases/download/v$version/ignite-pr-checker.jar" \
    || fail "could not download the jar of v$version"
actual="$(sha256sum "$NEW" | cut -d ' ' -f 1)"
[ "$actual" = "$expected" ] || fail "the download's sha256 $actual is not the release's $expected"

# install, not cp: an app.jar.prev the service account owned from an older install becomes root's.
if [ -f "$JAR" ]; then
    install -m 644 "$JAR" "$APP_DIR/app.jar.prev"
fi
mv -f "$NEW" "$JAR"
rm -f "$FAILED"
echo "installed v$version"
UPDATE
chown root:root "$APP_DIR/update.sh"
chmod 755 "$APP_DIR/update.sh"

# 5. the latest release, through update.sh
LATEST="$(curl -fsSL -m 30 -H 'Accept: application/vnd.github+json' \
    "https://api.github.com/repos/${REPO}/releases/latest" \
    | sed -n 's/.*"tag_name": *"v\{0,1\}\([^"]*\)".*/\1/p' | head -n 1)"
[ -n "$LATEST" ] || die "could not find the latest release of $REPO"
log "installing v$LATEST ..."
"$APP_DIR/update.sh" "$LATEST" || die "v$LATEST was not installed"
rm -f "$APP_DIR/.update-requested"

# 6. run.sh: starts the service as its user, after update.sh.
cat > "$APP_DIR/run.sh" <<'RUN'
#!/usr/bin/env bash
set -uo pipefail
APP_DIR=/opt/ignite-pr-checker
JAR="$APP_DIR/app.jar"

# A heap dump is as big as the heap: keep only the newest. The JVM names them java_pid<pid>.hprof.
DUMPS="$APP_DIR/dumps"
[ -d "$DUMPS" ] || mkdir -m 700 "$DUMPS"
# shellcheck disable=SC2012
ls -1t "$DUMPS"/*.hprof 2>/dev/null | tail -n +2 | while read -r old; do rm -f "$old"; done

# JAVA_OPTS comes from the env file and goes last, so a flag repeated there wins over the default.
# shellcheck disable=SC2086
exec "${PRC_JAVA:-java}" -Xmx512m -XX:+ExitOnOutOfMemoryError -XX:+HeapDumpOnOutOfMemoryError \
    -XX:HeapDumpPath="$DUMPS" ${JAVA_OPTS:-} -jar "$JAR"
RUN
chown root:root "$APP_DIR/run.sh"
chmod 755 "$APP_DIR/run.sh"

# 7. systemd unit (refreshed every run so unit changes propagate)
cat > "/etc/systemd/system/${SERVICE}.service" <<UNIT
[Unit]
Description=Ignite PR Checker
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=prc
Group=prc
# What the service writes is its own: snapshots hold stored tokens (encrypted), the log names users.
UMask=0077
Environment=PRC_JAVA=${JAVA}
# A month of the service's own log, daily files; journald on a shared host keeps far less.
Environment=PRC_LOG_FILE=${APP_DIR}/logs/ignite-pr-checker.log
EnvironmentFile=${ETC_DIR}/env
# As root ("+"): installs a release the service asked for, then the service starts as prc.
ExecStartPre=+${APP_DIR}/update.sh
ExecStart=${APP_DIR}/run.sh
# Room for update.sh to download a release before the start.
TimeoutStartSec=180
Restart=on-failure
RestartSec=5
# 143 is the JVM stopped by SIGTERM (systemctl stop/restart, a deploy); 75 is the restart the status page
# asks for, which systemd restarts though it is not a failure.
SuccessExitStatus=143 75
RestartForceExitStatus=75
NoNewPrivileges=true
ProtectSystem=full
PrivateTmp=true

[Install]
WantedBy=multi-user.target
UNIT

# 8. (re)start, and wait for the service to answer: it takes about 15 s to start.
systemctl daemon-reload
systemctl enable "$SERVICE" >/dev/null 2>&1 || true
systemctl restart "$SERVICE"

setting() { sed -n "s/^$1=//p" "$ETC_DIR/env" | tail -n 1; }
ADDR="$(setting SERVER_ADDRESS)"
case "$ADDR" in ''|0.0.0.0|::) ADDR=127.0.0.1 ;; esac
PORT="$(setting SERVER_PORT)"
RUNNING=""
for _ in $(seq 1 30); do
    sleep 2
    RUNNING="$(curl -fsS -m 5 "http://$ADDR:${PORT:-8080}/api/status" 2>/dev/null \
        | sed -n 's/^{"version":"\([^"]*\)".*/\1/p')" || true
    [ "$RUNNING" = "$LATEST" ] && break
done
if [ "$RUNNING" != "$LATEST" ]; then
    systemctl --no-pager --lines=20 status "$SERVICE" || true
    die "v$LATEST did not answer on http://$ADDR:${PORT:-8080} within 60 s (answering: ${RUNNING:-nothing});" \
        "see journalctl -u $SERVICE"
fi
log "v$RUNNING answers on http://$ADDR:${PORT:-8080}"

if [ -z "$(setting APP_PUBLIC_URL)" ]; then
    cat <<NEXT

Next:
  1. DNS: point a name, e.g. prc.example.org, at this host.
  2. HTTPS: serve it through a proxy, e.g. in the Caddyfile:
       prc.example.org {
           reverse_proxy $ADDR:${PORT:-8080}
       }
  3. $ETC_DIR/env: APP_PUBLIC_URL=https://prc.example.org (and GITHUB_TOKEN, PRC_ADMINS if you need them).
  4. systemctl restart $SERVICE
Then open https://prc.example.org and log in with your own TeamCity token (Profile -> Access Tokens).
NEXT
else
    log "done. Open $(setting APP_PUBLIC_URL) and log in with your own TeamCity token (Profile -> Access Tokens)."
fi
