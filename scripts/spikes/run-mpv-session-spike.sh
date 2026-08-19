#!/usr/bin/env bash
# Compile and run MpvSessionSpike.kt against the REAL MpvSession plumbing.
#
#   run-mpv-session-spike.sh <file-or-url>
#
# Compiled together with the production sources so Kotlin `internal` is visible, and with
# -Xfriend-paths because the controller reads the app-internal PlayerSettingsStorage.
# Needs a Gradle build first: ./gradlew :composeApp:compileKotlinJvm
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MPV_SRC="$REPO/composeApp/src/desktopMain/kotlin/com/nuvio/app/desktop/mpv"
OUT="${TMPDIR:-/tmp}/mpv-session-spike"
CACHE="$HOME/.gradle/caches/modules-2/files-2.1"
CLASSES="$REPO/composeApp/build/classes/kotlin/jvm/main"

[ -d "$CLASSES" ] || { echo "no build output -- run ./gradlew :composeApp:compileKotlinJvm"; exit 1; }

# ⚠ Pick the stdlib that MATCHES the compiler. A bare `find ... | head -1` returns them in
# arbitrary order, and pairing e.g. compiler 2.3.0 with stdlib 2.3.21 dies mid-compile with
# NoClassDefFoundError: kotlin/coroutines/jvm/internal/SpillingKt -- which reads as a fault in
# the code being compiled and is not.
KOTLINC=$(find "$CACHE" -name "kotlin-compiler-embeddable-*.jar" | grep -v sources | sort -V | tail -1)
KVER=$(basename "$KOTLINC" | sed 's/kotlin-compiler-embeddable-\(.*\)\.jar/\1/')
STDLIB=$(find "$CACHE" -name "kotlin-stdlib-$KVER.jar" | head -1)
REFLECT=$(find "$CACHE" -name "kotlin-reflect-$KVER.jar" | head -1)
SCRIPT_RT=$(find "$CACHE" -name "kotlin-script-runtime-$KVER.jar" | head -1)
[ -n "$STDLIB" ] || { echo "no kotlin-stdlib matching compiler $KVER"; exit 1; }
ANNOTATIONS=$(find "$CACHE" -name "annotations-13.0.jar" | head -1)
# The compiler needs coroutines on ITS OWN classpath or it dies mid-pipeline.
COROUTINES=$(find "$CACHE" -name "kotlinx-coroutines-core-jvm-*.jar" | grep -v sources | sort -V | tail -1)

DEP_JARS=$(find /opt/nuvio/lib/app -name '*.jar' 2>/dev/null | tr '\n' ':')
[ -n "$DEP_JARS" ] || { echo "no dependency jars in /opt/nuvio/lib/app"; exit 1; }

COMPILER_CP="$KOTLINC:$STDLIB:${REFLECT:-}:${SCRIPT_RT:-}:$ANNOTATIONS:$COROUTINES"
APP_CP="$CLASSES:$DEP_JARS$STDLIB"

rm -rf "$OUT"; mkdir -p "$OUT"
echo "compiling (kotlin $KVER)..."
java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -cp "$APP_CP" -d "$OUT" -nowarn -jvm-target 17 -Xfriend-paths="$CLASSES" \
  "$MPV_SRC/LibMpv.kt" "$MPV_SRC/MpvHandle.kt" "$MPV_SRC/MpvPlayerController.kt" \
  "$MPV_SRC/MpvEngineOptions.kt" "$MPV_SRC/MpvSoftwareRenderer.kt" "$MPV_SRC/MpvSession.kt" \
  "$REPO/scripts/spikes/MpvSessionSpike.kt"

echo "running..."
exec java -cp "$OUT:$APP_CP" com.nuvio.app.desktop.mpv.MpvSessionSpikeKt "$@"
