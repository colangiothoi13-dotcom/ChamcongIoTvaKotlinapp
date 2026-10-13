"""Automatic face door tests; writes only fresh demo projects on a local emulator."""

from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
import os
from pathlib import Path
import re
import tempfile
import threading
import unittest
from unittest.mock import patch
import uuid

from face_attendance.backend import FirestoreBackend, create_firestore_client, validate_face_door
from face_attendance.events import Event
from face_attendance.storage import Gateway, Outbox


NOW = datetime(2026, 10, 13, 6, tzinfo=timezone.utc)
DIRECT = "spark-anonymous-v18-face-name-door-ttl"
GATEWAY = "spark-gateway-v19-face-name-door-ttl"
POLICY = {"enabled": True, "targetDeviceId": "GATE-01", "requiredFirmware": [DIRECT, GATEWAY]}


def event(kind="FACE", key="scan", age=0, person="a"):
    payload = {"sourceEventId": key, "deviceId": "GATE-01" if kind == "FINGERPRINT" else "CAM-01",
               "stationId": "GATE-01", "capturedAt": (NOW - timedelta(seconds=age)).isoformat()}
    if kind == "FACE":
        payload.update(employeeId=person, profileVersion=1, faceSimilarity=0.85)
    else:
        payload.update(templateId=1, confidence=100)
    return Event.parse(payload, kind)


class FaceDoorPolicyTests(unittest.TestCase):
    def test_default_and_explicit_disabled_never_require_firmware(self):
        self.assertEqual(validate_face_door(None), {"enabled": False})
        self.assertEqual(validate_face_door({"enabled": False}), {"enabled": False})

    def test_known_direct_and_gateway_versions_are_allowed_with_bounded_defaults(self):
        validated = validate_face_door(POLICY)
        self.assertEqual(validate_face_door(validated), validated)
        self.assertEqual(validated["requiredFirmware"], (DIRECT, GATEWAY))
        self.assertEqual(validated["maxEventAgeSeconds"], 15)
        self.assertEqual(validated["commandTTLSeconds"], 15)
        self.assertEqual(validated["heartbeatMaxAgeSeconds"], 90)
        one = dict(POLICY, requiredFirmware=DIRECT)
        self.assertEqual(validate_face_door(one)["requiredFirmware"], (DIRECT,))

    def test_unknown_or_untimed_firmware_cannot_be_configured(self):
        for firmware in ([], None, {}, 123, [DIRECT, None], [DIRECT, "old-version"],
                         "spark-anonymous-v16-tls-memory", "spark-gateway-v17-face-ready"):
            with self.subTest(firmware=firmware), self.assertRaises(ValueError):
                validate_face_door(dict(POLICY, requiredFirmware=firmware))

    def test_enabled_and_device_id_must_have_exact_types(self):
        for policy in ({}, True, {"enabled": 1}, {"enabled": "true"},
                       dict(POLICY, targetDeviceId="../GATE-01"), dict(POLICY, targetDeviceId=None)):
            with self.subTest(policy=policy), self.assertRaises(ValueError):
                validate_face_door(policy)

    def test_timing_limits_reject_boolean_float_negative_and_unbounded_values(self):
        for field, upper in (("maxEventAgeSeconds", 15), ("commandTTLSeconds", 15),
                             ("heartbeatMaxAgeSeconds", 90)):
            for value in (True, 1.0, "15", None, 0, -1, upper + 1):
                with self.subTest(field=field, value=value), self.assertRaises(ValueError):
                    validate_face_door(dict(POLICY, **{field: value}))


