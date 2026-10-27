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

boot_status=$(timeout 20s adb -s emulator-5554 shell getprop sys.boot_completed 2>&1 | tr -d '\r')
printf '%s\n' "$boot_status" | tee android-test-diagnostics/boot-status.txt
if [ "$boot_status" != "1" ]; then
  capture_diagnostics
  echo "Android emulator did not report sys.boot_completed=1" >&2
  exit 3
fi

timeout 15s adb logcat -c || true
timeout --signal=INT --kill-after=30s 15m ./gradlew \
  :app:connectedGithubPreviewDebugAndroidTest \
  -PnoSplits \
  -PABI_FILTERS=x86_64 \
  --no-daemon \
  --no-configuration-cache \
  "-Dorg.gradle.jvmargs=-Xmx3g -Dfile.encoding=UTF-8" \
  --stacktrace --info --console=plain \
  > android-test-diagnostics/gradle.log 2>&1
gradle_status=$?
printf '%s\n' "$gradle_status" > android-test-diagnostics/gradle-exit-code.txt

timeout 15s adb devices -l > android-test-diagnostics/adb-after.txt 2>&1 || true
capture_diagnostics
printf 'Gradle exit code: %s\n' "$gradle_status"
cat android-test-diagnostics/adb-after.txt
tail -n 220 android-test-diagnostics/gradle.log
exit "$gradle_status"
