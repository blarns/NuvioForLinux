#!/usr/bin/env bash
# Compile and run MpvControllerSpike.kt against the REAL controller.
#
# Unlike the binding spike this one needs the rest of the app on the classpath
# (PlayerEngineController, PlayerPlaybackSnapshot, PlayerSettingsStorage), so it uses the
# LOCAL Gradle build output -- not the installed jars, which would be a different version of
# the very code under test. Run a build first:
#     ./gradlew :composeApp:compileKotlinJvm
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MPV_SRC="$REPO/composeApp/src/desktopMain/kotlin/com/nuvio/app/desktop/mpv"
OUT="${TMPDIR:-/tmp}/mpv-controller-spike"
CACHE="$HOME/.gradle/caches/modules-2/files-2.1"
CLASSES="$REPO/composeApp/build/classes/kotlin/jvm/main"

[ -d "$CLASSES" ] || { echo "no build output at $CLASSES -- run ./gradlew :composeApp:compileKotlinJvm"; exit 1; }

find_jar() { find "$CACHE" -name "$1" 2>/dev/null | grep -v sources | head -1; }

# ⚠ Pick the stdlib that MATCHES the compiler. A bare `find | head -1` returns them in
# arbitrary order, and pairing e.g. compiler 2.3.0 with stdlib 2.3.21 dies mid-compile with
# NoClassDefFoundError: kotlin/coroutines/jvm/internal/SpillingKt -- which reads as a fault in
# the code being compiled and is not.
KOTLINC=$(find "$CACHE" -name "kotlin-compiler-embeddable-*.jar" | grep -v sources | sort -V | tail -1)
KVER=$(basename "$KOTLINC" | sed 's/kotlin-compiler-embeddable-\(.*\)\.jar/\1/')
STDLIB=$(find "$CACHE" -name "kotlin-stdlib-$KVER.jar" | head -1)
REFLECT=$(find "$CACHE" -name "kotlin-reflect-$KVER.jar" | head -1)
SCRIPT_RT=$(find "$CACHE" -name "kotlin-script-runtime-$KVER.jar" | head -1)
ANNOTATIONS=$(find_jar "annotations-13.0.jar")
# ⚠ The compiler itself needs coroutines on ITS classpath, or it dies mid-pipeline with
# NoClassDefFoundError: kotlinx/coroutines/CoroutineScope -- which looks like a fault in the
# code being compiled and is not.
COROUTINES=$(find_jar "kotlinx-coroutines-core-jvm-*.jar")

for v in KOTLINC STDLIB REFLECT SCRIPT_RT ANNOTATIONS COROUTINES; do
  [ -n "${!v}" ] || { echo "missing jar for $v"; exit 1; }
done

# Everything the app was built against. The installed app dir is the cheapest place to get a
# complete, consistent dependency set; LOCAL classes come first on the classpath so the code
# under test is always the working tree's.
DEP_JARS=$(find /opt/nuvio/lib/app -name '*.jar' 2>/dev/null | tr '\n' ':')
[ -n "$DEP_JARS" ] || { echo "no dependency jars found in /opt/nuvio/lib/app"; exit 1; }

COMPILER_CP="$KOTLINC:$STDLIB:$REFLECT:$SCRIPT_RT:$ANNOTATIONS:$COROUTINES"
APP_CP="$CLASSES:$DEP_JARS$STDLIB"

rm -rf "$OUT"; mkdir -p "$OUT"
echo "compiling..."
# ⚠ -Xfriend-paths is required, not cosmetic: the controller reads PlayerSettingsStorage,
# which is `internal` to the app module. Gradle compiles it as part of that module, but this
# is a separate compilation, so without declaring the app's classes a friend every reference
# fails with "cannot access ... it is internal in file".
java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -cp "$APP_CP" -d "$OUT" -nowarn -jvm-target 17 -Xfriend-paths="$CLASSES" \
  "$MPV_SRC/LibMpv.kt" "$MPV_SRC/MpvHandle.kt" "$MPV_SRC/MpvPlayerController.kt" \
  "$MPV_SRC/MpvEngineOptions.kt" "$REPO/scripts/spikes/MpvControllerSpike.kt"

echo "running..."
exec java -cp "$OUT:$APP_CP" com.nuvio.app.desktop.mpv.MpvControllerSpikeKt "$@"
