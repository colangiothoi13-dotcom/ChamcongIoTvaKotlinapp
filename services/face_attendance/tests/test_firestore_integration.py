"""Optional real-SDK integration tests against a LOCAL Firestore emulator.

Set FIRESTORE_EMULATOR_HOST=127.0.0.1:8184 and install firebase-admin to run.
Every test uses a fresh demo project; no existing attendance or real project is
read, written or cleared. Without the environment variable these tests skip.
"""

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

from face_attendance.backend import FirestoreBackend, create_firestore_client
from face_attendance.events import Event
from face_attendance.storage import Gateway, Outbox


def event(kind, event_id, seconds=0, person="a", profile_version=1):
    stamp = datetime(2026, 1, 1, 1, tzinfo=timezone.utc) + timedelta(seconds=seconds)
    payload = {
        "sourceEventId": event_id,
        "deviceId": "GATE-01" if kind == "FINGERPRINT" else "CAM-01",
        "stationId": "GATE-01",
        "capturedAt": stamp.isoformat(),
    }
    if kind == "FINGERPRINT":
        payload.update(templateId=1 if person == "a" else 2, confidence=100)
    else:
        payload.update(employeeId=person, profileVersion=profile_version, faceSimilarity=0.85)
    return Event.parse(payload, kind)


