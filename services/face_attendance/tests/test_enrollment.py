"""Offline command/rollback tests. No real Firebase or biometric models."""

from copy import deepcopy
from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
import sys
import tempfile
import threading
from types import ModuleType, SimpleNamespace
import unittest
from unittest.mock import Mock, patch

from face_attendance.capture import CaptureSummary
from face_attendance.enrollment import CommandRejected, EnrollmentWorker, FirestoreEnrollmentStore


DELETE = object()
SERVER_TIME = object()
NOW = datetime(2026, 10, 11, 12, tzinfo=timezone.utc)


class Snapshot:
    def __init__(self, reference, value):
        self.id = reference.path.split("/")[-1]
        self.exists = value is not None
        self.value = deepcopy(value)
        self.read_time = NOW

    def to_dict(self):
        return deepcopy(self.value)


class Ref:
    def __init__(self, db, path):
        self.db, self.path = db, path

    def get(self, transaction=None):
        if transaction and transaction.writes:
            raise AssertionError("Firestore does not allow reads after writes")
        return Snapshot(self, self.db.data.get(self.path))

    def update(self, data):
        self.db.apply("update", self, data)

    def on_snapshot(self, callback):
        self.db.watched = callback
        self.db.watchers[self.path] = callback
        callback([self.get()], [], NOW)
        return SimpleNamespace(unsubscribe=lambda: self.db.watchers.pop(self.path, None))


class Collection:
    def __init__(self, db, path):
        self.db, self.path = db, path

    def document(self, name):
        return Ref(self.db, f"{self.path}/{name}")


class Tx:
    def __init__(self, db):
        self.db, self.writes = db, []

    def update(self, reference, value):
        self.writes.append(("update", reference, value))

    def set(self, reference, value, merge=False):
        self.writes.append(("update" if merge else "set", reference, value))

    def delete(self, reference):
        self.writes.append(("delete", reference, None))


class FakeClient:
    def __init__(self):
        self.data = {}
        self.watchers = {}

    def collection(self, path):
        return Collection(self, path)

    def transaction(self):
        return Tx(self)

    def apply(self, op, ref, value):
        if op == "delete":
            self.data.pop(ref.path, None)
        elif op == "set":
            self.data[ref.path] = deepcopy(value)
        else:
            data = self.data.setdefault(ref.path, {})
            for key, item in value.items():
                if item is DELETE:
                    data.pop(key, None)
                elif item is SERVER_TIME:
                    data[key] = NOW
                else:
                    data[key] = deepcopy(item)
        callback = self.watchers.get(ref.path)
        if callback:
            callback([ref.get()], [], NOW)


def sdk_stub():
    module = ModuleType("google.cloud.firestore")
    module.DELETE_FIELD = DELETE
    module.SERVER_TIMESTAMP = SERVER_TIME

    def transactional(callback):
        def run(tx):
            result = callback(tx)
            for op, reference, value in tx.writes:
                tx.db.apply(op, reference, value)
            return result
        return run

    module.transactional = transactional
    cloud, google = ModuleType("google.cloud"), ModuleType("google")
    cloud.firestore, google.cloud = module, cloud
    return {"google": google, "google.cloud": cloud, "google.cloud.firestore": module}


