#!/usr/bin/env bash
set -euo pipefail

out=nightprint-e2e-ci
reports=/sdcard/Android/data/com.u1.slicer.orca.genesis.qa3/files
mkdir -p "$out"
cp native-candidate/source.txt native-candidate/candidate.sha256 "$out/"
adb shell getprop ro.product.cpu.abilist > "$out/device-abi.txt"
adb shell getprop ro.dalvik.vm.native.bridge >> "$out/device-abi.txt"

result=0
bash ./gradlew --no-daemon :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.u1.slicer.slicing.NightPrintNativeE2ETest || result=$?
adb logcat -d -s NIGHTPRINT_E2E:I NIGHTPRINT_3MF_E2E:I AndroidRuntime:E '*:S' > "$out/native-android-logcat.txt" || true
adb pull "$reports/nightprint-native-e2e-report.json" "$out/raw-stl-native-evidence.json" || true
adb pull "$reports/nightprint-3mf-profile-e2e-report.json" "$out/embedded-3mf-native-evidence.json" || true
if (( result != 0 )); then
  exit "$result"
fi

python3 tools/nightcore/verify_native_evidence.py "$out"