@unittest.skipUnless(os.environ.get("FIRESTORE_EMULATOR_HOST"), "Local Firestore emulator is not configured")
class FirestoreIntegrationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        endpoint = os.environ["FIRESTORE_EMULATOR_HOST"]
        if not re.fullmatch(r"(?:127\.0\.0\.1|localhost|\[::1\]):[0-9]{1,5}", endpoint):
            raise RuntimeError("Integration tests require a loopback Firestore emulator")
        # Keep Google imports lazy: the default offline suite requires stdlib only.
        from google.cloud.firestore_v1.document import DocumentReference
        cls.document_reference_class = DocumentReference

    def setUp(self):
        # Keep the ID inside the normal 30-character project limit as well.
        self.project = "demo-face-attendance-" + uuid.uuid4().hex[:9]
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name)
        self.clients = []
        self.client = self.make_client()
        batch = self.client.batch()
        for employee_id, template_id in (("a", 1), ("b", 2)):
            batch.set(self.client.collection("employees").document(employee_id), {
                "active": True,
                "fullName": f"Integration demo {employee_id}",
                "fingerprintTemplateId": template_id,
                "fingerprintDeviceId": "GATE-01",
            })
            batch.set(self.client.collection("fingerprintMappings").document(str(template_id)), {
                "employeeId": employee_id, "enabled": True,
            })
            batch.set(self.client.collection("faceProfiles").document(employee_id), {
                "employeeId": employee_id, "enabled": True, "version": 1,
            })
        batch.commit()
        self.backend = FirestoreBackend(self.client)
        self.gateway = self.make_gateway("primary", self.backend)

    def make_client(self):
        client = create_firestore_client(self.project)
        self.clients.append(client)
        self.addCleanup(client.close)
        return client

    def make_gateway(self, name, backend=None):
        return Gateway(
            Outbox(self.path / f"{name}.sqlite"), backend or self.backend,
            station_id="GATE-01", device_id="GATE-01",
        )

    def documents(self, collection):
        return list(self.client.collection(collection).stream())

    def assert_receipt(self, source_event, result):
        snapshot = self.client.collection("attendanceReceipts").document(source_event.key).get()
        self.assertTrue(snapshot.exists)
        receipt = snapshot.to_dict()
        self.assertEqual(receipt["digest"], source_event.digest)
        self.assertEqual(receipt["result"], result)
        self.assertEqual(receipt["capturedAt"], source_event.captured_at)

    def test_simultaneous_fingerprint_and_face_from_distinct_gateways_are_atomic(self):
        second_gateway = self.make_gateway("second", FirestoreBackend(self.make_client()))
        sources = [event("FACE", "parallel-face"), event("FINGERPRINT", "parallel-finger", 1)]
        gateways = [self.gateway, second_gateway]
        barrier = threading.Barrier(2)

        def submit(index):
            barrier.wait(timeout=15)
            return gateways[index].submit(sources[index])

        with ThreadPoolExecutor(max_workers=2) as pool:
            results = list(pool.map(submit, range(2)))
        # The emulator can exhaust the SDK's five transactional retries with
        # ABORTED / Transaction lock timeout. QUEUED is a valid durable response,
        # but it must eventually produce the SAME single canonical attendance.
        for gateway, source_event, result in zip(gateways, sources, results):
            self.assertIn(result["status"], {"ACCEPTED_SYNCED", "DUPLICATE_SYNCED", "QUEUED"})
            if result["status"] == "QUEUED":
                pending = gateway.outbox.pending()
                self.assertEqual(len(pending), 1)
                self.assertEqual(pending[0]["payload"], source_event.json)
                self.assertEqual(pending[0]["digest"], source_event.digest)
        self.assertLessEqual(len(self.documents("attendance")), 1)
        for _ in range(3):
            # Recover without recreating events or changing their captured time.
            for gateway in gateways:
                gateway.flush()
            results = [gateway.submit(source_event) for gateway, source_event in zip(gateways, sources)]
            self.assertLessEqual(len(self.documents("attendance")), 1)
            if all(result["status"] != "QUEUED" for result in results):
                break
        self.assertTrue(all(gateway.outbox.pending_count() == 0 for gateway in gateways))
        self.assertEqual({result["status"] for result in results}, {"ACCEPTED_SYNCED", "DUPLICATE_SYNCED"})
        self.assertEqual(len({result["attendanceId"] for result in results}), 1)
        attendance = self.documents("attendance")
        self.assertEqual(len(attendance), 1)
        self.assertEqual(attendance[0].id, results[0]["attendanceId"])
        self.assertEqual(len(self.documents("attendanceReceipts")), 2)
        self.assertEqual(len(self.documents("attendanceGates")), 1)
        for source_event, result in zip(sources, results):
            self.assert_receipt(source_event, result)

    def test_late_source_matches_original_history_after_newer_attendance(self):
        early = event("FACE", "morning")
        later = event("FACE", "later", 3600)
        late = event("FINGERPRINT", "arrived-late", 1)
        original = self.gateway.submit(early)
        self.assertEqual(original["status"], "ACCEPTED_SYNCED")
        self.assertEqual(self.gateway.submit(later)["status"], "ACCEPTED_SYNCED")
        result = self.gateway.submit(late)
        self.assertEqual(result["status"], "DUPLICATE_SYNCED")
        self.assertEqual(result["attendanceId"], original["attendanceId"])
        self.assertEqual(len(self.documents("attendance")), 2)
        self.assert_receipt(late, result)

    def test_disabled_profile_is_rechecked_inside_firestore_transaction(self):
        # Construct backend BEFORE disabling: status cannot come from a cached registry.
        self.client.collection("faceProfiles").document("a").update({"enabled": False})
        source_event = event("FACE", "disabled-profile")
        observed_transactions = []
        original_get = self.document_reference_class.get

        def tracked_get(reference, *args, **kwargs):
            if reference.path == "faceProfiles/a":
                observed_transactions.append(kwargs.get("transaction"))
            return original_get(reference, *args, **kwargs)

        with patch.object(self.document_reference_class, "get", tracked_get):
            result = self.gateway.submit(source_event)
        self.assertEqual(result, {
            "sourceEventId": "disabled-profile", "status": "REJECTED", "reason": "FACE_DISABLED",
        })
        self.assertTrue(observed_transactions)
        self.assertTrue(all(transaction is not None for transaction in observed_transactions))
        self.assertEqual(len(self.documents("attendance")), 0)
        self.assert_receipt(source_event, result)

    def test_profile_version_and_employee_state_are_checked_on_delivery(self):
        self.client.collection("faceProfiles").document("a").update({"version": 2})
        stale = self.gateway.submit(event("FACE", "stale-profile"))
        self.assertEqual(stale["reason"], "FACE_PROFILE_CHANGED")
        self.client.collection("employees").document("a").update({"active": False})
        disabled = self.gateway.submit(event("FACE", "inactive-employee", profile_version=2))
        self.assertEqual(disabled["reason"], "EMPLOYEE_INACTIVE")
        self.assertEqual(len(self.documents("attendance")), 0)

    def test_cloud_receipt_replays_exact_result_without_original_local_outbox(self):
        source_event = event("FACE", "receipt-replay")
        original = self.gateway.submit(source_event)
        self.assertEqual(original["status"], "ACCEPTED_SYNCED")
        # State can change after the completed event; a receipt remains immutable.
        self.client.collection("faceProfiles").document("a").update({"enabled": False})
        clean_gateway = self.make_gateway("no-original-outbox", FirestoreBackend(self.make_client()))
        self.assertEqual(clean_gateway.outbox.pending_count(), 0)
        self.assertEqual(clean_gateway.submit(source_event), original)
        self.assertEqual(len(self.documents("attendance")), 1)
        self.assert_receipt(source_event, original)

    def test_cloud_commit_with_lost_ack_retries_from_durable_local_event(self):
        source_event = event("FINGERPRINT", "lost-ack")
        real_backend = self.backend

        class LostAck:
            def deliver(self, received_event, received_at):
                real_backend.deliver(received_event, received_at)
                raise ConnectionError("simulated lost response after cloud commit")

        first_gateway = self.make_gateway("lost-ack-outbox", LostAck())
        self.assertEqual(first_gateway.submit(source_event)["status"], "QUEUED")
        self.assertEqual(first_gateway.outbox.pending_count(), 1)
        recovered = self.make_gateway("lost-ack-outbox", FirestoreBackend(self.make_client()))
        results = recovered.flush()
        self.assertEqual(len(results), 1)
        self.assertEqual(results[0]["status"], "ACCEPTED_SYNCED")
        self.assertEqual(recovered.outbox.pending_count(), 0)
        self.assertEqual(len(self.documents("attendance")), 1)
        self.assert_receipt(source_event, results[0])

    def test_cloud_rejects_same_source_id_with_changed_payload_without_local_history(self):
        original_event = event("FACE", "immutable-cloud-event")
        original_result = self.gateway.submit(original_event)
        self.assertEqual(original_result["status"], "ACCEPTED_SYNCED")
        changed_event = event("FACE", "immutable-cloud-event", 1)
        clean_gateway = self.make_gateway("conflict-fresh-outbox", FirestoreBackend(self.make_client()))
        result = clean_gateway.submit(changed_event)
        self.assertEqual(result["reason"], "EVENT_ID_CONFLICT")
        self.assertEqual(len(self.documents("attendance")), 1)
        self.assert_receipt(original_event, original_result)


if __name__ == "__main__":
    unittest.main()
