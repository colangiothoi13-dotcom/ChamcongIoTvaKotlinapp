import json
import math
from pathlib import Path
import tempfile
from types import SimpleNamespace
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

    def make(self, **kwargs):
        return FaceRecognizer(self.detector, self.recognizer, self.gallery, **kwargs)

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
        self.assertEqual(result.to_dict()["sharpnessScore"], 12.0)
        self.assertEqual(result.to_dict()["sharpnessRequired"], 50.0)
        recognizer._recognizer.feature.assert_not_called()

    def test_sharpness_metrics_accept_zero_reject_nonfinite_and_preserve_legacy_result(self):
        legacy = RecognitionResult(face_count=1, reason="quality_ok").to_dict()
        self.assertNotIn("sharpnessScore", legacy)
        self.assertNotIn("sharpnessRequired", legacy)
        self.assertEqual(RecognitionResult(sharpness_score=0, sharpness_required=50).to_dict()["sharpnessScore"], 0)
        for score, required in ((-1, 50), (math.nan, 50), (math.inf, 50), (True, 50),
                                (12, 0), (12, math.inf), (12, None), (None, 50), (1e10, 50)):
            with self.subTest(score=score, required=required), self.assertRaises(ValueError):
                RecognitionResult(sharpness_score=score, sharpness_required=required)

    def test_successful_capture_and_identity_match_preserve_same_sharpness_measurement(self):
        recognizer = self.make()
        recognizer.profiles = {"NV001": profile(feature())}
        recognizer._extract = Mock(return_value=(feature(), RecognitionResult(
            face_count=1, reason="feature_extracted", sharpness_score=73.25, sharpness_required=50,
            sharpness_metric="FACE_MAX128_LAPLACIAN_V1")))
        for result in (recognizer.inspect_quality(object()), recognizer.recognize(object())):
            self.assertEqual((result.sharpness_score, result.sharpness_required), (73.25, 50))
            self.assertEqual(result.to_dict()["sharpnessMetric"], "FACE_MAX128_LAPLACIAN_V1")
        self.assertEqual(recognizer.recognize(object()).employee_id, "NV001")

    def test_nonfinite_measured_sharpness_still_rejects_but_never_publishes_nan(self):
        recognizer = self.make()
        recognizer._cv = Mock()
        recognizer._cv.Laplacian.return_value.var.return_value = math.nan
        recognizer._detector = Mock()
        recognizer._recognizer = Mock()
        recognizer._detector.detect.return_value = (1, [[100, 100, 100, 100] + [150] * 10 + [0.99]])
        image = Mock()
        image.shape = (480, 640, 3)
        image.__getitem__ = Mock(return_value=object())
        result = recognizer.inspect_quality(image)
        self.assertEqual((result.reason, result.quality_issue), ("low_quality", "blurry"))
        self.assertNotIn("sharpnessScore", result.to_dict())
        recognizer._recognizer.feature.assert_not_called()

    def test_sharpness_normalization_size_and_metric_reject_malformed_configuration(self):
        for size in (True, 0, 79, 513, 128.0, "128"):
            with self.subTest(size=size), self.assertRaises(ValueError):
                self.make(sharpness_max_size=size)
        for metric in ("FACE_MAX999_LAPLACIAN_V1", "FACE_MAX0128_LAPLACIAN_V1", "raw", True):
            with self.subTest(metric=metric), self.assertRaises(ValueError):
                RecognitionResult(sharpness_score=12, sharpness_required=50, sharpness_metric=metric)
        with self.assertRaises(ValueError):
            RecognitionResult(sharpness_metric="FACE_MAX128_LAPLACIAN_V1")

    def _pixel_quality_recognizer(self, cv, size, maximum=128):
        recognizer = self.make(sharpness_max_size=maximum)
        recognizer._cv = cv
        recognizer._detector = Mock()
        recognizer._recognizer = Mock()
        recognizer._recognizer.feature.return_value.reshape.return_value.tolist.return_value = feature()
        recognizer._detector.detect.return_value = (1, [[20, 20, size, size] + [size / 2] * 10 + [.99]])
        return recognizer

    def test_same_clear_face_edges_remain_accepted_near_and_far_but_strong_blur_does_not(self):
        try:
            import cv2 as cv
            import numpy as np
        except ImportError:
            self.skipTest("Optional OpenCV/numpy pixel-quality regression")
        # A synthetic facial-edge pattern is not an identity or real employee
        # image. Its scaled copies expose raw-variance dependence on ROI size.
        base = np.full((128, 128), 120, dtype=np.uint8)
        cv.ellipse(base, (64, 64), (44, 58), 0, 0, 360, 190, -1)
        cv.ellipse(base, (46, 49), (12, 5), 0, 0, 360, 30, -1)
        cv.ellipse(base, (82, 49), (12, 5), 0, 0, 360, 30, -1)
        cv.line(base, (64, 58), (60, 81), 45, 3)
        cv.ellipse(base, (64, 92), (20, 5), 0, 0, 180, 40, 3)
        base = cv.GaussianBlur(base, (0, 0), .6)
        blurred = cv.GaussianBlur(base, (0, 0), 5)
        for size in (128, 256, 384):
            for sample, expected in ((base, "quality_ok"), (blurred, "low_quality")):
                with self.subTest(size=size, expected=expected):
                    gray = cv.resize(sample, (size, size), interpolation=cv.INTER_CUBIC)
                    image = np.full((size + 40, size + 40, 3), 120, dtype=np.uint8)
                    image[20:20 + size, 20:20 + size] = cv.cvtColor(gray, cv.COLOR_GRAY2BGR)
                    before = image.copy()
                    recognizer = self._pixel_quality_recognizer(cv, size)
                    result = recognizer.inspect_quality(image)
                    self.assertEqual(result.reason, expected)
                    self.assertEqual(result.sharpness_metric, "FACE_MAX128_LAPLACIAN_V1")
                    self.assertEqual(result.sharpness_required, 50)
                    self.assertTrue(np.array_equal(image, before))
                    if expected == "quality_ok":
                        self.assertGreaterEqual(result.sharpness_score, 50)
                        recognizer._recognizer.alignCrop.assert_called_once()
                    else:
                        self.assertLess(result.sharpness_score, 50)
                        recognizer._recognizer.feature.assert_not_called()
        # The legacy raw metric rejects the large scaled clear image. This is
        # the near/far inconsistency normalized measurement intentionally fixes.
        legacy = self._pixel_quality_recognizer(cv, 384, maximum=None)
        image[20:404, 20:404] = cv.cvtColor(cv.resize(base, (384, 384), interpolation=cv.INTER_CUBIC), cv.COLOR_GRAY2BGR)
        self.assertEqual(legacy.inspect_quality(image).quality_issue, "blurry")

    def test_small_face_roi_is_never_upscaled_for_sharpness(self):
        try:
            import cv2 as cv
            import numpy as np
        except ImportError:
            self.skipTest("Optional OpenCV/numpy pixel-quality regression")
        proxy = SimpleNamespace(cvtColor=cv.cvtColor, Laplacian=cv.Laplacian, resize=Mock(wraps=cv.resize),
                                COLOR_BGR2GRAY=cv.COLOR_BGR2GRAY, CV_64F=cv.CV_64F, INTER_AREA=cv.INTER_AREA)
        for size in (80, 96, 128):
            recognizer = self._pixel_quality_recognizer(proxy, size)
            image = np.zeros((size + 40, size + 40, 3), dtype=np.uint8)
            result = recognizer.inspect_quality(image)
            self.assertEqual((result.quality_issue, result.sharpness_score), ("blurry", 0))
        proxy.resize.assert_not_called()

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

    def _accessory_pipeline(self, issue=None):
        guard = Mock()
        guard.inspect.return_value = issue
        recognizer = self.make(accessory_guard=guard, min_sharpness=0)
        recognizer._cv = Mock()
        recognizer._detector = Mock()
        recognizer._recognizer = Mock()
        recognizer._recognizer.feature.return_value.reshape.return_value.tolist.return_value = feature()
        recognizer.profiles = {"NV001": profile(feature())}
        recognizer._detector.detect.return_value = (1, [[100, 100, 100, 100] + [150] * 10 + [.99]])
        image = Mock()
        image.shape = (480, 640, 3)
        return recognizer, guard, image

    def test_accessory_policy_blocks_registration_and_attendance_before_identity(self):
        for issue in ("mask", "hat", "glasses", "accessory_uncertain", "accessory_head_out_of_frame"):
            with self.subTest(issue=issue):
                recognizer, guard, image = self._accessory_pipeline(issue)
                for operation in (recognizer.inspect_quality, recognizer.recognize):
                    result = operation(image)
                    self.assertEqual((result.reason, result.face_count, result.quality_issue), ("low_quality", 1, issue))
                    self.assertFalse(result.matched)
                guard.inspect.assert_called_with(image, (100., 100., 100., 100.))
                recognizer._recognizer.alignCrop.assert_not_called()
                recognizer._recognizer.feature.assert_not_called()

    def test_bare_face_with_guard_still_matches_and_gallery_hashes_are_unchanged(self):
        recognizer, guard, image = self._accessory_pipeline()
        self.assertEqual(recognizer.inspect_quality(image).reason, "quality_ok")
        self.assertEqual(recognizer.recognize(image).employee_id, "NV001")
        self.assertEqual(recognizer.model_hashes, self.make().model_hashes)
        self.assertEqual(guard.inspect.call_count, 2)

    def test_unavailable_accessory_check_fails_closed_without_reclassifying_frame(self):
        recognizer, guard, image = self._accessory_pipeline()
        guard.inspect.side_effect = FaceRecognitionError("Accessory model unavailable")
        for operation in (recognizer.inspect_quality, recognizer.recognize):
            with self.assertRaisesRegex(FaceRecognitionError, "Accessory model unavailable"):
                operation(image)
        recognizer._recognizer.feature.assert_not_called()

    def test_enrollment_rechecks_accessories_and_does_not_write_blocked_features(self):
        recognizer, guard, image = self._accessory_pipeline("mask")
        recognizer.read_image = Mock(return_value=image)
        with self.assertRaisesRegex(FaceRecognitionError, "low_quality \\(mask\\)"):
            recognizer.enroll("NV002", ["a.jpg", "b.jpg", "c.jpg"], 1)
        self.assertFalse(self.gallery.exists())
        self.assertNotIn("NV002", recognizer.profiles)
        recognizer._recognizer.feature.assert_not_called()

    def test_disabled_accessory_guard_keeps_legacy_pipeline(self):
        recognizer, _, image = self._accessory_pipeline("mask")
        recognizer.accessory_guard = None
        self.assertEqual(recognizer.inspect_quality(image).reason, "quality_ok")
        self.assertEqual(recognizer.recognize(image).employee_id, "NV001")


if __name__ == "__main__":
    unittest.main()
