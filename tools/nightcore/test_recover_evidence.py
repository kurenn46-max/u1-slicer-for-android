import json
import tempfile
import unittest
from pathlib import Path

from recover_evidence import run


class RecoveryTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.inputs = self.root / "input"
        self.inputs.mkdir()
        self.output = self.root / "output"
        self.stl = self.inputs / "raw-stl-native-evidence.json"
        self.log = self.inputs / "native-android-logcat.txt"
        self.stl.write_text('{"test":"PASS","final_audit_pass":true,"gcode_bytes":10}')
        self.log.write_text(
            'I/NIGHTPRINT_3MF_E2E( 10): {"result":"PASS","audit_passed":true,"gcode_bytes":20}\n'
        )

    def recover(self, exit_code=0):
        result = run(self.inputs, self.output, "a" * 40, "fixture://android", exit_code, "b" * 40)
        return result, json.loads((self.output / "checkpoint.json").read_text())

    def test_missing_report_is_recovered_and_revalidated(self):
        code, state = self.recover()
        self.assertEqual(code, 0)
        self.assertEqual(state["attempts"][0]["exit"], 0)
        self.assertTrue(state["attempts"][0]["existing_reports_unchanged"])
        self.assertFalse(state["release_authorized"])
        self.assertFalse((self.inputs / "embedded-3mf-native-evidence.json").exists())
        with self.assertRaises(FileExistsError):
            self.recover()

    def test_instrumentation_failure_prevents_recovery(self):
        code, state = self.recover(42)
        self.assertEqual(code, 1)
        self.assertEqual(state["attempts"], [])
        self.assertEqual(state["verification"], "UNVERIFIED")

    def test_invalid_existing_report_is_preserved(self):
        self.stl.write_text("{broken")
        code, state = self.recover()
        self.assertEqual(code, 1)
        self.assertEqual(state["attempts"], [])
        self.assertEqual((self.output / "evidence" / self.stl.name).read_text(), "{broken")

    def test_conflicting_recovery_has_one_failed_attempt(self):
        with self.log.open("a") as stream:
            stream.write('I/NIGHTPRINT_3MF_E2E( 11): {"result":"PASS","gcode_bytes":25}\n')
        code, state = self.recover()
        self.assertEqual(code, 1)
        self.assertEqual([attempt["exit"] for attempt in state["attempts"]], [1])
        self.assertIn("conflicting reports", state["error"])
