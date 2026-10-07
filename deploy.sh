#!/usr/bin/env bash
#
# Build the jar locally with the tests, ship it to the server and restart the service; the jar it replaces stays as
# app.jar.prev if it was answering. Checks that the new version answers within 60 s and prints how to roll back if not.
# Refuses a tree with changes to what goes into the jar unless --force: the jar's version and commit must tell the
# code it runs (a forced build is versioned "...-dirty"). No secrets here: server-side config lives only in
# /etc/ignite-pr-checker/env on the host. Override the SSH target with PRC_SSH_HOST.
#
# Roll back by hand:
#   ssh <host> 'cd /opt/ignite-pr-checker && cp app.jar.prev app.jar && systemctl restart ignite-pr-checker'
#
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SSH_HOST="${PRC_SSH_HOST:-ignite-prc}"
JAR="$HERE/build/libs/ignite-pr-checker.jar"
INFO="$HERE/build/resources/main/META-INF/build-info.properties"

force=false
for arg in "$@"; do
    case "$arg" in
        --force) force=true ;;
        *) echo "usage: $0 [--force]" >&2; exit 2 ;;
    esac
done

# The same paths build.gradle counts as the jar's sources.
changes="$(git -C "$HERE" status --porcelain -- src build.gradle settings.gradle gradle)"
if [ -n "$changes" ] && [ "$force" != true ]; then
    printf 'ERROR: uncommitted changes in what goes into the jar:\n%s\n' "$changes" >&2
    echo "Commit them, or pass --force to ship a build versioned ...-dirty." >&2
    exit 1
fi

# On macOS, make sure a JDK 17 is used to launch Gradle if JAVA_HOME is unset.
if [ -z "${JAVA_HOME:-}" ] && command -v /usr/libexec/java_home >/dev/null 2>&1; then
    export JAVA_HOME="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
fi

echo ">> building and testing ..."
"$HERE/gradlew" -p "$HERE" build
VERSION="$(sed -n 's/^build\.version=//p' "$INFO")"

echo ">> shipping $VERSION to $SSH_HOST ..."
scp "$JAR" "$SSH_HOST:/opt/ignite-pr-checker/app.jar.new"
ssh "$SSH_HOST" bash -s -- "$VERSION" <<'REMOTE'
set -euo pipefail
version="$1"
cd /opt/ignite-pr-checker
env=/etc/ignite-pr-checker/env
addr="$(sed -n 's/^SERVER_ADDRESS=//p' "$env" | tail -n 1)"
case "$addr" in ''|0.0.0.0|::) addr=127.0.0.1 ;; esac
port="$(sed -n 's/^SERVER_PORT=//p' "$env" | tail -n 1)"
answering() {
    curl -fsS -m 5 "http://$addr:${port:-8080}/api/status" 2>/dev/null | sed -n 's/^{"version":"\([^"]*\)".*/\1/p'
}

# app.jar.prev is the jar to roll back to, so only a jar that answers replaces it: not one that never came up (the
# deploy before this one failed), and not the same build again. install, not cp: an app.jar.prev the service account
# owned from an older install becomes root's. A link is not a jar anyone installed.
if [ -z "$(answering)" ]; then
    echo ">> nothing answers now: app.jar.prev stays the jar to roll back to"
elif [ -f app.jar ] && [ ! -L app.jar ] && ! cmp -s app.jar app.jar.new; then
    install -o root -g root -m 644 app.jar app.jar.prev
fi
install -o root -g root -m 644 app.jar.new app.jar
rm -f app.jar.new update-failed
systemctl restart ignite-pr-checker

running=""
for _ in $(seq 1 30); do
    sleep 2
    running="$(answering)" || true
    if [ "$running" = "$version" ]; then
        echo ">> $version is up"
        exit 0
    fi
done
echo "ERROR: $version did not answer within 60 s (answering: ${running:-nothing})." >&2
echo "Roll back: cd /opt/ignite-pr-checker && cp app.jar.prev app.jar && systemctl restart ignite-pr-checker" >&2
systemctl --no-pager --lines=20 status ignite-pr-checker >&2 || true
exit 1
REMOTE
echo ">> done"
