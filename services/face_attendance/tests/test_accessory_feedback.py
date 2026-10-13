from datetime import datetime, timedelta, timezone
import unittest

from face_attendance.enrollment import GUIDED_CAPTURE_PLAN, _capture_message
from face_attendance.feedback import FaceBlockFeedback, accessory_block_message
from face_attendance.recognition import RecognitionResult


class AccessoryFeedbackTests(unittest.TestCase):
    def blocked(self, issue):
        return RecognitionResult(face_count=1, reason="low_quality", quality_issue=issue)

    def test_known_blocks_name_the_item_and_do_not_advance_guided_pose(self):
        words = {"mask": "khẩu trang", "hat": "mũ", "glasses": "kính",
                 "accessory_uncertain": "Chưa xác định", "accessory_head_out_of_frame": "toàn bộ đầu"}
        for issue, word in words.items():
            for saved, guidance in ((0, "Nhìn thẳng"), (2, "sang trái"), (4, "sang phải")):
                with self.subTest(issue=issue, saved=saved):
                    message = _capture_message(saved, "low_quality", issue, sample_count=6,
                                               capture_plan=GUIDED_CAPTURE_PLAN)
                    self.assertIn(word, message)
                    self.assertIn(f"{saved}/6", message)
                    self.assertIn(guidance, message)
                    self.assertIn("ảnh 1/2", message)
        self.assertIsNone(accessory_block_message("blurry"))
        self.assertIsNone(accessory_block_message("untrusted_text"))

    def test_status_is_fresh_without_duplicate_console_messages_or_private_fields(self):
        messages = []
        now = [datetime(2026, 10, 13, tzinfo=timezone.utc)]
        feedback = FaceBlockFeedback(messages.append, enabled=True, clock=lambda: now[0])
        result = self.blocked("mask")
        feedback.observe(result)
        first = feedback.get()
        now[0] += timedelta(seconds=2)
        feedback.observe(result)
        second = feedback.get()
        self.assertEqual(len(messages), 1)
        self.assertEqual(second["status"], "FACE_BLOCKED")
        self.assertGreater(second["updatedAt"], first["updatedAt"])
        self.assertEqual(set(second), {"status", "qualityIssue", "message", "updatedAt", "accessoryGateEnabled"})
        self.assertTrue(second["accessoryGateEnabled"])
        second["message"] = "changed by reader"
        self.assertNotEqual(feedback.get()["message"], second["message"])
        self.assertEqual(result.face_count, 1)
        self.assertEqual(result.quality_issue, "mask")

    def test_item_change_and_clear_face_have_one_notification_each(self):
        messages = []
        feedback = FaceBlockFeedback(messages.append, enabled=True)
        feedback.observe(self.blocked("mask"))
        feedback.observe(self.blocked("glasses"))
        clear = RecognitionResult(face_count=1, reason="unknown")
        feedback.observe(clear)
        feedback.observe(clear)
        self.assertEqual([item["status"] for item in messages], ["FACE_BLOCKED", "FACE_BLOCKED", "FACE_READY"])
        self.assertIsNone(feedback.get()["qualityIssue"])

    def test_genuine_empty_clears_block_and_blurry_frame_does_not_claim_ready(self):
        messages = []
        feedback = FaceBlockFeedback(messages.append, enabled=True)
        feedback.observe(self.blocked("hat"))
        feedback.observe(RecognitionResult(face_count=1, reason="low_quality", quality_issue="blurry"))
        self.assertEqual(feedback.get()["status"], "FACE_WAITING")
        self.assertNotIn("FACE_READY", [item["status"] for item in messages])
        feedback.observe(self.blocked("hat"))
        feedback.observe(RecognitionResult(reason="no_face"))
        feedback.observe(RecognitionResult(reason="no_face"))
        self.assertEqual(messages[-1]["status"], "FACE_CLEARED")
        self.assertEqual(feedback.get()["status"], "IDLE")
        self.assertEqual(len([item for item in messages if item["status"] == "FACE_CLEARED"]), 1)

    def test_unavailable_and_registration_reset_old_block(self):
        feedback = FaceBlockFeedback(lambda value: None, enabled=True)
        feedback.observe(self.blocked("mask"))
        feedback.reset("UNAVAILABLE", "Không xử lý được camera.")
        self.assertIsNone(feedback.get()["qualityIssue"])
        self.assertEqual(feedback.get()["status"], "UNAVAILABLE")
        feedback.reset("REGISTERING", "Đang đăng ký.")
        self.assertEqual(feedback.get()["status"], "REGISTERING")


if __name__ == "__main__":
    unittest.main()
