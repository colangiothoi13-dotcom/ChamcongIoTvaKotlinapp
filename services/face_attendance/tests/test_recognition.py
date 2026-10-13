import json
import math
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock

from face_attendance.recognition import (
    FaceRecognitionError, FaceRecognizer, RecognitionResult, match_embedding,
)


def feature(x=1.0, y=0.0):
    return [x, y] + [0.0] * 126


def profile(vector, version=1):
    return {"centroid": vector, "profileVersion": version, "sampleCount": 3}


class MatchingTests(unittest.TestCase):
    def test_cosine_is_normalized_and_versions_follow_identity(self):
        result = match_embedding([5, 0], {
            "NV001": profile([10, 0], 7), "NV002": profile([0, 1]),
        })
        self.assertEqual(result.employee_id, "NV001")
        self.assertEqual(result.profile_version, 7)
        self.assertAlmostEqual(result.similarity, 1)

    def test_unknown_and_ambiguous_never_return_an_identity(self):
        self.assertEqual(match_embedding([1, 0], {"NV001": profile([0, 1])}).reason, "unknown")
        result = match_embedding([1, 0], {
            "NV001": profile([1, 0]), "NV002": profile([1, 0.1]),
        })
        self.assertEqual(result.reason, "ambiguous")
        self.assertIsNone(result.employee_id)

    def test_exact_tie_is_rejected_even_with_zero_margin(self):
        result = match_embedding([1, 0], {
            "NV001": profile([1, 0]), "NV002": profile([1, 0]),
        }, ambiguity_margin=0)
        self.assertEqual(result.reason, "ambiguous")

    def test_corrupt_features_fail_closed(self):
        for invalid in ([math.nan, 1], [0, 0], [math.inf, 0]):
            with self.assertRaises(FaceRecognitionError):
                match_embedding(invalid, {"NV001": profile([1, 0])})
        with self.assertRaises(FaceRecognitionError):
            match_embedding([1, 0], {"NV001": profile([1, 0, 0])})


class GalleryTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)
        self.detector = self.root / "yunet.onnx"
        self.recognizer = self.root / "sface.onnx"
        self.gallery = self.root / "private-gallery.json"
        self.detector.write_bytes(b"test detector bytes; no real inference")
        self.recognizer.write_bytes(b"test recognizer bytes; no real inference")

    def tearDown(self):
        self.directory.cleanup()

    def make(self):
        return FaceRecognizer(self.detector, self.recognizer, self.gallery)

    def stub_enrollment(self, recognizer, embeddings=None):
        recognizer.read_image = Mock(side_effect=lambda path: str(path))
        features = embeddings or [feature(1, 0), feature(1, 0.1), feature(1, -0.1)]
        recognizer._extract = Mock(side_effect=[
            (value, RecognitionResult(face_count=1, reason="feature_extracted"))
            for value in features
        ])

    def test_enrollment_atomic_gallery_and_metadata_without_biometrics(self):
        recognizer = self.make()
        self.stub_enrollment(recognizer)
        metadata = recognizer.enroll("NV001", ["front.jpg", "left.jpg", "right.jpg"], 1)
        self.assertEqual(metadata["sampleCount"], 3)
        self.assertNotIn("centroid", metadata)
        loaded = self.make()
        self.assertEqual(loaded.profiles["NV001"]["profileVersion"], 1)
        self.assertEqual(match_embedding(feature(), loaded.profiles).employee_id, "NV001")
        with self.assertRaises(ValueError):
            loaded.enroll("NV001", ["front.jpg", "left.jpg", "right.jpg"], 1)
        self.assertEqual(list(self.root.glob("*.tmp")), [])

    def test_bad_sample_does_not_replace_existing_gallery(self):
        recognizer = self.make()
        self.stub_enrollment(recognizer)
        recognizer.enroll("NV001", ["a.jpg", "b.jpg", "c.jpg"], 1)
        original_bytes = self.gallery.read_bytes()
        recognizer.read_image = Mock(return_value=object())
        recognizer._extract = Mock(return_value=(None, RecognitionResult(face_count=2, reason="multiple")))
        with self.assertRaisesRegex(FaceRecognitionError, "multiple"):
            recognizer.enroll("NV001", ["a.jpg", "b.jpg", "c.jpg"], 2)
        self.assertEqual(self.gallery.read_bytes(), original_bytes)
        self.assertEqual(recognizer.profiles["NV001"]["profileVersion"], 1)

    def test_duplicate_person_and_repeated_samples_are_rejected(self):
        recognizer = self.make()
        self.stub_enrollment(recognizer)
        recognizer.enroll("NV001", ["a.jpg", "b.jpg", "c.jpg"], 1)
        self.stub_enrollment(recognizer)
        with self.assertRaises(FaceRecognitionError):
            recognizer.enroll("NV002", ["a.jpg", "b.jpg", "c.jpg"], 1)
        with self.assertRaises(ValueError):
            recognizer.enroll("NV002", ["a.jpg", "a.jpg", "c.jpg"], 1)

    def test_gallery_rejects_model_changes_and_git_lfs_pointer(self):
        recognizer = self.make()
        self.stub_enrollment(recognizer)
        recognizer.enroll("NV001", ["a.jpg", "b.jpg", "c.jpg"], 1)
        self.recognizer.write_bytes(b"different model")
        with self.assertRaisesRegex(FaceRecognitionError, "mismatch"):
            self.make()
        self.detector.write_bytes(b"version https://git-lfs.github.com/spec/v1\n")
        with self.assertRaisesRegex(FaceRecognitionError, "Git LFS"):
            self.make()

    def test_corrupt_gallery_is_not_silently_treated_as_empty(self):
        self.gallery.write_text("{}", encoding="utf-8")
        with self.assertRaises(FaceRecognitionError):
            self.make()

    def test_detector_count_quality_and_real_feature_pipeline_contract(self):
        recognizer = self.make()
        recognizer.min_sharpness = 0
        recognizer._cv = object()
        recognizer._detector = Mock()
        recognizer._recognizer = Mock()
        recognizer._recognizer.feature.return_value.reshape.return_value.tolist.return_value = feature()
        recognizer.profiles = {"NV001": profile(feature())}
        image = Mock()
        image.shape = (480, 640, 3)
        face = [100, 100, 100, 100] + [150] * 10 + [0.99]
        recognizer._detector.detect.return_value = (1, [face])
        self.assertEqual(recognizer.recognize(image).employee_id, "NV001")
        recognizer._recognizer.alignCrop.assert_called_once_with(image, face)
        recognizer._detector.setInputSize.assert_called_once_with((640, 480))
        recognizer._detector.detect.return_value = (1, [face, face])
        self.assertEqual(recognizer.recognize(image).reason, "multiple")
        recognizer._detector.detect.return_value = (1, [[100, 100, 30, 30] + [115] * 10 + [0.99]])
        self.assertEqual(recognizer.recognize(image).reason, "low_quality")
        recognizer._detector.detect.return_value = (1, None)
        self.assertEqual(recognizer.recognize(image).reason, "no_face")

    def test_inference_error_never_returns_no_face(self):
        recognizer = self.make()
        recognizer._cv = object()
        recognizer._detector = Mock()
        recognizer._detector.detect.side_effect = RuntimeError("failed")
        image = Mock()
        image.shape = (480, 640, 3)
        with self.assertRaises(FaceRecognitionError):
            recognizer.recognize(image)

    def test_blurry_face_is_not_treated_as_empty_or_matched(self):
        recognizer = self.make()
        recognizer._cv = Mock()
        recognizer._cv.Laplacian.return_value.var.return_value = 12.0
        recognizer._detector = Mock()
        recognizer._recognizer = Mock()
        recognizer._detector.detect.return_value = (1, [[100, 100, 100, 100] + [150] * 10 + [0.99]])
        image = Mock()
        image.shape = (480, 640, 3)
        image.__getitem__ = Mock(return_value=object())
        result = recognizer.recognize(image)
        self.assertEqual((result.reason, result.face_count), ("low_quality", 1))
        self.assertEqual(result.quality_issue, "blurry")
        recognizer._recognizer.feature.assert_not_called()

    def test_low_quality_diagnostics_keep_reason_and_reject_before_feature_extraction(self):
        recognizer = self.make()
        recognizer.min_sharpness = 0
        recognizer._cv = Mock()
        recognizer._detector = Mock()
        recognizer._recognizer = Mock()
        image = Mock()
        image.shape = (480, 640, 3)
        cases = [
            ([100, 100, 79, 100] + [150] * 10 + [0.99], "face_too_small"),
            ([100, 390, 100, 100] + [150] * 10 + [0.99], "face_out_of_frame"),
            ([-1, 100, 100, 100] + [150] * 10 + [0.99], "face_out_of_frame"),
            ([100, 100, 100, 100] + [150] * 10 + [0.89], "low_confidence"),
            ([100, 100, 0, 100] + [150] * 10 + [0.99], "invalid_detection"),
            ([100, 100, 100, 100] + [float("nan")] * 10 + [0.99], "invalid_detection"),
            ([100, 100, 100, 100], "invalid_detection"),
        ]
        for face, issue in cases:
            with self.subTest(issue=issue, face=face[:4]):
                recognizer._detector.detect.return_value = (1, [face])
                result = recognizer.inspect_quality(image)
                self.assertEqual((result.reason, result.face_count, result.quality_issue), ("low_quality", 1, issue))
                self.assertEqual(result.to_dict()["qualityIssue"], issue)
        recognizer._recognizer.feature.assert_not_called()

    def test_quality_boundary_thresholds_are_unchanged_and_optional_metadata_is_absent(self):
        recognizer = self.make()
        recognizer._cv = Mock()
        recognizer._cv.Laplacian.return_value.var.return_value = 50.0
        recognizer._detector = Mock()
        recognizer._recognizer = Mock()
        recognizer._recognizer.feature.return_value.reshape.return_value.tolist.return_value = feature()
        image = Mock()
        image.shape = (480, 640, 3)
        image.__getitem__ = Mock(return_value=object())
        face = [560, 400, 80, 80] + [600] * 10 + [0.9]
        recognizer._detector.detect.return_value = (1, [face])
        result = recognizer.inspect_quality(image)
        self.assertEqual(result.reason, "quality_ok")
        self.assertIsNone(result.quality_issue)
        self.assertNotIn("qualityIssue", result.to_dict())
        recognizer._recognizer.feature.assert_called_once()

    def test_capture_quality_accepts_unregistered_face_without_identity_lookup(self):
        recognizer = self.make()
        recognizer._extract = Mock(return_value=(feature(), RecognitionResult(face_count=1, reason="feature_extracted")))
        self.assertEqual(recognizer.inspect_quality(object()).reason, "quality_ok")
        self.assertEqual(recognizer.profiles, {})

    def test_quality_only_worker_does_not_read_or_replace_gallery(self):
        self.gallery.write_bytes(b"an existing gallery is intentionally not loaded for capture")
        recognizer = FaceRecognizer(self.detector, self.recognizer, None)
        self.assertEqual(recognizer.profiles, {})
        with self.assertRaises(FaceRecognitionError):
            recognizer.enroll("a", ["a.jpg", "b.jpg", "c.jpg"], 1)
        self.assertEqual(self.gallery.read_bytes(), b"an existing gallery is intentionally not loaded for capture")


if __name__ == "__main__":
    unittest.main()
