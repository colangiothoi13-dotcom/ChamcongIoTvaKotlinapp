import threading
import unittest
from unittest.mock import Mock
from urllib import error, request

from face_attendance.capture_preview import CapturePreviewStore, MAX_PREVIEW_BYTES
from face_attendance.server import make_server


JPEG_A = b"\xff\xd8attempt-one\xff\xd9"
JPEG_B = b"\xff\xd8attempt-two\xff\xd9"


class FakeTimer:
    def __init__(self, interval, callback, args=()):
        self.interval, self.callback, self.args = interval, callback, args
        self.cancelled = False
        self.started = False

    def start(self):
        self.started = True

    def cancel(self):
        self.cancelled = True

    def fire(self):
        # A timer may have started its callback just before it was canceled.
        self.callback(*self.args)


class CapturePreviewTests(unittest.TestCase):
    def setUp(self):
        self.now = 10
        self.timers = []

        def timer_factory(*args, **kwargs):
            timer = FakeTimer(*args, **kwargs)
            self.timers.append(timer)
            return timer

        self.encoder = Mock(return_value=JPEG_A)
        self.preview = CapturePreviewStore(clock=lambda: self.now, encoder=self.encoder,
                                           timer_factory=timer_factory)
        self.addCleanup(self.preview.close)
        self.preview.begin("request-A")

    def publish(self, sequence=1, *, reason="low_quality", saved=0, image=None):
        return self.preview.publish("request-A", sequence, image if image is not None else object(),
                                    reason=reason, saved_count=saved)

    def test_rejected_photo_is_available_for_exact_attempt_and_never_other_ids(self):
        self.assertTrue(self.publish())
        record = self.preview.get("request-A", 1)
        self.assertEqual((record.jpeg, record.reason, record.saved_count), (JPEG_A, "low_quality", 0))
        self.assertIsNone(self.preview.get("request-A", 2))
        self.assertIsNone(self.preview.get("request-B", 1))
        self.assertEqual(record.sample_count, 10)

    def test_guided_preview_records_total_and_rejects_saved_count_over_that_total(self):
        self.assertTrue(self.preview.publish("request-A", 6, object(), reason="quality_ok",
                                             saved_count=6, sample_count=6))
        self.assertEqual(self.preview.get("request-A", 6).sample_count, 6)
        for count, total in ((7, 6), (0, 2), (0, True), (0, "6")):
            with self.subTest(count=count, total=total):
                self.assertFalse(self.preview.publish("request-A", 7, object(), reason="quality_ok",
                                                     saved_count=count, sample_count=total))
                self.assertIsNone(self.preview.get("request-A", 6))

    def test_latest_attempt_replaces_previous_and_canceled_timer_cannot_delete_new_image(self):
        self.publish()
        previous_timer = self.timers[-1]
        self.encoder.return_value = JPEG_B
        self.assertTrue(self.publish(2, reason="quality_ok", saved=1))
        self.assertTrue(previous_timer.cancelled)
        previous_timer.fire()
        self.assertIsNone(self.preview.get("request-A", 1))
        self.assertEqual(self.preview.get("request-A", 2).jpeg, JPEG_B)

    def test_ttl_expires_by_clock_and_timer_releases_idle_image(self):
        self.publish()
        self.now += 300
        self.assertIsNone(self.preview.get("request-A", 1))
        self.now += 1
        self.publish(2)
        self.timers[-1].fire()
        self.assertIsNone(self.preview.get("request-A", 2))

    def test_replacement_request_and_clear_cannot_leak_prior_preview(self):
        self.publish()
        self.preview.begin("request-B")
        self.assertIsNone(self.preview.get("request-A", 1))
        self.assertFalse(self.publish(2))
        self.preview.publish("request-B", 1, object(), reason="quality_ok", saved_count=1)
        self.preview.clear("request-A")
        self.assertIsNotNone(self.preview.get("request-B", 1))
        self.preview.clear("request-B")
        self.assertIsNone(self.preview.get("request-B", 1))
        self.assertFalse(self.preview.publish("request-B", 2, object(), reason="quality_ok", saved_count=2))

    def test_camera_error_clears_stale_image_and_next_click_can_publish(self):
        self.publish()
        self.assertFalse(self.preview.publish("request-A", 2, None, reason="camera_error", saved_count=0))
        self.assertIsNone(self.preview.get("request-A", 1))
        self.assertIsNone(self.preview.get("request-A", 2))
        self.assertTrue(self.publish(3))

    def test_oversize_invalid_jpeg_and_encoder_failure_clear_stale_attempt(self):
        for bad in (b"", b"not a jpeg", b"\xff\xd8" + b"a" * MAX_PREVIEW_BYTES + b"\xff\xd9", bytearray(JPEG_A)):
            with self.subTest(encoded_type=type(bad), encoded_size=len(bad)):
                self.encoder.return_value = JPEG_A
                self.publish()
                self.encoder.return_value = bad
                self.assertFalse(self.publish(2))
                self.assertIsNone(self.preview.get("request-A", 1))
        self.encoder.side_effect = RuntimeError("encoder unavailable")
        self.assertFalse(self.publish(3))
        self.assertIsNone(self.preview.get("request-A", 3))

    def test_invalid_keys_counts_and_header_values_are_rejected(self):
        for request_id in ("..", "request/other", "request:other", "bad\r\nheader", "a" * 129):
            with self.subTest(request_id=request_id):
                with self.assertRaises(ValueError):
                    self.preview.begin(request_id)
                self.assertIsNone(self.preview.get(request_id, 1))
        for sequence in (0, 101, True, "1"):
            with self.subTest(sequence=sequence):
                self.assertFalse(self.publish(sequence))
        self.assertFalse(self.publish(reason="unsafe\r\nheader"))
        self.assertFalse(self.publish(saved=True))
        self.assertFalse(self.publish(saved=11))

    def test_encoding_in_flight_cannot_restore_image_after_cancellation(self):
        started, finish = threading.Event(), threading.Event()

        def slow_encode(image):
            started.set()
            finish.wait(2)
            return JPEG_A

        self.encoder.side_effect = slow_encode
        result = []
        thread = threading.Thread(target=lambda: result.append(self.publish()))
        thread.start()
        self.assertTrue(started.wait(2))
        self.preview.clear("request-A")
        finish.set()
        thread.join(2)
        self.assertEqual(result, [False])
        self.assertIsNone(self.preview.get("request-A", 1))

    def test_shutdown_clears_and_permanently_prevents_new_preview(self):
        self.publish()
        self.preview.close()
        self.preview.begin("request-A")
        self.assertFalse(self.publish())
        self.assertIsNone(self.preview.get("request-A", 1))


