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


if __name__ == "__main__":
    unittest.main()
