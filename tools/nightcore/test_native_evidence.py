"""Offline regression tests for the NIGHT PRINT native evidence gate."""
import json
import pathlib
import tempfile
import unittest

from verify_native_evidence import validate, recover_from_logcat


class NativeEvidenceTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.path = pathlib.Path(self.tmp.name)
        self.write("raw-stl-native-evidence.json", {
            "test": "PASS", "final_audit_pass": True, "gcode_bytes": 200
        })
        self.write("embedded-3mf-native-evidence.json", {
            "result": "PASS", "audit_passed": True, "gcode_bytes": 400
        })

    def write(self, name, data):
        (self.path / name).write_text(json.dumps(data), encoding="utf-8")

    def test_both_pass(self):
        self.assertEqual(validate(self.path), [])

    def test_missing_3mf_fails(self):
        (self.path / "embedded-3mf-native-evidence.json").unlink()
        self.assertTrue(validate(self.path))

    def test_empty_gcode_fails(self):
        self.write("raw-stl-native-evidence.json", {
            "test": "PASS", "final_audit_pass": True, "gcode_bytes": 0
        })
        self.assertTrue(validate(self.path))

    def test_boolean_gcode_bytes_fails(self):
        self.write("raw-stl-native-evidence.json", {
            "test": "PASS", "final_audit_pass": True, "gcode_bytes": True
        })
        self.assertTrue(validate(self.path))

    def test_false_audit_fails(self):
        self.write("embedded-3mf-native-evidence.json", {
            "result": "PASS", "audit_passed": False, "gcode_bytes": 400
        })
        self.assertTrue(validate(self.path))

    def test_invalid_json_fails(self):
        (self.path / "raw-stl-native-evidence.json").write_text("{broken")
        self.assertTrue(validate(self.path))

    def test_recover_complete_logcat_json(self):
        (self.path / "raw-stl-native-evidence.json").unlink()
        (self.path / "embedded-3mf-native-evidence.json").unlink()
        log = (
            '11-26 I NIGHTPRINT_E2E: {"test":"PASS","final_audit_pass":true,"gcode_bytes":200}\\n'
            '11-26 I NIGHTPRINT_3MF_E2E: {"result":"PASS","audit_passed":true,"gcode_bytes":400}\\n'
        )
        (self.path / "native-android-logcat.txt").write_text(log)
        recover_from_logcat(self.path)
        self.assertEqual(validate(self.path), [])

    def test_incomplete_logcat_does_not_fake_success(self):
        (self.path / "raw-stl-native-evidence.json").unlink()
        (self.path / "native-android-logcat.txt").write_text(
            'I NIGHTPRINT_E2E: {"test":"PASS","final_audit_pass":true'
        )
        recover_from_logcat(self.path)
        self.assertTrue(validate(self.path))

    def test_symlink_fails(self):
        file = self.path / "raw-stl-native-evidence.json"
        file.unlink()
        file.symlink_to(self.path / "embedded-3mf-native-evidence.json")
        self.assertTrue(validate(self.path))


if __name__ == "__main__":
    unittest.main()
