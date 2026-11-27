#!/usr/bin/env bash
set -euo pipefail

out=nightprint-e2e-ci
stage="${1:-}"
mkdir -p "$out"

if [[ "$stage" == verify ]]; then
  trap 'echo $? > "$out/exit-code.txt"' EXIT
  for suite in native ui; do
    if [[ ! -f "$out/$suite-junit-passed" || ! -f "$out/$suite-runner-exit-code.txt" ]] ||
        ! grep -Fxq 0 "$out/$suite-runner-exit-code.txt"; then
      echo "Missing or failed Android stage: $suite" >&2
      exit 1
    fi
  done
  grep -E 'save_bytes=[1-9][0-9]* share_bytes=[1-9][0-9]*' "$out/export-logcat.txt"
  python3 .github/scripts/nightprint-product-evidence.py "$out" --verify
  exit 0
fi
if [[ "$stage" != native && "$stage" != ui ]]; then
  echo "Usage: $0 native|ui|verify" >&2
  exit 2
fi

package=com.u1.slicer.orca.genesis.qa4
reports="/sdcard/Android/data/$package/files"
serial="${ANDROID_SERIAL:-emulator-5554}"
export ANDROID_SERIAL="$serial"
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
  collect_host_diagnostics > "$out/$stage-host-final.txt"
  echo "$code" > "$out/$stage-runner-exit-code.txt"
  exit "$code"
}
trap finish EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

collect_host_diagnostics > "$out/$stage-host-initial.txt"
if ! connection_state; then
  echo "ADB target $serial is not booted and online" >&2
  exit 69
fi
device shell getprop ro.build.version.sdk | tee "$out/$stage-device-api.txt"
grep -Fxq 36 "$out/$stage-device-api.txt"
device shell getprop ro.product.cpu.abilist > "$out/$stage-device-abi.txt"
device shell getprop ro.dalvik.vm.native.bridge >> "$out/$stage-device-abi.txt"

device logcat -c
# Stream before the tests so evidence already emitted survives a disconnect.
timeout -k 2s 20m adb -s "$serial" logcat -v brief -s NIGHTPRINT_E2E:I NIGHTPRINT_3MF_E2E:I \
  NIGHTPRINT_EXPORT:I NIGHTPRINT_PETG_E2E:I NIGHTPRINT_QUALITY:I NIGHTPRINT_PETG_SETTINGS:I AndroidRuntime:E ActivityManager:E '*:S' > "$out/$stage-live-logcat.txt" 2>&1 &
log_pid=$!

run_suite() {
  local suite="$1" class="$2" expected="$3" result=0
  local results=app/build/outputs/androidTest-results/connected
  if ! connection_state; then
    echo "ADB lost before $suite suite" >&2
    return 69
  fi
  rm -rf -- "$results" || return
  timeout -k 15s 7m bash ./gradlew --no-daemon :app:connectedDebugAndroidTest \
    "-Pandroid.testInstrumentationRunnerArguments.class=$class" 2>&1 | tee "$out/$suite-gradle.log" || result=$?
  if [[ -d "$results" ]]; then
    cp -a "$results" "$out/$suite-results" || return
  fi
  printf '%s\n' "$result" > "$out/$suite-exit-code.txt"
  if ! connection_state; then
    echo "ADB disconnected during $suite suite" >&2
    return 69
  fi
  if (( result != 0 )); then
    return "$result"
  fi
  python3 - "$out/$suite-results" "$class" "$expected" <<'PY' || return
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

directory, classnames, expected = sys.argv[1:]
cases = [
    (case.get("classname"), case.get("name"), case)
    for report in Path(directory).rglob("TEST-*.xml")
    for case in ET.parse(report).iter("testcase")
]
names = {(classname, name) for classname, name, _ in cases}
if len(names) != len(cases) or any(not name for _, name in names):
    raise SystemExit("Duplicate or unnamed JUnit testcase")
classes = dict(zip(classnames.split(","), map(int, expected.split(",")), strict=True))
if {classname for classname, _, _ in cases} != classes.keys():
    raise SystemExit("JUnit classes do not match the selected suites")
for classname, count in classes.items():
    selected = [(name, case) for group, name, case in cases if group == classname]
    if len(selected) != count:
        raise SystemExit(f"{classname}: expected {count} distinct tests, found {len(selected)}")
    if classname.endswith("NightPrintAndroid16UiTest") and not any(
        name == "petgJobSliceReachesVerifiedOutputOrExplicitSafetyHold" for name, _ in selected
    ):
        raise SystemExit("Missing PETG UI acceptance test")
    for name, case in selected:
        if any(case.find(tag) is not None for tag in ("failure", "error", "skipped")):
            raise SystemExit(f"{classname}: failed or skipped {name}")
    print(f"{classname}: {count}/{count} passed")
PY
  touch "$out/$suite-junit-passed"
}

if [[ "$stage" == native ]]; then
  native_result=0
  run_suite native com.u1.slicer.slicing.NightPrintNativeE2ETest,com.u1.slicer.slicing.NightPrintQualityE2ETest 2,3 || native_result=$?
  if connection_state; then
    device logcat -d -v brief -s NIGHTPRINT_E2E:I NIGHTPRINT_3MF_E2E:I NIGHTPRINT_QUALITY:I NIGHTPRINT_PETG_SETTINGS:I AndroidRuntime:E '*:S' \
      > "$out/native-android-logcat.txt" || cp "$out/$stage-live-logcat.txt" "$out/native-android-logcat.txt"
    device pull "$reports/nightprint-native-e2e-report.json" "$out/raw-stl-native-evidence.json" || true
    device pull "$reports/nightprint-3mf-profile-e2e-report.json" "$out/embedded-3mf-native-evidence.json" || true
  else
    cp "$out/$stage-live-logcat.txt" "$out/native-android-logcat.txt"
  fi
  if (( native_result != 0 )); then exit "$native_result"; fi
  python3 tools/nightcore/verify_native_evidence.py "$out"
  exit 0
fi

ui_result=0
run_suite ui com.u1.slicer.ui.NightPrintAndroid16UiTest 8 || ui_result=$?
device logcat -d -v brief -s NIGHTPRINT_EXPORT:I NIGHTPRINT_PETG_E2E:I '*:S' > "$out/export-logcat.txt" || cp "$out/$stage-live-logcat.txt" "$out/export-logcat.txt"
exit "$ui_result"
