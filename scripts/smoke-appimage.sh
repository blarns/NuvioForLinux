#!/usr/bin/env bash
#
# Launches a built AppImage for a few seconds and reports what its log says.
#
# Why this exists: cutting v0.3.7, every static check passed — unit tests, `javap` confirming
# the new classes and the baked backend URL, the glibc floor, the deb Depends, a clean tree —
# and the artifact was still wrong twice over. Only running the packaged build showed it:
#
#   * two membership RPCs fired before the Supabase session restored, so every cold start sent
#     a pair of guaranteed-401 requests to the backend;
#   * the whole app-icon feature did nothing, because src/desktopMain/resources was never
#     registered as a resources dir and so no icon PNG was ever packaged. It failed silently:
#     the window-icon loader falls back to nuvio-icon.png, which resolves only because a
#     duplicate copy happens to live in src/jvmMain/resources.
#
# Neither is visible to a unit test, to jar inspection, or to `gradlew run` (which reads
# resources straight off the build tree and rides an already-warm session). Run this before
# publishing, every time.
#
# ⚠⚠ THE TRAP THIS SCRIPT EXISTS TO AVOID: AppImageLauncher is installed on this machine and
# intercepts AppImage execution through binfmt. Launching an AppImage directly MOVES it to
# ~/Applications/<name>_<hash>.AppImage — the file vanishes from appimage-build/dist/, and if
# you have not noticed, the release you are about to publish has no artifact to upload. That
# happened three times cutting v0.3.7. This script defends twice over: it sets
# APPIMAGELAUNCHER_DISABLE=1, and it runs a COPY in a temp dir so the real artifact is never
# the thing being executed.
#
# ⚠ Never stop the app with `pkill -f`/`pgrep -f` against the AppImage name or the main class:
# the pattern matches the shell running it and kills your own session (exit 144). This script
# kills the exact PID it started.
#
# Usage:
#   scripts/smoke-appimage.sh [path-to-appimage] [seconds]
# Defaults to appimage-build/dist/Nuvio-*-x86_64.AppImage and 45 seconds.

set -uo pipefail

APPIMAGE="${1:-}"
WAIT_SECONDS="${2:-45}"

if [[ -z "$APPIMAGE" ]]; then
    APPIMAGE=$(ls -1t appimage-build/dist/Nuvio-*-x86_64.AppImage 2>/dev/null | head -1)
fi

if [[ -z "$APPIMAGE" || ! -f "$APPIMAGE" ]]; then
    echo "==> No AppImage found. Pass one explicitly, or build one first:" >&2
    echo "    scripts/package-appimage.sh --version X.Y.Z --vlc-bundle appimage-build/vlc-bundle" >&2
    exit 1
fi

WORKDIR=$(mktemp -d)
LOG="$WORKDIR/smoke.log"
COPY="$WORKDIR/$(basename "$APPIMAGE")"
cleanup() { rm -rf "$WORKDIR"; }
trap cleanup EXIT

echo "==> Smoke-testing $(basename "$APPIMAGE")"
echo "    original stays put; running a copy under $WORKDIR"
cp "$APPIMAGE" "$COPY"
chmod +x "$COPY"

APPIMAGELAUNCHER_DISABLE=1 "$COPY" > "$LOG" 2>&1 &
APP_PID=$!

echo "==> pid $APP_PID, watching for ${WAIT_SECONDS}s"
for _ in $(seq 1 "$WAIT_SECONDS"); do
    kill -0 "$APP_PID" 2>/dev/null || break
    sleep 1
done

if ! kill -0 "$APP_PID" 2>/dev/null; then
    echo "==> ⚠ the app EXITED on its own before the window was closed — that is a failure"
    STARTUP_OK=1
else
    kill "$APP_PID" 2>/dev/null
    sleep 2
    kill -9 "$APP_PID" 2>/dev/null
    STARTUP_OK=0
fi

# The original artifact must still be where it was. If AppImageLauncher grabbed the copy that
# is fine — but say so, because it means the guard above is the only reason dist/ survived.
if [[ ! -f "$APPIMAGE" ]]; then
    echo "==> ⚠⚠ THE ORIGINAL ARTIFACT IS GONE FROM $(dirname "$APPIMAGE") — look in ~/Applications"
fi

echo
echo "===== findings ====="
FOUND=0
while IFS='|' read -r label pattern; do
    count=$(grep -ciE "$pattern" "$LOG")
    if [[ "$count" -gt 0 ]]; then
        printf '  %-34s %s\n' "$label" "$count"
        FOUND=1
    fi
done <<'PATTERNS'
permission denied (RLS/grant)     |permission denied for function
unable to load (repository warns) |Warn:.*Unable to load
missing resource                  |useResource|Resource.*not found|FileNotFoundException
fatal JVM error                   |A fatal error has been detected
PATTERNS

if [[ "$FOUND" -eq 0 ]]; then
    echo "  nothing flagged"
fi

echo
echo "===== exception classes seen ====="
grep -oE "^[a-z][a-z0-9.]+\.[A-Za-z]+(Exception|Error)" "$LOG" | sort | uniq -c | sort -rn | head -10 \
    || echo "  none"
echo
echo "⚠ Some of these are pre-existing addon noise (restricted header name, websocket 503,"
echo "  fetch timeouts). Compare the list against the previous release's run rather than"
echo "  reading it as a pass/fail — what matters is a class that is NEW."
echo
SAVED="appimage-build/smoke-$(basename "$APPIMAGE" .AppImage).log"
if mkdir -p appimage-build 2>/dev/null && cp "$LOG" "$SAVED" 2>/dev/null; then
    echo "Full log: $SAVED   (under the gitignored appimage-build/, so it cannot be committed)"
else
    echo "Full log: $LOG   (temp dir, deleted on exit — copy it now if you need it)"
fi

exit "$STARTUP_OK"
