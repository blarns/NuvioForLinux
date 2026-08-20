#!/usr/bin/env bash
#
# Stages libVLC + its plugin tree + the plugins' native dependency closure from an
# OLDER distro (default Ubuntu 22.04, glibc 2.35) so the AppImage runs on stable bases
# (22.04 LTS, Debian 12) instead of only glibc>=2.38 boxes. The jpackage app-image's own
# libs (JRE/skiko/launcher) already target <=2.36, so the VLC stack was the only thing
# pinning the floor higher — this re-sources just that, in a container.
#
# Output: appimage-build/vlc-bundle/{vlc/, lib/}, consumed by package-appimage.sh --vlc-bundle.
#
# Usage: scripts/build-vlc-bundle.sh [--image ubuntu:22.04]
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD_DIR="$PROJECT_ROOT/appimage-build"
OUT="$BUILD_DIR/vlc-bundle"
EXCLUDE="$BUILD_DIR/tools/excludelist"
IMAGE="ubuntu:22.04"

while [ $# -gt 0 ]; do
  case "$1" in
    --image) IMAGE="$2"; shift ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
  shift
done

[ -f "$EXCLUDE" ] || { echo "missing $EXCLUDE (run package-appimage.sh setup first)" >&2; exit 1; }
# Prior output is root-owned (docker wrote it); clean it via a container, then recreate.
[ -e "$OUT" ] && docker run --rm -v "$BUILD_DIR":/b "$IMAGE" rm -rf /b/vlc-bundle
mkdir -p "$OUT"
HOST_UID="$(id -u)"; HOST_GID="$(id -g)"

echo "==> Staging VLC from $IMAGE"
docker run --rm \
  -e HOST_UID="$HOST_UID" -e HOST_GID="$HOST_GID" \
  -v "$OUT":/out \
  -v "$EXCLUDE":/excludelist:ro \
  "$IMAGE" bash -euc '
    export DEBIAN_FRONTEND=noninteractive
    apt-get update -qq
    apt-get install -y -qq --no-install-recommends libvlc5 vlc-plugin-base >/dev/null
    SYS=/usr/lib/x86_64-linux-gnu
    mkdir -p /out/vlc /out/lib
    cp -a "$SYS"/libvlc.so* "$SYS"/libvlccore.so* /out/vlc/
    cp -a "$SYS"/vlc/plugins /out/vlc/plugins
    rm -f /out/vlc/plugins/plugins.dat
    # VLCJ/JNA discovery looks for the unversioned names; libvlc5 alone ships only *.so.N
    ( cd /out/vlc
      [ -e libvlc.so ]     || ln -s "$(basename $(ls libvlc.so.5*     | grep -E "so\.[0-9.]+$" | head -1))" libvlc.so
      [ -e libvlccore.so ] || ln -s "$(basename $(ls libvlccore.so.9* | grep -E "so\.[0-9.]+$" | head -1))" libvlccore.so )

    mapfile -t EXCL < <(grep -oE "^[^# ]+\.so[^ ]*" /excludelist | sort -u)
    is_excl(){ local b=$1; for e in "${EXCL[@]}"; do [ "$b" = "$e" ] && return 0; done; return 1; }
    declare -A SEEN
    while IFS= read -r so; do
      while read -r _n _a path _x; do
        [ "${path:0:1}" = "/" ] || continue
        b=$(basename "$path")
        [ -n "${SEEN[$b]:-}" ] && continue; SEEN[$b]=1
        case "$path" in /out/*) continue;; esac
        case "$b" in libvlc*.so*) continue;; esac   # libvlc/libvlccore already in /out/vlc
        # ⚠ NEVER bundle libva. It dlopens the HOST driver (/usr/lib/.../dri/*_drv_video.so),
        # and an old bundled libva against a newer host driver fails the handshake SILENTLY:
        # measured on 24.04 with the 22.04 bundle ahead on LD_LIBRARY_PATH, mpv reported
        # hwdec-current=no — i.e. 4K decoded in software at several times the CPU, no error
        # anywhere. Removing these three restored hwdec-current=vaapi on the same run.
        # Costs VLC nothing here: libVLC already refuses hardware decode through the vmem
        # video output this app renders with, which is why the mpv engine exists at all.
        case "$b" in libva.so*|libva-drm.so*|libva-x11.so*|libva-glx.so*) continue;; esac
        is_excl "$b" && continue
        cp -aL "$path" "/out/lib/$b"
      done < <(ldd "$so" 2>/dev/null)
    done < <(find /out/vlc -name "*.so" -type f)

    echo "VLC: $(basename $(readlink -f $SYS/libvlc.so.5))  plugins: $(find /out/vlc/plugins -name "*.so" | wc -l)  deps: $(ls /out/lib | wc -l)"
    chown -R "$HOST_UID:$HOST_GID" /out   # hand ownership back to the host user
  '
echo "==> Done: $OUT"
ls "$OUT"