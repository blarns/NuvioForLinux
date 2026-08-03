#!/bin/bash
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"
./gradlew composeApp:run --no-daemon 2>&1 | tee /tmp/nuvio.log
