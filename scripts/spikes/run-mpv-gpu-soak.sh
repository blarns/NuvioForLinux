#!/usr/bin/env bash
# Compile and run MpvGpuSoakSpike.kt: the PRODUCTION GPU renderer, created and destroyed over
# and over on a real EGL context, with the source changing mid-session.
#
#   run-mpv-gpu-soak.sh <file> [second-file] [cycles]
#   SOAK_SECONDS=30 SOAK_W=3840 SOAK_H=2160 run-mpv-gpu-soak.sh a.mp4 b.mp4 10
#
# Two classpath halves, both needed: the app's own classes (the code under test, compiled with
# -Xfriend-paths so `internal` is visible) and the shim that provides an X11-platform EGL
# context. ⚠ The X11 platform is not incidental — a surfaceless EGL display silently gives
# hwdec-current=no, and the soak would then prove nothing about the zero-copy path.
#
# Needs a Gradle build first: ./gradlew :composeApp:compileKotlinJvm
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SPIKES="$REPO/scripts/spikes"
MPV_SRC="$REPO/composeApp/src/desktopMain/kotlin/com/nuvio/app/desktop/mpv"
EGL_SRC="$REPO/composeApp/src/desktopMain/kotlin/com/nuvio/app/desktop/egl"
OUT="${TMPDIR:-/tmp}/mpv-gpu-soak"
CACHE="$HOME/.gradle/caches/modules-2/files-2.1"
CLASSES="$REPO/composeApp/build/classes/kotlin/jvm/main"
APP_DIR=/opt/nuvio/lib/app

[ -n "${DISPLAY:-}" ] || { echo "no DISPLAY -- the soak needs a real X display"; exit 1; }
[ -d "$CLASSES" ] || { echo "no build output -- run ./gradlew :composeApp:compileKotlinJvm"; exit 1; }

KOTLINC=$(find "$CACHE" -name "kotlin-compiler-embeddable-*.jar" | grep -v sources | sort -V | tail -1)
KVER=$(basename "$KOTLINC" | sed 's/kotlin-compiler-embeddable-\(.*\)\.jar/\1/')
STDLIB=$(find "$CACHE" -name "kotlin-stdlib-$KVER.jar" | head -1)
REFLECT=$(find "$CACHE" -name "kotlin-reflect-$KVER.jar" | head -1)
SCRIPT_RT=$(find "$CACHE" -name "kotlin-script-runtime-$KVER.jar" | head -1)
ANNOTATIONS=$(find "$CACHE" -name "annotations-13.0.jar" | head -1)
# The compiler needs coroutines on ITS OWN classpath or it dies mid-pipeline.
COROUTINES=$(find "$CACHE" -name "kotlinx-coroutines-core-jvm-*.jar" | grep -v sources | sort -V | tail -1)
[ -n "$STDLIB" ] || { echo "no kotlin-stdlib matching compiler $KVER"; exit 1; }

DEP_JARS=$(find "$APP_DIR" -name '*.jar' 2>/dev/null | tr '\n' ':')
[ -n "$DEP_JARS" ] || { echo "no dependency jars in $APP_DIR"; exit 1; }

mkdir -p "$OUT"
echo "building shim..."
gcc -O2 -shared -fPIC "$SPIKES/egl_skia_shim.c" -o "$OUT/libeglskiashim.so" \
  $(pkg-config --cflags --libs egl x11 mpv)

COMPILER_CP="$KOTLINC:$STDLIB:${REFLECT:-}:${SCRIPT_RT:-}:$ANNOTATIONS:$COROUTINES"
APP_CP="$CLASSES:$DEP_JARS$STDLIB"

rm -rf "$OUT/classes"; mkdir -p "$OUT/classes"
echo "compiling (kotlin $KVER)..."
java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -cp "$APP_CP" -d "$OUT/classes" -nowarn -jvm-target 17 -Xfriend-paths="$CLASSES" \
  "$MPV_SRC/LibMpv.kt" "$MPV_SRC/MpvHandle.kt" "$MPV_SRC/MpvPlayerController.kt" \
  "$MPV_SRC/MpvEngineOptions.kt" "$MPV_SRC/MpvGpuRenderer.kt" \
  "$EGL_SRC/EglSeam.kt" "$EGL_SRC/EglRenderer.kt" \
  "$SPIKES/MpvGpuSoakSpike.kt"

echo "running..."
exec java -Dskiko.library.path="$APP_DIR" -Djna.library.path="$OUT" \
  -cp "$OUT/classes:$APP_CP" com.nuvio.app.desktop.mpv.MpvGpuSoakSpikeKt "$@"
