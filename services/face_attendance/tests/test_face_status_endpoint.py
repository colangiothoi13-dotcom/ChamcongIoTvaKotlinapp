import json
import threading
import unittest
from unittest.mock import Mock, patch
from urllib import error, request

from face_attendance.feedback import FaceBlockFeedback
from face_attendance.recognition import RecognitionResult
from face_attendance.server import make_server


class FaceStatusEndpointTests(unittest.TestCase):
    def setUp(self):
        self.gateway = Mock()
        self.feedback = FaceBlockFeedback(lambda _: None, enabled=True)
        self.feedback.observe(RecognitionResult(face_count=1, reason="low_quality", quality_issue="glasses"))
        self.server = make_server(self.gateway, "127.0.0.1", 0, "a" * 32, "d" * 32,
                                  face_status=self.feedback.get, allow_face_events=False)
        self.thread = threading.Thread(target=self.server.serve_forever, kwargs={"poll_interval": .01})
        self.thread.start()
        self.addCleanup(self.close)
        self.url = f"http://127.0.0.1:{self.server.server_port}"

    def close(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(2)

    def test_only_admin_can_read_minimal_local_status(self):
        for headers in ({}, {"X-Device-Token": "d" * 32}):
            with self.assertRaises(error.HTTPError) as denied:
                request.urlopen(request.Request(self.url + "/v1/face-status", headers=headers))
            self.assertEqual(denied.exception.code, 401)
        with request.urlopen(request.Request(self.url + "/v1/face-status", headers={"X-Admin-Token": "a" * 32})) as reply:
            value = json.load(reply)
            self.assertEqual(reply.headers["Cache-Control"], "no-store")
        self.assertEqual(value["status"], "FACE_BLOCKED")
        self.assertTrue(value["accessoryGateEnabled"])
        self.assertEqual(value["qualityIssue"], "glasses")
        self.assertEqual(set(value), {"status", "qualityIssue", "message", "updatedAt", "accessoryGateEnabled"})
        self.gateway.submit.assert_not_called()

    def test_metadata_face_request_cannot_bypass_enabled_guard(self):
        req = request.Request(self.url + "/v1/face", data=b'{}', headers={"X-Admin-Token": "a" * 32})
        with self.assertRaises(error.HTTPError) as denied:
            request.urlopen(req)
        self.assertEqual(denied.exception.code, 422)
        self.assertEqual(json.load(denied.exception)["reason"], "ACCESSORY_CHECK_REQUIRED")
        self.gateway.submit.assert_not_called()

    def test_fingerprint_route_stays_available(self):
        self.gateway.submit.return_value = {"status": "ACCEPTED_SYNCED"}
        with patch("face_attendance.server.Event.parse", return_value=object()) as parse:
            req = request.Request(self.url + "/v1/fingerprint", data=b'{}', headers={"X-Device-Token": "d" * 32})
            with request.urlopen(req) as reply:
                self.assertEqual(json.load(reply)["status"], "ACCEPTED_SYNCED")
        parse.assert_called_once_with({}, "FINGERPRINT")
        self.gateway.submit.assert_called_once()
