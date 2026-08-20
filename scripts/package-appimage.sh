#!/usr/bin/env bash
#
# Builds a universal x86_64 AppImage of NuvioForLinux.
#
# Why this exists: the .deb (see composeApp/build.gradle.kts) is Debian/Ubuntu-only and
# leaves libVLC as an external dependency (VLCJ dlopen's the system libvlc.so). An AppImage
# has to run on any distro with NO install step, so it must BUNDLE libVLC + its plugin tree
# AND the plugins' transitive native deps (ffmpeg/codecs/etc.) — while still using the host's
# glibc/graphics/audio libs (those must NOT be bundled; that's what the AppImage excludelist is).
#
# Pipeline:
#   1. gradle :composeApp:createDistributable      -> a jpackage app-image (JRE + jars + torrserver)
#   2. lay that app-image out as an AppDir under usr/
#   3. copy in libVLC + plugins, then bundle the plugins' deps (ldd minus excludelist)
#   4. AppRun points VLCJ at the bundled VLC via LD_LIBRARY_PATH + VLC_PLUGIN_PATH
#   5. appimagetool packs AppDir -> Nuvio-<version>-x86_64.AppImage
#
# ⚠ libmpv is deliberately NOT bundled. The experimental mpv engine (NUVIO_MPV=1) needs client
# API 2.x, i.e. libmpv.so.2, which first appeared in mpv 0.36 — Ubuntu 22.04 and Debian 12 ship
# 0.34/0.35 (libmpv.so.1), so the staged 22.04 bundle CANNOT supply one, and taking this host's
# 24.04 copy instead would drag the AppImage's glibc floor from 2.35 up to 2.38, which is the
# exact thing build-vlc-bundle.sh exists to prevent. So: mpv uses the HOST's libmpv when the user
# has it, and VLCJ (bundled, always present) is used when they do not.
#
# ⚠ The risk that creates, and it is REAL, not theoretical: AppRun puts the bundled 22.04
# libraries FIRST on LD_LIBRARY_PATH, so a host libmpv resolves any same-soname library against
# the bundle. ffmpeg is safe (soname 58 vs 60 — they cannot shadow each other), but libva is NOT:
# measured with the 22.04 bundle in front, mpv reported hwdec-current=no — silent software decode
# of 4K. libva is now excluded from both bundling paths, which restored hwdec-current=vaapi.
#
# Tools (extracted, NOT run as AppImages — the host's AppImageLauncher deletes them on exec):
#   appimage-build/tools/appimagetool.AppDir/usr/bin/appimagetool
#   appimage-build/tools/excludelist            (AppImage/pkg2appimage)
#   appimage-build/tools/runtime-x86_64         (AppImage/type2-runtime)
#
# Usage: scripts/package-appimage.sh [--skip-build] [--version X.Y.Z]
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD_DIR="$PROJECT_ROOT/appimage-build"
TOOLS="$BUILD_DIR/tools"
APP_SRC="$PROJECT_ROOT/composeApp/build/compose/binaries/main/app/nuvio"
APPDIR="$BUILD_DIR/AppDir"
DIST="$BUILD_DIR/dist"
SYS_LIB="/usr/lib/x86_64-linux-gnu"

SKIP_BUILD=0
VERSION=""
# Optional pre-staged VLC bundle (scripts/build-vlc-bundle.sh) for a lower glibc floor.
# When unset, libVLC + deps are taken from THIS host (whatever glibc it was built on).
VLC_BUNDLE=""
while [ $# -gt 0 ]; do
  case "$1" in
    --skip-build) SKIP_BUILD=1 ;;
    --version) VERSION="$2"; shift ;;
    --vlc-bundle) VLC_BUNDLE="$2"; shift ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
  shift
done

log() { printf '\033[1;36m==>\033[0m %s\n' "$*"; }

# --- 1. build the jpackage app-image -----------------------------------------
# Build the jlinked runtime with an OLD-glibc JDK (Adoptium Temurin, glibc<=2.15) if one is
# staged under appimage-build/jdk; the system OpenJDK is built on the host glibc (2.38 on
# 24.04) and would pin the AppImage's floor there. See scripts/build-vlc-bundle.sh for the
# matching VLC step.
STAGED_JDK="$(find "$BUILD_DIR/jdk" -maxdepth 1 -type d -name 'jdk-21*' 2>/dev/null | head -1)"
if [ "$SKIP_BUILD" -eq 0 ]; then
  if [ -n "$STAGED_JDK" ]; then
    log "Building app-image with staged JDK: $STAGED_JDK"
    # ⚠ Which JDK built the jlink runtime is NOT a tracked Gradle input, so createRuntimeImage
    # stays UP-TO-DATE and silently reuses a runtime some earlier build made with the SYSTEM
    # JDK — which pins the AppImage's glibc floor to this host (2.38) while every log line still
    # says "staged JDK". Cost a whole build to find; the runtime lives under tmp/, so wiping
    # binaries/ (the obvious thing) does not clear it.
    rm -rf "$PROJECT_ROOT/composeApp/build/compose/tmp/main/runtime"
    ( cd "$PROJECT_ROOT" && JAVA_HOME="$STAGED_JDK" ./gradlew :composeApp:createDistributable --no-daemon )
  else
    log "Building app-image (system JDK — WARNING: AppImage glibc floor will match this host)"
    ( cd "$PROJECT_ROOT" && ./gradlew :composeApp:createDistributable )
  fi
fi
[ -x "$APP_SRC/bin/nuvio" ] || { echo "app-image not found at $APP_SRC (run without --skip-build)" >&2; exit 1; }

