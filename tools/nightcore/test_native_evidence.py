"""Offline regression tests for the NIGHT PRINT native evidence gate."""
import json
import pathlib
import tempfile
import unittest

from verify_native_evidence import validate


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

    def test_symlink_fails(self):
        file = self.path / "raw-stl-native-evidence.json"
        file.unlink()
        file.symlink_to(self.path / "embedded-3mf-native-evidence.json")
        self.assertTrue(validate(self.path))


if __name__ == "__main__":
    unittest.main()
