#!/usr/bin/env bash
# The emulator-runner action executes each script line in a separate shell, so
# keep variable state, conditionals, timeouts, and log collection in one script.
set +e

mkdir -p android-test-diagnostics
timeout 15s adb devices -l > android-test-diagnostics/adb-before.txt 2>&1
boot_status=$(timeout 20s adb -s emulator-5554 shell getprop sys.boot_completed 2>&1 | tr -d '\r')
printf '%s\n' "$boot_status" | tee android-test-diagnostics/boot-status.txt
if [ "$boot_status" != "1" ]; then
  timeout 20s adb logcat -d -v threadtime -t 3000 > android-test-diagnostics/logcat.txt 2>&1 || true
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
timeout 20s adb logcat -d -v threadtime -t 5000 > android-test-diagnostics/logcat.txt 2>&1 || true
timeout 20s adb shell dumpsys activity activities > android-test-diagnostics/activities.txt 2>&1 || true
printf 'Gradle exit code: %s\n' "$gradle_status"
cat android-test-diagnostics/adb-after.txt
tail -n 220 android-test-diagnostics/gradle.log
exit "$gradle_status"
