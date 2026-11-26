#!/usr/bin/env python3
"""Fail-closed NIGHT PRINT STL/3MF evidence gate. Stdlib only.

Usage: python3 tools/nightcore/verify_native_evidence.py DIR
Does not interpret a passing Gradle task as evidence of G-code validity.
"""
import json
import pathlib
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


def main(argv):
    if len(argv) != 2:
        print("usage: verify_native_evidence.py DIR", file=sys.stderr)
        return 2
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
