"""Local YuNet/SFace recognition; importing this module requires only stdlib.

Models and biometric gallery belong in the ignored local data directory. A
cosine score is a comparison score, not a probability or a liveness check.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
import hashlib
import importlib
import json
import math
import os
from pathlib import Path
import tempfile
from typing import Any, Iterable, Mapping, Sequence


class FaceRecognitionError(RuntimeError):
    """Model, gallery or image processing failure (never an empty frame)."""


@dataclass(frozen=True)
class RecognitionResult:
    employee_id: str | None = None
    similarity: float | None = None
    profile_version: int | None = None
    face_count: int = 0
    reason: str = "no_face"
    quality_issue: str | None = None

    @property
    def matched(self) -> bool:
        return self.reason == "matched" and self.employee_id is not None

    def to_dict(self) -> dict[str, Any]:
        value = {
            "employeeId": self.employee_id,
            "faceSimilarity": self.similarity,
            "profileVersion": self.profile_version,
            "faceCount": self.face_count,
            "reason": self.reason,
        }
        if self.quality_issue is not None:
            value["qualityIssue"] = self.quality_issue
        return value


def _normalized(values: Sequence[float]) -> tuple[float, ...]:
    vector = tuple(float(value) for value in values)
    if not vector or not all(math.isfinite(value) for value in vector):
        raise FaceRecognitionError("Face feature contains invalid values")
    norm = math.sqrt(sum(value * value for value in vector))
    if norm <= 1e-12 or not math.isfinite(norm):
        raise FaceRecognitionError("Face feature has zero or invalid length")
    return tuple(value / norm for value in vector)


def match_embedding(
    embedding: Sequence[float],
    profiles: Mapping[str, Mapping[str, Any]],
    *,
    threshold: float = 0.5,
    ambiguity_margin: float = 0.1,
) -> RecognitionResult:
    """Compare normalized SFace feature to each employee's normalized centroid."""
    if not 0 < threshold <= 1 or not 0 <= ambiguity_margin <= 2:
        raise ValueError("Invalid cosine threshold or ambiguity margin")
    if not profiles:
        return RecognitionResult(face_count=1, reason="no_profiles")
    feature = _normalized(embedding)
    rankings: list[tuple[float, str, int]] = []
    for employee_id, profile in profiles.items():
        centroid = _normalized(profile["centroid"])
        if len(centroid) != len(feature):
            raise FaceRecognitionError("Gallery feature dimension does not match SFace model")
        score = max(-1.0, min(1.0, sum(a * b for a, b in zip(feature, centroid))))
        rankings.append((score, employee_id, profile["profileVersion"]))
    rankings.sort(reverse=True)
    best_score, best_employee, best_version = rankings[0]
    if best_score < threshold:
        return RecognitionResult(similarity=best_score, face_count=1, reason="unknown")
    if len(rankings) > 1 and best_score - rankings[1][0] <= ambiguity_margin:
        return RecognitionResult(similarity=best_score, face_count=1, reason="ambiguous")
    return RecognitionResult(best_employee, best_score, best_version, 1, "matched")


def _model_sha256(path: Path) -> str:
    if not path.is_file():
        raise FaceRecognitionError(f"ONNX model not found: {path}")
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        first = stream.read(1024)
        if first.startswith(b"version https://git-lfs.github.com/spec/"):
            raise FaceRecognitionError(f"{path} is a Git LFS pointer; download the actual ONNX model")
        digest.update(first)
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


