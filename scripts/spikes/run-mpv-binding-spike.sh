#!/usr/bin/env bash
# Compile and run MpvBindingSpike.kt against the REAL production binding.
#
# The spike is compiled together with LibMpv.kt and MpvHandle.kt on purpose: those types are
# Kotlin `internal`, so only a same-module compilation can see them. This means the spike
# tests the shipping code, not a copy that can drift away from it.
#
# kotlinc is taken from the Gradle cache rather than requiring a system Kotlin install.
# ⚠ stdlib, reflect, script-runtime, coroutines and annotations must ALL be on the COMPILER's
# own -cp or it dies partway through with NoClassDefFoundError.
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SRC="$REPO/composeApp/src/desktopMain/kotlin/com/nuvio/app/desktop/mpv"
OUT="${TMPDIR:-/tmp}/mpv-binding-spike"
CACHE="$HOME/.gradle/caches/modules-2/files-2.1"

find_jar() { find "$CACHE" -name "$1" 2>/dev/null | grep -v sources | head -1; }

KOTLINC=$(find_jar "kotlin-compiler-embeddable-*.jar")
STDLIB=$(find_jar "kotlin-stdlib-2*.jar")
REFLECT=$(find_jar "kotlin-reflect-2*.jar")
SCRIPT_RT=$(find_jar "kotlin-script-runtime-2*.jar")
ANNOTATIONS=$(find_jar "annotations-13.0.jar")
# ⚠ Not optional: the compiler itself needs coroutines on ITS classpath. Without it the run
# dies mid-pipeline with NoClassDefFoundError: kotlinx/coroutines/CoroutineScope, which looks
# like a problem with the code being compiled and is not.
COROUTINES=$(find_jar "kotlinx-coroutines-core-jvm-*.jar")
JNA=$(find_jar "jna-jpms-5.12.1.jar")
JNA_PLATFORM=$(find_jar "jna-platform-jpms-5.12.1.jar")

for v in KOTLINC STDLIB REFLECT SCRIPT_RT ANNOTATIONS COROUTINES JNA; do
  [ -n "${!v}" ] || { echo "missing jar for $v -- run a Gradle build first"; exit 1; }
done

COMPILER_CP="$KOTLINC:$STDLIB:$REFLECT:$SCRIPT_RT:$ANNOTATIONS:$COROUTINES"
APP_CP="$STDLIB:$JNA:$JNA_PLATFORM"

rm -rf "$OUT"; mkdir -p "$OUT"
echo "compiling..."
java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -cp "$APP_CP" -d "$OUT" -nowarn \
  "$SRC/LibMpv.kt" "$SRC/MpvHandle.kt" "$REPO/scripts/spikes/MpvBindingSpike.kt"

echo "running..."
exec java -cp "$APP_CP:$OUT" com.nuvio.app.desktop.mpv.MpvBindingSpikeKt
