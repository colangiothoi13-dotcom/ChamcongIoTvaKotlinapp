"""Real worker/RAM/HTTP boundary; only generated pixel arrays and fake SDK data."""

from pathlib import Path
import tempfile
import threading
import time
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch
from urllib import error, request

import cv2
import numpy as np

from face_attendance.capture_preview import CapturePreviewStore
from face_attendance.enrollment import EnrollmentWorker
from face_attendance.recognition import RecognitionResult
from face_attendance.server import make_server
from face_attendance.source import FrameSourceError
import test_enrollment as fixtures


def synthetic_frame(offset):
    """Asymmetric smooth pixels make flips and mismatched attempts detectable."""
    y, x = np.indices((96, 128))
    image = np.stack(((x + offset) % 256, y + 30, (x // 2 + y + offset) % 256), axis=2)
    return image.astype(np.uint8)


class EnrollmentPreviewIntegrationTests(unittest.TestCase):
    def setUp(self):
        # This fixture replaces Google Firestore with an in-memory SDK boundary.
        # Worker/capture/JPEG encoding/cache and HTTP server all remain real.
        fixtures.StoreTests.setUp(self)
        self.command = self.db.data["faceEnrollmentCommands/req-1"]
        self.command.update(captureMode="MANUAL", captureRequestSeq=0,
                            captureConsumedSeq=0, samplesCaptured=0)
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.path = Path(directory.name)
        self.face = fixtures.LocalFace(self.path / "gallery.json")
        self.face.inspect_quality = Mock(return_value=RecognitionResult(face_count=1, reason="quality_ok"))
        self.source = Mock()
        self.source.read.return_value = SimpleNamespace(image=synthetic_frame(17))
        self.stop = threading.Event()
        self.preview = CapturePreviewStore()
        self.addCleanup(self.preview.close)
        self.worker = EnrollmentWorker(self.store, self.face, self.source, self.path,
                                       self.stop, interval=0, preview=self.preview)
        self.reference = self.db.collection("faceEnrollmentCommands").document("req-1")
        self.gateway = Mock()
        self.admin = "integration-admin-" + "a" * 32
        self.server = make_server(self.gateway, "127.0.0.1", 0, self.admin,
                                  "integration-device-" + "b" * 32, preview=self.preview)
        self.http_thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.http_thread.start()
        self.addCleanup(self.close_server)
        self.url = f"http://127.0.0.1:{self.server.server_port}"
        self.worker_thread = None
        self.errors = []
        self.ack_observations = []
        self.addCleanup(self.stop_worker)
        original_ack = self.store.acknowledge_capture

        def acknowledge(request_id, sequence, saved_count, reason, *extra):
            # Fetching through actual HTTP inside this callback proves publication
            # occurred BEFORE the Firestore consumed sequence/count was updated.
            code, headers, jpeg = self.read_preview(sequence, request_id=request_id)
            self.ack_observations.append({"sequence": sequence, "reason": reason,
                                          "saved": saved_count, "consumed_before": self.command["captureConsumedSeq"],
                                          "code": code, "headers": headers, "jpeg": jpeg})
            original_ack(request_id, sequence, saved_count, reason, *extra)

        acknowledgement = patch.object(self.store, "acknowledge_capture", side_effect=acknowledge)
        acknowledgement.start()
        self.addCleanup(acknowledgement.stop)

    def close_server(self):
        self.server.shutdown()
        self.server.server_close()
        self.http_thread.join(3)
        self.gateway.submit.assert_not_called()
        self.gateway.flush.assert_not_called()

    def stop_worker(self):
        self.stop.set()
        if self.worker_thread:
            self.worker_thread.join(3)
            self.assertFalse(self.worker_thread.is_alive(), "Manual worker must not outlive its test")

    def wait_until(self, predicate):
        deadline = time.monotonic() + 3
        while time.monotonic() < deadline:
            if predicate():
                return
            if self.errors:
                self.fail(f"Worker failed: {self.errors}")
            time.sleep(0.005)
        self.fail("Worker did not reach the expected state")

    def start_worker(self):
        def run():
            try:
                self.worker.run_one("req-1")
            except BaseException as exc:
                self.errors.append(exc)

        self.worker_thread = threading.Thread(target=run)
        self.worker_thread.start()
        self.wait_until(lambda: self.command.get("status") == "CAPTURING"
                        and self.reference.path in self.db.watchers)

    def click(self):
        sequence = self.command["captureRequestSeq"] + 1
        self.reference.update({"captureRequestSeq": sequence})
        self.wait_until(lambda: self.command.get("captureConsumedSeq") == sequence)
        return sequence

    def read_preview(self, sequence, *, request_id="req-1"):
        req = request.Request(f"{self.url}/v1/enrollment-preview/{request_id}/{sequence}",
                              headers={"X-Admin-Token": self.admin})
        try:
            with request.urlopen(req, timeout=2) as response:
                return response.status, response.headers, response.read()
        except error.HTTPError as response:
            return response.code, response.headers, response.read()

    def assert_finished(self, status):
        self.worker_thread.join(3)
        self.assertFalse(self.worker_thread.is_alive())
        self.assertEqual(self.errors, [])
        self.assertEqual(self.command["status"], status)
        self.assertNotIn(self.reference.path, self.db.watchers)
        self.source.close.assert_not_called()

    def assert_same_pixels(self, jpeg, inspected):
        decoded = cv2.imdecode(np.frombuffer(jpeg, dtype=np.uint8), cv2.IMREAD_COLOR)
        self.assertIsNotNone(decoded)
        self.assertEqual(decoded.shape, inspected.shape)
        difference = np.abs(decoded.astype(np.int16) - inspected.astype(np.int16))
        self.assertLess(float(difference.mean()), 2, "Preview must use the inspected frame pixels")
        flipped = np.abs(decoded.astype(np.int16) - inspected[::-1, ::-1].astype(np.int16))
        self.assertGreater(float(flipped.mean()), 20, "Corrected pixels must not rotate a second time")

    def test_rejected_then_accepted_clicks_show_exact_photos_before_ack_and_camera_error_drops_old_image(self):
        rejected, accepted = synthetic_frame(4), synthetic_frame(65)
        self.source.read.side_effect = [SimpleNamespace(image=rejected), SimpleNamespace(image=accepted),
                                        FrameSourceError("Synthetic camera disconnected")]
        self.face.inspect_quality.side_effect = [
            RecognitionResult(face_count=1, reason="low_quality", quality_issue="face_out_of_frame"),
            RecognitionResult(face_count=1, reason="quality_ok"),
        ]
        self.start_worker()
        self.assertEqual(self.read_preview(1)[0], 404)
        self.assertEqual(self.click(), 1)
        self.assertEqual(self.command["samplesCaptured"], 0)
        self.assertIn("Lùi lại", self.command["message"])
        code, headers, jpeg = self.read_preview(1)
        self.assertEqual(code, 200)
        self.assertEqual(headers["X-Capture-Reason"], "low_quality")
        self.assertEqual(headers["X-Capture-Saved"], "0")
        self.assert_same_pixels(jpeg, self.face.inspect_quality.call_args_list[0].args[0])
        self.assertEqual(list((self.path / "photos" / "employee-a").glob("*.jpg")), [])
        self.assertEqual(self.read_preview(1, request_id="other-request")[0], 404)

        self.assertEqual(self.click(), 2)
        self.assertEqual(self.command["samplesCaptured"], 1)
        self.assertEqual(self.read_preview(1)[0], 404, "Only the latest attempt may remain")
        code, headers, jpeg = self.read_preview(2)
        self.assertEqual(code, 200)
        self.assertEqual(headers["X-Capture-Reason"], "quality_ok")
        self.assertEqual(headers["X-Capture-Saved"], "1")
        self.assert_same_pixels(jpeg, self.face.inspect_quality.call_args_list[1].args[0])
        saved = list((self.path / "photos" / "employee-a").glob("*.jpg"))
        self.assertEqual(len(saved), 1)
        self.assertEqual(saved[0].read_bytes(), jpeg, "Accepted sample and preview encode the identical image")
        self.assertEqual([(entry["sequence"], entry["consumed_before"], entry["code"])
                          for entry in self.ack_observations], [(1, 0, 200), (2, 1, 200)])

        self.assertEqual(self.click(), 3)
        self.assertEqual(self.command["samplesCaptured"], 1, "A camera error cannot remove/add an accepted sample")
        self.assertEqual(self.command["captureLastReason"], "camera_error")
        self.assertEqual(self.read_preview(2)[0], 404)
        self.assertEqual(self.read_preview(3)[0], 404)
        self.assertEqual((self.ack_observations[-1]["consumed_before"], self.ack_observations[-1]["code"]), (2, 404))
        self.source.read.assert_has_calls([unittest.mock.call(), unittest.mock.call(), unittest.mock.call()])
        self.assertEqual(self.source.read.call_count, 3)
        self.reference.update({"cancelRequested": True})
        self.assert_finished("CANCELLED")
        self.assertEqual(self.face.profiles["employee-a"]["profileVersion"], 2)
        self.assertIsNone(self.face.inputs)

    def test_cancellation_erases_an_available_accepted_preview_without_enrollment(self):
        self.start_worker()
        self.click()
        self.assertEqual(self.read_preview(1)[0], 200)
        self.reference.update({"cancelRequested": True})
        self.assert_finished("CANCELLED")
        self.assertEqual(self.read_preview(1)[0], 404)
        self.assertIsNone(self.face.inputs)
        self.assertEqual(self.face.profiles["employee-a"]["profileVersion"], 2)

    def test_changed_session_failure_erases_an_available_rejected_preview(self):
        self.face.inspect_quality.return_value = RecognitionResult(face_count=1, reason="low_quality", quality_issue="blurry")
        self.start_worker()
        self.click()
        self.assertEqual(self.read_preview(1)[0], 200)
        self.reference.update({"sessionId": "replacement-session"})
        self.assert_finished("FAILED")
        self.assertEqual(self.command["errorCode"], "COMMAND_CHANGED")
        self.assertEqual(self.read_preview(1)[0], 404)
        self.assertIsNone(self.face.inputs)
        self.assertEqual(self.command["samplesCaptured"], 0)

    def test_completed_enrollment_keeps_last_exact_preview_but_not_previous_attempts(self):
        frames = [synthetic_frame(10 + index * 3) for index in range(10)]
        self.source.read.side_effect = [SimpleNamespace(image=image) for image in frames]
        self.start_worker()
        for index in range(10):
            self.assertEqual(self.click(), index + 1)
            self.assertEqual(self.command["samplesCaptured"], index + 1)
        self.assert_finished("COMPLETED")
        code, headers, jpeg = self.read_preview(10)
        self.assertEqual(code, 200)
        self.assertEqual(headers["X-Capture-Saved"], "10")
        self.assert_same_pixels(jpeg, frames[-1])
        self.assertEqual(self.read_preview(9)[0], 404)
        self.assertEqual(len(self.face.inputs), 10)
        self.assertEqual(self.source.read.call_count, 10)
        self.assertEqual(len(self.ack_observations), 10)
        self.assertTrue(all(entry["code"] == 200 and entry["consumed_before"] == entry["sequence"] - 1
                            for entry in self.ack_observations))

    def test_guided_six_shots_http_preview_reports_actual_total_and_enrolls_six_fresh_files(self):
        self.command.update(sampleCount=6, capturePlan="FRONT_LEFT_RIGHT_V1")
        frames = [synthetic_frame(10 + index * 3) for index in range(6)]
        self.source.read.side_effect = [SimpleNamespace(image=image) for image in frames]
        self.start_worker()
        for index in range(6):
            self.assertEqual(self.click(), index + 1)
            self.assertEqual(self.command["samplesCaptured"], index + 1)
        self.assert_finished("COMPLETED")
        code, headers, jpeg = self.read_preview(6)
        self.assertEqual((code, headers["X-Capture-Saved"], headers["X-Capture-Total"]), (200, "6", "6"))
        self.assert_same_pixels(jpeg, frames[-1])
        self.assertEqual(self.read_preview(5)[0], 404)
        self.assertEqual(len(self.face.inputs), 6)
        self.assertEqual(self.face.profiles["employee-a"]["sampleCount"], 6)
        self.assertTrue(all(entry["headers"]["X-Capture-Total"] == "6" for entry in self.ack_observations))


if __name__ == "__main__":
    unittest.main()
