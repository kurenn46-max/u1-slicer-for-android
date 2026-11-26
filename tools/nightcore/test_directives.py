import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from jsonschema import ValidationError

from directive_rules import QA4_ADB_HISTORY
from directives import build, render_files, repair_missing, validate_bundle
from github_cycle import REPO, api
from github_supervisor import failed_job_log
from nightprint_monitor import NATIVE_WORKFLOW, classify_failure


def entry(number, kind=None, *, status="failure", attempt=1, parent=None):
    head = f"{number:040x}"
    job = {"id": 200 + number, "conclusion": status}
    run = {"id": 100 + number, "attempt": attempt, "workflow_id": 11,
           "conclusion": status, "path": NATIVE_WORKFLOW, "jobs": [job]}
    issue = {"run_id": run["id"], "attempt": attempt, "workflow_id": 11, "job_id": job["id"],
             "kind": kind, "url": "https://untrusted.example/inject"}
    dependencies = [{"number": parent, "head_sha": f"{parent:040x}", "base_matches": True}] if parent else []
    return {"observation": {"number": number, "head_sha": head, "base_sha": f"{parent or 8:040x}",
                            "dependencies": dependencies}, "runs": [run], "repairs": [],
            "checks": "FAIL" if status == "failure" else "PASS", "issues": [issue] if kind else []}


def checkpoint(*entries, run_id=123):
    return {"schema": 3, "repository": REPO, "status": "UPDATED", "pending": {},
            "release_authorized": False, "run_id": run_id, "run_attempt": 1,
            "prs": {str(pr["observation"]["number"]): pr for pr in entries}}


class DirectiveTests(unittest.TestCase):
    def test_jni_marker_requires_the_emitted_error_and_maven_requires_its_host(self):
        marker = "C++ native sources changed but the bundled arm64 libprusaslicer-jni.so was not rebuilt."
        self.assertEqual(classify_failure(f'echo "::error::{marker}"'), "unclassified_ci_failure")
        self.assertEqual(classify_failure(f"timestamp ##[error]{marker}"), "stale_arm64_jni")
        maven = "Could not GET 'https://repo.maven.apache.org/maven2/a.pom'. Received status code 429"
        self.assertEqual(classify_failure(maven), "maven_central_rate_limit")
        self.assertEqual(classify_failure(maven.replace("repo.maven.apache.org", "example.com")),
                         "dependency_rate_limit")

    def test_source_failure_requires_project_assertion_context(self):
        self.assertEqual(classify_failure("java.lang.AssertionError: mismatch\n\tat com.u1.slicer.Check.run"),
                         "project_assertion_failure")
        self.assertEqual(classify_failure("java.lang.AssertionError: mismatch\n\tat external.foo"),
                         "test_failure")

    def test_stacked_build_commands_wait_for_upstream(self):
        data = build(checkpoint(entry(1, "stale_arm64_jni"), entry(2, "stale_arm64_jni", parent=1)))
        validate_bundle(data)
        upstream, downstream = data["directives"]
        self.assertEqual(downstream["blocked_by"], [upstream["id"]])
        self.assertEqual(downstream["status"], "blocked_by_dependency")
        self.assertNotIn("## PR #2", render_files(data)["codex-prompts.md"])

    def test_build_mismatch_is_reviewed_before_external_outage(self):
        data = build(checkpoint(entry(1, "maven_central_rate_limit"), entry(2, "stale_arm64_jni")))
        self.assertEqual([task["kind"] for task in data["directives"]],
                         ["stale_arm64_jni", "maven_central_rate_limit"])

    def test_duplicate_observations_and_reruns_keep_one_task_and_key(self):
        first = entry(2, "maven_central_rate_limit")
        repeat = entry(2, "maven_central_rate_limit", attempt=2)
        repeat["issues"] *= 2
        before = build(checkpoint(first))["directives"]
        after = build(checkpoint(repeat, run_id=124))["directives"]
        self.assertEqual(len(after), 1)
        self.assertEqual(len(after[0]["evidence"]), 1)
        self.assertEqual(before[0]["id"], after[0]["id"])
        self.assertNotIn("untrusted.example", json.dumps(after))

    def test_historical_or_passed_run_cannot_create_a_fix(self):
        passed = entry(6, "adb_disconnect", status="success")
        passed["tests"] = {"passed": 9, "failed": 0, "skipped": 0}
        passed["audit"] = {"status": "PASS", "observation": {"run_id": 106}}
        old = entry(2, "stale_arm64_jni")
        old["issues"][0]["historical"] = True
        data = build(checkpoint(passed, old))
        self.assertEqual(data["directives"], [])
        self.assertEqual(data["history"][0]["status"], "not_reproduced_at_current_head")

    def test_failing_foreign_job_id_does_not_form_evidence(self):
        fixture = entry(1, "stale_arm64_jni")
        fixture["issues"][0]["job_id"] = 999
        self.assertFalse(build(checkpoint(fixture))["directives"])

    def test_reject_interrupt_and_disallow_dispatch_in_schema(self):
        interrupted = checkpoint()
        interrupted["pending"] = {"1": []}
        with self.assertRaises(ValueError):
            build(interrupted)
        payload = build(checkpoint(entry(1, "stale_arm64_jni")))
        payload["execution"]["codex_sessions_started"] = 1
        with self.assertRaises(ValidationError):
            validate_bundle(payload)

    def test_repair_only_missing_output_and_never_overwrite_a_conflict(self):
        bundle = build(checkpoint(entry(1, "stale_arm64_jni")))
        with tempfile.TemporaryDirectory() as work:
            folder = Path(work)
            originals = render_files(bundle)
            self.assertEqual(set(repair_missing(folder, bundle)), set(originals))
            self.assertEqual(repair_missing(folder, bundle), [])
            (folder / "directives.ja.md").unlink()
            self.assertEqual(repair_missing(folder, bundle), ["directives.ja.md"])
            self.assertEqual((folder / "directives.ja.md").read_text(), originals["directives.ja.md"])
            (folder / "directives.ja.md").write_text("unexpected text")
            (folder / "summary.md").unlink()
            with self.assertRaises(ValueError):
                repair_missing(folder, bundle)
            self.assertFalse((folder / "summary.md").exists())


