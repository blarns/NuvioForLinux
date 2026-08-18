#!/usr/bin/env bash
# Build the shim and run the sustained interleaved Skia/mpv soak.
#
#   run-egl-mpv-soak.sh <file|url> [iterations] [hwdec]
#
# Needs a real X display: the shim opens an X11-platform EGL display on purpose, because a
# surfaceless one silently gives hwdec-current=no and the soak would then prove nothing.
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SPIKES="$REPO/scripts/spikes"
OUT="${TMPDIR:-/tmp}/egl-mpv-soak"
CACHE="$HOME/.gradle/caches/modules-2/files-2.1"
APP_DIR=/opt/nuvio/lib/app

[ -n "${DISPLAY:-}" ] || { echo "no DISPLAY -- the soak needs a real X display"; exit 1; }

find_jar() { find "$CACHE" -name "$1" 2>/dev/null | grep -v sources | head -1; }
KOTLINC=$(find_jar "kotlin-compiler-embeddable-*.jar")
STDLIB=$(find_jar "kotlin-stdlib-2*.jar")
REFLECT=$(find_jar "kotlin-reflect-2*.jar")
SCRIPT_RT=$(find_jar "kotlin-script-runtime-2*.jar")
ANNOTATIONS=$(find_jar "annotations-13.0.jar")
# The compiler needs coroutines on ITS OWN classpath or it dies mid-pipeline.
COROUTINES=$(find_jar "kotlinx-coroutines-core-jvm-*.jar")

mkdir -p "$OUT"
echo "building shim..."
gcc -O2 -shared -fPIC "$SPIKES/egl_skia_shim.c" -o "$OUT/libeglskiashim.so" \
  $(pkg-config --cflags --libs egl x11 mpv)

APP_CP="$(find $APP_DIR -name '*.jar' | tr '\n' ':')$STDLIB"

echo "compiling soak..."
java -cp "$KOTLINC:$STDLIB:$REFLECT:$SCRIPT_RT:$ANNOTATIONS:$COROUTINES" \
  org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -cp "$APP_CP" -d "$OUT/classes" -nowarn "$SPIKES/EglSkiaMpvSoak.kt"

echo "running soak..."
exec java -Dskiko.library.path="$APP_DIR" -Djna.library.path="$OUT" \
  -cp "$APP_CP:$OUT/classes" EglSkiaMpvSoakKt "$@"
