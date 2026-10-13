from datetime import datetime, timezone
import sqlite3
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

from face_attendance.__main__ import poll, capture, build_gateway
from face_attendance.events import Event
from pathlib import Path
import tempfile
from face_attendance.recognition import RecognitionResult


class PollStorageFailureTests(unittest.TestCase):
    def test_camera_poll_passes_mounted_orientation_to_the_live_source(self):
        config = {
            "mode": "demo", "faceDeviceId": "CAM-01", "stationId": "GATE-01",
            "camera": {"url": "http://camera.invalid/capture", "token": "test-token", "rotation": 180},
            "recognition": {"stableFrames": 1, "emptyFrames": 1, "pollSeconds": 0.1},
        }
        source = Mock()
        source.read.side_effect = KeyboardInterrupt()
        with (
            patch("face_attendance.__main__.build_gateway", return_value=Mock()),
            patch("face_attendance.__main__.recognizer", return_value=Mock()),
            patch("face_attendance.source.UrlFrameSource", return_value=source) as create_source,
            patch("face_attendance.__main__.emit"),
        ):
            poll(config, webcam=None)
        create_source.assert_called_once_with("http://camera.invalid/capture", "test-token", rotation=180)
        source.close.assert_called_once()

    def test_camera_capture_passes_rotation_and_does_not_change_webcam_source(self):
        config = {"_base": Path("."), "dataDir": "unused",
                  "camera": {"url": "http://camera.invalid/capture", "token": "test-token", "rotation": 270}}
        summary = Mock(files=("synthetic.jpg",))
        for webcam in (None, 0):
            with (
                self.subTest(webcam=webcam),
                patch("face_attendance.__main__.recognizer", return_value=Mock()),
                patch("face_attendance.source.UrlFrameSource", return_value=Mock()) as create_camera,
                patch("face_attendance.source.WebcamFrameSource", return_value=Mock()) as create_webcam,
                patch("face_attendance.capture.capture_samples", return_value=summary),
                patch("face_attendance.__main__.emit"),
            ):
                capture(config, "test-employee", count=1, interval=0, webcam=webcam)
            if webcam is None:
                create_camera.assert_called_once_with("http://camera.invalid/capture", "test-token", rotation=270)
                create_webcam.assert_not_called()
            else:
                create_camera.assert_not_called()
                create_webcam.assert_called_once_with(0)

    def test_failed_local_write_retries_identical_face_event_before_reading_more_frames(self):
        gateway = Mock()
        gateway.submit.side_effect = [
            sqlite3.OperationalError("simulated full database"),
            {"status": "ACCEPTED_DEMO"},
        ]
        gateway.flush.return_value = []
        timestamp = datetime.now(timezone.utc).isoformat()
        source = Mock()
        source.read.side_effect = [
            SimpleNamespace(image=object(), captured_at=timestamp, sequence=str(index))
            for index in range(6)
        ] + [KeyboardInterrupt()]
        face = Mock()
        face.recognize.side_effect = [RecognitionResult(reason="no_face")] * 3 + [
            RecognitionResult("a", 0.85, 1, 1, "matched")
        ] * 3
        config = {
            "mode": "demo", "faceDeviceId": "CAM-01", "stationId": "GATE-01",
            "recognition": {"stableFrames": 3, "emptyFrames": 3, "pollSeconds": 0.1},
        }
        with (
            patch("face_attendance.__main__.build_gateway", return_value=gateway),
            patch("face_attendance.__main__.recognizer", return_value=face),
            patch("face_attendance.source.WebcamFrameSource", return_value=source),
            patch("face_attendance.__main__.emit") as output,
            patch("face_attendance.__main__.time.sleep"),
        ):
            poll(config, webcam=0)
        self.assertEqual(gateway.submit.call_count, 2)
        first_event = gateway.submit.call_args_list[0].args[0]
        retry_event = gateway.submit.call_args_list[1].args[0]
        self.assertIs(first_event, retry_event)
        self.assertEqual(first_event.payload["capturedAt"], timestamp)
        self.assertEqual(first_event.payload["employeeId"], "a")
        statuses = [call.args[0].get("status") for call in output.call_args_list]
        self.assertIn("NOT_STORED", statuses)
        self.assertIn("ACCEPTED_DEMO", statuses)
        source.close.assert_called_once()

    def test_full_outbox_flushes_before_retrying_current_face_visit(self):
        gateway = Mock()
        has_capacity = {"available": False}

        def submit(source_event):
            if not has_capacity["available"]:
                raise OSError("Outbox is full")
            return {"status": "ACCEPTED_DEMO"}

        def flush():
            # Earlier empty-frame iterations do not free capacity until there
            # is actually a recognized event waiting to be saved.
            if gateway.submit.call_count:
                has_capacity["available"] = True
            return []

        gateway.submit.side_effect = submit
        gateway.flush.side_effect = flush
        source = Mock()
        timestamp = datetime.now(timezone.utc).isoformat()
        source.read.side_effect = [
            SimpleNamespace(image=object(), captured_at=timestamp, sequence=str(index))
            for index in range(6)
        ] + [KeyboardInterrupt()]
        face = Mock()
        face.recognize.side_effect = [RecognitionResult(reason="no_face")] * 3 + [
            RecognitionResult("a", 0.85, 1, 1, "matched")
        ] * 3
        config = {
            "mode": "demo", "faceDeviceId": "CAM-01", "stationId": "GATE-01",
            "recognition": {"stableFrames": 3, "emptyFrames": 3, "pollSeconds": 0.1},
        }
        with (
            patch("face_attendance.__main__.build_gateway", return_value=gateway),
            patch("face_attendance.__main__.recognizer", return_value=face),
            patch("face_attendance.source.WebcamFrameSource", return_value=source),
            patch("face_attendance.__main__.emit"),
            patch("face_attendance.__main__.time.sleep"),
        ):
            poll(config, webcam=0)
        self.assertEqual(gateway.submit.call_count, 2)
        self.assertIs(gateway.submit.call_args_list[0].args[0], gateway.submit.call_args_list[1].args[0])
        source.close.assert_called_once()