def live(directory, snapshot):
    bundle = json.loads((directory / "directives.json").read_text())
    original = json.loads(snapshot.read_text())
    validate_bundle(bundle)
    if bundle != build(original):
        raise ValueError("directive JSON diverges from the checkpoint")
    samples = [
        (45590018322, 139121406468, "stale_arm64_jni"),
        (45798006794, 139833706633, "stale_arm64_jni"),
        (45798006797, 139835376962, "maven_central_rate_limit"),
        (QA4_ADB_HISTORY["run_id"], QA4_ADB_HISTORY["job_id"], "adb_disconnect"),
    ]
    for run_id, job_id, expected in samples:
        job = api(f"actions/jobs/{job_id}")
        if job["run_id"] != run_id or job["conclusion"] != "failure":
            raise ValueError(f"unexpected source job {job_id}")
        if expected == "adb_disconnect" and job["head_sha"] != QA4_ADB_HISTORY["commit_sha"]:
            raise ValueError("historical QA4 checkout changed")
        observed = classify_failure(failed_job_log(job))
        if observed != expected:
            raise ValueError(f"job {job_id}: expected {expected}, got {observed}")
        print(f"verified GitHub job {job_id}: {observed}", flush=True)

    history = next((item for item in bundle["history"] if item["pr"] == 6), None)
    baseline_qa4_head = "058899b113acae31aa5cb4e4de5f2a5e359750b6"
    if history and history["current_commit_sha"] == baseline_qa4_head:
        if (history["status"] != "not_reproduced_at_current_head"
                or any(task["pr"] == 6 and task["kind"] == "adb_disconnect" for task in bundle["directives"])):
            raise ValueError("QA4's nine passing tests must suppress the historical ADB fix request")
    if not bundle["source_checkpoint"]["resumed_from_run_id"]:
        print("No retained predecessor checkpoint was available.", flush=True)
    for name, content in render_files(bundle).items():
        if (directory / name).read_text() != content:
            raise ValueError(f"unexpected generated {name}")
    with tempfile.TemporaryDirectory() as work:
        copied = Path(work)
        expected = render_files(bundle)
        for name, content in expected.items():
            if name != "directives.ja.md":
                (copied / name).write_text(content)
        (copied / "directives.json").write_bytes((directory / "directives.json").read_bytes())
        command = [sys.executable, str(Path(__file__).with_name("directives.py")), str(copied),
                   "--checkpoint", str(snapshot), "--repair-missing"]
        restored = json.loads(subprocess.check_output(command, text=True, timeout=10))
        repeated = json.loads(subprocess.check_output(command, text=True, timeout=10))
        if (restored != {"restored": ["directives.ja.md"]}
                or (copied / "directives.ja.md").read_text() != expected["directives.ja.md"]
                or repeated != {"restored": []}):
            raise ValueError("report repair was not exact and idempotent")
    print("Validated command JSON, Japanese report, Codex candidates and Summary. "
          "Restored a missing Japanese report from the real checkpoint; duplicate repair count 0.",
          flush=True)


if __name__ == "__main__":
    if sys.argv[1:2] == ["--live"]:
        live(Path(sys.argv[2]), Path(sys.argv[3]))
    else:
        unittest.main()
