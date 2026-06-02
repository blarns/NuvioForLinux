#!/usr/bin/env bash
set -euo pipefail

cp "$(dirname "$0")/../nuvio.desktop" ~/.local/share/applications/nuvio.desktop
update-desktop-database ~/.local/share/applications/ 2>/dev/null || true
echo "Nuvio added to application menu."
