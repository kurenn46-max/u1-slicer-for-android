"""Run one bounded NIGHT CORE recovery against an existing Android CI result."""
import argparse
import hashlib
import json
import re
from pathlib import Path

from verify_native_evidence import FILES, recover_from_logcat, validate

LOG = "native-android-logcat.txt"


def run(evidence, output, source_sha, artifact_url, instrumentation_exit, recovery_sha):
    if any(not re.fullmatch(r"[0-9a-f]{40}", sha) for sha in (source_sha, recovery_sha)):
        raise ValueError("source and recovery SHA must be full Git commit IDs")
    output.mkdir(parents=True, exist_ok=False)
    state = {
        "source_sha": source_sha,
        "recovery_sha": recovery_sha,
        "artifact_url": artifact_url,
        "instrumentation_exit": instrumentation_exit,
        "verification": "UNVERIFIED",
        "release_authorized": False,
        "attempts": [],
    }

    def save():
        temporary = output / "checkpoint.tmp"
        temporary.write_text(json.dumps(state, indent=2) + "\n", encoding="utf-8")
        temporary.replace(output / "checkpoint.json")

    save()
    reports = output / "evidence"
    reports.mkdir()
    try:
        state["input_sha256"] = {}
        for name in (*FILES, LOG):
            original = evidence / name
            if original.is_symlink():
                raise ValueError(f"symlinked input: {name}")
            if original.exists():
                content = original.read_bytes()
                (reports / name).write_bytes(content)
                state["input_sha256"][name] = hashlib.sha256(content).hexdigest()

        failures = validate(reports)
        state["detected_failures"] = failures
        missing = [name for name in FILES if not (reports / name).exists()]
        if instrumentation_exit != 0:
            state["cause"] = "instrumentation_failed"
            state["next_action"] = "Investigate the original instrumentation failure."
        elif not failures:
            state["verification"] = "EVIDENCE_PASS"
            state["next_action"] = "Retain the original validated audit reports."
        elif len(failures) != len(missing) or not (reports / LOG).is_file():
            state["cause"] = "invalid_report_or_missing_log"
            state["next_action"] = "Obtain complete evidence from a fresh successful Android run."
        else:
            state["cause"] = "audit_reports_missing_after_instrumentation"
            attempt = {"number": 1, "action": "restore_missing_reports_from_logcat"}
            state["attempts"].append(attempt)
            save()
            recover_from_logcat(reports)
            original_reports = state["input_sha256"].keys() & FILES.keys()
            attempt["existing_reports_unchanged"] = all(
                hashlib.sha256((reports / name).read_bytes()).hexdigest()
                == state["input_sha256"][name] for name in original_reports
            )
            attempt["revalidation_errors"] = validate(reports)
            passed = attempt["existing_reports_unchanged"] and not attempt["revalidation_errors"]
            attempt["exit"] = 0 if passed else 1
            state["verification"] = "EVIDENCE_PASS" if passed else "UNVERIFIED"
            state["next_action"] = (
                "Retain the recovered reports and original logcat." if passed
                else "Investigate missing or inconsistent logcat output before retrying."
            )
    except (OSError, ValueError) as error:
        state["error"] = str(error)
        if state["attempts"]:
            state["attempts"][-1]["exit"] = 1
        state["next_action"] = "Review the rejected input before retrying."
    save()
    return 0 if state["verification"] == "EVIDENCE_PASS" else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("evidence", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--source-sha", required=True)
    parser.add_argument("--recovery-sha", required=True)
    parser.add_argument("--artifact-url", required=True)
    parser.add_argument("--instrumentation-exit", type=int, required=True)
    args = parser.parse_args()
    return run(args.evidence, args.output, args.source_sha, args.artifact_url,
               args.instrumentation_exit, args.recovery_sha)


if __name__ == "__main__":
    raise SystemExit(main())
