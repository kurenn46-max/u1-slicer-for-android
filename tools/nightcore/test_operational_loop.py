import json
import os
import shutil
import tempfile
import unittest
from unittest.mock import patch
from pathlib import Path

from operational_loop import cycle
from recover_evidence import LOG
from verify_native_evidence import FILES


class OperationalLoopTests(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        self.evidence = self.root / "evidence"
        self.evidence.mkdir()
        archive = os.getenv("NIGHTCORE_REAL_EVIDENCE")
        if archive:
            for name in (*FILES, LOG):
                shutil.copyfile(Path(archive) / name, self.evidence / name)
        else:
            lines = []
            for (filename, (status, audit)), tag in zip(
                FILES.items(), ("NIGHTPRINT_E2E", "NIGHTPRINT_3MF_E2E")
            ):
                payload = json.dumps({status: "PASS", audit: True, "gcode_bytes": 1001})
                (self.evidence / filename).write_text(payload)
                lines.append(f"I/{tag}( 42): {payload}")
            (self.evidence / LOG).write_text("\n".join(lines))
        self.observation = {
            "head_sha": "a" * 40, "checkout_sha": "b" * 40, "run_id": 12,
            "instrumentation": "success", "artifact_url": "fixture://android",
        }

    def execute(self, name="first", previous=None):
        return cycle(self.observation, self.evidence, self.root / name, previous, "c" * 40)

    def remove_one(self):
        (self.evidence / next(iter(FILES))).unlink()

    def test_a_missing_is_repaired(self):
        filename = next(iter(FILES))
        expected = json.loads((self.evidence / filename).read_text())
        self.remove_one()
        state = self.execute()
        self.assertEqual((state["status"], state["attempts"]), ("PASS", 1))
        restored = self.root / "first" / "recovery" / "evidence" / filename
        self.assertEqual(json.loads(restored.read_text()), expected)
        self.assertFalse(state["release_authorized"])

    def test_b_corrupt_is_held(self):
        (self.evidence / next(iter(FILES))).write_text("{broken")
        state = self.execute()
        self.assertEqual((state["status"], state["attempts"]), ("HOLD", 0))

    def test_c_android_failure_is_held(self):
        self.observation["instrumentation"] = "failure"
        self.assertEqual(self.execute()["status"], "HOLD")
        self.assertFalse((self.root / "first" / "recovery").exists())

    def test_d_same_fault_is_not_retried_on_new_run(self):
        self.remove_one()
        (self.evidence / LOG).write_text("")
        previous = self.execute()
        self.assertEqual(previous["status"], "FAIL")
        self.observation["run_id"] += 1
        state = self.execute("second", previous)
        self.assertEqual((state["status"], state["attempts"]), ("SKIPPED", 0))
        self.assertEqual(state["prior_result"]["status"], "FAIL")

    def test_e_resume_from_disk_detects_new_commit(self):
        self.execute()
        previous = json.loads((self.root / "first" / "phase2.json").read_text())
        self.remove_one()
        self.observation["head_sha"] = "d" * 40
        result = self.execute("second", previous)
        self.assertIn("head_sha", result["changed_fields"])
        self.assertEqual(result["previous_observation"], previous["observation"])
        self.assertEqual((result["status"], result["attempts"]), ("PASS", 1))

    def test_interrupted_repair_does_not_restart(self):
        self.remove_one()
        with patch("operational_loop.run", side_effect=KeyboardInterrupt):
            with self.assertRaises(KeyboardInterrupt):
                self.execute()
        previous = json.loads((self.root / "first" / "phase2.json").read_text())
        result = self.execute("resumed", previous)
        self.assertEqual(result["status"], "SKIPPED")
        self.assertEqual(result["prior_result"]["status"], "INTERRUPTED")
