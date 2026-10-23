#!/usr/bin/env bash
# Build, install and (re)start the user-level systemd service.
set -euo pipefail
cd "$(dirname "$0")/.."
go test ./... >/dev/null
go build -o "$HOME/.local/bin/cc-harness.new" .
mv -f "$HOME/.local/bin/cc-harness.new" "$HOME/.local/bin/cc-harness"
mkdir -p "$HOME/.config/systemd/user" "$HOME/.config/cc-harness"
cp deploy/cc-harness.service "$HOME/.config/systemd/user/cc-harness.service"
if [ ! -f "$HOME/.config/cc-harness/env" ]; then
  install -m 600 deploy/env.example "$HOME/.config/cc-harness/env"
  echo "created ~/.config/cc-harness/env - set HARNESS_PASSWORD there, then re-run" >&2
  exit 1
fi
chmod 600 "$HOME/.config/cc-harness/env"
systemctl --user daemon-reload
systemctl --user enable cc-harness.service
systemctl --user restart cc-harness.service
loginctl show-user "$USER" -p Linger | grep -q yes || echo "note: run 'sudo loginctl enable-linger $USER' so the service survives logout" >&2
systemctl --user --no-pager status cc-harness.service | head -12
