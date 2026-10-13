"""Local accessory classifiers, run before extracting an identity feature.

The models classify image content; their scores are not a guarantee that a
person is unobstructed or live. Missing models and invalid inference fail closed.
Importing this module needs only stdlib; OpenCV/numpy load on first inspection.
"""

from __future__ import annotations

import importlib
import math
from pathlib import Path
from typing import Any, Sequence

from .recognition import FaceRecognitionError, _model_sha256


ATTRIBUTE_MODEL_SHA256 = "1bf7c6453bec2fb28e0830f3a76dceb9ffd020124f87b28da5355940a7bc6e48"
HAT_MODEL_SHA256 = "6c76c0e8d88dd8c6e5671992e850a2ebb3c5a374edf211d4fea31a39f7f2a825"
HEAD_MODEL_SHA256 = "e6e303752f94163f6f96dcbb9f3713e695221327982711f206557935f728e581"


class AccessoryGuard:
    """Block masks, clear/tinted glasses and optionally hats with pinned models.

    ``threshold +/- uncertainty_margin`` is a rejection band. Named detections
    are at or above its upper edge; scores inside the band require another photo.
    Only scores below its lower edge permit identity extraction. No model score
    is interpreted as a calibrated confidence or a liveness result.
    """

    ATTRIBUTE_ORDER = ("left_eye_open", "right_eye_open", "eyeglasses", "mask", "sunglasses")

    def __init__(
        self,
        attribute_model: str | Path,
        hat_model: str | Path | None = None,
        *,
        head_model: str | Path | None = None,
        block_hat: bool = True,
        threshold: float = 0.5,
        uncertainty_margin: float = 0.1,
    ) -> None:
        if type(block_hat) is not bool:
            raise ValueError("block_hat must be a boolean")
        if block_hat and (hat_model is None or head_model is None):
            raise ValueError("Hat checking requires hat and head models")
        if (
            not isinstance(threshold, (int, float))
            or not isinstance(uncertainty_margin, (int, float))
            or isinstance(threshold, bool)
            or isinstance(uncertainty_margin, bool)
            or not math.isfinite(threshold)
            or not math.isfinite(uncertainty_margin)
            or not 0 < threshold < 1
            or not 0 <= uncertainty_margin < min(threshold, 1 - threshold)
        ):
            raise ValueError("Invalid accessory threshold or uncertainty margin")
        self.attribute_model = Path(attribute_model)
        self.block_hat = block_hat
        self.hat_model = Path(hat_model) if block_hat else None
        self.head_model = Path(head_model) if block_hat else None
        self.threshold = threshold
        self.uncertainty_margin = uncertainty_margin
        self._cv: Any = None
        self._np: Any = None
        self._attributes: Any = None
        self._headwear: Any = None
        self._heads: Any = None
        self._model_hashes: dict[str, str] = {}

    def check_models(self) -> dict[str, str]:
        """Verify actual model bytes for setup diagnostics without loading DNNs."""
        hashes = {
            "attributeSha256": _model_sha256(self.attribute_model),
        }
        expected = {"attributeSha256": ATTRIBUTE_MODEL_SHA256}
        if self.block_hat:
            hashes.update(hatSha256=_model_sha256(self.hat_model), headSha256=_model_sha256(self.head_model))
            expected.update(hatSha256=HAT_MODEL_SHA256, headSha256=HEAD_MODEL_SHA256)
        if hashes != expected:
            raise FaceRecognitionError("Accessory ONNX model checksum mismatch; download the pinned models")
        return hashes

    def _runtime(self) -> tuple[Any, Any]:
        if self._cv is None:
            hashes = self.check_models()
            try:
                cv = importlib.import_module("cv2")
                np = importlib.import_module("numpy")
                attributes = cv.dnn.readNetFromONNX(str(self.attribute_model))
                headwear = cv.dnn.readNetFromONNX(str(self.hat_model)) if self.block_hat else None
                heads = cv.dnn.readNetFromONNX(str(self.head_model)) if self.block_hat else None
                models = (attributes, headwear, heads) if self.block_hat else (attributes,)
                for model in models:
                    model.setPreferableBackend(cv.dnn.DNN_BACKEND_OPENCV)
                    model.setPreferableTarget(cv.dnn.DNN_TARGET_CPU)
            except Exception as exc:
                raise FaceRecognitionError("Cannot load local accessory checks (OpenCV/numpy and pinned ONNX models required)") from exc
            self._cv, self._np = cv, np
            self._attributes, self._headwear = attributes, headwear
            self._heads = heads
            self._model_hashes = hashes
        return self._cv, self._np

    def check_runtime(self) -> dict[str, str]:
        """Warm up CPU inference and validate output shapes using blank inputs.

        This checks installation readiness, not model accuracy or whether a real
        frame has a bare head. Zero-input scores are never used for acceptance.
        """
        self._runtime()
        try:
            self._attributes.setInput(self._attribute_blob(self._np.zeros((128, 128, 3), dtype=self._np.uint8)))
            self._probabilities(self._attributes.forward(), 5)
            if self.block_hat:
                blank_head = self._np.zeros((48, 48, 3), dtype=self._np.uint8)
                self._headwear.setInput(self._cv.dnn.blobFromImage(blank_head, 1 / 255, (48, 48), swapRB=True, crop=False))
                self._probabilities(self._headwear.forward(), 1)
                self._heads.setInput(self._cv.dnn.blobFromImage(blank_head, 1 / 255, (640, 480), swapRB=True, crop=False))
                self._head_candidates(self._heads.forward(), 640, 480)
        except FaceRecognitionError:
            raise
        except Exception as exc:
            raise FaceRecognitionError("Local accessory model warm-up failed") from exc
        return dict(self._model_hashes)

    def _probabilities(self, output: Any, count: int) -> tuple[float, ...]:
        try:
            values = self._np.asarray(output).reshape(-1)
            if len(values) != count:
                raise ValueError("unexpected classifier output size")
            scores = tuple(float(value) for value in values)
            if any(not math.isfinite(value) or not 0 <= value <= 1 for value in scores):
                raise ValueError("classifier output is not a finite probability")
        except Exception as exc:
            raise FaceRecognitionError("Accessory model returned invalid probabilities") from exc
        return scores

    def _attribute_blob(self, image: Any) -> Any:
        # Match the model author's face-bbox crop and zero-letterbox procedure.
        height, width = image.shape[:2]
        scale = min(128 / width, 128 / height)
        resized_width = 128 if width >= height else max(1, int(width * scale))
        resized_height = 128 if height >= width else max(1, int(height * scale))
        interpolation = self._cv.INTER_AREA if scale < 1 else self._cv.INTER_LINEAR
        resized = self._cv.resize(image, (resized_width, resized_height), interpolation=interpolation)
        padded = self._np.zeros((128, 128, 3), dtype=self._np.uint8)
        left, top = (128 - resized_width) // 2, (128 - resized_height) // 2
        padded[top:top + resized_height, left:left + resized_width] = resized
        return self._cv.dnn.blobFromImage(padded, 1 / 255, (128, 128), swapRB=True, crop=False)

    def _head_candidates(self, output: Any, image_width: int, image_height: int) -> list[tuple[float, float, float, float]]:
        predictions = self._np.asarray(output)
        if (
            predictions.shape != (1, 32, 6300)
            or not self._np.isfinite(predictions).all()
            or (predictions[:, 4:, :] < 0).any()
            or (predictions[:, 4:, :] > 1).any()
        ):
            raise FaceRecognitionError("Head detector returned invalid output")
        candidates, scores = [], []
        classes = predictions[0, 4:, :].argmax(axis=0)
        for index in self._np.flatnonzero((classes == 7) & (predictions[0, 11] > 0.35)):
            center_x, center_y, width, height = (float(value) for value in predictions[0, :4, index])
            if width <= 0 or height <= 0:
                raise FaceRecognitionError("Head detector returned an invalid bounding box")
            candidates.append([
                (center_x - width / 2) * image_width / 640,
                (center_y - height / 2) * image_height / 480,
                width * image_width / 640,
                height * image_height / 480,
            ])
            scores.append(float(predictions[0, 11, index]))
        if not candidates:
            return []
        retained = self._cv.dnn.NMSBoxes(candidates, scores, 0.35, 0.45)
        return [tuple(candidates[int(index)]) for index in self._np.asarray(retained).reshape(-1)]

    def _head_region(self, image: Any, bbox: Sequence[float]) -> tuple[Any | None, str | None]:
        image_height, image_width = image.shape[:2]
        self._heads.setInput(self._cv.dnn.blobFromImage(image, 1 / 255, (640, 480), swapRB=True, crop=False))
        heads = self._head_candidates(self._heads.forward(), image_width, image_height)
        face_x, face_y, face_width, face_height = bbox
        matching = []
        for x, y, width, height in heads:
            overlap_width = max(0, min(face_x + face_width, x + width) - max(face_x, x))
            overlap_height = max(0, min(face_y + face_height, y + height) - max(face_y, y))
            # The detected crown must belong to this face, not a nearby person.
            if overlap_width * overlap_height / (face_width * face_height) >= 0.65:
                matching.append((x, y, width, height))
        if len(matching) != 1:
            return None, "accessory_uncertain"
        x, y, width, height = matching[0]
        # Keep original (unclamped) detection coordinates. A head touching an
        # edge could hide the hat outside the frame and cannot prove no-hat.
        if x <= 1 or y <= 1 or x + width >= image_width - 1 or y + height >= image_height - 1:
            return None, "accessory_head_out_of_frame"
        left, top = math.floor(x), math.floor(y)
        right, bottom = math.ceil(x + width), math.ceil(y + height)
        return image[top:bottom, left:right], None

    def inspect(self, image: Any, face_bbox: Sequence[float]) -> str | None:
        """Return a rejection issue or None; throw when the guard is unavailable.

        ``face_bbox`` is YuNet's (x, y, width, height) in the corrected source
        frame. Attributes use this unaligned crop. The author's head detector
        locates the matching full head when hat checking is enabled; fixed expanded
        face crops can mistake background for headwear. All inputs are RGB NCHW
        float32 in [0,1]. Disabling hats skips both head models and the crown
        visibility requirement; mask and glasses checks still apply.
        """
        self._runtime()
        try:
            if (
                not isinstance(image, self._np.ndarray)
                or len(image.shape) != 3
                or image.shape[2] != 3
                or image.dtype != self._np.uint8
                or min(image.shape[:2]) < 1
                or len(face_bbox) != 4
            ):
                raise ValueError("invalid accessory input")
            bbox = tuple(float(value) for value in face_bbox)
            x, y, width, height = bbox
            if (
                not all(math.isfinite(value) for value in bbox)
                or width < 1 or height < 1
                or x < 0 or y < 0
                or x + width > image.shape[1] or y + height > image.shape[0]
            ):
                raise ValueError("invalid face bounding box")
            left, top, right, bottom = (round(value) for value in (x, y, x + width, y + height))
            face = image[top:bottom, left:right]
            if min(face.shape[:2]) < 1:
                raise ValueError("empty face crop")
            self._attributes.setInput(self._attribute_blob(face))
            attributes = self._probabilities(self._attributes.forward(), 5)
            upper = self.threshold + self.uncertainty_margin
            lower = self.threshold - self.uncertainty_margin
            face_scores = (("mask", attributes[3]), ("glasses", max(attributes[2], attributes[4])))
            for issue, score in face_scores:
                if score >= upper:
                    return issue
            if any(score >= lower for _, score in face_scores):
                return "accessory_uncertain"
            if not self.block_hat:
                return None
            head, head_issue = self._head_region(image, bbox)
            if head_issue is not None:
                return head_issue
            self._headwear.setInput(self._cv.dnn.blobFromImage(head, 1 / 255, (48, 48), swapRB=True, crop=False))
            hat = self._probabilities(self._headwear.forward(), 1)[0]
            if hat >= upper:
                return "hat"
            if hat >= lower:
                return "accessory_uncertain"
            return None
        except FaceRecognitionError:
            raise
        except Exception as exc:
            raise FaceRecognitionError("Local accessory image processing failed; face acceptance is blocked") from exc
