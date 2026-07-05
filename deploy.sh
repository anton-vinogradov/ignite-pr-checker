#!/usr/bin/env bash
#
# Build the fat jar locally and ship it to the server, then restart the service.
# No secrets here: server-side config (TeamCity URL/token) lives only in
# /etc/ignite-pr-checker/env on the host. Override the SSH target with PRC_SSH_HOST.
#
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SSH_HOST="${PRC_SSH_HOST:-ignite-prc}"
JAR="$HERE/build/libs/ignite-pr-checker.jar"

# On macOS, make sure a JDK 17 is used to launch Gradle if JAVA_HOME is unset.
if [ -z "${JAVA_HOME:-}" ] && command -v /usr/libexec/java_home >/dev/null 2>&1; then
    export JAVA_HOME="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
fi

echo ">> building jar ..."
# Version the dev build after the latest release tag (plus -dev) so the running app reports
# something truthful instead of the stale build.gradle fallback.
V="$(git -C "$HERE" describe --tags --abbrev=0 2>/dev/null | sed 's/^v//')"
"$HERE/gradlew" bootJar -Pappversion="${V:-0.0.0}-dev"

echo ">> shipping to $SSH_HOST ..."
scp "$JAR" "$SSH_HOST:/opt/ignite-pr-checker/app.jar.new"
ssh "$SSH_HOST" '
    install -o prc -g prc -m 644 /opt/ignite-pr-checker/app.jar.new /opt/ignite-pr-checker/app.jar &&
    rm -f /opt/ignite-pr-checker/app.jar.new &&
    systemctl restart ignite-pr-checker &&
    sleep 2 &&
    systemctl --no-pager --lines=0 status ignite-pr-checker | head -4
'
echo ">> done"
