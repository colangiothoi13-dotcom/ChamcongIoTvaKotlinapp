from email.message import Message
import hashlib
from importlib import metadata
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from face_attendance.diagnostics import run_checks


class SetupChecksTests(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        template = Path(__file__).resolve().parents[1] / "config.example.json"
        self.config = json.loads(template.read_text(encoding="utf-8"))
        self.config["accessories"]["enabled"] = False
        self.config.update(_base=self.root, adminToken="a" * 32, deviceToken="d" * 32)
        self.config["camera"]["token"] = "c" * 32
        (self.root / self.config["registryFile"]).write_text('{"employees":{}}', encoding="utf-8")
        hashes = {}
        for field in ("detectorModel", "recognizerModel"):
            target = self.root / self.config["recognition"][field]
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(field.encode())
            hashes[field] = hashlib.sha256(field.encode()).hexdigest()
        model_patch = patch("face_attendance.diagnostics.MODEL_HASHES", hashes)
        model_patch.start()
        self.addCleanup(model_patch.stop)
        version_patch = patch("face_attendance.diagnostics.metadata.version", return_value="test")
        version_patch.start()
        self.addCleanup(version_patch.stop)

    @staticmethod
    def check(report, name):
        return next(check for check in report["checks"] if check["name"] == name)

    def test_new_setup_warns_no_profiles_without_camera_or_firebase_requests(self):
        self.config.update(mode="firebase", projectId="demo-doctor-test")
        with patch("face_attendance.backend.create_firestore_client", side_effect=AssertionError("Cloud request")), \
                patch("face_attendance.diagnostics.request.build_opener", side_effect=AssertionError("Camera request")):
            report = run_checks(self.config)
        self.assertTrue(report["ok"])
        self.assertFalse(report["readyForRecognition"])
        self.assertIsNone(report["cameraReady"])
        self.assertEqual(self.check(report, "gallery")["status"], "WARN")
        self.assertEqual(self.check(report, "storage")["status"], "OK")
        self.assertEqual(list((self.root / "data").glob(".storage-check-*")), [])
        self.assertEqual(list((self.root / "data").glob("*.sqlite")), [])

    def test_bad_tokens_and_malformed_sections_fail_without_printing_values(self):
        self.config.update(adminToken="private-token\nSECRET", deviceToken="private-token\nSECRET", camera=[])
        self.config["recognition"] = None
        report = run_checks(self.config)
        self.assertFalse(report["ok"])
        self.assertNotIn("SECRET", json.dumps(report))
        self.assertEqual(self.check(report, "gateway_tokens")["status"], "ERROR")
        self.assertEqual(self.check(report, "camera_token")["status"], "ERROR")

    def test_model_corruption_and_missing_dependency_are_actionable(self):
        (self.root / self.config["recognition"]["detectorModel"]).write_bytes(b"corrupt")
        with patch("face_attendance.diagnostics.metadata.version", side_effect=metadata.PackageNotFoundError()):
            report = run_checks(self.config)
        self.assertFalse(report["ok"])
        self.assertEqual(self.check(report, "detectorModel")["status"], "ERROR")
        self.assertEqual(self.check(report, "numpy")["status"], "ERROR")

    def test_failed_camera_check_redacts_network_errors(self):
        with patch("face_attendance.diagnostics.request.build_opener",
                   side_effect=OSError("http://camera/private?token=SECRET")):
            report = run_checks(self.config, include_camera=True)
        self.assertFalse(report["ok"])
        self.assertFalse(report["cameraReady"])
        self.assertNotIn("SECRET", json.dumps(report))

    def test_health_uses_camera_token_only_and_rejects_invalid_state(self):
        headers = Message()
        headers["Content-Type"] = "application/json"

        class Reply:
            def __enter__(self): return self
            def __exit__(self, *_): pass
            def getcode(self): return 200
            def read(self, _): return b'{"cameraReady":true,"wifiConnected":true,"psram":false}'

        Reply.headers = headers
        with patch("face_attendance.diagnostics.request.build_opener") as opener:
            opener.return_value.open.return_value = Reply()
            report = run_checks(self.config, include_camera=True)
            req = opener.return_value.open.call_args.args[0]
        self.assertTrue(report["ok"])
        self.assertTrue(report["cameraReady"])
        self.assertEqual(req.full_url, "http://192.168.1.50/health")
        self.assertEqual(dict(req.header_items())["X-camera-token"], "c" * 32)
        self.assertNotIn("a" * 32, str(req.header_items()))
        self.assertNotIn("d" * 32, str(req.header_items()))
        self.assertEqual(self.check(report, "psram")["status"], "WARN")

    def test_camera_url_with_credentials_is_not_contacted(self):
        self.config["camera"]["url"] = "http://user:SECRET@localhost/capture"
        with patch("face_attendance.diagnostics.request.build_opener", side_effect=AssertionError("Must not connect")):
            report = run_checks(self.config, include_camera=True)
        self.assertFalse(report["ok"])
        self.assertNotIn("SECRET", json.dumps(report))

    def test_enabled_accessory_gate_is_checked_and_missing_models_fail_readiness(self):
        self.config["accessories"]["enabled"] = True
        report = run_checks(self.config)
        self.assertFalse(report["ok"])
        self.assertFalse(report["readyForRecognition"])
        self.assertEqual(self.check(report, "accessories")["status"], "ERROR")

    def test_successful_accessory_warmup_is_reported_without_real_images(self):
        self.config["accessories"]["enabled"] = True
        with patch("face_attendance.accessory_setup.create_accessory_guard") as factory:
            report = run_checks(self.config)
        factory.return_value.check_runtime.assert_called_once_with()
        self.assertTrue(report["ok"])
        self.assertEqual(self.check(report, "accessories")["status"], "OK")


if __name__ == "__main__":
    unittest.main()
