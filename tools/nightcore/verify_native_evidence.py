#!/usr/bin/env python3
"""Fail-closed NIGHT PRINT STL/3MF evidence gate. Stdlib only.

Usage: python3 tools/nightcore/verify_native_evidence.py DIR
Does not interpret a passing Gradle task as evidence of G-code validity.
"""
import json
import pathlib
import re
import sys

FILES = {
    "raw-stl-native-evidence.json": ("test", "final_audit_pass"),
    "embedded-3mf-native-evidence.json": ("result", "audit_passed"),
}


def validate(directory):
    directory = pathlib.Path(directory)
    errors = []
    for name, (status_key, audit_key) in FILES.items():
        path = directory / name
        try:
            if path.is_symlink() or not path.is_file():
                raise ValueError("missing or symlinked evidence")
            obj = json.loads(path.read_text(encoding="utf-8"))
            if not isinstance(obj, dict):
                raise ValueError("expected JSON object")
            if obj.get(status_key) != "PASS":
                raise ValueError(f"{status_key} is not PASS")
            if obj.get(audit_key) is not True:
                raise ValueError(f"{audit_key} is not true")
            size = obj.get("gcode_bytes")
            if type(size) is not int or size <= 0:
                raise ValueError("gcode_bytes must be a positive integer")
        except (OSError, ValueError, UnicodeError, json.JSONDecodeError) as exc:
            errors.append(f"{name}: {exc}")
    return errors


def recover_from_logcat(directory):
    """Recover complete JSON emitted by passing Android instrumentation tests.

    AndroidX Test Orchestrator clears app data between test invocations, so
    files written to targetContext.getExternalFilesDir may not survive.
    Only use exact test-tag lines containing a full JSON object.
    """
    directory = pathlib.Path(directory)
    log = directory / "native-android-logcat.txt"
    if not log.is_file():
        return
    tags = {
        "NIGHTPRINT_E2E": "raw-stl-native-evidence.json",
        "NIGHTPRINT_3MF_E2E": "embedded-3mf-native-evidence.json",
    }
    for line in log.read_text(encoding="utf-8", errors="replace").splitlines():
        for tag, filename in tags.items():
            if not re.search(r"\\b" + re.escape(tag) + r"\\s*:", line):
                continue
            candidate = line.split(tag + ":", 1)[-1].strip()
            if not candidate.startswith("{") or not candidate.endswith("}"):
                continue
            try:
                obj = json.loads(candidate)
            except ValueError:
                continue
            if not isinstance(obj, dict):
                continue
            status_key = FILES[filename][0]
            if obj.get(status_key) != "PASS":
                continue
            target = directory / filename
            if not target.exists():
                target.write_text(json.dumps(obj, indent=2), encoding="utf-8")


def main(argv):
    if len(argv) != 2:
        print("usage: verify_native_evidence.py DIR", file=sys.stderr)
        return 2
    recover_from_logcat(argv[1])
    errors = validate(argv[1])
    for error in errors:
        print("FAIL:", error, file=sys.stderr)
    if errors:
        print("RELEASE HOLD: native evidence incomplete", file=sys.stderr)
        return 1
    print("STL and 3MF evidence fields PASS; hardware print still unverified")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
