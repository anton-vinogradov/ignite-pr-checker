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

# 3. config (created once; never overwritten on update). Users log in with their own TeamCity
# token via the web UI; these are just non-secret overrides plus the session-cookie secret.
if [ ! -f "$ETC_DIR/env" ]; then
    log "writing config template at $ETC_DIR/env"
    cat > "$ETC_DIR/env" <<'ENV'
# Optional overrides of the built-in defaults; uncomment to change.
#TC_BASE_URL=https://ci2.ignite.apache.org/
#TC_RUN_ALL_BUILD_TYPE=IgniteTests24Java8_RunAll
# Set to true once the service is served over HTTPS (e.g. behind Caddy):
SESSION_COOKIE_SECURE=false
ENV
fi
# Ensure a stable session secret exists (so logins survive restarts/updates). Generated once.
if ! grep -q '^SESSION_SECRET=' "$ETC_DIR/env"; then
    log "generating SESSION_SECRET"
    printf 'SESSION_SECRET=%s\n' "$(head -c 32 /dev/urandom | base64)" >> "$ETC_DIR/env"
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

log "done. Open the site and log in with your own TeamCity token (Profile -> Access Tokens)."