class StoreTests(unittest.TestCase):
    def setUp(self):
        self.sdk = patch.dict(sys.modules, sdk_stub())
        self.sdk.start()
        self.addCleanup(self.sdk.stop)
        self.db = FakeClient()
        self.store = FirestoreEnrollmentStore(self.db, "GATE-01", "CAM-01", clock=lambda: NOW)
        self.db.data = {
            "faceStations/GATE-01": {"sessionId": self.store.session_id, "pendingCommandId": "req-1"},
            "employees/employee-a": {"active": True},
            "users/admin": {"active": True, "role": "ADMIN"},
            "faceEnrollmentLocks/employee-a": {"employeeId": "employee-a", "stationId": "GATE-01", "commandId": "req-1"},
            "faceProfiles/employee-a": {"version": 5, "enabled": True},
            "faceEnrollmentCommands/req-1": {
                "requestId": "req-1", "employeeId": "employee-a", "stationId": "GATE-01", "deviceId": "CAM-01",
                "sessionId": self.store.session_id, "action": "ENROLL", "sampleCount": 10, "status": "PENDING",
                "requestedBy": "admin", "requestedAt": NOW - timedelta(seconds=5), "cancelRequested": False,
            },
        }

    @property
    def command(self):
        return self.db.data["faceEnrollmentCommands/req-1"]

    def test_claim_finalize_complete_checks_identity_locks_and_bumps_version(self):
        self.assertEqual(self.store.claim("req-1")["employeeId"], "employee-a")
        self.assertEqual(self.command["status"], "CAPTURING")
        self.store.progress("req-1", 10)
        version = self.store.begin_finalizing("req-1", 2)
        self.assertEqual(version, 6)
        self.assertEqual(self.command["status"], "FINALIZING")
        self.store.complete("req-1", version, "ENROLL")
        self.assertEqual(self.command["status"], "COMPLETED")
        self.assertEqual(self.db.data["faceProfiles/employee-a"]["version"], 6)
        self.assertTrue(self.db.data["faceProfiles/employee-a"]["enabled"])
        self.assertNotIn("pendingCommandId", self.db.data["faceStations/GATE-01"])
        self.assertNotIn("faceEnrollmentLocks/employee-a", self.db.data)

    def test_old_session_expired_employee_or_nonadmin_never_claim_camera(self):
        cases = [
            ("sessionId", "old-session", "STATION_SESSION_CHANGED"),
            ("requestedAt", NOW - timedelta(minutes=6), "REQUEST_EXPIRED"),
            ("requestedAt", NOW + timedelta(seconds=31), "REQUEST_EXPIRED"),
            ("sampleCount", 9, "INVALID_SAMPLE_COUNT"),
        ]
        for key, value, code in cases:
            with self.subTest(key=key, value=value):
                self.setUp()
                self.command[key] = value
                self.assertIsNone(self.store.claim("req-1"))
                self.assertEqual(self.command["status"], "FAILED")
                self.assertEqual(self.command["errorCode"], code)
        for path, field, value, code in [
            ("employees/employee-a", "active", False, "EMPLOYEE_INACTIVE"),
            ("users/admin", "role", "EMPLOYEE", "REQUESTER_NOT_ADMIN"),
            ("users/admin", "active", False, "REQUESTER_NOT_ADMIN"),
        ]:
            with self.subTest(path=path, field=field):
                self.setUp()
                self.db.data[path][field] = value
                self.assertIsNone(self.store.claim("req-1"))
                self.assertEqual(self.command["errorCode"], code)

    def test_cancel_before_claim_or_finalize_never_overwrites_cancel_flag(self):
        self.command["cancelRequested"] = True
        self.assertIsNone(self.store.claim("req-1"))
        self.assertEqual(self.command["status"], "CANCELLED")
        self.setUp()
        self.store.claim("req-1")
        self.command["cancelRequested"] = True
        with self.assertRaisesRegex(CommandRejected, "CANCELLED"):
            self.store.begin_finalizing("req-1", 1)
        self.assertEqual(self.command["status"], "CAPTURING")
        self.assertEqual(self.db.data["faceProfiles/employee-a"]["version"], 5)

    def test_current_session_command_survives_slightly_ahead_laptop_clock(self):
        self.store.clock = lambda: NOW + timedelta(seconds=20)
        self.assertEqual(self.store.claim("req-1")["employeeId"], "employee-a")
        self.assertEqual(self.command["status"], "CAPTURING")

    def test_wrong_device_is_ignored_and_failure_preserves_reassigned_locks(self):
        self.command["deviceId"] = "CAM-02"
        self.assertIsNone(self.store.claim("req-1"))
        self.assertEqual(self.command["status"], "PENDING")
        self.command["deviceId"] = "CAM-01"
        self.db.data["faceStations/GATE-01"]["pendingCommandId"] = "req-2"
        self.db.data["faceEnrollmentLocks/employee-a"]["commandId"] = "req-2"
        self.store.finish("req-1", "FAILED", "COMMAND_LOCK_CHANGED")
        self.assertEqual(self.db.data["faceStations/GATE-01"]["pendingCommandId"], "req-2")
        self.assertEqual(self.db.data["faceEnrollmentLocks/employee-a"]["commandId"], "req-2")

    def test_employee_retired_before_commit_is_rechecked_and_not_completed(self):
        self.store.claim("req-1")
        version = self.store.begin_finalizing("req-1", 8)
        self.assertEqual(version, 9)
        self.db.data["employees/employee-a"]["active"] = False
        with self.assertRaisesRegex(CommandRejected, "EMPLOYEE_INACTIVE"):
            self.store.complete("req-1", version, "ENROLL")
        self.assertEqual(self.command["status"], "FINALIZING")
        self.assertEqual(self.db.data["faceProfiles/employee-a"]["version"], 5)

    def test_delete_disables_remote_profile_and_releases_both_locks(self):
        self.command.update(action="DELETE", sampleCount=0)
        self.store.claim("req-1")
        version = self.store.begin_finalizing("req-1", 0)
        self.store.complete("req-1", version, "DELETE")
        self.assertFalse(self.db.data["faceProfiles/employee-a"]["enabled"])
        self.assertEqual(self.command["status"], "COMPLETED")
        self.assertNotIn("faceEnrollmentLocks/employee-a", self.db.data)

    def test_station_lease_refuses_live_process_and_heartbeat_preserves_client_lock(self):
        self.db.data["faceStations/GATE-01"]["lastSeenAt"] = NOW
        self.store.clock = lambda: NOW + timedelta(minutes=5)
        with self.assertRaisesRegex(CommandRejected, "STATION_ALREADY_RUNNING"):
            self.store.start()
        self.store.heartbeat(True)
        self.assertEqual(self.db.data["faceStations/GATE-01"]["pendingCommandId"], "req-1")
        self.assertTrue(self.db.data["faceStations/GATE-01"]["cameraReady"])
        self.assertEqual(self.db.data["faceStations/GATE-01"]["lastSeenAt"], NOW)

    def test_close_releases_owned_session_for_immediate_restart(self):
        self.store.clock = lambda: NOW + timedelta(minutes=5)
        self.store.close()
        station = self.db.data["faceStations/GATE-01"]
        self.assertFalse(station["cameraReady"])
        self.assertNotIn("sessionId", station)
        self.assertEqual(station["lastSeenAt"], NOW)
        replacement = FirestoreEnrollmentStore(self.db, "GATE-01", "CAM-01", clock=lambda: NOW)
        replacement.start()
        self.assertEqual(station["sessionId"], replacement.session_id)

    def test_watch_station_document_only_enqueues_pending_id_changes(self):
        self.db.data["faceStations/GATE-01"] = {"sessionId": "old", "lastSeenAt": NOW - timedelta(minutes=2)}
        self.store.start()
        self.db.data["faceStations/GATE-01"]["pendingCommandId"] = "req-1"
        snapshot = self.store.station.get()
        self.db.watched([snapshot], [], NOW)
        self.db.watched([snapshot], [], NOW)
        self.assertEqual(self.store.next_command(), "req-1")
        self.assertIsNone(self.store.next_command())
        self.db.watched([snapshot], [], NOW)
        self.assertIsNone(self.store.next_command())

    def test_manual_mode_claim_rejects_invalid_or_prequeued_clicks(self):
        for extra in [
            {"captureMode": "AUTO"}, {"captureMode": "MANUAL"},
            {"captureRequestSeq": 0, "captureConsumedSeq": 0},
            {"captureRequestSeq": 1}, {"captureConsumedSeq": True},
            {"samplesCaptured": 1}, {"action": "DELETE", "sampleCount": 0},
        ]:
            with self.subTest(extra=extra):
                self.setUp()
                self.command.update(captureMode="MANUAL", captureRequestSeq=0, captureConsumedSeq=0,
                                    samplesCaptured=0)
                if extra == {"captureRequestSeq": 0, "captureConsumedSeq": 0}:
                    self.command.pop("captureMode")
                elif extra == {"captureMode": "MANUAL"}:
                    self.command.pop("captureRequestSeq")
                else:
                    self.command.update(extra)
                self.assertIsNone(self.store.claim("req-1"))
                self.assertEqual(self.command["status"], "FAILED")

    def test_manual_ack_is_atomic_validated_and_cannot_replay_or_forge_saved_count(self):
        self.command.update(captureMode="MANUAL", captureRequestSeq=0, captureConsumedSeq=0,
                            samplesCaptured=0)
        self.store.claim("req-1")
        self.assertIn("bấm Chụp ảnh", self.command["message"])
        self.command["captureRequestSeq"] = 1
        self.store.check_manual_request("req-1", 1)
        self.store.acknowledge_capture("req-1", 1, 0, "no_face")
        self.assertEqual(self.command["captureConsumedSeq"], 1)
        self.assertEqual(self.command["samplesCaptured"], 0)
        self.assertIn("Chưa thấy khuôn mặt", self.command["message"])
        with self.assertRaisesRegex(CommandRejected, "INVALID_CAPTURE_SEQUENCE"):
            self.store.acknowledge_capture("req-1", 1, 1, "quality_ok")
        self.command["captureRequestSeq"] = 2
        with self.assertRaisesRegex(CommandRejected, "INVALID_CAPTURE_SEQUENCE"):
            self.store.acknowledge_capture("req-1", 2, 2, "quality_ok")
        self.assertEqual(self.command["captureConsumedSeq"], 1)
        self.store.acknowledge_capture("req-1", 2, 1, "quality_ok")
        self.assertEqual((self.command["captureConsumedSeq"], self.command["samplesCaptured"]), (2, 1))
        self.command["captureRequestSeq"] = 3
        self.db.data["employees/employee-a"]["active"] = False
        with self.assertRaisesRegex(CommandRejected, "EMPLOYEE_INACTIVE"):
            self.store.check_manual_request("req-1", 3)
        with self.assertRaisesRegex(CommandRejected, "EMPLOYEE_INACTIVE"):
            self.store.acknowledge_capture("req-1", 3, 2, "quality_ok")
        self.assertEqual(self.command["captureConsumedSeq"], 2)

    def test_manual_finalizing_requires_ten_accepted_acknowledged_photos(self):
        self.command.update(captureMode="MANUAL", captureRequestSeq=0, captureConsumedSeq=0,
                            samplesCaptured=0)
        self.store.claim("req-1")
        with self.assertRaisesRegex(CommandRejected, "CAPTURE_INCOMPLETE"):
            self.store.begin_finalizing("req-1", 2)
        self.command.update(captureRequestSeq=10, captureConsumedSeq=10, samplesCaptured=10)
        self.assertEqual(self.store.begin_finalizing("req-1", 2), 6)

    def test_guided_six_plan_requires_exact_manual_schema_and_rejects_legacy_plan_fields(self):
        cases = [
            {"sampleCount": 6},
            {"sampleCount": 6, "capturePlan": "FRONT_LEFT_RIGHT_V1"},
            {"sampleCount": 6, "capturePlan": "OTHER", "captureMode": "MANUAL"},
            {"sampleCount": 10, "capturePlan": "FRONT_LEFT_RIGHT_V1", "captureMode": "MANUAL"},
            {"sampleCount": 10, "capturePlan": None},
            {"action": "DELETE", "sampleCount": 0, "capturePlan": "FRONT_LEFT_RIGHT_V1"},
            {"sampleCount": True},
        ]
        for extra in cases:
            with self.subTest(extra=extra):
                self.setUp()
                self.command.update(extra)
                if self.command.get("captureMode") == "MANUAL":
                    self.command.update(captureRequestSeq=0, captureConsumedSeq=0, samplesCaptured=0)
                self.assertIsNone(self.store.claim("req-1"))
                self.assertEqual(self.command["errorCode"], "INVALID_SAMPLE_COUNT")

    def test_guided_six_accepted_shots_complete_metadata_and_ack_bound_is_six(self):
        self.command.update(sampleCount=6, capturePlan="FRONT_LEFT_RIGHT_V1", captureMode="MANUAL",
                            captureRequestSeq=0, captureConsumedSeq=0, samplesCaptured=0)
        self.assertEqual(self.store.claim("req-1")["sampleCount"], 6)
        self.assertIn("Nhìn thẳng", self.command["message"])
        self.command.update(captureRequestSeq=5, captureConsumedSeq=5, samplesCaptured=5)
        with self.assertRaisesRegex(CommandRejected, "CAPTURE_INCOMPLETE"):
            self.store.begin_finalizing("req-1", 2)
        self.command["captureRequestSeq"] = 6
        with self.assertRaisesRegex(CommandRejected, "INVALID_CAPTURE_SEQUENCE"):
            self.store.acknowledge_capture("req-1", 6, 7, "quality_ok")
        self.store.acknowledge_capture("req-1", 6, 6, "quality_ok")
        self.assertIn("6/6", self.command["message"])
        self.command["captureRequestSeq"] = 7
        with self.assertRaisesRegex(CommandRejected, "INVALID_CAPTURE_SEQUENCE"):
            self.store.check_manual_request("req-1", 7)
        self.command["captureRequestSeq"] = 6
        version = self.store.begin_finalizing("req-1", 2)
        self.store.complete("req-1", version, "ENROLL")
        profile = self.db.data["faceProfiles/employee-a"]
        self.assertEqual((profile["sampleCount"], profile["capturePlan"]), (6, "FRONT_LEFT_RIGHT_V1"))

    def test_legacy_reenroll_removes_stale_guided_profile_metadata(self):
        self.db.data["faceProfiles/employee-a"]["capturePlan"] = "FRONT_LEFT_RIGHT_V1"
        self.store.claim("req-1")
        version = self.store.begin_finalizing("req-1", 2)
        self.store.complete("req-1", version, "ENROLL")
        profile = self.db.data["faceProfiles/employee-a"]
        self.assertEqual(profile["sampleCount"], 10)
        self.assertNotIn("capturePlan", profile)


