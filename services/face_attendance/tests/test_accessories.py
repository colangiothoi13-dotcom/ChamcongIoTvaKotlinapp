from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

from face_attendance.accessories import (
    AccessoryGuard, ATTRIBUTE_MODEL_SHA256, HAT_MODEL_SHA256, HEAD_MODEL_SHA256,
)
from face_attendance.recognition import FaceRecognitionError

try:
    import cv2
    import numpy as np
except ImportError:
    cv2 = np = None


class AccessoryInstallationTests(unittest.TestCase):
    def test_invalid_thresholds_do_not_silently_change_policy(self):
        for threshold, margin in ((0, .1), (1, .1), (.5, -.1), (.5, .5), (float("nan"), .1), (.5, float("inf")), (True, .1), (None, .1), (.5, "0.1")):
            with self.subTest(threshold=threshold, margin=margin), self.assertRaises(ValueError):
                AccessoryGuard("attributes.onnx", "headwear.onnx", head_model="head.onnx", threshold=threshold, uncertainty_margin=margin)

    def test_missing_models_are_lazy_but_inspection_cannot_accept(self):
        with tempfile.TemporaryDirectory() as directory:
            guard = AccessoryGuard(Path(directory) / "missing-a.onnx", Path(directory) / "missing-h.onnx", head_model=Path(directory) / "missing-d.onnx")
            with self.assertRaisesRegex(FaceRecognitionError, "not found"):
                guard.inspect(None, (0, 0, 10, 10))

    def test_model_verification_is_pinned_and_rejects_replaced_weights(self):
        guard = AccessoryGuard("attributes.onnx", "headwear.onnx", head_model="head.onnx")
        with patch("face_attendance.accessories._model_sha256", side_effect=[ATTRIBUTE_MODEL_SHA256, HAT_MODEL_SHA256, HEAD_MODEL_SHA256]):
            self.assertEqual(guard.check_models(), {"attributeSha256": ATTRIBUTE_MODEL_SHA256, "hatSha256": HAT_MODEL_SHA256, "headSha256": HEAD_MODEL_SHA256})
        with patch("face_attendance.accessories._model_sha256", side_effect=[ATTRIBUTE_MODEL_SHA256, "0" * 64, HEAD_MODEL_SHA256]):
            with self.assertRaisesRegex(FaceRecognitionError, "checksum"):
                guard.check_models()

    def test_hat_policy_requires_a_real_boolean_and_models_only_when_enabled(self):
        for value in (None, 0, 1, "false", [], {}):
            with self.subTest(value=value), self.assertRaisesRegex(ValueError, "boolean"):
                AccessoryGuard("attributes.onnx", block_hat=value)
        for hat, head in ((None, None), ("hat.onnx", None), (None, "head.onnx")):
            with self.subTest(hat=hat, head=head), self.assertRaisesRegex(ValueError, "requires"):
                AccessoryGuard("attributes.onnx", hat, head_model=head)
        guard = AccessoryGuard("attributes.onnx", block_hat=False)
        self.assertIsNone(guard.hat_model)
        self.assertIsNone(guard.head_model)

    def test_disabled_hat_check_verifies_only_attribute_model_even_with_missing_hat_paths(self):
        guard = AccessoryGuard("attributes.onnx", "missing-hat.onnx", head_model="missing-head.onnx", block_hat=False)
        with patch("face_attendance.accessories._model_sha256", return_value=ATTRIBUTE_MODEL_SHA256) as checksum:
            self.assertEqual(guard.check_models(), {"attributeSha256": ATTRIBUTE_MODEL_SHA256})
            checksum.assert_called_once_with(Path("attributes.onnx"))
        with patch("face_attendance.accessories._model_sha256", return_value="0" * 64):
            with self.assertRaisesRegex(FaceRecognitionError, "checksum"):
                guard.check_models()

    def test_disabled_hat_runtime_loads_only_attributes_and_caches_model_validation(self):
        guard = AccessoryGuard("attributes.onnx", block_hat=False)
        fake_cv, fake_np, attributes = Mock(), Mock(), Mock()
        fake_cv.dnn.readNetFromONNX.return_value = attributes
        with patch("face_attendance.accessories._model_sha256", return_value=ATTRIBUTE_MODEL_SHA256) as checksum:
            with patch("face_attendance.accessories.importlib.import_module", side_effect=[fake_cv, fake_np]):
                self.assertEqual(guard._runtime(), (fake_cv, fake_np))
                self.assertEqual(guard._runtime(), (fake_cv, fake_np))
        checksum.assert_called_once_with(Path("attributes.onnx"))
        fake_cv.dnn.readNetFromONNX.assert_called_once_with("attributes.onnx")
        attributes.setPreferableBackend.assert_called_once()
        attributes.setPreferableTarget.assert_called_once()
        self.assertIsNone(guard._heads)
        self.assertIsNone(guard._headwear)


