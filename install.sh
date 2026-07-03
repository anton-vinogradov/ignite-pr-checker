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
JAR_URL="https://github.com/${REPO}/releases/latest/download/ignite-pr-checker.jar"

log() { printf '>> %s\n' "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

[ "$(id -u)" = 0 ] || die "run as root (e.g. pipe into 'sudo bash')"
command -v apt-get >/dev/null 2>&1 || die "this installer targets Debian/Ubuntu (apt-get not found)"

# 1. Java 17 runtime
if ! command -v java >/dev/null 2>&1; then
    log "installing JRE 17 ..."
    export DEBIAN_FRONTEND=noninteractive
    apt-get update -qq
    apt-get install -y -qq openjdk-17-jre-headless >/dev/null
fi

# 2. service user + directories
id prc >/dev/null 2>&1 || useradd --system --no-create-home --shell /usr/sbin/nologin prc
install -d -o prc  -g prc  -m 755 "$APP_DIR"
install -d -o root -g prc  -m 750 "$ETC_DIR"

# 3. config (only created once; never overwritten on update)
if [ ! -f "$ETC_DIR/env" ]; then
    log "writing config template at $ETC_DIR/env (fill in TC_TOKEN)"
    cat > "$ETC_DIR/env" <<'ENV'
TC_BASE_URL=https://ci2.ignite.apache.org/
TC_RUN_ALL_BUILD_TYPE=IgniteTests24Java8_RunAll
TC_TOKEN=
ENV
    NEED_TOKEN=1
fi
chown root:prc "$ETC_DIR/env"
chmod 640 "$ETC_DIR/env"

# 4. download the latest released jar (atomic swap)
log "downloading latest release jar ..."
curl -fSL "$JAR_URL" -o "$APP_DIR/app.jar.new"
install -o prc -g prc -m 644 "$APP_DIR/app.jar.new" "$APP_DIR/app.jar"
rm -f "$APP_DIR/app.jar.new"

# 5. systemd unit (refreshed every run so unit changes propagate)
cat > "/etc/systemd/system/${SERVICE}.service" <<UNIT
[Unit]
Description=Ignite PR Checker
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=prc
Group=prc
EnvironmentFile=${ETC_DIR}/env
ExecStart=/usr/bin/java -Xmx256m -jar ${APP_DIR}/app.jar
Restart=on-failure
RestartSec=5
NoNewPrivileges=true
ProtectSystem=full
PrivateTmp=true

[Install]
WantedBy=multi-user.target
UNIT

# 6. (re)start
systemctl daemon-reload
systemctl enable "$SERVICE" >/dev/null 2>&1 || true
systemctl restart "$SERVICE"

sleep 2
systemctl --no-pager --lines=0 status "$SERVICE" | head -4 || true

log "done."
if [ "${NEED_TOKEN:-0}" = 1 ]; then
    printf '\nNEXT: put your TeamCity token into %s/env (TC_TOKEN=...), then:\n  systemctl restart %s\n' "$ETC_DIR" "$SERVICE"
fi
