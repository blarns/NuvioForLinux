#!/usr/bin/env bash
set -euo pipefail

REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"

mkdir -p ~/.local/share/applications

cat > ~/.local/share/applications/nuvio.desktop << EOF
[Desktop Entry]
Name=Nuvio
GenericName=Media Player
Comment=Modern media hub with Stremio addon ecosystem support
Exec=bash -c 'cd ${REPO_DIR} && ./gradlew composeApp:run --quiet'
Icon=video-player
Type=Application
Categories=AudioVideo;Video;Player;TV;
Keywords=video;media;streaming;stremio;
StartupNotify=true
Terminal=false
MimeType=video/x-matroska;video/mp4;video/x-msvideo;video/webm;video/quicktime;video/x-flv;video/mpeg;
EOF

update-desktop-database ~/.local/share/applications/ 2>/dev/null || true
echo "Nuvio added to application menu (running from: ${REPO_DIR})"