@unittest.skipUnless(os.environ.get("FIRESTORE_EMULATOR_HOST"), "Local Firestore emulator is not configured")
class FaceDoorFirestoreTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not re.fullmatch(r"(?:127\.0\.0\.1|localhost|\[::1\]):[0-9]{1,5}",
                            os.environ["FIRESTORE_EMULATOR_HOST"]):
            raise RuntimeError("Door tests require a loopback Firestore emulator")
        from google.cloud.firestore_v1.document import DocumentReference
        cls.reference_class = DocumentReference

    def setUp(self):
        self.client = create_firestore_client("demo-face-door-" + uuid.uuid4().hex[:12])
        self.addCleanup(self.client.close)
        self.clock = patch("face_attendance.backend._utc_now", return_value=NOW)
        self.clock.start()
        self.addCleanup(self.clock.stop)
        self.backend = FirestoreBackend(self.client, face_door=POLICY)
        self.command_ref = self.client.collection("deviceCommands").document("GATE-01")
        self.device_ref = self.client.collection("devices").document("GATE-01")
        batch = self.client.batch()
        for person in ("a", "b"):
            batch.set(self.client.collection("employees").document(person), {
                "active": True, "fullName": "Local test " + person,
                "fingerprintTemplateId": 1, "fingerprintDeviceId": "GATE-01"})
            batch.set(self.client.collection("faceProfiles").document(person), {
                "employeeId": person, "enabled": True, "version": 1})
        batch.set(self.client.collection("fingerprintMappings").document("1"), {
            "employeeId": "a", "enabled": True})
        batch.set(self.device_ref, {"deviceId": "GATE-01", "status": "ONLINE", "wifiStatus": "ONLINE",
                                    "lastHeartbeat": NOW, "firmwareVersion": DIRECT, "capabilities": ["door"]})
        batch.commit()

    def deliver(self, source=None, received_at=NOW):
        source = source or event()
        result = self.backend.deliver(source, received_at)
        receipt = self.client.collection("attendanceReceipts").document(source.key).get().to_dict()
        self.assertEqual(receipt["result"], result)
        self.assertEqual(receipt["digest"], source.digest)
        return result

    def assert_skip(self, status, source=None, received_at=NOW):
        result = self.deliver(source, received_at)
        self.assertEqual(result["status"], "ACCEPTED_SYNCED")
        self.assertEqual(result["doorCommandStatus"], status)
        self.assertFalse(self.command_ref.get().exists)
        attendance = self.client.collection("attendance").document(result["attendanceId"]).get().to_dict()
        self.assertEqual(attendance["doorCommandStatus"], status)
        self.assertEqual(len(list(self.client.collection("attendance").stream())), 1)

    def test_new_face_creates_one_atomic_door_command_attendance_and_receipt(self):
        # Also exercise a usable TTL against the emulator's real server-time
        # transform, rather than only asserting a mocked wall-clock payload.
        wall_now = datetime.now(timezone.utc)
        payload = dict(event().payload, capturedAt=wall_now.isoformat())
        source = Event.parse(payload, "FACE")
        self.device_ref.update({"lastHeartbeat": wall_now})
        with patch("face_attendance.backend._utc_now", return_value=wall_now):
            result = self.deliver(source)
        self.assertEqual(result["status"], "ACCEPTED_SYNCED")
        self.assertEqual(result["doorCommandStatus"], "REQUESTED")
        command = self.command_ref.get().to_dict()
        self.assertEqual(command["requestId"], "face-door-" + source.key)
        self.assertEqual(command["attendanceId"], source.key)
        self.assertEqual(command["source"], "FACE_ATTENDANCE")
        self.assertEqual(command["deviceId"], "GATE-01")
        self.assertEqual(command["type"], "OPEN_DOOR")
        self.assertEqual(command["status"], "REQUESTED")
        self.assertEqual(command["employeeName"], "Local test a")
        self.assertIs(command["applied"], True)
        self.assertIsInstance(command["createdAt"], datetime)
        self.assertEqual(command["expiresAt"], wall_now + timedelta(seconds=15))
        lifetime = (command["expiresAt"] - command["createdAt"]).total_seconds()
        self.assertGreater(lifetime, 0)
        self.assertLessEqual(lifetime, 15)
        attendance = self.client.collection("attendance").document(source.key).get().to_dict()
        self.assertEqual(attendance["doorCommandStatus"], "REQUESTED")
        self.assertEqual(len(list(self.client.collection("attendance").stream())), 1)

    def test_gateway_firmware_and_shorter_expiry_are_supported(self):
        self.device_ref.update({"firmwareVersion": GATEWAY})
        self.backend = FirestoreBackend(self.client, face_door=dict(POLICY, commandTTLSeconds=5))
        self.assertEqual(self.deliver()["doorCommandStatus"], "REQUESTED")
        self.assertEqual(self.command_ref.get().to_dict()["expiresAt"], NOW + timedelta(seconds=5))

    def test_face_exactly_15_seconds_old_can_open_but_older_delivery_cannot(self):
        self.assertEqual(self.deliver(event(age=15))["doorCommandStatus"], "REQUESTED")

    def test_late_outbox_uses_delivery_clock_not_original_received_at(self):
        self.assert_skip("SKIPPED_EXPIRED", event(age=15.001), received_at=NOW - timedelta(minutes=10))

    def test_future_face_outside_3_second_tolerance_never_opens(self):
        self.assert_skip("SKIPPED_CLOCK_SKEW", event(age=-3.001))

    def test_old_firmware_still_records_attendance_without_opening(self):
        self.device_ref.update({"firmwareVersion": "spark-anonymous-v16-tls-memory"})
        self.assert_skip("SKIPPED_UNSUPPORTED_FIRMWARE")

    def test_missing_device_is_unsupported_and_never_creates_command(self):
        self.device_ref.delete()
        self.assert_skip("SKIPPED_UNSUPPORTED_FIRMWARE")

    def test_stale_offline_and_invalid_heartbeats_skip_without_losing_attendance(self):
        baseline = self.device_ref.get().to_dict()
        changes = ({"lastHeartbeat": NOW - timedelta(seconds=90.001)}, {"status": "OFFLINE"},
                   {"wifiStatus": "OFFLINE"}, {"online": False}, {"active": False},
                   {"lastHeartbeat": "not-a-timestamp"}, {"lastHeartbeat": None},
                   {"lastHeartbeat": NOW + timedelta(seconds=3.001)}, {"deviceId": "OTHER"})
        for index, change in enumerate(changes):
            with self.subTest(change=change):
                self.device_ref.set(dict(baseline, **change))
                source = event(key=f"offline-{index}", age=index * 181)
                # Give each case a fresh capturedAt/delivery pair so the
                # duplicate window and expiry do not mask device validation.
                clock = NOW - timedelta(seconds=index * 181)
                device = self.device_ref.get().to_dict()
                if change.get("lastHeartbeat") == NOW + timedelta(seconds=3.001):
                    device["lastHeartbeat"] = clock + timedelta(seconds=3.001)
                elif change.get("lastHeartbeat") == NOW - timedelta(seconds=90.001):
                    device["lastHeartbeat"] = clock - timedelta(seconds=90.001)
                elif "lastHeartbeat" not in change:
                    device["lastHeartbeat"] = clock
                self.device_ref.set(device)
                with patch("face_attendance.backend._utc_now", return_value=clock):
                    result = self.deliver(source)
                self.assertEqual(result["doorCommandStatus"], "SKIPPED_OFFLINE")
                self.assertFalse(self.command_ref.get().exists)

    def test_door_capability_must_be_a_real_list_containing_door(self):
        self.device_ref.update({"capabilities": "door"})
        self.assert_skip("SKIPPED_NO_DOOR")

    def test_processing_requested_and_unapplied_terminal_commands_are_preserved(self):
        cases = ({"status": "REQUESTED", "applied": True}, {"status": "PROCESSING", "applied": True},
                 {"status": "COMPLETED", "applied": False}, {"status": "FAILED", "applied": False},
                 {"status": "UNKNOWN", "applied": True})
        for index, state in enumerate(cases):
            with self.subTest(state=state):
                original = dict(state, type="ENROLL_FINGERPRINT", requestId=f"finger-{index}",
                                employeeId="a", templateId=2)
                self.command_ref.set(original)
                source = event(key=f"busy-{index}", age=index * 181)
                clock = source.captured_at
                self.device_ref.update({"lastHeartbeat": clock})
                with patch("face_attendance.backend._utc_now", return_value=clock):
                    result = self.deliver(source)
                self.assertEqual(result["status"], "ACCEPTED_SYNCED")
                self.assertEqual(result["doorCommandStatus"], "SKIPPED_BUSY")
                self.assertEqual(self.command_ref.get().to_dict(), original)

    def test_applied_terminal_command_can_be_replaced(self):
        self.command_ref.set({"type": "TEST_LED_GREEN", "status": "COMPLETED", "applied": True})
        self.assertEqual(self.deliver()["doorCommandStatus"], "REQUESTED")
        self.assertEqual(self.command_ref.get().to_dict()["type"], "OPEN_DOOR")

    def test_receipt_replay_cannot_recreate_command_even_when_slot_was_removed(self):
        source = event()
        original = self.deliver(source)
        self.command_ref.delete()
        self.device_ref.update({"status": "OFFLINE"})
        self.assertEqual(self.deliver(source), original)
        self.assertFalse(self.command_ref.get().exists)
        self.assertEqual(len(list(self.client.collection("attendance").stream())), 1)

    def test_busy_skip_is_immutable_and_cannot_dispatch_later(self):
        self.command_ref.set({"status": "PROCESSING", "applied": True})
        source = event()
        original = self.deliver(source)
        self.assertEqual(original["doorCommandStatus"], "SKIPPED_BUSY")
        self.command_ref.delete()
        self.assertEqual(self.deliver(source), original)
        self.assertFalse(self.command_ref.get().exists)

    def test_duplicate_and_fingerprint_never_create_automatic_face_command(self):
        first = self.deliver(event("FINGERPRINT", key="finger"))
        self.assertEqual(first["status"], "ACCEPTED_SYNCED")
        self.assertNotIn("doorCommandStatus", first)
        self.assertFalse(self.command_ref.get().exists)
        duplicate = self.deliver(event(key="face-after-finger", age=-1))
        self.assertEqual(duplicate["status"], "DUPLICATE_SYNCED")
        self.assertEqual(duplicate["attendanceId"], first["attendanceId"])
        self.assertNotIn("doorCommandStatus", duplicate)
        self.assertFalse(self.command_ref.get().exists)
        self.assertEqual(len(list(self.client.collection("attendance").stream())), 1)

    def test_face_profile_rejection_never_creates_command(self):
        self.client.collection("faceProfiles").document("a").update({"enabled": False})
        result = self.deliver()
        self.assertEqual(result["status"], "REJECTED")
        self.assertEqual(result["reason"], "FACE_DISABLED")
        self.assertFalse(self.command_ref.get().exists)
        self.assertEqual(len(list(self.client.collection("attendance").stream())), 0)

    def test_disabled_policy_does_not_read_device_or_command(self):
        self.backend = FirestoreBackend(self.client)
        reads = []
        original = self.reference_class.get

        def observed(reference, *args, **kwargs):
            reads.append(reference.path)
            return original(reference, *args, **kwargs)

        with patch.object(self.reference_class, "get", observed):
            result = self.deliver()
        self.assertEqual(result["status"], "ACCEPTED_SYNCED")
        self.assertNotIn("doorCommandStatus", result)
        self.assertFalse(any(path.startswith(("devices/", "deviceCommands/")) for path in reads))

    def test_simultaneous_people_share_slot_without_overwriting_a_door_command(self):
        barrier = threading.Barrier(2)
        sources = [event(key="concurrent-a", person="a"), event(key="concurrent-b", person="b")]
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        second_client = create_firestore_client(self.client.project)
        self.addCleanup(second_client.close)
        backends = [self.backend, FirestoreBackend(second_client, face_door=POLICY)]
        gateways = [Gateway(Outbox(Path(directory.name) / f"gateway-{index}.sqlite"), backend,
                            station_id="GATE-01", device_id="GATE-01")
                    for index, backend in enumerate(backends)]

        def submit(index):
            barrier.wait(timeout=15)
            # SDK retries a conflict on the shared command document. Each
            # attempt rechecks that it has not become REQUESTED in the meantime.
            return gateways[index].submit(sources[index])

        with ThreadPoolExecutor(max_workers=2) as pool:
            results = list(pool.map(submit, range(2)))
        for gateway, source, result in zip(gateways, sources, results):
            self.assertIn(result["status"], {"ACCEPTED_SYNCED", "QUEUED"})
            if result["status"] == "QUEUED":
                # Heavy parallel emulator suites can exhaust the SDK's five
                # retries with a Transaction lock timeout. Exercise the real
                # service's durable retry path without changing the event ID,
                # capturedAt or digest; a permanently failing commit still
                # fails this test after the bounded recovery below.
                pending = gateway.outbox.pending()
                self.assertEqual(len(pending), 1)
                self.assertEqual(pending[0]["payload"], source.json)
                self.assertEqual(pending[0]["digest"], source.digest)
        self.assertLessEqual(len(list(self.client.collection("attendance").stream())), 2)
        for _ in range(3):
            for gateway in gateways:
                gateway.flush()
            results = [gateway.submit(source) for gateway, source in zip(gateways, sources)]
            if all(result["status"] != "QUEUED" for result in results):
                break
        self.assertTrue(all(gateway.outbox.pending_count() == 0 for gateway in gateways))
        self.assertTrue(all(result["status"] == "ACCEPTED_SYNCED" for result in results))
        self.assertEqual({result["doorCommandStatus"] for result in results}, {"REQUESTED", "SKIPPED_BUSY"})
        self.assertEqual(len(list(self.client.collection("attendance").stream())), 2)
        self.assertEqual(len(list(self.client.collection("attendanceReceipts").stream())), 2)
        requested = next(result for result in results if result["doorCommandStatus"] == "REQUESTED")
        self.assertEqual(self.command_ref.get().to_dict()["attendanceId"], requested["attendanceId"])


if __name__ == "__main__":
    unittest.main()
