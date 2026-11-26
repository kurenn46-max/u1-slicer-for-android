"""NIGHT PRINT's project-specific diagnostics and audited artifact handling."""
import re
import xml.etree.ElementTree as ET

from github_cycle import archive
from operational_loop import cycle
from recover_evidence import LOG
from verify_native_evidence import FILES

NATIVE_WORKFLOW = ".github/workflows/nightprint-arm64-stage3.yml"
INSTRUMENTATION = "Real native Android instrumentation"


def classify_failure(log):
    if log is None:
        return "unclassified_ci_failure"
    if re.search(r"ADB (?:target .* is not booted|lost before|disconnected during)|AdbCommandRejectedException: device offline", log):
        return "adb_disconnect"
    if re.search(r"Received status code 429|HTTP 429|Too Many Requests", log, re.I):
        return "dependency_rate_limit"
    if re.search(r"^FAIL: .*native-evidence\.json:", log, re.M):
        return "audit_evidence_rejected"
    if re.search(r"Tests on .* failed|AssertionError|tests? failed", log, re.I):
        return "test_failure"
    return "unclassified_ci_failure"


def inspect_native(raw_run, jobs, artifacts, work, output, previous, recovery_sha, checkout_sha):
    step = next(
        (step for job in jobs if job["name"].startswith("native-e2e /")
         for step in job.get("steps", []) if step["name"].startswith(INSTRUMENTATION)), None
    )
    # An incomplete test run is never eligible for recovery.
    if not step or step["conclusion"] != "success":
        return None, None
    artifact = next((item for item in artifacts if item["name"] ==
                     f"nightprint-native-android-e2e-{raw_run['id']}"), None)
    if artifact is None:
        return None, None

    evidence = work / "android"
    evidence.mkdir(parents=True)
    with archive(artifact, raw_run["id"], raw_run["head_sha"]) as bundle:
        for name in (*FILES, LOG, "tested-source.txt"):
            path = f"nightprint-e2e-ci/{name}"
            if path in bundle.namelist():
                (evidence / name).write_bytes(bundle.read(path))
        tests = android_counts(bundle)
    tested_source = (evidence / "tested-source.txt").read_text().strip()
    if tested_source != checkout_sha:
        raise ValueError("Android artifact checkout differs from the current PR merge SHA")
    result = cycle({
        "head_sha": raw_run["head_sha"],
        "checkout_sha": tested_source,
        "instrumentation": "success",
        "run_id": raw_run["id"],
        "artifact_url": f"{raw_run['html_url']}/artifacts/{artifact['id']}",
    }, evidence, output, previous, recovery_sha)
    return result, tests


def android_counts(bundle):
    paths = bundle.namelist()
    split = [path for path in paths if path.startswith(("nightprint-e2e-ci/native-results/",
                                                         "nightprint-e2e-ci/ui-results/"))]
    paths = split or [path for path in paths if path.startswith("app/build/outputs/androidTest-results/")]
    totals = {"passed": 0, "failed": 0, "skipped": 0}
    for path in paths:
        if not (path.rsplit("/", 1)[-1].startswith("TEST-") and path.endswith(".xml")):
            continue
        for case in ET.fromstring(bundle.read(path)).iter("testcase"):
            if case.find("failure") is not None or case.find("error") is not None:
                totals["failed"] += 1
            elif case.find("skipped") is not None:
                totals["skipped"] += 1
            else:
                totals["passed"] += 1
    return totals if sum(totals.values()) else None