class LocalFace:
    def __init__(self, path):
        self.path = path
        self.profiles = {"employee-a": {"profileVersion": 2, "centroid": [0.1], "sampleCount": 3}}
        self.inputs = None
        self._save_gallery(self.profiles)

    def _save_gallery(self, profiles):
        self.path.write_text(json.dumps(profiles), encoding="utf-8")

    def enroll(self, employee_id, files, version):
        self.inputs = tuple(files)
        profiles = dict(self.profiles)
        profiles[employee_id] = {"profileVersion": version, "centroid": [0.9], "sampleCount": len(files)}
        self._save_gallery(profiles)
        self.profiles = profiles


class FakeStore:
    def __init__(self):
        self.command = {"employeeId": "employee-a", "action": "ENROLL", "sampleCount": 10, "status": "PENDING"}
        self.remote_version = 5
        self.fail_metadata = False
        self.lost_ack = False
        self.progresses = []
        self.cancel = False

    def claim(self, request_id):
        self.command["status"] = "CAPTURING"
        return dict(self.command)

    def check_active(self, request_id):
        if self.cancel:
            raise CommandRejected("CANCELLED")

    def progress(self, request_id, count):
        self.progresses.append(count)

    def begin_finalizing(self, request_id, local_version):
        self.check_active(request_id)
        self.command["status"] = "FINALIZING"
        return max(self.remote_version, local_version) + 1

    def complete(self, request_id, version, action):
        if self.fail_metadata:
            raise OSError("simulated metadata failure")
        self.command.update(status="COMPLETED", profileVersion=version)
        if self.lost_ack:
            raise ConnectionError("commit succeeded; ACK lost")

    def result(self, request_id):
        return dict(self.command)

    def finish(self, request_id, status, code):
        self.command.update(status=status, errorCode=code)


class WorkerTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name)
        self.face = LocalFace(self.path / "gallery.json")
        self.store = FakeStore()
        self.stop = threading.Event()
        self.source = Mock()
        self.worker = EnrollmentWorker(self.store, self.face, self.source, self.path, self.stop, interval=0)
        self.fresh = tuple(f"fresh-{index}.jpg" for index in range(10))
        self.summary = CaptureSummary("employee-a", 10, self.fresh, 10, False)

    def test_enroll_only_uses_current_capture_files_and_advances_max_version(self):
        with patch("face_attendance.enrollment.capture_samples", return_value=self.summary):
            self.assertTrue(self.worker.run_one("req-1"))
        self.assertEqual(self.face.inputs, self.fresh)
        self.assertEqual(self.face.profiles["employee-a"]["profileVersion"], 6)
        self.assertEqual(self.store.command["status"], "COMPLETED")
        self.assertFalse(self.worker.journal.exists())
        self.source.close.assert_not_called()

    def test_partial_capture_never_enrolls_or_changes_previous_profile(self):
        partial = CaptureSummary("employee-a", 10, self.fresh[:2], 100, False)
        with patch("face_attendance.enrollment.capture_samples", return_value=partial):
            self.worker.run_one("req-1")
        self.assertIsNone(self.face.inputs)
        self.assertEqual(self.face.profiles["employee-a"]["profileVersion"], 2)
        self.assertEqual(self.store.command["errorCode"], "CAPTURE_INCOMPLETE")

    def test_cancel_during_capture_prevents_gallery_and_completes_cancelled(self):
        def capture(*args, **kwargs):
            self.store.cancel = True
            kwargs["stop_requested"]()
        with patch("face_attendance.enrollment.capture_samples", side_effect=capture):
            self.worker.run_one("req-1")
        self.assertEqual(self.store.command["status"], "CANCELLED")
        self.assertIsNone(self.face.inputs)

    def test_control_c_in_capture_stops_whole_station_and_cancels_command(self):
        stopped = CaptureSummary("employee-a", 10, self.fresh[:3], 3, True)
        with patch("face_attendance.enrollment.capture_samples", return_value=stopped):
            self.worker.run_one("req-1")
        self.assertTrue(self.stop.is_set())
        self.assertEqual(self.store.command["status"], "CANCELLED")
        self.assertIsNone(self.face.inputs)

    def test_metadata_failure_rolls_back_durable_gallery_and_reports_failed(self):
        before = (self.path / "gallery.json").read_bytes()
        self.store.fail_metadata = True
        with patch("face_attendance.enrollment.capture_samples", return_value=self.summary):
            self.worker.run_one("req-1")
        self.assertEqual((self.path / "gallery.json").read_bytes(), before)
        self.assertEqual(self.store.command["status"], "FAILED")
        self.assertFalse(self.worker.journal.exists())

    def test_lost_commit_ack_is_confirmed_and_never_rolled_back(self):
        self.store.lost_ack = True
        with patch("face_attendance.enrollment.capture_samples", return_value=self.summary):
            self.worker.run_one("req-1")
        self.assertEqual(self.face.profiles["employee-a"]["profileVersion"], 6)
        self.assertEqual(self.store.command["status"], "COMPLETED")
        self.assertFalse(self.worker.journal.exists())

    def test_delete_removes_local_feature_without_capturing(self):
        self.store.command["action"] = "DELETE"
        with patch("face_attendance.enrollment.capture_samples") as capture:
            self.worker.run_one("req-1")
        capture.assert_not_called()
        self.assertNotIn("employee-a", self.face.profiles)
        self.assertEqual(self.store.command["status"], "COMPLETED")

    def test_ambiguous_commit_preserves_journal_and_stops_for_safe_recovery(self):
        self.store.fail_metadata = True
        self.store.result = Mock(side_effect=ConnectionError("offline"))
        with patch("face_attendance.enrollment.capture_samples", return_value=self.summary):
            with self.assertRaisesRegex(RuntimeError, "Chưa xác nhận"):
                self.worker.run_one("req-1")
        self.assertTrue(self.worker.journal.exists())
        self.assertEqual(self.face.profiles["employee-a"]["profileVersion"], 6)
        self.store.result = lambda request_id: {"status": "FINALIZING"}
        self.worker.recover()
        self.assertEqual(self.face.profiles["employee-a"]["profileVersion"], 2)
        self.assertEqual(self.store.command["status"], "FAILED")
        self.assertFalse(self.worker.journal.exists())

    def test_restart_completed_commit_keeps_gallery_and_clears_journal(self):
        previous = deepcopy(self.face.profiles["employee-a"])
        self.face.enroll("employee-a", self.fresh, 6)
        self.worker._journal({"requestId": "req-1", "employeeId": "employee-a", "version": 6,
                              "previousProfile": previous, "action": "ENROLL"})
        self.store.command.update(status="COMPLETED", profileVersion=6)
        self.worker.recover()
        self.assertEqual(self.face.profiles["employee-a"]["profileVersion"], 6)
        self.assertFalse(self.worker.journal.exists())


if __name__ == "__main__":
    unittest.main()
