"""Button-driven enrollment with synthetic frames and an in-memory SDK boundary."""

from pathlib import Path
import tempfile
import threading
import time
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

from face_attendance.enrollment import EnrollmentWorker
from face_attendance.recognition import RecognitionResult
from face_attendance.source import FrameSourceError
import test_enrollment as fixtures


class ManualWorkerTests(unittest.TestCase):
    def setUp(self):
        fixtures.StoreTests.setUp(self)
        self.command = self.db.data["faceEnrollmentCommands/req-1"]
        self.command.update(captureMode="MANUAL", captureRequestSeq=0, captureConsumedSeq=0, samplesCaptured=0)
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.path = Path(directory.name)
        self.face = fixtures.LocalFace(self.path / "gallery.json")
        self.face.inspect_quality = Mock(return_value=RecognitionResult(face_count=1, reason="quality_ok"))
        self.source = Mock()
        self.source.read.return_value = SimpleNamespace(image=object())
        self.stop = threading.Event()
        self.worker = EnrollmentWorker(self.store, self.face, self.source, self.path, self.stop, interval=0)
        self.reference = self.db.collection("faceEnrollmentCommands").document("req-1")
        self.errors = []
        self.encode = patch("face_attendance.capture._encode_jpeg", return_value=b"\xff\xd8synthetic\xff\xd9")
        self.encode.start()
        self.addCleanup(self.encode.stop)
        self.thread = None
        self.addCleanup(self.stop_worker)

    def wait_until(self, predicate):
        deadline = time.monotonic() + 3
        while time.monotonic() < deadline:
            if predicate():
                return
            if self.errors:
                raise AssertionError(self.errors)
            time.sleep(0.005)
        self.fail("Manual enrollment did not reach the expected state")

    def start_worker(self):
        def run():
            try:
                self.worker.run_one("req-1")
            except BaseException as error:
                self.errors.append(error)
        self.thread = threading.Thread(target=run)
        self.thread.start()
        self.wait_until(lambda: "faceEnrollmentCommands/req-1" in self.db.watchers)

    def stop_worker(self):
        self.stop.set()
        if self.thread:
            self.thread.join(3)
            self.assertFalse(self.thread.is_alive(), "Waiting manual worker must stop promptly")

    def click(self):
        number = self.command["captureRequestSeq"] + 1
        self.reference.update({"captureRequestSeq": number})
        self.wait_until(lambda: self.command.get("captureConsumedSeq") == number)
        return number

    def assert_finished(self, status):
        self.thread.join(3)
        self.assertFalse(self.thread.is_alive())
        self.assertEqual(self.errors, [])
        self.assertEqual(self.command["status"], status)
        self.assertNotIn("faceEnrollmentCommands/req-1", self.db.watchers)
        self.source.close.assert_not_called()

    def test_one_click_reads_one_frame_rejection_consumes_click_and_old_snapshot_cannot_replay(self):
        read_threads = []
        self.source.read.side_effect = lambda: read_threads.append(threading.get_ident()) or SimpleNamespace(image=object())
        self.start_worker()
        time.sleep(0.03)
        self.source.read.assert_not_called()
        self.click()
        self.assertEqual((self.command["samplesCaptured"], self.source.read.call_count), (1, 1))
        # Duplicate document notifications must not be interpreted as clicks.
        callback = self.db.watchers[self.reference.path]
        callback([self.reference.get()], [], fixtures.NOW)
        callback([self.reference.get()], [], fixtures.NOW)
        time.sleep(0.03)
        self.assertEqual(self.source.read.call_count, 1)
        self.face.inspect_quality.return_value = RecognitionResult(face_count=2, reason="multiple")
        self.click()
        self.assertEqual((self.command["samplesCaptured"], self.source.read.call_count), (1, 2))
        self.assertIn("Có nhiều người", self.command["message"])
        self.assertEqual(read_threads, [self.thread.ident, self.thread.ident])
        self.reference.update({"cancelRequested": True})
        self.assert_finished("CANCELLED")
        self.assertIsNone(self.face.inputs)
        self.assertEqual(self.face.profiles["employee-a"]["profileVersion"], 2)

    def test_ten_explicit_accepted_clicks_finalize_only_these_fresh_photos(self):
        self.start_worker()
        for number in range(1, 11):
            self.click()
            self.assertEqual(self.command["samplesCaptured"], number)
            self.assertEqual(self.source.read.call_count, number)
            if number < 10:
                self.assertEqual(self.command["status"], "CAPTURING")
                self.assertIsNone(self.face.inputs)
        self.assert_finished("COMPLETED")
        self.assertEqual(len(self.face.inputs), 10)
        self.assertEqual(len(set(self.face.inputs)), 10)
        self.assertEqual(self.face.profiles["employee-a"]["profileVersion"], 6)
        self.assertEqual(self.db.data["faceProfiles/employee-a"]["version"], 6)

    def test_source_error_acknowledges_zero_saved_and_waits_for_another_click(self):
        self.source.read.side_effect = FrameSourceError("test camera offline")
        self.start_worker()
        self.click()
        self.assertEqual(self.command["samplesCaptured"], 0)
        self.assertEqual(self.command["captureLastReason"], "camera_error")
        self.assertIn("nguồn và Wi-Fi", self.command["message"])
        time.sleep(0.03)
        self.assertEqual(self.source.read.call_count, 1)
        self.reference.update({"cancelRequested": True})
        self.assert_finished("CANCELLED")

    def test_clipped_face_guidance_and_exact_preview_keep_zero_until_an_accepted_click(self):
        preview = Mock()
        self.worker.preview = preview
        rejected = object()
        accepted = object()
        self.source.read.side_effect = [SimpleNamespace(image=rejected), SimpleNamespace(image=accepted)]
        self.face.inspect_quality.side_effect = [
            RecognitionResult(face_count=1, reason="low_quality", quality_issue="face_out_of_frame"),
            RecognitionResult(face_count=1, reason="quality_ok"),
        ]
        publications = []
        def publish(request, sequence, image, **metadata):
            publications.append((request, sequence, image, metadata, self.command["captureConsumedSeq"]))
        preview.publish.side_effect = publish
        self.start_worker()
        preview.begin.assert_called_once_with("req-1")
        self.click()
        self.assertEqual((self.command["captureLastReason"], self.command["captureLastQualityIssue"]),
                         ("low_quality", "face_out_of_frame"))
        self.assertIn("Lùi lại", self.command["message"])
        self.assertNotIn("Đến gần", self.command["message"])
        self.assertEqual(self.command["samplesCaptured"], 0)
        self.assertEqual(list((self.path / "photos" / "employee-a").iterdir()), [])
        self.assertEqual(publications[0][0:2], ("req-1", 1))
        self.assertIs(publications[0][2], rejected)
        self.assertEqual(publications[0][3], {"reason": "low_quality", "saved_count": 0, "sample_count": 10})
        self.assertEqual(publications[0][4], 0, "Preview must precede acknowledgement")
        self.click()
        self.assertEqual(self.command["samplesCaptured"], 1)
        self.assertIsNone(self.command["captureLastQualityIssue"])
        self.assertIs(publications[1][2], accepted)
        self.assertEqual(publications[1][3], {"reason": "quality_ok", "saved_count": 1, "sample_count": 10})
        self.reference.update({"cancelRequested": True})
        self.assert_finished("CANCELLED")
        preview.clear.assert_called_once_with("req-1")

    def test_completed_request_retains_last_preview_and_preview_errors_never_stop_capture(self):
        self.worker.preview = Mock()
        self.worker.preview.begin.side_effect = RuntimeError("preview unavailable")
        self.worker.preview.publish.side_effect = RuntimeError("preview unavailable")
        self.start_worker()
        for number in range(1, 11):
            self.click()
        self.assert_finished("COMPLETED")
        self.assertEqual(self.worker.preview.publish.call_count, 10)
        self.worker.preview.clear.assert_not_called()

    def test_blur_click_roundtrip_keeps_zero_samples_reports_measurement_and_replaces_it_on_save(self):
        self.face.inspect_quality.side_effect = [
            RecognitionResult(face_count=1, reason="low_quality", quality_issue="blurry",
                              sharpness_score=12, sharpness_required=50, sharpness_metric="FACE_MAX128_LAPLACIAN_V1"),
            RecognitionResult(face_count=1, reason="quality_ok", sharpness_score=80, sharpness_required=50,
                              sharpness_metric="FACE_MAX128_LAPLACIAN_V1"),
        ]
        self.start_worker()
        self.click()
        self.assertEqual((self.command["samplesCaptured"], self.command["captureConsumedSeq"]), (0, 1))
        self.assertEqual((self.command["captureLastSharpness"], self.command["captureSharpnessRequired"]), (12, 50))
        self.assertEqual(self.command["captureSharpnessMetric"], "FACE_MAX128_LAPLACIAN_V1")
        self.assertIn("Đã thấy mặt", self.command["message"])
        self.assertEqual(list((self.path / "photos" / "employee-a").iterdir()), [])
        self.click()
        self.assertEqual((self.command["samplesCaptured"], self.command["captureConsumedSeq"]), (1, 2))
        self.assertEqual(self.command["captureLastSharpness"], 80)
        self.assertIsNone(self.command["captureLastQualityIssue"])
        self.assertEqual(len(list((self.path / "photos" / "employee-a").iterdir())), 1)
        self.reference.update({"cancelRequested": True})
        self.assert_finished("CANCELLED")
        self.assertIsNone(self.face.inputs)

    def test_source_error_clears_previous_preview_and_cancel_cleanup_errors_are_isolated(self):
        self.worker.preview = Mock()
        self.worker.preview.clear.side_effect = RuntimeError("preview cleanup unavailable")
        self.source.read.side_effect = FrameSourceError("test camera offline")
        self.start_worker()
        self.click()
        self.assertEqual(self.command["samplesCaptured"], 0)
        self.assertEqual(self.command["captureLastReason"], "camera_error")
        self.worker.preview.publish.assert_called_once_with("req-1", 1, None, reason="camera_error", saved_count=0,
                                                           sample_count=10)
        self.worker.preview.clear.assert_not_called()
        self.reference.update({"cancelRequested": True})
        self.assert_finished("CANCELLED")
        self.worker.preview.clear.assert_called_once_with("req-1")

    def test_idle_deadline_uses_no_camera_or_firestore_polling_and_cleans_watch(self):
        self.worker.manual_idle_timeout = 0.05
        with patch.object(self.store, "check_active", side_effect=AssertionError("Manual waiting must not poll")):
            self.start_worker()
            self.assert_finished("FAILED")
        self.assertEqual(self.command["errorCode"], "CAPTURE_IDLE_TIMEOUT")
        self.source.read.assert_not_called()

    def test_stop_event_and_changed_session_interrupt_wait_without_a_frame(self):
        self.start_worker()
        self.stop.set()
        self.assert_finished("CANCELLED")
        self.assertEqual(self.command["errorCode"], "STATION_STOPPED")
        self.source.read.assert_not_called()

    def test_changed_command_session_stops_wait_without_a_frame(self):
        self.start_worker()
        self.reference.update({"sessionId": "replacement-session"})
        self.assert_finished("FAILED")
        self.assertEqual(self.command["errorCode"], "COMMAND_CHANGED")
        self.source.read.assert_not_called()

    def test_employee_retired_after_claim_is_checked_before_camera_access(self):
        self.start_worker()
        self.db.data["employees/employee-a"]["active"] = False
        self.reference.update({"captureRequestSeq": 1})
        self.assert_finished("FAILED")
        self.assertEqual(self.command["errorCode"], "EMPLOYEE_INACTIVE")
        self.source.read.assert_not_called()

    def test_lost_capture_ack_does_not_repeat_frame_or_change_saved_profile(self):
        acknowledge = self.store.acknowledge_capture
        def lost_ack(*args):
            acknowledge(*args)
            raise ConnectionError("committed capture acknowledgement lost")
        with patch.object(self.store, "acknowledge_capture", side_effect=lost_ack):
            self.start_worker()
            self.reference.update({"captureRequestSeq": 1})
            self.assert_finished("FAILED")
        self.assertEqual(self.source.read.call_count, 1)
        self.assertEqual((self.command["captureConsumedSeq"], self.command["samplesCaptured"]), (1, 1))
        self.assertIsNone(self.face.inputs)
        self.assertEqual(self.face.profiles["employee-a"]["profileVersion"], 2)

    def test_guided_six_shots_advance_front_left_right_only_after_two_accepted_samples(self):
        self.command.update(sampleCount=6, capturePlan="FRONT_LEFT_RIGHT_V1")
        self.worker.preview = Mock()
        self.start_worker()
        self.assertIn("Bước 1/3", self.command["message"])
        self.assertIn("Nhìn thẳng", self.command["message"])
        self.click()
        self.assertIn("Chụp ảnh 2/2", self.command["message"])
        self.face.inspect_quality.return_value = RecognitionResult(face_count=1, reason="low_quality",
                                                                   quality_issue="blurry")
        self.click()
        self.assertEqual(self.command["samplesCaptured"], 1)
        self.assertIn("Bước 1/3", self.command["message"])
        self.assertIn("Chụp ảnh 2/2", self.command["message"])
        self.face.inspect_quality.return_value = RecognitionResult(face_count=1, reason="quality_ok")
        self.click()
        self.assertEqual(self.command["samplesCaptured"], 2)
        self.assertIn("trái của nhân viên", self.command["message"])
        self.click()
        self.assertIn("Chụp ảnh 2/2", self.command["message"])
        self.click()
        self.assertEqual(self.command["samplesCaptured"], 4)
        self.assertIn("phải của nhân viên", self.command["message"])
        self.face.inspect_quality.return_value = RecognitionResult(face_count=0, reason="no_face")
        self.click()
        self.assertEqual(self.command["samplesCaptured"], 4)
        self.assertIn("Bước 3/3", self.command["message"])
        self.face.inspect_quality.return_value = RecognitionResult(face_count=1, reason="quality_ok")
        self.click()
        self.click()
        self.assert_finished("COMPLETED")
        self.assertEqual(len(self.face.inputs), 6)
        self.assertEqual(self.source.read.call_count, 8)
        self.assertTrue(all(call.kwargs["sample_count"] == 6 for call in self.worker.preview.publish.call_args_list))
        self.assertEqual(self.face.profiles["employee-a"]["sampleCount"], 6)
        self.assertEqual(self.db.data["faceProfiles/employee-a"]["sampleCount"], 6)

    def test_changed_guided_plan_interrupts_wait_before_camera_access(self):
        self.command.update(sampleCount=6, capturePlan="FRONT_LEFT_RIGHT_V1")
        self.start_worker()
        self.reference.update({"sampleCount": 10, "capturePlan": None})
        self.assert_finished("FAILED")
        self.assertEqual(self.command["errorCode"], "COMMAND_CHANGED")
        self.source.read.assert_not_called()


if __name__ == "__main__":
    unittest.main()
