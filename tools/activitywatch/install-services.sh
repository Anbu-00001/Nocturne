#!/usr/bin/env bash
# Makes the laptop side start by itself at every login: ActivityWatch, the watcher that records laptop use, the Wi-Fi sync
# server the phone calls, and the top-bar moon. Safe to run again. Run it once; nothing else is needed after a reboot.
#
# Without these units nothing records laptop use and nothing answers the phone, which then reports "did not answer".
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
units=(aw-server.service nocturne-watch.service nocturne-serve.service nocturne-indicator.service)

mkdir -p "$HOME/.config/systemd/user"
cp "$here"/systemd/*.service "$here"/systemd/*.timer "$HOME/.config/systemd/user/"
systemctl --user daemon-reload

# Copies started by hand hold the same ports; stop them so the units can bind (the bracket keeps pkill from matching itself).
pkill -f '[n]octurne_aw.py (watch|serve)' || true
pkill -f '[a]w-server-rust/aw-server-rust' || true
sleep 2

systemctl --user enable --now "${units[@]}"
# The old USB timer is not needed once the phone starts the exchange.
systemctl --user disable --now nocturne-sync.timer 2>/dev/null || true

sleep 4
failed=0
for u in "${units[@]}"; do
    state=$(systemctl --user is-active "$u" || true)
    printf '%-32s %s\n' "$u" "$state"
    [ "$state" = active ] || failed=1
done
ss -ltn | grep -E ':(5600|47650)\b' || failed=1
if [ "$failed" = 0 ]; then
    echo "ok: the laptop answers the phone from now on, after every reboot."
else
    echo "something is not running: systemctl --user status <unit>, or journalctl --user -u <unit>" >&2
    exit 1
fi
