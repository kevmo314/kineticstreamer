#!/bin/bash
# Runs the instrumented tests with the end-to-end receiver (built by an earlier step) alive for
# the duration. Started here rather than in its own step because the runner kills background
# processes when a step ends. The emulator reaches the receiver at 10.0.2.2.
set -u
# The CI emulator renders 1080p with software GL and manages about 20 fps, so only require 15.
"$RUNNER_TEMP/e2erecv" -min-fps 15 -out "$RUNNER_TEMP/e2e-out" > "$RUNNER_TEMP/e2erecv.log" 2>&1 &
receiver=$!
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.e2eRequired=true --stacktrace
status=$?
kill "$receiver" 2>/dev/null
exit $status
