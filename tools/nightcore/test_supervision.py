import io
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from zipfile import ZipFile

from github_cycle import api, archive
from github_supervisor import failed_job_log, review
from nightprint_monitor import android_counts, classify_failure
from recover_evidence import LOG
from supervision import current_runs, dependencies, render_summary, summarize_checks
import test_operational_loop
from verify_native_evidence import FILES, validate


def run_record(run_id=100, base="b", head="a", event="pull_request", conclusion="success", attempt=1):
    return {"id": run_id, "workflow_id": 2, "head_sha": head, "event": event, "path": "tests.yml",
            "name": "Tests", "html_url": "https://github.com/test/example/actions/runs/100",
            "run_attempt": attempt, "status": "completed", "conclusion": conclusion,
            "pull_requests": [{"number": 6, "head": {"sha": head}, "base": {"sha": base}}]}


class SupervisorTests(unittest.TestCase):
    def test_only_latest_ci_for_exact_head_and_base_is_current(self):
        runs = [
            run_record(), run_record(101, conclusion="failure"), run_record(102, base="old"),
            run_record(103, head="old"), run_record(104, event="workflow_dispatch"),
        ]
        matched, stale = current_runs({"number": 6, "head_sha": "a", "base_sha": "b"}, runs)
        self.assertEqual([run["id"] for run in matched], [101])
        self.assertEqual(stale, 1)
        self.assertEqual(summarize_checks(matched), "FAIL")
        self.assertEqual(summarize_checks([]), "UNVERIFIED")

    def test_dependency_is_pinned_to_base_revision(self):
        pr = {"base_ref": "parent", "base_repo_id": 3, "base_sha": "old"}
        parent = {"head_ref": "parent", "head_repo_id": 3, "head_sha": "new", "number": 5}
        fork = {**parent, "head_repo_id": 4}
        self.assertEqual(dependencies(pr, [parent, fork]),
                         [{"number": 5, "head_sha": "new", "base_matches": False}])

    def test_unchanged_checkout_reuses_checkpoint_and_does_not_repeat_work(self):
        pr = {"number": 6, "head_sha": "a", "base_sha": "b"}
        with tempfile.TemporaryDirectory() as directory, patch("github_supervisor.api") as gh, \
                patch("github_supervisor.review_run") as process:
            gh.return_value = {"total_count": 1, "workflow_runs": [run_record()]}
            process.return_value = {"jobs": [], "issues": [], "artifacts": [], "tests": None, "audit": None}
            work = Path(directory)
            state = {"pending": {}, "repository": "test/example", "status": "INTERRUPTED", "prs": {}}
            pr.update(dependencies=[], files_complete=True)
            first = review(pr, None, state, work / "first", work, "abc")
            state["pending"].clear()
            second = review(pr, first, state, work / "second", work, "abc")
            self.assertEqual((second["changes"], second["repairs"]), ([], []))
            self.assertEqual(process.call_count, 1)
            self.assertFalse((work / "second").exists())

    def test_interrupted_work_is_held_and_not_retried(self):
        pr = {"number": 6, "head_sha": "a", "base_sha": "b", "url": "https://github.com/test/example/pull/6",
              "dependencies": [], "files_complete": True}
        with tempfile.TemporaryDirectory() as directory, patch("github_supervisor.api") as gh, \
                patch("github_supervisor.review_run") as process:
            gh.return_value = {"total_count": 1, "workflow_runs": [run_record()]}
            state = {"pending": {"6": ["a", "b", [[100, 1]]]}, "repository": "test/example",
                     "status": "INTERRUPTED", "prs": {}}
            work = Path(directory)
            result = review(pr, None, state, work / "second", work, "abc")
            self.assertEqual(result["checks"], "HOLD")
            self.assertEqual(result["issues"][0]["kind"], "interrupted_review")
            process.assert_not_called()

    def test_classifies_adb_disconnect_and_dependency_limits(self):
        self.assertEqual(classify_failure("adb: device offline\nBoot complete"), "unclassified_ci_failure")
        self.assertEqual(classify_failure("com.android.ddmlib.AdbCommandRejectedException: device offline"),
                         "adb_disconnect")
        self.assertEqual(classify_failure("Received status code 429 from server"), "dependency_rate_limit")
        self.assertEqual(classify_failure("1 tests failed"), "test_failure")
        self.assertEqual(classify_failure(None), "unclassified_ci_failure")

    def test_junit_uses_split_results_without_counting_duplicate_paths(self):
        prefix = "nightprint-e2e-ci/"
        data = io.BytesIO()
        with ZipFile(data, "w") as bundle:
            bundle.writestr(prefix + "native-results/TEST-X.xml",
                            "<testsuite><testcase/><testcase><error/></testcase></testsuite>")
            bundle.writestr(prefix + "ui-results/TEST-Y.xml", "<testsuite><testcase><skipped/></testcase></testsuite>")
            bundle.writestr("app/build/outputs/androidTest-results/TEST-X.xml", "<testsuite><testcase/></testsuite>")
        with ZipFile(data) as bundle:
            self.assertEqual(android_counts(bundle), {"passed": 1, "failed": 1, "skipped": 1})

    def test_summary_records_no_changes(self):
        summary = render_summary({"repository": "test/example", "status": "NO_CHANGES", "prs": {}})
        self.assertIn("変更なし", summary)


def real_evidence_suite():
    # This QA4 artifact was produced by nine real Android 16 emulator tests.
    artifact_id = 15152739202
    artifact = api(f"actions/artifacts/{artifact_id}")
    head = "058899b113acae31aa5cb4e4de5f2a5e359750b6"
    digest = "sha256:0a788f36a0aeec9111c005f34816c9cea148cb33ea804ccab2ce4bb5cf496f63"
    if artifact["digest"] != digest:
        raise ValueError("pinned QA4 artifact digest changed")
    with tempfile.TemporaryDirectory() as directory, archive(artifact, 45894054825, head) as bundle:
        evidence = Path(directory)
        for name in (*FILES, LOG):
            (evidence / name).write_bytes(bundle.read(f"nightprint-e2e-ci/{name}"))
        if validate(evidence) or android_counts(bundle) != {"passed": 9, "failed": 0, "skipped": 0}:
            raise ValueError("QA4 must contain verified audit JSON and nine passing Android tests")
        adb_log = failed_job_log({"id": 140158278755})
        if classify_failure(adb_log) != "adb_disconnect":
            raise ValueError("historical Android CI log did not reproduce the ADB disconnect classification")
        print(f"QA4 archive {artifact_id}: 9 Android tests passed; ADB disconnect reproduced from job 140158278755.",
              flush=True)
        with patch.dict(os.environ, {"NIGHTCORE_REAL_EVIDENCE": directory}):
            suite = unittest.defaultTestLoader.loadTestsFromTestCase(test_operational_loop.OperationalLoopTests)
            result = unittest.TextTestRunner(verbosity=2).run(suite)
    return not result.wasSuccessful()


if __name__ == "__main__":
    if sys.argv[1:] == ["--real-evidence"]:
        raise SystemExit(real_evidence_suite())
    unittest.main()
