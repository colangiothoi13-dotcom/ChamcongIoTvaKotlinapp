"""Real Admin SDK transactions on isolated LOCAL emulator projects only."""

from datetime import datetime, timedelta, timezone
import os
from pathlib import Path
import re
import tempfile
import threading
import unittest
from unittest.mock import patch
import uuid

from face_attendance.backend import create_firestore_client
from face_attendance.capture import CaptureSummary
from face_attendance.enrollment import CommandRejected, EnrollmentWorker, FirestoreEnrollmentStore


@unittest.skipUnless(os.environ.get("FIRESTORE_EMULATOR_HOST"), "Local Firestore emulator is not configured")
class EnrollmentFirestoreTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not re.fullmatch(r"(?:127\.0\.0\.1|localhost|\[::1\]):[0-9]{1,5}", os.environ["FIRESTORE_EMULATOR_HOST"]):
            raise RuntimeError("Enrollment tests require a loopback Firestore emulator")

    def setUp(self):
        self.client = create_firestore_client("demo-face-enroll-" + uuid.uuid4().hex[:9])
        self.addCleanup(self.client.close)
        self.now = datetime.now(timezone.utc)
        self.store = FirestoreEnrollmentStore(self.client, "GATE-01", "CAM-01")
        batch = self.client.batch()
        batch.set(self.store.station, {"stationId": "GATE-01", "faceDeviceId": "CAM-01", "mode": "firebase",
                                       "sessionId": self.store.session_id, "pendingCommandId": "request-a"})
        batch.set(self.client.collection("employees").document("employee-a"), {"active": True, "fullName": "Demo A"})
        batch.set(self.client.collection("users").document("admin-a"), {"active": True, "role": "ADMIN"})
        batch.set(self.client.collection("faceEnrollmentLocks").document("employee-a"), {
            "employeeId": "employee-a", "commandId": "request-a", "stationId": "GATE-01", "updatedAt": self.now,
        })
        self.command = self.client.collection("faceEnrollmentCommands").document("request-a")
        batch.set(self.command, {
            "requestId": "request-a", "employeeId": "employee-a", "stationId": "GATE-01", "deviceId": "CAM-01",
            "sessionId": self.store.session_id, "action": "ENROLL", "sampleCount": 10, "samplesCaptured": 0,
            "status": "PENDING", "requestedBy": "admin-a", "requestedAt": self.now, "cancelRequested": False,
        })
        batch.set(self.client.collection("faceProfiles").document("employee-a"), {"enabled": True, "version": 3})
        batch.commit()

    def test_real_claim_finalize_and_atomic_profile_status_lock_release(self):
        self.assertEqual(self.store.claim("request-a")["employeeId"], "employee-a")
        self.assertIsNone(self.store.claim("request-a"))
        self.store.progress("request-a", 10)
        version = self.store.begin_finalizing("request-a", 8)
        self.assertEqual(version, 9)
        self.store.complete("request-a", version, "ENROLL")
        self.assertEqual(self.command.get().to_dict()["status"], "COMPLETED")
        profile = self.client.collection("faceProfiles").document("employee-a").get().to_dict()
        self.assertTrue(profile["enabled"])
        self.assertEqual(profile["version"], 9)
        self.assertNotIn("pendingCommandId", self.store.station.get().to_dict())
        self.assertFalse(self.client.collection("faceEnrollmentLocks").document("employee-a").get().exists)
        self.assertEqual(list(self.client.collection("attendance").stream()), [])

    def test_cancellation_and_retirement_are_rechecked_in_real_transactions(self):
        self.store.claim("request-a")
        self.command.update({"cancelRequested": True})
        with self.assertRaisesRegex(CommandRejected, "CANCELLED"):
            self.store.begin_finalizing("request-a", 2)
        self.command.update({"cancelRequested": False})
        version = self.store.begin_finalizing("request-a", 2)
        self.client.collection("employees").document("employee-a").update({"active": False})
        with self.assertRaisesRegex(CommandRejected, "EMPLOYEE_INACTIVE"):
            self.store.complete("request-a", version, "ENROLL")
        self.assertEqual(self.command.get().to_dict()["status"], "FINALIZING")
        self.assertEqual(self.client.collection("faceProfiles").document("employee-a").get().to_dict()["version"], 3)
        self.store.finish("request-a", "FAILED", "EMPLOYEE_INACTIVE")
        self.assertEqual(self.command.get().to_dict()["status"], "FAILED")

    def test_real_delete_disables_profile_and_releases_locks(self):
        self.command.update({"action": "DELETE", "sampleCount": 0})
        self.store.claim("request-a")
        version = self.store.begin_finalizing("request-a", 5)
        self.store.complete("request-a", version, "DELETE")
        profile = self.client.collection("faceProfiles").document("employee-a").get().to_dict()
        self.assertFalse(profile["enabled"])
        self.assertEqual(profile["version"], 6)
        self.assertEqual(self.command.get().to_dict()["status"], "COMPLETED")

    def test_real_auto_accessory_rejection_preserves_progress_profile_and_locks(self):
        self.store.claim("request-a")
        self.store.reject_capture("request-a", 0, "glasses")
        rejected = self.command.get().to_dict()
        self.assertEqual(rejected["samplesCaptured"], 0)
        self.assertEqual(rejected["captureLastQualityIssue"], "glasses")
        self.assertIn("Tháo kính", rejected["message"])
        self.assertEqual(rejected["status"], "CAPTURING")
        self.assertEqual(self.client.collection("faceProfiles").document("employee-a").get().to_dict()["version"], 3)
        self.assertTrue(self.client.collection("faceEnrollmentLocks").document("employee-a").get().exists)
        self.assertEqual(list(self.client.collection("attendance").stream()), [])
        self.store.progress("request-a", 1)
        self.assertIsNone(self.command.get().to_dict()["captureLastQualityIssue"])
        self.command.update({"cancelRequested": True})
        with self.assertRaisesRegex(CommandRejected, "CANCELLED"):
            self.store.reject_capture("request-a", 1, "mask")
        self.assertEqual(self.command.get().to_dict()["samplesCaptured"], 1)

    def test_real_manual_click_acknowledges_rejected_and_saved_frames_atomically(self):
        self.command.update({"captureMode": "MANUAL", "captureRequestSeq": 0, "captureConsumedSeq": 0})
        self.store.claim("request-a")
        changed = threading.Event()
        observed = []
        def on_command(data):
            observed.append(data)
            if data.get("captureRequestSeq") == 1:
                changed.set()
        watch = self.store.watch_command("request-a", on_command)
        self.addCleanup(watch.unsubscribe)
        self.command.update({"captureRequestSeq": 1})
        self.assertTrue(changed.wait(10), "Real command watch must deliver a manual click")
        self.store.check_manual_request("request-a", 1)
        self.store.acknowledge_capture("request-a", 1, 0, "no_face")
        data = self.command.get().to_dict()
        self.assertEqual((data["captureConsumedSeq"], data["samplesCaptured"]), (1, 0))
        self.assertIn("Chưa thấy khuôn mặt", data["message"])
        with self.assertRaisesRegex(CommandRejected, "INVALID_CAPTURE_SEQUENCE"):
            self.store.acknowledge_capture("request-a", 1, 1, "quality_ok")
        self.command.update({"captureRequestSeq": 2})
        self.store.acknowledge_capture("request-a", 2, 1, "quality_ok")
        data = self.command.get().to_dict()
        self.assertEqual((data["captureConsumedSeq"], data["samplesCaptured"]), (2, 1))
        self.assertEqual(self.client.collection("faceProfiles").document("employee-a").get().to_dict()["version"], 3)
        self.assertEqual(list(self.client.collection("attendance").stream()), [])
        self.command.update({"captureRequestSeq": 3})
        self.store.station.update({"sessionId": "replacement-session"})
        with self.assertRaisesRegex(CommandRejected, "STATION_SESSION_CHANGED"):
            self.store.check_manual_request("request-a", 3)
        with self.assertRaisesRegex(CommandRejected, "STATION_SESSION_CHANGED"):
            self.store.acknowledge_capture("request-a", 3, 2, "quality_ok")
        self.assertEqual(self.command.get().to_dict()["captureConsumedSeq"], 2)

    def test_real_sharpness_feedback_ack_never_advances_rejected_sample_and_clears_stale_metrics(self):
        self.command.update({"captureMode": "MANUAL", "captureRequestSeq": 0, "captureConsumedSeq": 0})
        self.store.claim("request-a")
        self.command.update({"captureRequestSeq": 1})
        self.store.acknowledge_capture("request-a", 1, 0, "low_quality", "blurry",
                                       sharpness_score=12, sharpness_required=50,
                                       sharpness_metric="FACE_MAX128_LAPLACIAN_V1")
        data = self.command.get().to_dict()
        self.assertEqual((data["captureConsumedSeq"], data["samplesCaptured"]), (1, 0))
        self.assertEqual((data["captureLastSharpness"], data["captureSharpnessRequired"]), (12, 50))
        self.assertEqual(data["captureSharpnessMetric"], "FACE_MAX128_LAPLACIAN_V1")
        self.assertIn("Đã thấy mặt", data["message"])
        self.assertEqual(self.client.collection("faceProfiles").document("employee-a").get().to_dict()["version"], 3)
        self.command.update({"captureRequestSeq": 2})
        with self.assertRaisesRegex(CommandRejected, "INVALID_SHARPNESS_DIAGNOSTICS"):
            self.store.acknowledge_capture("request-a", 2, 1, "quality_ok",
                                           sharpness_score=float("nan"), sharpness_required=50)
        self.assertEqual(self.command.get().to_dict()["captureConsumedSeq"], 1)
        self.store.acknowledge_capture("request-a", 2, 1, "quality_ok")
        data = self.command.get().to_dict()
        self.assertEqual((data["captureConsumedSeq"], data["samplesCaptured"]), (2, 1))
        self.assertIsNone(data["captureLastSharpness"])
        self.assertIsNone(data["captureSharpnessRequired"])
        self.assertIsNone(data["captureSharpnessMetric"])

    def test_real_guided_six_shots_acceptance_and_profile_total_use_command_contract(self):
        self.command.update({"sampleCount": 6, "capturePlan": "FRONT_LEFT_RIGHT_V1",
                             "captureMode": "MANUAL", "captureRequestSeq": 0, "captureConsumedSeq": 0})
        self.assertEqual(self.store.claim("request-a")["sampleCount"], 6)
        for count in range(1, 7):
            self.command.update({"captureRequestSeq": count})
            self.store.check_manual_request("request-a", count)
            self.store.acknowledge_capture("request-a", count, count, "quality_ok")
            data = self.command.get().to_dict()
            self.assertEqual((data["samplesCaptured"], data["captureConsumedSeq"]), (count, count))
            if count == 2:
                self.assertIn("trái của nhân viên", data["message"])
            elif count == 4:
                self.assertIn("phải của nhân viên", data["message"])
            if count < 6:
                with self.assertRaisesRegex(CommandRejected, "CAPTURE_INCOMPLETE"):
                    self.store.begin_finalizing("request-a", 1)
        version = self.store.begin_finalizing("request-a", 1)
        self.store.complete("request-a", version, "ENROLL")
        profile = self.client.collection("faceProfiles").document("employee-a").get().to_dict()
        self.assertEqual((profile["sampleCount"], profile["capturePlan"]), (6, "FRONT_LEFT_RIGHT_V1"))
        self.assertEqual(self.command.get().to_dict()["status"], "COMPLETED")
        self.assertEqual(list(self.client.collection("attendance").stream()), [])

    def test_real_guided_count_without_plan_is_rejected_before_capture(self):
        self.command.update({"sampleCount": 6, "captureMode": "MANUAL",
                             "captureRequestSeq": 0, "captureConsumedSeq": 0})
        self.assertIsNone(self.store.claim("request-a"))
        data = self.command.get().to_dict()
        self.assertEqual((data["status"], data["errorCode"]), ("FAILED", "INVALID_SAMPLE_COUNT"))
        self.assertEqual(self.client.collection("faceProfiles").document("employee-a").get().to_dict()["version"], 3)

    def test_sdk_metadata_failure_rolls_back_worker_gallery(self):
        from test_enrollment import LocalFace
        with tempfile.TemporaryDirectory() as directory:
            data = Path(directory)
            face = LocalFace(data / "gallery.json")
            before = face.path.read_bytes()
            worker = EnrollmentWorker(self.store, face, None, data, threading.Event(), interval=0)
            summary = CaptureSummary("employee-a", 10, tuple(f"photo-{index}.jpg" for index in range(10)), 10, False)
            with (patch("face_attendance.enrollment.capture_samples", return_value=summary),
                  patch.object(self.store, "complete", side_effect=ConnectionError("simulated network failure"))):
                worker.run_one("request-a")
            self.assertEqual(face.path.read_bytes(), before)
            self.assertEqual(self.command.get().to_dict()["status"], "FAILED")
            self.assertEqual(self.client.collection("faceProfiles").document("employee-a").get().to_dict()["version"], 3)
            self.assertNotIn("pendingCommandId", self.store.station.get().to_dict())

    def test_real_station_timestamps_use_server_clock_and_close_allows_restart(self):
        # A deliberately incorrect laptop clock must never become an online
        # heartbeat; Android/rules compare that field with server time.
        ahead = lambda: datetime.now(timezone.utc) + timedelta(minutes=5)
        self.store.clock = ahead
        self.store.start()
        self.store.heartbeat(True)
        snapshot = self.store.station.get()
        station = snapshot.to_dict()
        self.assertLess(abs((snapshot.read_time - station["lastSeenAt"]).total_seconds()), 10)
        self.assertLess(abs((snapshot.read_time - station["startedAt"]).total_seconds()), 10)
        second = FirestoreEnrollmentStore(self.client, "GATE-01", "CAM-01", clock=ahead)
        with self.assertRaisesRegex(CommandRejected, "STATION_ALREADY_RUNNING"):
            second.start()
        self.store.close()
        self.assertNotIn("sessionId", self.store.station.get().to_dict())
        second.start()
        self.addCleanup(second.close)
        self.assertEqual(self.store.station.get().to_dict()["sessionId"], second.session_id)

    def test_real_current_session_request_survives_small_clock_offset(self):
        self.store.clock = lambda: datetime.now(timezone.utc) + timedelta(seconds=20)
        self.assertEqual(self.store.claim("request-a")["employeeId"], "employee-a")
        self.assertEqual(self.command.get().to_dict()["status"], "CAPTURING")


if __name__ == "__main__":
    unittest.main()