class GatewayNamespaceTests(unittest.TestCase):
    def test_pending_event_does_not_cross_projects_stations_or_fingerprint_devices(self):
        with tempfile.TemporaryDirectory() as directory:
            config = {
                "_base": Path(directory), "dataDir": "data", "mode": "firebase",
                "projectId": "demo-project-a", "stationId": "GATE-01",
                "fingerprintDeviceId": "GATE-01", "recognition": {"threshold": 0.5},
            }
            source_event = Event.parse({
                "sourceEventId": "pending-original", "deviceId": "CAM-01", "stationId": "GATE-01",
                "capturedAt": datetime.now(timezone.utc).isoformat(), "employeeId": "a",
                "profileVersion": 1, "faceSimilarity": 0.85,
            }, "FACE")
            with patch("face_attendance.__main__.create_firestore_client", return_value=Mock()):
                original = build_gateway(config)
                original.outbox.put(source_event)
                restored = build_gateway(config)
                self.assertEqual(restored.outbox.pending_count(), 1)
                for change in (
                    {"projectId": "demo-project-b"}, {"stationId": "GATE-02"},
                    {"fingerprintDeviceId": "FINGER-02"},
                ):
                    alternate = build_gateway(dict(config, **change))
                    alternate.backend.deliver = Mock()
                    self.assertNotEqual(alternate.outbox.path, original.outbox.path)
                    self.assertEqual(alternate.outbox.pending_count(), 0)
                    self.assertEqual(alternate.flush(), [])
                    alternate.backend.deliver.assert_not_called()
            self.assertEqual(original.outbox.pending_count(), 1)
            self.assertEqual(original.outbox.pending()[0]["payload"], source_event.json)


if __name__ == "__main__":
    unittest.main()
