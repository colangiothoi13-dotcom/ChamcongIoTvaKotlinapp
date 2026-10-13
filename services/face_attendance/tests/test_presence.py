import unittest

from face_attendance.presence import PresenceGate
from face_attendance.recognition import RecognitionResult


EMPTY = RecognitionResult(reason="no_face", face_count=0)


def match(employee="NV001", version=1, score=0.8):
    return RecognitionResult(employee, score, version, 1, "matched")


class PresenceGateTests(unittest.TestCase):
    def setUp(self):
        self.gate = PresenceGate(stable_frames=3, empty_frames=2)

    def arm(self):
        self.assertIsNone(self.gate.observe(EMPTY))
        self.assertIsNone(self.gate.observe(EMPTY))
        self.assertTrue(self.gate.armed)

    def emit(self, employee="NV001"):
        self.assertIsNone(self.gate.observe(match(employee, score=0.9)))
        self.assertIsNone(self.gate.observe(match(employee, score=0.8)))
        return self.gate.observe(match(employee, score=0.85))

    def test_startup_face_never_arms_without_fresh_empty(self):
        for _ in range(20):
            self.assertIsNone(self.gate.observe(match()))
        self.assertFalse(self.gate.armed)
        self.arm()
        self.assertEqual(self.emit(), {
            "employeeId": "NV001", "profileVersion": 1, "faceSimilarity": 0.8,
        })

    def test_one_visit_emits_once_even_if_identity_changes(self):
        self.arm()
        self.assertIsNotNone(self.emit())
        for employee in ("NV001", "NV002", "NV001"):
            for _ in range(10):
                self.assertIsNone(self.gate.observe(match(employee)))
        self.arm()
        self.assertIsNotNone(self.emit("NV002"))

    def test_unknown_low_quality_multiple_and_error_are_not_empty(self):
        self.arm()
        self.emit()
        for result in (
            RecognitionResult(face_count=1, reason="unknown"),
            RecognitionResult(face_count=1, reason="low_quality"),
            RecognitionResult(face_count=2, reason="multiple"),
            RecognitionResult(reason="invalid_image"),
            RecognitionResult(face_count=1, reason="ambiguous"),
        ):
            for _ in range(4):
                self.assertIsNone(self.gate.observe(result))
            self.assertFalse(self.gate.armed)

    def test_error_breaks_empty_sequence_and_requires_departure_again(self):
        self.gate.observe(EMPTY)
        self.gate.reset()
        self.gate.observe(EMPTY)
        self.assertFalse(self.gate.armed)
        self.gate.observe(match())
        self.gate.observe(EMPTY)
        self.assertFalse(self.gate.armed)
        self.gate.observe(EMPTY)
        self.assertTrue(self.gate.armed)

    def test_unstable_identity_version_and_unknown_break_candidate(self):
        self.arm()
        self.gate.observe(match())
        self.gate.observe(match())
        self.gate.observe(match(version=2))
        self.assertIsNone(self.gate.observe(match(version=2)))
        self.gate.observe(RecognitionResult(face_count=1, reason="unknown"))
        self.assertIsNone(self.gate.observe(match(version=2)))
        self.assertIsNone(self.gate.observe(match(version=2)))
        self.assertEqual(self.gate.observe(match(version=2))["profileVersion"], 2)

    def test_stale_empty_frame_does_not_arm(self):
        for _ in range(5):
            self.gate.observe(EMPTY, fresh=False)
        self.assertFalse(self.gate.armed)
        self.arm()
        self.gate.observe(match(), fresh=False)
        self.assertFalse(self.gate.armed)

    def test_invalid_image_disarms_previously_armed_gate(self):
        self.arm()
        self.gate.observe(RecognitionResult(reason="invalid_image"))
        self.assertFalse(self.gate.armed)
        for _ in range(4):
            self.assertIsNone(self.gate.observe(match()))


if __name__ == "__main__":
    unittest.main()
