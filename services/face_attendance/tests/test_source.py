from email.message import Message
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch
from urllib import request

from face_attendance.source import FrameSourceError, ImageFrameSource, UrlFrameSource


class FrameSourceTests(unittest.TestCase):
    def response(self, frame_id="boot-1", content_type="image/jpeg", data=b"JPEG"):
        headers = Message()
        headers["Content-Type"] = content_type
        if frame_id is not None:
            headers["X-Frame-Id"] = frame_id
        result = Mock()
        result.__enter__ = Mock(return_value=result)
        result.__exit__ = Mock(return_value=False)
        result.headers = headers
        result.getcode.return_value = 200
        result.read.return_value = data
        return result

    @patch("face_attendance.source._decode", return_value=object())
    def test_url_authenticates_and_refuses_stale_snapshot(self, decode):
        source = UrlFrameSource("http://192.168.1.2/capture", "local-camera-token")
        source._opener = Mock()
        source._opener.open.return_value = self.response()
        frame = source.read()
        self.assertEqual(frame.sequence, "boot-1")
        self.assertTrue(frame.captured_at.endswith("+00:00"))
        req = source._opener.open.call_args.args[0]
        self.assertEqual(req.get_header("X-camera-token"), "local-camera-token")
        with self.assertRaisesRegex(FrameSourceError, "stale"):
            source.read()
        decode.assert_called_once()

    @patch("face_attendance.source._decode", return_value=object())
    def test_missing_id_bad_type_oversize_and_network_error_fail_before_decode(self, decode):
        source = UrlFrameSource("http://192.168.1.2/capture", "token", max_bytes=8)
        source._opener = Mock()
        for response in (
            self.response(frame_id=None),
            self.response(content_type="text/html"),
            self.response(data=b"123456789"),
        ):
            source._opener.open.return_value = response
            with self.assertRaises(FrameSourceError):
                source.read()
        source._opener.open.side_effect = OSError("disconnected")
        with self.assertRaises(FrameSourceError):
            source.read()
        decode.assert_not_called()

    @patch("face_attendance.source._decode", return_value=object())
    def test_old_snapshot_after_newer_frame_is_still_stale(self, decode):
        source = UrlFrameSource("http://192.168.1.2/capture", "token")
        source._opener = Mock()
        source._opener.open.side_effect = [self.response("boot-1"), self.response("boot-2"), self.response("boot-1")]
        source.read()
        source.read()
        with self.assertRaises(FrameSourceError):
            source.read()

    def test_token_never_follows_redirect(self):
        source = UrlFrameSource("http://192.168.1.2/capture", "token")
        handler = next(handler for handler in source._opener.handlers if isinstance(handler, request.HTTPRedirectHandler))
        with self.assertRaises(FrameSourceError):
            handler.redirect_request(None, None, 302, "redirect", {}, "http://other-host/capture")

    def test_invalid_orientation_is_rejected_before_preparing_a_network_reader(self):
        for rotation in (True, False, "180", 180.0, 45, -90, 360, None, [], {}):
            with self.subTest(rotation=rotation), patch("face_attendance.source.request.build_opener") as opener:
                with self.assertRaisesRegex(ValueError, "rotation"):
                    UrlFrameSource("http://192.168.1.2/capture", "token", rotation=rotation)
                opener.assert_not_called()

    def test_omitted_orientation_keeps_existing_cameras_unrotated(self):
        source = UrlFrameSource("http://192.168.1.2/capture", "token")
        self.assertEqual(source.rotation, 0)

    @patch("face_attendance.source._decode", return_value=object())
    def test_local_image_is_one_shot_test_source(self, decode):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "test.jpg"
            path.write_bytes(b"test photo")
            source = ImageFrameSource(path)
            self.assertFalse(source.is_live)
            self.assertEqual(source.read().sequence, "local-test-1")
            with self.assertRaises(FrameSourceError):
                source.read()


if __name__ == "__main__":
    unittest.main()
