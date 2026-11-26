#!/usr/bin/env sh
# Installs prebuilt APKs on the connected device or emulator and runs the instrumented
# tests with `am instrument`, without Gradle (so no dependency downloads are needed).
#
# Usage: scripts/run-instrumented-tests.sh <app-debug.apk> <app-debug-androidTest.apk> [log file]
set -eu

APP_APK="$1"
TEST_APK="$2"
LOG="${3:-instrumented-tests.log}"
RUNNER="io.github.kawincodes.stiksound.test/androidx.test.runner.AndroidJUnitRunner"

adb wait-for-device
adb install -r "$APP_APK"
adb install -r "$TEST_APK"
adb shell am instrument -w "$RUNNER" | tee "$LOG"

# `am instrument` exits 0 even when tests fail, so read the JUnit summary instead.
if grep -q "FAILURES!!!" "$LOG" || grep -q "INSTRUMENTATION_FAILED" "$LOG" || ! grep -q "^OK (" "$LOG"; then
  echo "Instrumented tests failed." >&2
  exit 1
fi
echo "Instrumented tests passed."