class FaceRecognizer:
    """YuNet detection plus aligned SFace feature extraction for a small gallery.

    This object is owned by one recognition worker. Enroll while the poll worker
    is stopped, then restart it to load the new gallery/profile versions.
    """

    FEATURE_DIMENSION = 128

    def __init__(
        self,
        detector_model: str | Path,
        recognizer_model: str | Path,
        gallery_path: str | Path | None,
        *,
        threshold: float = 0.5,
        ambiguity_margin: float = 0.1,
        min_face_size: int = 80,
        detection_threshold: float = 0.9,
        min_samples: int = 3,
        min_sharpness: float = 50.0,
    ) -> None:
        if not 0 < threshold <= 1 or not 0 <= ambiguity_margin <= 2:
            raise ValueError("Invalid cosine threshold or ambiguity margin")
        if min_face_size < 1 or min_samples < 1 or not 0 < detection_threshold <= 1:
            raise ValueError("Invalid face size, sample count or detection threshold")
        if not math.isfinite(min_sharpness) or min_sharpness < 0:
            raise ValueError("Invalid minimum face sharpness")
        self.detector_model = Path(detector_model)
        self.recognizer_model = Path(recognizer_model)
        # None is a quality-only worker for photo collection. It must neither
        # read existing biometric features nor replace a gallery on enrollment.
        self.gallery_path = None if gallery_path is None else Path(gallery_path)
        self.threshold = threshold
        self.ambiguity_margin = ambiguity_margin
        self.min_face_size = min_face_size
        self.detection_threshold = detection_threshold
        self.min_samples = min_samples
        self.min_sharpness = min_sharpness
        self._cv: Any = None
        self._np: Any = None
        self._detector: Any = None
        self._recognizer: Any = None
        self.model_hashes = {
            "detectorSha256": _model_sha256(self.detector_model),
            "recognizerSha256": _model_sha256(self.recognizer_model),
        }
        self.profiles = self._load_gallery()

    def _load_gallery(self) -> dict[str, dict[str, Any]]:
        if self.gallery_path is None or not self.gallery_path.exists():
            return {}
        try:
            data = json.loads(self.gallery_path.read_text(encoding="utf-8"))
            if data.get("schemaVersion") != 1 or data.get("models") != self.model_hashes:
                raise FaceRecognitionError("Gallery model/schema mismatch; re-enroll with these models")
            profiles = data["profiles"]
            if not isinstance(profiles, dict):
                raise ValueError("profiles is not an object")
            for employee_id, profile in profiles.items():
                self._validate_employee(employee_id)
                if not isinstance(profile, dict):
                    raise ValueError("profile is not an object")
                version = profile["profileVersion"]
                if type(version) is not int or version < 1:
                    raise ValueError("invalid profile version")
                feature = _normalized(profile["centroid"])
                if len(feature) != self.FEATURE_DIMENSION:
                    raise ValueError("invalid feature dimension")
                sample_count = profile["sampleCount"]
                if type(sample_count) is not int or sample_count < 1:
                    raise ValueError("invalid sample count")
                profile["centroid"] = list(feature)
            return profiles
        except FaceRecognitionError:
            raise
        except (OSError, ValueError, KeyError, TypeError, AttributeError) as exc:
            raise FaceRecognitionError("Cannot read a valid local face gallery") from exc

    @staticmethod
    def _validate_employee(employee_id: str) -> None:
        if (
            not isinstance(employee_id, str)
            or not employee_id.strip()
            or employee_id != employee_id.strip()
            or len(employee_id) > 128
            or "/" in employee_id
            or "\\" in employee_id
            or any(ord(character) < 32 for character in employee_id)
        ):
            raise ValueError("employeeId must be a valid existing employee document ID")

    def _runtime(self) -> tuple[Any, Any]:
        if self._cv is None:
            try:
                cv = importlib.import_module("cv2")
                np = importlib.import_module("numpy")
                detector = cv.FaceDetectorYN.create(
                    str(self.detector_model), "", (320, 320), self.detection_threshold, 0.3, 5000
                )
                recognizer = cv.FaceRecognizerSF.create(str(self.recognizer_model), "")
            except (ImportError, AttributeError) as exc:
                raise FaceRecognitionError(
                    "Install the optional recognition dependencies (OpenCV with YuNet/SFace and numpy)"
                ) from exc
            except Exception as exc:
                raise FaceRecognitionError("Cannot load YuNet/SFace ONNX models") from exc
            self._cv, self._np = cv, np
            self._detector, self._recognizer = detector, recognizer
        return self._cv, self._np

    def read_image(self, image_path: str | Path) -> Any:
        cv, np = self._runtime()
        try:
            encoded = np.frombuffer(Path(image_path).read_bytes(), dtype=np.uint8)
            image = cv.imdecode(encoded, cv.IMREAD_COLOR)
        except Exception as exc:
            raise FaceRecognitionError(f"Cannot read image: {image_path}") from exc
        if image is None:
            raise FaceRecognitionError(f"Cannot decode image: {image_path}")
        return image

    def _extract(self, image: Any) -> tuple[tuple[float, ...] | None, RecognitionResult]:
        self._runtime()
        if image is None or len(getattr(image, "shape", ())) != 3 or image.shape[2] != 3:
            return None, RecognitionResult(reason="invalid_image")
        height, width = image.shape[:2]
        if width < 1 or height < 1:
            return None, RecognitionResult(reason="invalid_image")
        try:
            self._detector.setInputSize((width, height))
            _, faces = self._detector.detect(image)
            count = 0 if faces is None else len(faces)
            if count == 0:
                return None, RecognitionResult(reason="no_face")
            if count != 1:
                return None, RecognitionResult(face_count=count, reason="multiple")
            face = faces[0]
            if len(face) < 15 or not all(math.isfinite(float(value)) for value in face):
                return None, RecognitionResult(face_count=1, reason="low_quality", quality_issue="invalid_detection")
            x, y, face_width, face_height = (float(value) for value in face[:4])
            if face_width <= 0 or face_height <= 0:
                return None, RecognitionResult(face_count=1, reason="low_quality", quality_issue="invalid_detection")
            if x < 0 or y < 0 or x + face_width > width or y + face_height > height:
                return None, RecognitionResult(face_count=1, reason="low_quality", quality_issue="face_out_of_frame")
            if min(face_width, face_height) < self.min_face_size:
                return None, RecognitionResult(face_count=1, reason="low_quality", quality_issue="face_too_small")
            if float(face[-1]) < self.detection_threshold:
                return None, RecognitionResult(face_count=1, reason="low_quality", quality_issue="low_confidence")
            if self.min_sharpness > 0:
                face_image = image[int(y):int(y + face_height), int(x):int(x + face_width)]
                gray = self._cv.cvtColor(face_image, self._cv.COLOR_BGR2GRAY)
                sharpness = float(self._cv.Laplacian(gray, self._cv.CV_64F).var())
                if not math.isfinite(sharpness) or sharpness < self.min_sharpness:
                    return None, RecognitionResult(face_count=1, reason="low_quality", quality_issue="blurry")
            aligned = self._recognizer.alignCrop(image, face)
            feature = self._recognizer.feature(aligned).reshape(-1).tolist()
            normalized = _normalized(feature)
            if len(normalized) != self.FEATURE_DIMENSION:
                raise FaceRecognitionError("SFace returned an unexpected feature dimension")
            return normalized, RecognitionResult(face_count=1, reason="feature_extracted")
        except FaceRecognitionError:
            raise
        except Exception as exc:
            raise FaceRecognitionError("YuNet/SFace image processing failed") from exc

    def recognize(self, image: Any) -> RecognitionResult:
        feature, result = self._extract(image)
        if feature is None:
            return result
        return match_embedding(
            feature, self.profiles, threshold=self.threshold, ambiguity_margin=self.ambiguity_margin
        )

    def inspect_quality(self, image: Any) -> RecognitionResult:
        """Validate a registration photo without requiring an enrolled identity.

        Reuses the exact detection, face-size and sharpness checks applied before
        recognition. Unknown or ambiguous identities are irrelevant to capture.
        """
        feature, result = self._extract(image)
        if feature is None:
            return result
        return RecognitionResult(face_count=1, reason="quality_ok")

    def recognize_path(self, image_path: str | Path) -> RecognitionResult:
        return self.recognize(self.read_image(image_path))

    def enroll(self, employee_id: str, image_paths: Iterable[str | Path], version: int) -> dict[str, Any]:
        if self.gallery_path is None:
            raise FaceRecognitionError("A quality-only worker cannot enroll or write a gallery")
        self._validate_employee(employee_id)
        if type(version) is not int or version < 1:
            raise ValueError("profileVersion must be a positive integer")
        old_profile = self.profiles.get(employee_id)
        if old_profile and version <= old_profile["profileVersion"]:
            raise ValueError("Re-enrollment must increment profileVersion")
        samples = list(image_paths)
        if len(samples) < self.min_samples:
            raise ValueError(f"Provide at least {self.min_samples} different clear photos")
        if len({Path(path).resolve() for path in samples}) != len(samples):
            raise ValueError("Enrollment contains repeated image paths")
        features = []
        for image_path in samples:
            feature, result = self._extract(self.read_image(image_path))
            if feature is None:
                raise FaceRecognitionError(f"Enrollment image {image_path}: {result.reason}")
            features.append(feature)
        centroid = _normalized([
            sum(feature[index] for feature in features) / len(features)
            for index in range(self.FEATURE_DIMENSION)
        ])
        # Reject inconsistent samples and a second profile of an existing person.
        # These checks help registration, but do not substitute a camera trial.
        if any(sum(a * b for a, b in zip(feature, centroid)) < self.threshold for feature in features):
            raise FaceRecognitionError("Enrollment samples are inconsistent; check identity and image quality")
        others = {key: value for key, value in self.profiles.items() if key != employee_id}
        if others:
            result = match_embedding(centroid, others, threshold=self.threshold, ambiguity_margin=0)
            if result.reason in {"matched", "ambiguous"}:
                raise FaceRecognitionError("Face resembles another enrolled employee; review registration")
        created_at = datetime.now(timezone.utc).isoformat()
        profile = {
            "profileVersion": version,
            "sampleCount": len(features),
            "enrolledAt": created_at,
            "centroid": list(centroid),
        }
        updated_profiles = dict(self.profiles)
        updated_profiles[employee_id] = profile
        self._save_gallery(updated_profiles)
        self.profiles = updated_profiles
        return {
            "employeeId": employee_id,
            "profileVersion": version,
            "sampleCount": len(features),
            "enrolledAt": created_at,
        }

    def _save_gallery(self, profiles: Mapping[str, Mapping[str, Any]]) -> None:
        if self.gallery_path is None:
            raise FaceRecognitionError("A quality-only worker cannot write a gallery")
        self.gallery_path.parent.mkdir(parents=True, exist_ok=True)
        temporary_name: str | None = None
        try:
            with tempfile.NamedTemporaryFile(
                "w", encoding="utf-8", dir=self.gallery_path.parent, suffix=".tmp", delete=False
            ) as stream:
                temporary_name = stream.name
                json.dump(
                    {"schemaVersion": 1, "models": self.model_hashes, "profiles": profiles},
                    stream, allow_nan=False, ensure_ascii=False, indent=2,
                )
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temporary_name, self.gallery_path)
        except OSError as exc:
            raise FaceRecognitionError("Cannot save local face gallery") from exc
        finally:
            if temporary_name and Path(temporary_name).exists():
                Path(temporary_name).unlink()
