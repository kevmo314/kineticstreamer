#!/bin/bash
# Runs the instrumented tests with the end-to-end receiver (built by an earlier step) alive for
# the duration. Started here rather than in its own step because the runner kills background
# processes when a step ends. The emulator reaches the receiver at 10.0.2.2.
set -u
"$RUNNER_TEMP/e2erecv" -out "$RUNNER_TEMP/e2e-out" > "$RUNNER_TEMP/e2erecv.log" 2>&1 &
receiver=$!
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.e2eRequired=true --stacktrace
status=$?
kill "$receiver" 2>/dev/null
exit $status