@unittest.skipIf(np is None, "Optional OpenCV/numpy runtime is not installed")
class MaskAndGlassesOnlyPolicyTests(unittest.TestCase):
    def setUp(self):
        self.guard = AccessoryGuard("attributes.onnx", block_hat=False)
        self.guard._cv, self.guard._np = cv2, np
        self.guard._attributes = Mock()
        # These would reject a bare face or fail if accidentally invoked.
        self.guard._heads = Mock()
        self.guard._heads.forward.side_effect = RuntimeError("whole-head check must not run")
        self.guard._headwear = Mock()
        self.guard._headwear.forward.return_value = np.array([[.99]], dtype=np.float32)
        self.image = np.zeros((480, 640, 3), dtype=np.uint8)
        self.bbox = (220, 180, 120, 120)
        self.scores((.99, .99, .01, .01, .01))

    def scores(self, attributes):
        self.guard._attributes.forward.return_value = np.array([attributes], dtype=np.float32)

    def assert_head_models_unused(self):
        self.guard._heads.setInput.assert_not_called()
        self.guard._heads.forward.assert_not_called()
        self.guard._headwear.setInput.assert_not_called()
        self.guard._headwear.forward.assert_not_called()

    def test_bare_face_accepts_without_full_head_even_with_high_hat_output(self):
        for bbox in (self.bbox, (0, 0, 120, 120), (520, 360, 120, 120)):
            with self.subTest(bbox=bbox):
                self.assertIsNone(self.guard.inspect(self.image, bbox))
        self.assert_head_models_unused()

    def test_mask_clear_glasses_and_sunglasses_still_block(self):
        for attributes, expected in (
            ((.99, .99, .01, .91, .01), "mask"),
            ((.99, .99, .91, .01, .01), "glasses"),
            ((.99, .99, .01, .01, .91), "glasses"),
        ):
            with self.subTest(expected=expected, attributes=attributes):
                self.scores(attributes)
                self.assertEqual(self.guard.inspect(self.image, self.bbox), expected)
        self.assert_head_models_unused()

    def test_uncertain_mask_or_glasses_still_rejects_and_invalid_inference_fails_closed(self):
        for attributes in ((.99, .99, .01, .5, .01), (.99, .99, .5, .01, .01), (.99, .99, .01, .01, .5)):
            self.scores(attributes)
            self.assertEqual(self.guard.inspect(self.image, self.bbox), "accessory_uncertain")
        self.scores((.99, .99, .01, float("nan"), .01))
        with self.assertRaisesRegex(FaceRecognitionError, "probabilities"):
            self.guard.inspect(self.image, self.bbox)
        self.assert_head_models_unused()

    def test_warmup_validates_attributes_only(self):
        self.guard._model_hashes = {"attributeSha256": ATTRIBUTE_MODEL_SHA256}
        self.assertEqual(self.guard.check_runtime(), {"attributeSha256": ATTRIBUTE_MODEL_SHA256})
        self.assert_head_models_unused()
        self.guard._attributes.forward.return_value = np.array([float("nan")] * 5)
        with self.assertRaises(FaceRecognitionError):
            self.guard.check_runtime()