class CapturePreviewHttpTests(unittest.TestCase):
    def setUp(self):
        self.gateway = Mock()
        self.admin, self.device = "a" * 32, "b" * 32
        self.preview = CapturePreviewStore(encoder=lambda image: JPEG_A, timer_factory=FakeTimer)
        self.preview.begin("request-A")
        self.preview.publish("request-A", 3, object(), reason="low_quality", saved_count=0)
        self.server = make_server(self.gateway, "127.0.0.1", 0, self.admin, self.device, preview=self.preview)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.addCleanup(self.close)
        self.url = f"http://127.0.0.1:{self.server.server_port}"

    def close(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(2)
        self.preview.close()

    def get(self, path, headers=None):
        req = request.Request(self.url + path, headers=headers or {})
        try:
            with request.urlopen(req, timeout=3) as response:
                return response.status, response.headers, response.read()
        except error.HTTPError as response:
            return response.code, response.headers, response.read()

    def test_admin_auth_required_device_token_cannot_read_images(self):
        for headers in ({}, {"X-Device-Token": self.device}, {"X-Admin-Token": "wrong"}):
            code, meta, body = self.get("/v1/enrollment-preview/request-A/3", headers)
            self.assertEqual(code, 401)
            self.assertEqual(meta["Cache-Control"], "no-store")
            self.assertNotIn(JPEG_A, body)

    def test_exact_attempt_jpeg_metadata_and_get_has_no_attendance_effects(self):
        code, meta, body = self.get("/v1/enrollment-preview/request-A/3", {"X-Admin-Token": self.admin})
        self.assertEqual(code, 200)
        self.assertEqual(body, JPEG_A)
        self.assertEqual(meta["Content-Type"], "image/jpeg")
        self.assertEqual(meta["Cache-Control"], "no-store")
        self.assertEqual(meta["X-Content-Type-Options"], "nosniff")
        self.assertEqual(meta["X-Enrollment-Request"], "request-A")
        self.assertEqual(meta["X-Capture-Sequence"], "3")
        self.assertEqual(meta["X-Capture-Reason"], "low_quality")
        self.assertEqual(meta["X-Capture-Saved"], "0")
        self.assertEqual(meta["X-Capture-Total"], "10")
        self.gateway.submit.assert_not_called()
        self.gateway.flush.assert_not_called()
        self.gateway.outbox.assert_not_called()

    def test_no_listing_no_filepath_no_cross_request_and_canonical_sequence_only(self):
        paths = (
            "/v1/enrollment-preview/", "/v1/enrollment-preview/request-A/2",
            "/v1/enrollment-preview/request-B/3", "/v1/enrollment-preview/request-A/03",
            "/v1/enrollment-preview/request-A/0", "/v1/enrollment-preview/request-A/101",
            "/v1/enrollment-preview/request-A/3?file=secret", "/v1/enrollment-preview/../3",
            "/v1/enrollment-preview/request%2FA/3", "/v1/enrollment-preview/a:filename/3",
            "/v1/enrollment-preview/request-A/3/photos/local.jpg",
        )
        for path in paths:
            with self.subTest(path=path):
                code, meta, body = self.get(path, {"X-Admin-Token": self.admin})
                self.assertEqual(code, 404)
                self.assertNotIn(JPEG_A, body)
                self.assertEqual(meta["Cache-Control"], "no-store")

    def test_optional_preview_keeps_existing_server_compatibility(self):
        self.preview.clear()
        code, _, _ = self.get("/v1/enrollment-preview/request-A/3", {"X-Admin-Token": self.admin})
        self.assertEqual(code, 404)
        # The original make_server call does not need a preview provider.
        server = make_server(self.gateway, "127.0.0.1", 0, self.admin, self.device)
        server.server_close()


if __name__ == "__main__":
    unittest.main()
