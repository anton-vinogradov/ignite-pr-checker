#!/usr/bin/env bash
# Enable GitHub Deploy on an existing Ignite PR Checker installation.
set -euo pipefail
cd "$(dirname "$0")"

app=/opt/ignite-pr-checker
units=/etc/systemd/system
[ "$(id -u)" = 0 ] || { echo "Run this installer as root." >&2; exit 1; }
command -v python3 >/dev/null
systemctl is-active --quiet ignite-pr-checker.service
test -f "$app/app.jar" && test ! -L "$app/app.jar"

exec 9>"$app/.deploy.lock"
flock -n 9 || { echo "Another deployment is in progress." >&2; exit 1; }
install -o root -g root -m 755 github-deploy.py "$app/github-deploy.py"
install -d -m 755 "$units/ignite-pr-checker.service.d"
# The timer owns installation and rollback when enabled. Restart still starts the current JAR.
cat > "$units/ignite-pr-checker.service.d/github-deploy.conf" <<'UNIT'
[Service]
ExecStartPre=
Environment=UPDATE_ENABLED=false
UNIT

cat > "$units/ignite-pr-checker-deploy.service" <<'UNIT'
[Unit]
Description=Install the latest manual Ignite PR Checker deployment
After=network-online.target
Wants=network-online.target

[Service]
Type=oneshot
ExecStart=/usr/bin/python3 /opt/ignite-pr-checker/github-deploy.py
TimeoutStartSec=8min
UNIT

cat > "$units/ignite-pr-checker-deploy.timer" <<'UNIT'
[Unit]
Description=Check GitHub for an Ignite PR Checker deployment

[Timer]
OnBootSec=30s
OnUnitInactiveSec=3min

[Install]
WantedBy=timers.target
UNIT

systemctl daemon-reload
flock -u 9
systemctl enable --now ignite-pr-checker-deploy.timer
