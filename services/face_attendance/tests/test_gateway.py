from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone, timedelta
import json
from pathlib import Path
import tempfile
import unittest

from face_attendance.events import Event, InvalidEvent
from face_attendance.storage import DemoBackend, Gateway, Outbox


REGISTRY = {
    "employees": {"a":{"active":True,"fullName":"A","fingerprintTemplateId":1,"fingerprintDeviceId":"GATE-01"},
                  "b":{"active":True,"fullName":"B","fingerprintTemplateId":2,"fingerprintDeviceId":"GATE-01"}},
    "fingerprintMappings": {"1":{"employeeId":"a","enabled":True},"2":{"employeeId":"b","enabled":True}},
    "faceProfiles": {"a":{"employeeId":"a","enabled":True,"version":1},
                     "b":{"employeeId":"b","enabled":True,"version":1}}
}


def event(kind, event_id, seconds=0, person="a"):
    stamp = datetime(2026, 1, 1, 1, tzinfo=timezone.utc)+timedelta(seconds=seconds)
    p = {"sourceEventId":event_id,"deviceId":"GATE-01" if kind=="FINGERPRINT" else "CAM-01",
         "stationId":"GATE-01","capturedAt":stamp.isoformat()}
    if kind == "FINGERPRINT":
        p.update(templateId=1 if person=="a" else 2, confidence=100)
    else:
        p.update(employeeId=person, profileVersion=1, faceSimilarity=0.85)
    return Event.parse(p, kind)


class GatewayTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name)
        self.registry = json.loads(json.dumps(REGISTRY))
        self.backend = DemoBackend(self.path/"demo.sqlite", self.registry)
        self.gateway = self.make_gateway()

    def make_gateway(self, backend=None):
        return Gateway(Outbox(self.path/"outbox.sqlite"), backend or self.backend,
                       station_id="GATE-01", device_id="GATE-01")

    def test_both_orders_only_one_attendance(self):
        first = self.gateway.submit(event("FACE", "face"))
        second = self.gateway.submit(event("FINGERPRINT", "finger", 1))
        self.assertEqual(first["attendanceId"], second["attendanceId"])
        self.assertEqual("DUPLICATE_DEMO", second["status"])
        self.assertEqual(1, self.backend.count())

    def test_concurrent_requests_from_separate_gateways(self):
        # Distinct gateway locks exercise the database transaction rather than a shared Python lock.
        gateways = [self.make_gateway() for _ in range(8)]
        with ThreadPoolExecutor(max_workers=8) as pool:
            results = list(pool.map(lambda i: gateways[i].submit(event(
                "FACE" if i%2 else "FINGERPRINT", f"simultaneous-{i}")), range(8)))
        self.assertEqual(1, len({r["attendanceId"] for r in results}))
        self.assertEqual(1, self.backend.count())

    def test_same_id_retried_after_restart_returns_exact_result(self):
        e = event("FINGERPRINT", "retry")
        result = self.gateway.submit(e)
        self.assertEqual(result, self.make_gateway().submit(e))
        self.assertEqual(1, self.backend.count())

    def test_event_id_cannot_change_immutable_payload(self):
        self.gateway.submit(event("FINGERPRINT", "collision"))
        with self.assertRaises(InvalidEvent):
            self.gateway.submit(event("FINGERPRINT", "collision", 1))

    def test_duplicate_does_not_extend_window(self):
        self.gateway.submit(event("FACE", "first"))
        self.gateway.submit(event("FINGERPRINT", "duplicate", 179))
        result = self.gateway.submit(event("FACE", "new", 181))
        self.assertEqual("ACCEPTED_DEMO", result["status"])
        self.assertEqual(2, self.backend.count())

    def test_180_second_boundary_and_later_checkout(self):
        self.gateway.submit(event("FACE", "first"))
        self.assertEqual("DUPLICATE_DEMO", self.gateway.submit(event("FINGERPRINT", "boundary",180))["status"])
        self.assertEqual("ACCEPTED_DEMO", self.gateway.submit(event("FACE", "checkout",8*3600))["status"])

    def test_late_source_matches_history_not_only_last_event(self):
        original = self.gateway.submit(event("FACE", "old"))
        self.gateway.submit(event("FACE", "later",3600))
        late = self.gateway.submit(event("FINGERPRINT", "late",1))
        self.assertEqual(original["attendanceId"], late["attendanceId"])
        self.assertEqual(2, self.backend.count())

    def test_different_people_are_not_blocked(self):
        self.gateway.submit(event("FACE", "a"))
        self.assertEqual("ACCEPTED_DEMO", self.gateway.submit(event("FACE", "b",person="b"))["status"])
        self.assertEqual(2, self.backend.count())

    def test_disabled_profile_and_employee_rejected(self):
        self.registry["faceProfiles"]["a"]["enabled"] = False
        self.assertEqual("REJECTED", self.gateway.submit(event("FACE", "disabled"))["status"])
        self.registry["employees"]["b"]["active"] = False
        self.assertEqual("REJECTED", self.gateway.submit(event("FINGERPRINT", "inactive",person="b"))["status"])
        self.assertEqual(0, self.backend.count())

    def test_changed_fingerprint_mapping_rejected(self):
        self.registry["employees"]["a"]["fingerprintTemplateId"] = 7
        self.assertEqual("FINGERPRINT_MAPPING_CHANGED", self.gateway.submit(
            event("FINGERPRINT", "changed"))["reason"])

    def test_outage_retains_both_sources_until_sync(self):
        class Offline:
            def deliver(self, *args):
                raise ConnectionError()
        gateway = self.make_gateway(Offline())
        self.assertEqual("QUEUED", gateway.submit(event("FACE", "off-face"))["status"])
        self.assertEqual("QUEUED", gateway.submit(event("FINGERPRINT", "off-finger",1))["status"])
        self.assertEqual(2, gateway.outbox.pending_count())
        recovered = self.make_gateway()
        self.assertEqual(2, len(recovered.flush()))
        self.assertEqual(0, recovered.outbox.pending_count())
        self.assertEqual(1, self.backend.count())

    def test_lost_cloud_ack_does_not_duplicate(self):
        backend = self.backend
        class LostAck:
            def deliver(self, e, at):
                backend.deliver(e, at)
                raise ConnectionError()
        self.make_gateway(LostAck()).submit(event("FACE", "lost"))
        self.make_gateway().flush()
        self.assertEqual(1, self.backend.count())

    def test_invalid_timestamps_and_face_scores(self):
        p = dict(event("FACE","invalid").payload)
        for bad in (float("nan"), float("inf"), True, 2):
            with self.assertRaises(InvalidEvent):
                Event.parse(dict(p,faceSimilarity=bad), "FACE")
        with self.assertRaises(InvalidEvent):
            Event.parse(dict(p,capturedAt="2026-01-01T01:00:00"), "FACE")
        low = Event.parse(dict(p,faceSimilarity=0.1), "FACE")
        self.assertEqual("FACE_NOT_CONFIDENT", self.gateway.submit(low)["reason"])