# version: prefer arg, else read jpackage.app-version baked into the cfg
if [ -z "$VERSION" ]; then
  VERSION="$(sed -n 's/.*jpackage.app-version=\([0-9.]*\).*/\1/p' "$APP_SRC/lib/app/nuvio.cfg" | head -1)"
fi
[ -n "$VERSION" ] || { echo "could not determine version; pass --version" >&2; exit 1; }
log "Version: $VERSION"

# --- 2. assemble the AppDir (app under usr/, preserving bin<->lib siblinghood) -
log "Assembling AppDir"
rm -rf "$APPDIR"
mkdir -p "$APPDIR/usr"
cp -a "$APP_SRC/bin" "$APPDIR/usr/bin"
cp -a "$APP_SRC/lib" "$APPDIR/usr/lib"

# --- 3. bundle libVLC + plugins + their native dep closure --------------------
VLC_DEST="$APPDIR/usr/lib/vlc"
mkdir -p "$VLC_DEST"
if [ -n "$VLC_BUNDLE" ]; then
  # Pre-staged from an older distro (scripts/build-vlc-bundle.sh): already filtered.
  [ -d "$VLC_BUNDLE/vlc" ] && [ -d "$VLC_BUNDLE/lib" ] || { echo "bad --vlc-bundle: $VLC_BUNDLE" >&2; exit 1; }
  log "Using pre-staged VLC bundle: $VLC_BUNDLE"
  cp -a "$VLC_BUNDLE/vlc/." "$VLC_DEST/"
  cp -an "$VLC_BUNDLE/lib/." "$APPDIR/usr/lib/"
  log "Bundled VLC + $(ls "$VLC_BUNDLE/lib" | wc -l) dep libs from staged bundle"
else
  # From THIS host's system libVLC.
  log "Bundling libVLC + plugins (host)"
  cp -a "$SYS_LIB"/libvlc.so* "$SYS_LIB"/libvlccore.so* "$VLC_DEST/"
  cp -a "$SYS_LIB/vlc/plugins" "$VLC_DEST/plugins"
  rm -f "$VLC_DEST/plugins/plugins.dat"   # stale cache would pin host paths

  log "Resolving + bundling plugin dependencies (host)"
  EXCLUDE="$TOOLS/excludelist"
  mapfile -t EXCL < <(grep -oE '^[^# ]+\.so[^ ]*' "$EXCLUDE" | sort -u)
  is_excluded() { local b="$1"; for e in "${EXCL[@]}"; do [ "$b" = "$e" ] && return 0; done; return 1; }
  declare -A SEEN
  copied=0; skipped=0
  while IFS= read -r sofile; do
    while read -r _name _arrow path _addr; do
      [ "${path:0:1}" = "/" ] || continue                # only "name => /abs/path" lines
      base="$(basename "$path")"
      [ -n "${SEEN[$base]:-}" ] && continue
      SEEN[$base]=1
      case "$path" in "$APPDIR"/*) continue ;; esac       # already ours
      # ⚠ Never libva — see the note at the top of this file and in build-vlc-bundle.sh. A
      # bundled libva against the host's VA driver silently drops hardware decoding to none.
      case "$base" in libva.so*|libva-drm.so*|libva-x11.so*|libva-glx.so*)
        skipped=$((skipped+1)); continue ;;
      esac
      is_excluded "$base" && { skipped=$((skipped+1)); continue; }
      cp -aL "$path" "$APPDIR/usr/lib/$base" && copied=$((copied+1))
    done < <(ldd "$sofile" 2>/dev/null)
  done < <(find "$VLC_DEST" -name '*.so' -type f)
  log "Bundled $copied dep libs (excluded $skipped host libs)"
fi

# --- 4. AppRun ----------------------------------------------------------------
log "Writing AppRun + desktop entry + icon"
cat > "$APPDIR/AppRun" <<'EOF'
#!/usr/bin/env bash
HERE="$(dirname "$(readlink -f "${0}")")"
# bundled VLC: libvlc.so + libvlccore.so live in usr/lib/vlc, their deps in usr/lib
export LD_LIBRARY_PATH="$HERE/usr/lib/vlc:$HERE/usr/lib:${LD_LIBRARY_PATH:-}"
export VLC_PLUGIN_PATH="$HERE/usr/lib/vlc/plugins"
exec "$HERE/usr/bin/nuvio" "$@"
EOF
chmod +x "$APPDIR/AppRun"

cat > "$APPDIR/nuvio.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=Nuvio
Exec=nuvio %u
Icon=nuvio
Categories=AudioVideo;Video;Player;
Comment=Modern media hub with Stremio addon ecosystem support
Terminal=false
MimeType=x-scheme-handler/magnet;application/x-bittorrent;
EOF
desktop-file-validate "$APPDIR/nuvio.desktop" 2>/dev/null || true

ICON_SRC="$PROJECT_ROOT/nuvio-icon.png"
[ -f "$ICON_SRC" ] || ICON_SRC="$APP_SRC/lib/nuvio.png"
cp "$ICON_SRC" "$APPDIR/nuvio.png"
install -Dm644 "$ICON_SRC" "$APPDIR/usr/share/icons/hicolor/256x256/apps/nuvio.png"

# --- 5. pack ------------------------------------------------------------------
log "Packing AppImage"
mkdir -p "$DIST"
OUT="$DIST/Nuvio-${VERSION}-x86_64.AppImage"
ARCH=x86_64 "$TOOLS/appimagetool.AppDir/usr/bin/appimagetool" \
  --runtime-file "$TOOLS/runtime-x86_64" \
  "$APPDIR" "$OUT"
chmod +x "$OUT"
log "Done: $OUT ($(du -h "$OUT" | cut -f1))"
