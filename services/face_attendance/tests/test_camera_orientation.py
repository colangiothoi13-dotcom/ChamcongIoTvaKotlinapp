"""Pixel-level orientation checks use synthetic frames, never the real camera."""

from email.message import Message
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

from face_attendance.capture import capture_samples
from face_attendance.recognition import RecognitionResult
from face_attendance.source import FrameSourceError, UrlFrameSource, _rotate

try:
    import cv2
    import numpy as np
except ImportError:
    cv2 = np = None


@unittest.skipIf(cv2 is None or np is None, "Optional OpenCV/numpy are needed for pixel-level orientation checks")
class CameraOrientationTests(unittest.TestCase):
    def setUp(self):
        # The distinct colors identify each corner and reveal mirrors/180° mistakes.
        self.image = np.array([
            [[1, 11, 21], [2, 12, 22], [3, 13, 23]],
            [[4, 14, 24], [5, 15, 25], [6, 16, 26]],
        ], dtype=np.uint8)

    def response(self, frame_id="boot-1"):
        headers = Message()
        headers["Content-Type"] = "image/jpeg"
        headers["X-Frame-Id"] = frame_id
        response = Mock()
        response.__enter__ = Mock(return_value=response)
        response.__exit__ = Mock(return_value=False)
        response.headers = headers
        response.getcode.return_value = 200
        response.read.return_value = b"synthetic JPEG"
        return response

    def source(self, rotation):
        source = UrlFrameSource("http://camera.invalid/capture", "test-token", rotation=rotation)
        source._opener = Mock()
        source._opener.open.return_value = self.response()
        return source

    def test_each_allowed_angle_corrects_all_asymmetric_color_pixels(self):
        expected_indices = {
            0: [[0, 1, 2], [3, 4, 5]],
            90: [[3, 0], [4, 1], [5, 2]],
            180: [[5, 4, 3], [2, 1, 0]],
            270: [[2, 5], [1, 4], [0, 3]],
        }
        colors = self.image.reshape(6, 3)
        for rotation, indices in expected_indices.items():
            with self.subTest(rotation=rotation):
                original = self.image.copy()
                image = _rotate(self.image, rotation)
                np.testing.assert_array_equal(image, colors[np.array(indices)])
                np.testing.assert_array_equal(self.image, original)
                self.assertEqual(image.dtype, np.uint8)
                if rotation == 0:
                    self.assertIs(image, self.image)

    def test_rotated_frames_keep_their_freshness_identity_and_timestamp(self):
        source = self.source(180)
        with patch("face_attendance.source._decode", return_value=self.image):
            frame = source.read()
            np.testing.assert_array_equal(frame.image, self.image[::-1, ::-1])
            self.assertEqual(frame.sequence, "boot-1")
            self.assertTrue(frame.captured_at.endswith("+00:00"))
            with self.assertRaisesRegex(FrameSourceError, "stale"):
                source.read()

    def test_quality_and_saved_sample_use_the_same_corrected_frame(self):
        source = self.source(180)
        face = Mock()
        face.inspect_quality.return_value = RecognitionResult(face_count=1, reason="quality_ok")
        with (
            tempfile.TemporaryDirectory() as directory,
            patch("face_attendance.source._decode", return_value=self.image),
            patch("face_attendance.capture._encode_jpeg", return_value=b"synthetic JPEG") as encode,
            patch("face_attendance.capture._write_exclusive_jpeg", return_value=Path(directory) / "synthetic.jpg"),
        ):
            result = capture_samples(source, face, directory, "test-employee", count=1, interval=0)
        self.assertEqual(len(result.files), 1)
        quality_image = face.inspect_quality.call_args.args[0]
        self.assertIs(encode.call_args.args[0], quality_image)
        np.testing.assert_array_equal(quality_image, self.image[::-1, ::-1])
        face.recognize.assert_not_called()

    def test_failed_orientation_is_a_source_error_and_does_not_mark_a_frame_as_usable(self):
        source = self.source(180)
        with (
            patch("face_attendance.source._decode", return_value=self.image),
            patch("face_attendance.source._rotate", side_effect=FrameSourceError("orientation failed")),
        ):
            with self.assertRaises(FrameSourceError):
                source.read()
        self.assertEqual(source._recent_ids, set())


if __name__ == "__main__":
    unittest.main()
