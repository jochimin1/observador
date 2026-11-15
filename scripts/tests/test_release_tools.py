"""Release gates must reject false positives, especially adb's exit-zero failures."""
import sys
from pathlib import Path
import unittest
import json
import tempfile
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from release_tools import app_identity, certificate_hashes, check_update, instrumentation_count, parse_badging
import test_emulator
import validate_release


class ReleaseToolsTest(unittest.TestCase):
    def test_identity(self):
        self.assertEqual(("1.52.0", 63), app_identity('versionName = "1.52.0"\nversionCode = 63'))
        with self.assertRaises(ValueError):
            app_identity('versionCode = 63')

    def test_debug_and_release_badging(self):
        base = "package: name='com.ubers.observador' versionCode='63' versionName='1.52.0' platformBuildVersionName='16'\n"
        self.assertFalse(parse_badging(base)["debuggable"])
        self.assertTrue(parse_badging(base + "application-debuggable\n")["debuggable"])
        with self.assertRaises(ValueError):
            parse_badging("ERROR: invalid APK")

    def test_certificates(self):
        digest = "ab" * 32
        self.assertEqual([digest], certificate_hashes("Signer #1 certificate SHA-256 digest: " + digest.upper()))
        with self.assertRaises(ValueError):
            certificate_hashes("DOES NOT VERIFY")

    def test_instrumentation_apk_may_have_no_version(self):
        test = parse_badging("package: name='com.ubers.observador.test' versionCode='' versionName=''")
        self.assertEqual(0, test["versionCode"])
        self.assertEqual("", test["versionName"])

    def test_update_rejects_other_package_code_or_key(self):
        old = dict(package="com.ubers.observador", versionCode=62, certificatesSha256=["a"])
        new = dict(old, versionCode=63)
        check_update(old, new)
        for changes in ({"package": "other"}, {"versionCode": 62}, {"versionCode": 61}, {"certificatesSha256": ["b"]}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                check_update(old, dict(new, **changes))

    def test_instrumentation_success(self):
        self.assertEqual(20, instrumentation_count("Time: 1.5\n\nOK (20 tests)\n\nINSTRUMENTATION_CODE: -1"))
        self.assertEqual(1, instrumentation_count("OK (1 test)"))

    def test_exit_zero_does_not_hide_failures_or_empty_run(self):
        for output in ("", "OK (0 tests)", "FAILURES!!!\nTests run: 2, Failures: 1",
                       "INSTRUMENTATION_FAILED: runner", "shortMsg=Process crashed",
                       "OK (2 tests)\nINSTRUMENTATION_STATUS_CODE: -2"):
            with self.subTest(output=output), self.assertRaises(ValueError):
                instrumentation_count(output)

    def test_failed_host_rerun_cannot_reuse_a_previous_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            result = Path(directory) / "results.json"
            result.write_text('{"status":"PASSED"}', encoding="utf-8")
            with patch.object(sys, "argv", ["validate_release.py", "--output", directory]), \
                    patch.object(validate_release, "source_checks", side_effect=ValueError("invalid source")):
                with self.assertRaises(ValueError):
                    validate_release.main()
            self.assertNotEqual("PASSED", json.loads(result.read_text())["status"])

    def test_failed_emulator_rerun_cannot_reuse_a_previous_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            result = Path(directory) / "results.json"
            result.write_text('{"status":"PASSED"}', encoding="utf-8")
            with patch.object(sys, "argv", ["test_emulator.py", "--serial", "emulator-1234", "--output", directory]), \
                    patch.object(test_emulator, "sdk_tool", return_value="unused-adb"), \
                    patch.object(test_emulator, "run", return_value="1"), \
                    patch.object(test_emulator, "inspect_apk", side_effect=ValueError("invalid apk")):
                with self.assertRaises(ValueError):
                    test_emulator.main()
            self.assertNotEqual("PASSED", json.loads(result.read_text())["status"])


if __name__ == "__main__":
    unittest.main()
