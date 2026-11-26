#!/usr/bin/env bash
set -euo pipefail

out=nightprint-e2e-ci
package=com.u1.slicer.orca.genesis.qa4
reports="/sdcard/Android/data/$package/files"
serial="${ANDROID_SERIAL:-emulator-5554}"
export ANDROID_SERIAL="$serial"
mkdir -p "$out"
cp native-candidate/source.txt native-candidate/candidate.sha256 "$out/"

device() { timeout -k 2s 8s adb -s "$serial" "$@"; }

connection_state() {
  local state boot
  state=$(device get-state 2>/dev/null) || return 1
  [[ "$state" == device ]] || return 1
  boot=$(device shell getprop sys.boot_completed 2>/dev/null) || return 1
  [[ "$boot" == 1 ]]
}

collect_host_diagnostics() {
  {
    date -u
    timeout -k 2s 5s adb devices -l || true
    timeout -k 2s 5s adb version || true
    free -m || true
    df -h . || true
    ps -eo pid,stat,comm,rss | grep -E 'adb|emulator|qemu' || true
    timeout -k 2s 5s sudo -n dmesg --level=err,warn | grep -Ei 'qemu|emulator|oom|out of memory|segfault' | tail -40 || true
  } 2>&1
}

log_pid=
finish() {
  local code=$?
  trap - EXIT
  if [[ -n "$log_pid" ]]; then
    kill "$log_pid" 2>/dev/null || true
  fi
  collect_host_diagnostics > "$out/host-final.txt"
  echo "$code" > "$out/exit-code.txt"
  exit "$code"
}
trap finish EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

collect_host_diagnostics > "$out/host-initial.txt"
if ! connection_state; then
  echo "ADB target $serial is not booted and online" >&2
  exit 69
fi
device shell getprop ro.build.version.sdk | tee "$out/device-api.txt"
grep -Fxq 36 "$out/device-api.txt"
device shell getprop ro.product.cpu.abilist > "$out/device-abi.txt"
device shell getprop ro.dalvik.vm.native.bridge >> "$out/device-abi.txt"

device logcat -c
# Stream before the tests so evidence already emitted survives a disconnect.
timeout -k 2s 20m adb -s "$serial" logcat -v brief -s NIGHTPRINT_E2E:I NIGHTPRINT_3MF_E2E:I \
  NIGHTPRINT_EXPORT:I AndroidRuntime:E ActivityManager:E '*:S' > "$out/live-logcat.txt" 2>&1 &
log_pid=$!

run_suite() {
  local suite="$1" class="$2" expected="$3" result=0
  local results=app/build/outputs/androidTest-results/connected
  if ! connection_state; then
    echo "ADB lost before $suite suite" >&2
    return 69
  fi
  timeout -k 15s 7m bash ./gradlew --no-daemon :app:connectedDebugAndroidTest \
    "-Pandroid.testInstrumentationRunnerArguments.class=$class" 2>&1 | tee "$out/$suite-gradle.log" || result=$?
  if [[ -d "$results" ]]; then
    cp -a "$results" "$out/$suite-results"
  fi
  printf '%s\n' "$result" > "$out/$suite-exit-code.txt"
  if ! connection_state; then
    echo "ADB disconnected during $suite suite" >&2
    return 69
  fi
  if (( result != 0 )); then
    return "$result"
  fi
  python3 - "$out/$suite-results" "$class" "$expected" <<'PY'
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

directory, classname, expected = sys.argv[1:]
cases = [
    case for report in Path(directory).rglob("TEST-*.xml")
    for case in ET.parse(report).iter("testcase")
    if case.get("classname") == classname
]
names = {case.get("name") for case in cases}
if len(cases) != int(expected) or len(names) != len(cases):
    raise SystemExit(f"{classname}: expected {expected} distinct tests, found {len(names)}")
for case in cases:
    if any(case.find(tag) is not None for tag in ("failure", "error", "skipped")):
        raise SystemExit(f"{classname}: failed or skipped {case.get('name')}")
print(f"{classname}: {len(cases)}/{expected} passed")
PY
}

native_result=0
run_suite native com.u1.slicer.slicing.NightPrintNativeE2ETest 2 || native_result=$?
if connection_state; then
  device logcat -d -v brief -s NIGHTPRINT_E2E:I NIGHTPRINT_3MF_E2E:I AndroidRuntime:E '*:S' \
    > "$out/native-android-logcat.txt" || cp "$out/live-logcat.txt" "$out/native-android-logcat.txt"
  device pull "$reports/nightprint-native-e2e-report.json" "$out/raw-stl-native-evidence.json" || true
  device pull "$reports/nightprint-3mf-profile-e2e-report.json" "$out/embedded-3mf-native-evidence.json" || true
else
  cp "$out/live-logcat.txt" "$out/native-android-logcat.txt"
fi
audit_result=0
python3 tools/nightcore/verify_native_evidence.py "$out" || audit_result=$?
if (( native_result != 0 )); then exit "$native_result"; fi
if (( audit_result != 0 )); then exit "$audit_result"; fi

run_suite ui com.u1.slicer.ui.NightPrintAndroid16UiTest 7
device logcat -d -v brief -s NIGHTPRINT_EXPORT:I '*:S' > "$out/export-logcat.txt"
grep -E 'save_bytes=[1-9][0-9]* share_bytes=[1-9][0-9]*' "$out/export-logcat.txt"
