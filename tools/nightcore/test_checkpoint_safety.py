"""Safety regression tests for the existing NIGHT CORE checkpoint classifier.

Run from repository root:
    python3 -m unittest discover -s tools/nightcore -p 'test_checkpoint_safety.py' -v
No external dependencies, credentials, network, or deployment.
"""
import unittest
from checkpoint import classify

SHA = "a" * 40


def checkpoint(*attempts, marker=""):
    return "commit=" + SHA + "\n" + "".join(
        "attempt=%s exit=%s\n" % pair for pair in attempts
    ) + marker


class CheckpointSafetyTests(unittest.TestCase):
    def test_success_is_not_release_approval(self):
        self.assertEqual(
            classify(checkpoint((1, 0), marker="JVM_PASS_ONLY;")),
            "JVM_GATE_ONLY",
        )

    def test_success_without_explicit_gate_is_invalid(self):
        self.assertEqual(classify(checkpoint((1, 0))), "INVALID_CHECKPOINT")

    def test_failure_then_success(self):
        self.assertEqual(
            classify(checkpoint((1, 1), (2, 0), marker="JVM_PASS_ONLY;")),
            "JVM_GATE_ONLY",
        )

    def test_success_followed_by_more_attempts_is_invalid(self):
        self.assertEqual(
            classify(checkpoint((1, 0), (2, 1), marker="DETERMINISTIC_FAILURE;")),
            "INVALID_CHECKPOINT",
        )

    def test_more_than_three_attempts_is_invalid(self):
        self.assertEqual(
            classify(checkpoint((1, 1), (2, 1), (3, 1), (4, 1))),
            "INVALID_CHECKPOINT",
        )

    def test_skipped_attempt_number_is_invalid(self):
        self.assertEqual(
            classify(checkpoint((1, 1), (3, 1))),
            "INVALID_CHECKPOINT",
        )

    def test_duplicate_commit_is_invalid(self):
        self.assertEqual(
            classify(checkpoint((1, 0), marker="JVM_PASS_ONLY;\ncommit=" + SHA)),
            "INVALID_CHECKPOINT",
        )

    def test_timeout_requires_budgeted_resume(self):
        self.assertEqual(
            classify(checkpoint((1, 124))),
            "RESUME_WITH_BUDGET",
        )

    def test_deterministic_failure_proposes_patch_not_deployment(self):
        self.assertEqual(
            classify(checkpoint((1, 1), marker="DETERMINISTIC_FAILURE;")),
            "PROPOSE_PATCH",
        )

    def test_unclassified_failure_requires_investigation(self):
        self.assertEqual(classify(checkpoint((1, 2))), "INVESTIGATE")


if __name__ == "__main__":
    unittest.main()