@unittest.skipIf(np is None, "Optional OpenCV/numpy runtime is not installed")
class AccessoryPolicyTests(unittest.TestCase):
    def setUp(self):
        self.guard = AccessoryGuard("attributes.onnx", "headwear.onnx", head_model="head.onnx")
        self.guard._cv = cv2
        self.guard._np = np
        self.guard._attributes = Mock()
        self.guard._headwear = Mock()
        self.guard._heads = Mock()
        self.set_scores()
        self.set_heads([(200, 130, 160, 180, .9)])
        self.image = np.zeros((480, 640, 3), dtype=np.uint8)
        self.bbox = (220, 180, 120, 120)

    def set_scores(self, attributes=(.99, .99, .01, .01, .01), hat=.01):
        self.guard._attributes.forward.return_value = np.array([attributes], dtype=np.float32)
        self.guard._headwear.forward.return_value = np.array([[hat]], dtype=np.float32)

    def set_heads(self, boxes):
        predictions = np.zeros((1, 32, 6300), dtype=np.float32)
        for index, (x, y, width, height, score) in enumerate(boxes):
            predictions[0, :4, index] = (x + width / 2, y + height / 2, width, height)
            predictions[0, 11, index] = score
        self.guard._heads.forward.return_value = predictions

    def test_bare_face_and_closed_eyes_are_not_accessories(self):
        self.assertIsNone(self.guard.inspect(self.image, self.bbox))
        self.set_scores(attributes=(.01, .01, .01, .01, .01))
        self.assertIsNone(self.guard.inspect(self.image, self.bbox))

    def test_mask_hat_clear_glasses_and_sunglasses_are_each_blocked(self):
        cases = [
            ((.99, .99, .01, .91, .01), .01, "mask"),
            ((.99, .99, .01, .01, .01), .91, "hat"),
            ((.99, .99, .91, .01, .01), .01, "glasses"),
            ((.99, .99, .01, .01, .91), .01, "glasses"),
        ]
        for attributes, hat, expected in cases:
            with self.subTest(expected=expected, attributes=attributes):
                self.set_scores(attributes, hat)
                self.assertEqual(self.guard.inspect(self.image, self.bbox), expected)

    def test_uncertainty_is_rejected_and_never_resolved_as_bare(self):
        for score in (.4, .5, .59):
            self.set_scores(hat=score)
            self.assertEqual(self.guard.inspect(self.image, self.bbox), "accessory_uncertain")
        self.set_scores(hat=.6)
        self.assertEqual(self.guard.inspect(self.image, self.bbox), "hat")
        self.set_scores(hat=.39)
        self.assertIsNone(self.guard.inspect(self.image, self.bbox))

    def test_clipped_crown_or_side_brim_is_rejected_before_hat_inference(self):
        for head in ((200, 0, 160, 310, .9), (0, 130, 360, 180, .9), (200, 130, 440, 180, .9), (200, 130, 160, 350, .9)):
            with self.subTest(head=head):
                self.set_heads([head])
                self.assertEqual(self.guard.inspect(self.image, self.bbox), "accessory_head_out_of_frame")
        self.assertEqual(self.guard._attributes.forward.call_count, 4)
        self.guard._headwear.forward.assert_not_called()

    def test_head_missing_other_person_or_ambiguous_never_accepts(self):
        for boxes in ([], [(20, 30, 80, 100, .9)], [(190, 120, 190, 190, .9), (215, 165, 125, 140, .9)]):
            self.set_heads(boxes)
            if len(boxes) > 1:
                # Two candidates must survive NMS to test ambiguous association.
                with patch.object(cv2.dnn, "NMSBoxes", return_value=np.array([0, 1])):
                    self.assertEqual(self.guard.inspect(self.image, self.bbox), "accessory_uncertain")
            else:
                self.assertEqual(self.guard.inspect(self.image, self.bbox), "accessory_uncertain")
        self.guard._headwear.forward.assert_not_called()

    def test_head_decoder_uses_class_argmax_scaling_and_nms(self):
        self.set_heads([(200, 130, 160, 180, .9), (202, 132, 160, 180, .8)])
        boxes = self.guard._head_candidates(self.guard._heads.forward(), 1280, 960)
        self.assertEqual(len(boxes), 1)
        np.testing.assert_allclose(boxes[0], (400, 260, 320, 360))
        predictions = self.guard._heads.forward.return_value.copy()
        predictions[0, 4, :] = .99  # Another class wins each candidate.
        self.assertEqual(self.guard._head_candidates(predictions, 640, 480), [])

    def test_head_decoder_rejects_unexpected_shape_nonfinite_or_invalid_scores(self):
        for output in (np.zeros((1, 31, 6300)), np.full((1, 32, 6300), float("nan")), np.full((1, 32, 6300), 1.1)):
            self.guard._heads.forward.return_value = output
            with self.assertRaisesRegex(FaceRecognitionError, "invalid output"):
                self.guard.inspect(self.image, self.bbox)

    def test_obvious_face_accessory_is_blocked_even_when_head_missing(self):
        self.set_heads([])
        self.set_scores(attributes=(.99, .99, .91, .01, .01))
        self.assertEqual(self.guard.inspect(self.image, self.bbox), "glasses")
        self.guard._heads.forward.assert_not_called()

    def test_invalid_probabilities_or_shape_fail_closed(self):
        for output in ([.1, .2], [float("nan")] * 5, [float("inf")] * 5, [-.1] * 5, [1.1] * 5):
            self.guard._attributes.forward.return_value = np.array(output)
            with self.subTest(output=output), self.assertRaisesRegex(FaceRecognitionError, "probabilities"):
                self.guard.inspect(self.image, self.bbox)
        self.set_scores()
        for output in ([], [float("nan")], [1.1], [-.1], [.1, .2]):
            self.guard._headwear.forward.return_value = np.array(output)
            with self.subTest(output=output), self.assertRaisesRegex(FaceRecognitionError, "probabilities"):
                self.guard.inspect(self.image, self.bbox)

    def test_inference_exception_never_becomes_a_passing_photo(self):
        self.guard._attributes.forward.side_effect = RuntimeError("model failure")
        with self.assertRaisesRegex(FaceRecognitionError, "acceptance is blocked"):
            self.guard.inspect(self.image, self.bbox)

    def test_malformed_images_or_bounding_boxes_fail_closed(self):
        for image, bbox in ((None, self.bbox), (np.zeros((100, 100)), self.bbox), (self.image.astype(np.float32), self.bbox), (self.image, (0, 0, 0, 10)), (self.image, (0, 0, float("nan"), 10)), (self.image, (640, 0, 10, 10))):
            with self.subTest(bbox=bbox), self.assertRaises(FaceRecognitionError):
                self.guard.inspect(image, bbox)

    def test_attribute_preprocessing_keeps_aspect_zero_padding_and_rgb_range(self):
        face = np.full((4, 8, 3), [20, 40, 100], dtype=np.uint8)
        blob = self.guard._attribute_blob(face)
        self.assertEqual(blob.shape, (1, 3, 128, 128))
        self.assertEqual(blob.dtype, np.float32)
        self.assertTrue(np.all(blob[:, :, :32, :] == 0))
        self.assertTrue(np.all(blob[:, :, 96:, :] == 0))
        np.testing.assert_allclose(blob[0, :, 64, 64], np.array([100, 40, 20]) / 255, atol=1e-6)

    def test_headwear_uses_whole_head_crop_rgb_and_expected_model_size(self):
        self.image[:] = [20, 40, 100]
        self.guard.inspect(self.image, self.bbox)
        blob = self.guard._headwear.setInput.call_args.args[0]
        self.assertEqual(blob.shape, (1, 3, 48, 48))
        np.testing.assert_allclose(blob[0, :, 24, 24], np.array([100, 40, 20]) / 255, atol=1e-6)

    def test_runtime_warmup_validates_all_outputs_without_acceptance(self):
        self.guard._model_hashes = {"attributeSha256": ATTRIBUTE_MODEL_SHA256, "hatSha256": HAT_MODEL_SHA256, "headSha256": HEAD_MODEL_SHA256}
        self.assertEqual(self.guard.check_runtime()["hatSha256"], HAT_MODEL_SHA256)
        self.guard._headwear.forward.return_value = np.array([float("nan")])
        with self.assertRaises(FaceRecognitionError):
            self.guard.check_runtime()


if __name__ == "__main__":
    unittest.main()
