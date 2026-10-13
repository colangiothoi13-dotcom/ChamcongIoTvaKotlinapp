from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

from face_attendance.recognition import RecognitionResult
from face_attendance.station import run_station


class StationTests(unittest.TestCase):
    def config(self, mode):
        return {"mode": mode, "host": "127.0.0.1", "port": 0, "adminToken": "a" * 32, "deviceToken": "b" * 32,
                "stationId": "GATE-01", "faceDeviceId": "CAM-01", "camera": {"url": "http://camera/capture", "token": "c" * 32},
                "recognition": {"stableFrames": 1, "emptyFrames": 1, "pollSeconds": 0.1}}

    def test_demo_station_never_opens_firestore_or_registers_app_commands(self):
        source = Mock()
        source.read.side_effect = KeyboardInterrupt()
        face, gateway, server = Mock(), Mock(), Mock(server_port=8765)
        with (patch("face_attendance.station.UrlFrameSource", return_value=source),
              patch("face_attendance.station.make_server", return_value=server),
              patch("face_attendance.station.FirestoreEnrollmentStore") as store):
            run_station(self.config("demo"), gateway, face, ".", lambda value: None)
        store.assert_not_called()
        source.close.assert_called_once()
        server.server_close.assert_called_once()
        gateway.submit.assert_not_called()

    def test_station_passes_default_or_configured_orientation_to_its_shared_camera(self):
        for rotation in (None, 180):
            config = self.config("demo")
            if rotation is not None:
                config["camera"]["rotation"] = rotation
            source = Mock()
            source.read.side_effect = KeyboardInterrupt()
            with (
                self.subTest(rotation=rotation),
                patch("face_attendance.station.UrlFrameSource", return_value=source) as create_source,
                patch("face_attendance.station.make_server", return_value=Mock(server_port=8765)),
            ):
                run_station(config, Mock(), Mock(), ".", lambda value: None)
            create_source.assert_called_once_with("http://camera/capture", "c" * 32, rotation=rotation or 0)
            source.close.assert_called_once()

    def test_station_camera_rotation_does_not_rotate_laptop_webcam(self):
        config = self.config("demo")
        config["camera"]["rotation"] = 180
        source = Mock()
        source.read.side_effect = KeyboardInterrupt()
        with (
            patch("face_attendance.station.UrlFrameSource") as create_camera,
            patch("face_attendance.station.WebcamFrameSource", return_value=source) as create_webcam,
            patch("face_attendance.station.make_server", return_value=Mock(server_port=8765)),
        ):
            run_station(config, Mock(), Mock(), ".", lambda value: None, webcam=0)
        create_camera.assert_not_called()
        create_webcam.assert_called_once_with(0)

    def test_enrollment_resets_presence_before_camera_can_submit_face_attendance(self):
        frame = SimpleNamespace(image=object(), captured_at="2026-10-11T00:00:00+00:00")
        source = Mock()
        source.read.side_effect = [frame, frame, KeyboardInterrupt()]
        face, gateway, server = Mock(), Mock(), Mock(server_port=8765)
        face.recognize.side_effect = [RecognitionResult(reason="no_face"), RecognitionResult(
            face_count=1, employee_id="employee-a", profile_version=2, similarity=0.9, reason="matched")]
        store = Mock()
        store.next_command.side_effect = [None, "request-a", None, None]
        worker = Mock()
        with (patch("face_attendance.station.UrlFrameSource", return_value=source),
              patch("face_attendance.station.make_server", return_value=server),
              patch("face_attendance.station.FirestoreEnrollmentStore", return_value=store),
              patch("face_attendance.station.EnrollmentWorker", return_value=worker)):
            run_station(self.config("firebase"), gateway, face, ".", lambda value: None)
        worker.recover.assert_called_once()
        worker.run_one.assert_called_once_with("request-a")
        gateway.submit.assert_not_called()
        self.assertEqual(face.recognize.call_count, 2)
        store.close.assert_called_once()

    def test_station_shares_only_in_memory_preview_between_worker_and_admin_server_and_closes_it(self):
        source = Mock()
        source.read.side_effect = KeyboardInterrupt()
        preview, store, server = Mock(), Mock(), Mock(server_port=8765)
        store.next_command.return_value = None
        with (
            patch("face_attendance.station.UrlFrameSource", return_value=source),
            patch("face_attendance.station.CapturePreviewStore", return_value=preview),
            patch("face_attendance.station.make_server", return_value=server) as create_server,
            patch("face_attendance.station.FirestoreEnrollmentStore", return_value=store),
            patch("face_attendance.station.EnrollmentWorker") as create_worker,
        ):
            run_station(self.config("firebase"), Mock(), Mock(), ".", lambda value: None)
        self.assertIs(create_server.call_args.kwargs["preview"], preview)
        self.assertIs(create_worker.call_args.kwargs["preview"], preview)
        preview.close.assert_called_once()
        store.close.assert_called_once()

    def test_confirmed_face_result_is_available_to_camera_status_with_employee_name(self):
        frame = SimpleNamespace(image=object(), captured_at="2026-10-13T00:00:00+00:00")
        source = Mock()
        source.read.side_effect = [frame, frame, KeyboardInterrupt()]
        face = Mock()
        face.recognize.side_effect = [RecognitionResult(reason="no_face"), RecognitionResult(
            employee_id="employee-a", profile_version=2, similarity=0.9, face_count=1, reason="matched")]
        gateway = Mock()
        result = {"status": "ACCEPTED_DEMO", "employeeName": "Nguyễn Văn A"}
        gateway.submit.side_effect = lambda event: (gateway.result_listener(event, result), result)[1]
        server = Mock(server_port=8765)
        with (patch("face_attendance.station.UrlFrameSource", return_value=source),
              patch("face_attendance.station.make_server", return_value=server) as create_server):
            run_station(self.config("demo"), gateway, face, ".", lambda _: None)
        provider = create_server.call_args.kwargs["face_status"]
        snapshot = provider()
        self.assertEqual(snapshot["attendance"]["status"], "ACCEPTED")
        self.assertEqual(snapshot["attendance"]["employeeName"], "Nguyễn Văn A")
        self.assertEqual(snapshot["scan"]["status"], "ACCEPTED")

    def test_accessory_blocks_do_not_submit_attendance_or_release_an_occupied_visit(self):
        config = self.config("demo")
        config["accessories"] = {"enabled": True}
        frame = SimpleNamespace(image=object(), captured_at="2026-10-13T00:00:00+00:00")
        empty = RecognitionResult(reason="no_face")
        mask = RecognitionResult(face_count=1, reason="low_quality", quality_issue="mask")
        glasses = RecognitionResult(face_count=1, reason="low_quality", quality_issue="glasses")
        matched = RecognitionResult(employee_id="employee-a", face_count=1, profile_version=2,
                                    similarity=0.9, reason="matched")
        results = [empty, mask, mask, glasses, matched, mask, matched, empty, matched]
        face, source, gateway = Mock(), Mock(), Mock()
        face.recognize.side_effect = results
        source.read.side_effect = [frame] * len(results) + [KeyboardInterrupt()]
        messages = []
        with (
            patch("face_attendance.station.UrlFrameSource", return_value=source),
            patch("face_attendance.station.make_server", return_value=Mock(server_port=8765)) as create_server,
        ):
            run_station(config, gateway, face, ".", messages.append)
        self.assertEqual(gateway.submit.call_count, 2)
        blocked = [message for message in messages if message.get("status") == "FACE_BLOCKED"]
        self.assertEqual([message["qualityIssue"] for message in blocked], ["mask", "glasses", "mask"])
        self.assertEqual(len([message for message in messages if message.get("status") == "FACE_READY"]), 2)
        provider = create_server.call_args.kwargs["face_status"]
        self.assertEqual(provider()["status"], "STOPPED")
        self.assertTrue(provider()["accessoryGateEnabled"])


if __name__ == "__main__":
    unittest.main()
