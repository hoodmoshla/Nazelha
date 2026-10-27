#!/usr/bin/env bash
# The emulator-runner action executes each script line in a separate shell, so
# keep variable state, conditionals, timeouts, and log collection in one script.
set +e

mkdir -p android-test-diagnostics
timeout 15s adb devices -l > android-test-diagnostics/adb-before.txt 2>&1

capture_diagnostics() {
  timeout 20s adb logcat -d -v threadtime -t 20000 > android-test-diagnostics/logcat.txt 2>&1 || true
  timeout 20s adb logcat -d -v threadtime -t 20000 -s App:E AndroidRuntime:E > android-test-diagnostics/app-errors-logcat.txt 2>&1 || true
  timeout 20s adb exec-out screencap -p > android-test-diagnostics/screenshot.png 2> android-test-diagnostics/screencap-error.txt || true
  timeout 20s adb shell uiautomator dump --compressed /sdcard/nazelha-window.xml > android-test-diagnostics/uiautomator.log 2>&1 || true
  timeout 20s adb pull /sdcard/nazelha-window.xml android-test-diagnostics/window.xml > /dev/null 2>&1 || true
  timeout 20s adb shell dumpsys activity activities > android-test-diagnostics/activities.txt 2>&1 || true
}

preserve_test_reports() {
  local source="$1"
  local destination="$2"
  if [ -d "$source" ]; then
    mkdir -p "$destination"
    cp -a "$source"/. "$destination"/ || true
  fi
}

boot_status=$(timeout 20s adb -s emulator-5554 shell getprop sys.boot_completed 2>&1 | tr -d '\r')
printf '%s\n' "$boot_status" | tee android-test-diagnostics/boot-status.txt
if [ "$boot_status" != "1" ]; then
  capture_diagnostics
  echo "Android emulator did not report sys.boot_completed=1" >&2
  exit 3
fi

run_gradle() {
  local label="$1"
  shift
  (
    while sleep 60; do
      echo "Gradle task '$label' is still running; full output is being saved to android-test-diagnostics/gradle-${label}.log"
    done
  ) &
  local heartbeat_pid=$!
  timeout --signal=INT --kill-after=30s 10m ./gradlew "$@" \
    --no-daemon \
    --no-configuration-cache \
    "-Dorg.gradle.jvmargs=-Xmx3g -Dfile.encoding=UTF-8" \
    --stacktrace --info --console=plain \
    > "android-test-diagnostics/gradle-${label}.log" 2>&1
  local status=$?
  kill "$heartbeat_pid" 2>/dev/null || true
  wait "$heartbeat_pid" 2>/dev/null || true
  printf '%s\n' "$status" > "android-test-diagnostics/gradle-${label}-exit-code.txt"
  return "$status"
}

# Compose UI tests use test-only Compose/Kotlin APIs. Run the UI target without R8;
# the separate validation job builds, signs, verifies, and profiles the minified preview APK.
echo "Running Android UI instrumentation on the unminified Debug target."
timeout 15s adb logcat -c || true
run_gradle ui-tests \
  :app:connectedGithubPreviewDebugAndroidTest \
  -PnoSplits \
  -PABI_FILTERS=x86_64 \
  -PnazelhaUiTestsWithoutR8=true
ui_status=$?
printf '%s\n' "$ui_status" > android-test-diagnostics/gradle-exit-code.txt

preserve_test_reports app/build/outputs/androidTest-results/connected android-test-diagnostics/ui-test-results
preserve_test_reports app/build/reports/androidTests/connected android-test-diagnostics/ui-test-reports
timeout 15s adb devices -l > android-test-diagnostics/adb-after.txt 2>&1 || true
capture_diagnostics
printf 'UI instrumentation exit code: %s\n' "$ui_status"
grep -E '^Starting [0-9]+ tests|^Finished [0-9]+ tests|^BUILD (SUCCESSFUL|FAILED)' android-test-diagnostics/gradle-ui-tests.log || true
tail -n 140 android-test-diagnostics/gradle-ui-tests.log
exit "$ui_status"
