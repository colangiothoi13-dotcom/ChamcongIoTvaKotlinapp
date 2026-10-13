from datetime import datetime, timezone
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

from face_attendance.__main__ import capture
from face_attendance.capture import capture_samples, _write_exclusive_jpeg
from face_attendance.recognition import RecognitionResult
from face_attendance.source import FrameSourceError


FRAME = SimpleNamespace(image=object(), captured_at="2026-10-10T01:00:00+00:00", sequence="fresh-frame")
QUALITY_OK = RecognitionResult(face_count=1, reason="quality_ok")
JPEG = b"\xff\xd8fake encoded test image\xff\xd9"


class CaptureTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name)
        self.source = Mock()
        self.source.is_live = True
        self.face = Mock()

    def test_source_errors_multi_face_and_blur_are_skipped_before_saving(self):
        self.source.read.side_effect = [FrameSourceError("camera disconnected"), FRAME, FRAME, FRAME]
        self.face.inspect_quality.side_effect = [
            RecognitionResult(face_count=2, reason="multiple"),
            RecognitionResult(face_count=1, reason="low_quality"), QUALITY_OK,
        ]
        events = []
        with patch("face_attendance.capture._encode_jpeg", return_value=JPEG) as encoder:
            result = capture_samples(
                self.source, self.face, self.path, "NV001", count=1,
                interval=0, max_attempts=4, progress=events.append,
            )
        self.assertEqual(result.attempts, 4)
        self.assertEqual(len(result.files), 1)
        self.assertEqual(Path(result.files[0]).read_bytes(), JPEG)
        self.assertEqual(Path(result.files[0]).parent, self.path / "NV001")
        self.assertEqual([event["status"] for event in events], [
            "CAPTURE_SOURCE_ERROR", "CAPTURE_SKIP", "CAPTURE_SKIP", "CAPTURE_SAVED",
        ])
        encoder.assert_called_once_with(FRAME.image)
        self.face.recognize.assert_not_called()
        self.source.close.assert_called_once()

    def test_attempt_limit_returns_partial_and_closes_source(self):
        self.source.read.side_effect = FrameSourceError("no fresh frame")
        result = capture_samples(self.source, self.face, self.path, "NV001", count=2, interval=0, max_attempts=3)
        self.assertEqual(result.to_dict()["status"], "CAPTURE_PARTIAL")
        self.assertEqual(result.attempts, 3)
        self.assertEqual(result.files, ())
        self.source.close.assert_called_once()

    def test_manual_callback_gates_every_frame_and_counts_rejected_attempts(self):
        order = []
        self.source.read.side_effect = lambda: order.append("read") or FRAME
        self.face.inspect_quality.side_effect = [
            RecognitionResult(reason="no_face"), QUALITY_OK,
        ]
        events = []
        with patch("face_attendance.capture._encode_jpeg", return_value=JPEG):
            result = capture_samples(self.source, self.face, self.path, "NV001", count=1,
                                     interval=0, max_attempts=2, progress=events.append,
                                     before_attempt=lambda: order.append("click"))
        self.assertEqual(order, ["click", "read", "click", "read"])
        self.assertEqual(result.attempts, 2)
        self.assertEqual([value["saved"] for value in events], [0, 1])

    def test_manual_wait_cancel_or_control_c_never_reads_camera(self):
        self.face.inspect_quality.return_value = QUALITY_OK
        for exception in (RuntimeError("cancelled"), KeyboardInterrupt()):
            with self.subTest(exception=exception):
                trigger = Mock(side_effect=exception)
                if isinstance(exception, KeyboardInterrupt):
                    result = capture_samples(self.source, self.face, self.path, "NV001", count=1,
                                             before_attempt=trigger)
                    self.assertTrue(result.stopped)
                    self.assertEqual(result.attempts, 0)
                else:
                    with self.assertRaisesRegex(RuntimeError, "cancelled"):
                        capture_samples(self.source, self.face, self.path, "NV001", count=1,
                                        before_attempt=trigger)
        self.source.read.assert_not_called()

    def test_attempt_preview_uses_exact_quality_checked_frame_before_progress(self):
        second = SimpleNamespace(image=object())
        self.source.read.side_effect = [FRAME, second]
        self.face.inspect_quality.side_effect = [
            RecognitionResult(face_count=1, reason="low_quality", quality_issue="face_out_of_frame"), QUALITY_OK,
        ]
        events = []
        order = []
        def preview(image, value):
            order.append("preview")
            events.append((image, value))
            self.assertNotIn("path", value)
        def progress(value):
            order.append("ack")
        with patch("face_attendance.capture._encode_jpeg", return_value=JPEG):
            result = capture_samples(self.source, self.face, self.path, "NV001", count=1,
                                     max_attempts=2, interval=0, on_attempt=preview, progress=progress)
        self.assertEqual(order, ["preview", "ack", "preview", "ack"])
        self.assertIs(events[0][0], FRAME.image)
        self.assertIs(events[1][0], second.image)
        self.assertEqual(events[0][1]["qualityIssue"], "face_out_of_frame")
        self.assertEqual([value["saved"] for _, value in events], [0, 1])
        self.assertEqual(len(result.files), 1)
        self.assertEqual(self.source.read.call_count, 2)

    def test_camera_error_preview_has_no_frame_and_preview_failure_does_not_block_ack(self):
        self.source.read.side_effect = [FrameSourceError("offline"), FRAME]
        self.face.inspect_quality.return_value = QUALITY_OK
        events = []
        preview = Mock(side_effect=RuntimeError("preview encoder unavailable"))
        with patch("face_attendance.capture._encode_jpeg", return_value=JPEG):
            result = capture_samples(self.source, self.face, self.path, "NV001", count=1,
                                     max_attempts=2, interval=0, on_attempt=preview, progress=events.append)
        self.assertIsNone(preview.call_args_list[0].args[0])
        self.assertIs(preview.call_args_list[1].args[0], FRAME.image)
        self.assertEqual([value["status"] for value in events], ["CAPTURE_SOURCE_ERROR", "CAPTURE_SAVED"])
        self.assertEqual(len(result.files), 1)

    def test_exact_attempt_and_progress_include_raw_sharpness_for_reject_and_save(self):
        self.source.read.side_effect = [FRAME, FRAME]
        self.face.inspect_quality.side_effect = [
            RecognitionResult(face_count=1, reason="low_quality", quality_issue="blurry",
                              sharpness_score=0, sharpness_required=50, sharpness_metric="FACE_MAX128_LAPLACIAN_V1"),
            RecognitionResult(face_count=1, reason="quality_ok", sharpness_score=80.5, sharpness_required=50,
                              sharpness_metric="FACE_MAX128_LAPLACIAN_V1"),
        ]
        progress, previews = [], []
        with patch("face_attendance.capture._encode_jpeg", return_value=JPEG) as encoder:
            result = capture_samples(self.source, self.face, self.path, "NV001", count=1, interval=0,
                                     max_attempts=2, progress=progress.append,
                                     on_attempt=lambda image, value: previews.append(value))
        self.assertEqual(len(result.files), 1)
        encoder.assert_called_once()
        for events in (progress, previews):
            self.assertEqual([event["sharpnessScore"] for event in events], [0, 80.5])
            self.assertEqual([event["sharpnessRequired"] for event in events], [50, 50])
            self.assertEqual([event["sharpnessMetric"] for event in events], ["FACE_MAX128_LAPLACIAN_V1"] * 2)
            self.assertEqual([event["saved"] for event in events], [0, 1])

    def test_control_c_returns_saved_paths_and_closes_source(self):
        self.source.read.side_effect = [FRAME, KeyboardInterrupt()]
        self.face.inspect_quality.return_value = QUALITY_OK
        with patch("face_attendance.capture._encode_jpeg", return_value=JPEG):
            result = capture_samples(self.source, self.face, self.path, "NV001", count=2, interval=0)
        self.assertTrue(result.stopped)
        self.assertEqual(len(result.files), 1)
        self.assertEqual(Path(result.files[0]).read_bytes(), JPEG)
        self.source.close.assert_called_once()

    def test_path_traversal_windows_devices_and_nonlive_source_are_rejected(self):
        for employee_id in ("../employee", "..", ".", "CON", "NUL.jpg", "a:b", "trailing."):
            with self.assertRaises(ValueError):
                capture_samples(self.source, self.face, self.path, employee_id)
        self.source.read.assert_not_called()
        self.assertEqual(list(self.path.iterdir()), [])
        self.source.is_live = False
        with self.assertRaises(ValueError):
            capture_samples(self.source, self.face, self.path, "NV001")

    def test_colliding_filename_does_not_overwrite_photo_or_leave_temp_file(self):
        fixed_time = datetime(2026, 10, 10, 1, tzinfo=timezone.utc)
        stamp = fixed_time.strftime("%Y%m%dT%H%M%S_%fZ")
        target = self.path / f"{stamp}-existing.jpg"
        target.write_bytes(b"existing registration photo")
        with (
            patch("face_attendance.capture.datetime") as clock,
            patch("face_attendance.capture.uuid.uuid4", return_value=SimpleNamespace(hex="existing")),
        ):
            clock.now.return_value = fixed_time
            with self.assertRaises(FileExistsError):
                _write_exclusive_jpeg(self.path, JPEG)
        self.assertEqual(target.read_bytes(), b"existing registration photo")
        self.assertEqual(list(self.path.iterdir()), [target])

    def test_write_failure_closes_source_and_does_not_report_saved_file(self):
        self.source.read.return_value = FRAME
        self.face.inspect_quality.return_value = QUALITY_OK
        events = []
        with (
            patch("face_attendance.capture._encode_jpeg", return_value=JPEG),
            patch("face_attendance.capture.os.link", side_effect=OSError("storage unavailable")),
        ):
            with self.assertRaises(OSError):
                capture_samples(self.source, self.face, self.path, "NV001", count=1, progress=events.append)
        self.source.close.assert_called_once()
        self.assertEqual(list((self.path / "NV001").iterdir()), [])
        self.assertEqual(events, [])

    def test_firebase_config_capture_never_connects_gateway_or_enrolls(self):
        config = {
            "_base": self.path, "dataDir": "data", "mode": "firebase",
            "projectId": "unused-real-project", "camera": {"url": "http://camera/capture", "token": "token"},
        }
        self.source.read.return_value = FRAME
        self.face.inspect_quality.return_value = QUALITY_OK
        with (
            patch("face_attendance.__main__.recognizer", return_value=self.face),
            patch("face_attendance.source.WebcamFrameSource", return_value=self.source),
            patch("face_attendance.__main__.build_gateway") as gateway,
            patch("face_attendance.__main__.create_firestore_client") as firestore,
            patch("face_attendance.capture._encode_jpeg", return_value=JPEG),
            patch("face_attendance.__main__.emit") as output,
        ):
            capture(config, "NV001", count=1, interval=0, webcam=0)
        gateway.assert_not_called()
        firestore.assert_not_called()
        self.face.enroll.assert_not_called()
        report = output.call_args.args[0]
        self.assertEqual(report["status"], "CAPTURE_COMPLETE")
        self.assertEqual(len(report["files"]), 1)
        self.assertEqual(Path(report["files"][0]).parent, self.path / "data" / "photos" / "NV001")


if __name__ == "__main__":
    unittest.main()
