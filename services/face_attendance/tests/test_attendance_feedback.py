from datetime import datetime, timezone
import unittest

from face_attendance.attendance_feedback import FaceAttendanceFeedback
from face_attendance.events import Event
from face_attendance.recognition import RecognitionResult


class AttendanceFeedbackTests(unittest.TestCase):
    def setUp(self):
        self.now = datetime(2026, 10, 13, tzinfo=timezone.utc)
        self.feedback = FaceAttendanceFeedback(clock=lambda: self.now)
        payload = {"sourceEventId": "face-one", "deviceId": "CAM-01", "stationId": "GATE-01",
                   "capturedAt": self.now.isoformat(), "employeeId": "NV001", "profileVersion": 1,
                   "faceSimilarity": 0.9}
        self.event = Event.parse(payload, "FACE")

    def test_scan_feedback_and_confirmed_name_are_separate_from_accessory_frame_data(self):
        self.feedback.observe(RecognitionResult(employee_id="NV001", profile_version=1,
                                               similarity=0.9, face_count=1, reason="matched"))
        value = self.feedback.get()
        self.assertEqual(value["scan"]["status"], "CHECKING")
        self.assertNotIn("employeeId", value["scan"])
        self.feedback.begin()
        self.feedback.record_result(self.event, {"status": "ACCEPTED_SYNCED", "employeeName": " Nguyễn Văn A "})
        value = self.feedback.get()
        self.assertEqual(value["attendance"]["employeeName"], "Nguyễn Văn A")
        self.assertEqual(value["attendance"]["status"], "ACCEPTED")
        self.assertEqual(value["scan"]["status"], "ACCEPTED")

    def test_duplicate_says_modalities_were_merged_and_does_not_recreate_attendance(self):
        self.feedback.begin()
        self.feedback.record_result(self.event, {"status": "DUPLICATE_SYNCED", "employeeName": "A"})
        value = self.feedback.get()
        self.assertEqual(value["attendance"]["status"], "DUPLICATE")
        self.assertIn("gộp", value["attendance"]["message"])

    def test_pending_and_rejected_results_never_claim_attendance_was_saved(self):
        for result, expected in (({"status": "QUEUED"}, "PENDING"),
                                 ({"status": "REJECTED", "reason": "FACE_DISABLED"}, "REJECTED")):
            self.feedback.begin()
            self.feedback.record_result(self.event, result)
            value = self.feedback.get()["attendance"]
            self.assertEqual(value["status"], expected)
            self.assertNotIn("Đã ghi công trên Firebase", value["message"])

    def test_non_face_result_is_ignored_and_untrusted_control_characters_are_removed(self):
        fingerprint = Event.parse({"sourceEventId": "fp", "deviceId": "GATE-01", "stationId": "GATE-01",
                                   "capturedAt": self.now.isoformat(), "templateId": 1, "confidence": 100},
                                  "FINGERPRINT")
        self.feedback.record_result(fingerprint, {"status": "ACCEPTED_SYNCED", "employeeName": "not face"})
        self.assertEqual(self.feedback.get()["attendance"]["status"], "NONE")
        self.feedback.record_result(self.event, {"status": "ACCEPTED_SYNCED", "employeeName": "A\nB"})
        self.assertEqual(self.feedback.get()["attendance"]["employeeName"], "AB")


if __name__ == "__main__":
    unittest.main()
