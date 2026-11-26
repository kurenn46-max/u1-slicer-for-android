#!/usr/bin/env bash
set -euo pipefail

out=nightprint-e2e-ci
reports=/sdcard/Android/data/com.u1.slicer.orca.genesis.qa4/files
mkdir -p "$out"
cp native-candidate/source.txt native-candidate/candidate.sha256 "$out/"
adb shell getprop ro.build.version.sdk | tee "$out/device-api.txt"
grep -Fxq 36 "$out/device-api.txt"
adb shell getprop ro.product.cpu.abilist > "$out/device-abi.txt"
adb shell getprop ro.dalvik.vm.native.bridge >> "$out/device-abi.txt"

adb logcat -c
result=0
bash ./gradlew --no-daemon :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.u1.slicer.slicing.NightPrintNativeE2ETest,com.u1.slicer.ui.NightPrintAndroid16UiTest || result=$?
adb logcat -d -v brief -s NIGHTPRINT_E2E:I NIGHTPRINT_3MF_E2E:I AndroidRuntime:E '*:S' > "$out/native-android-logcat.txt" || true
{
  echo "QA4 package after connectedDebugAndroidTest:"
  adb shell pm path com.u1.slicer.orca.genesis.qa4 || echo "QA4 package is absent"
  echo "Audit directory after connectedDebugAndroidTest:"
  adb shell ls -ld "$reports" || true
} 2>&1 | tee "$out/post-test-device-state.txt"
adb pull "$reports/nightprint-native-e2e-report.json" "$out/raw-stl-native-evidence.json" || true
adb pull "$reports/nightprint-3mf-profile-e2e-report.json" "$out/embedded-3mf-native-evidence.json" || true
if (( result != 0 )); then
  exit "$result"
fi

python3 tools/nightcore/verify_native_evidence.py "$out"

for report in "$out/raw-stl-native-evidence.json" "$out/embedded-3mf-native-evidence.json"; do
  echo "$(basename "$report")"
  jq -c '{fixture,material,printer,gcode_bytes,nozzle_c,bed_c,startup_peak_c}' "$report"
done
