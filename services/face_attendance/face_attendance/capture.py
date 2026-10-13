"""Collect local registration JPEGs, without enrollment or attendance writes."""

from dataclasses import dataclass
from datetime import datetime, timezone
import importlib
import os
from pathlib import Path
import tempfile
import time
from typing import Callable
import uuid

from .events import identifier
from .recognition import FaceRecognitionError
from .source import FrameSourceError


@dataclass(frozen=True)
class CaptureSummary:
    employee_id: str
    requested: int
    files: tuple[str, ...]
    attempts: int
    stopped: bool

    def to_dict(self):
        return {
            "status": "CAPTURE_COMPLETE" if len(self.files) == self.requested else "CAPTURE_PARTIAL",
            "employeeId": self.employee_id,
            "requested": self.requested,
            "saved": len(self.files),
            "files": list(self.files),
            "attempts": self.attempts,
            "stopped": self.stopped,
            "message": "Ảnh chỉ được lưu trên laptop; dùng lệnh enroll để đăng ký sau khi kiểm tra ảnh",
        }


def validate_photo_employee_id(employee_id):
    identifier(employee_id, "employeeId")
    # IDs become literal Windows folder names. Reject traversal and device names.
    reserved = {"CON", "PRN", "AUX", "NUL"} | {f"{prefix}{index}" for prefix in ("COM", "LPT") for index in range(1, 10)}
    if (
        employee_id in {".", ".."} or ":" in employee_id
        or employee_id.endswith(".") or employee_id.upper().split(".")[0] in reserved
    ):
        raise ValueError("employeeId không dùng được làm tên thư mục ảnh")
    return employee_id


def _encode_jpeg(image):
    try:
        cv = importlib.import_module("cv2")
        ok, buffer = cv.imencode(".jpg", image, [cv.IMWRITE_JPEG_QUALITY, 95])
        if not ok:
            raise FaceRecognitionError("Không mã hóa được ảnh JPEG")
        return buffer.tobytes()
    except FaceRecognitionError:
        raise
    except Exception as exc:
        raise FaceRecognitionError("Không mã hóa được ảnh JPEG") from exc


def _write_exclusive_jpeg(directory, encoded):
    if not encoded or len(encoded) > 20 * 1024 * 1024:
        raise ValueError("Ảnh JPEG vượt giới hạn hoặc rỗng")
    temporary = None
    try:
        with tempfile.NamedTemporaryFile("wb", dir=directory, suffix=".tmp", delete=False) as stream:
            temporary = Path(stream.name)
            stream.write(encoded)
            stream.flush()
            os.fsync(stream.fileno())
        for _ in range(5):
            stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S_%fZ")
            target = directory / f"{stamp}-{uuid.uuid4().hex}.jpg"
            try:
                # Same-directory hardlink publishes only a complete file and
                # fails if a target exists. It never replaces an earlier photo.
                os.link(temporary, target)
                return target
            except FileExistsError:
                continue
        raise FileExistsError("Không tạo được tên ảnh riêng; các ảnh cũ được giữ nguyên")
    finally:
        if temporary and temporary.exists():
            temporary.unlink()


def capture_samples(
    source, recognizer, photos_directory, employee_id, *, count=10, interval=1.0,
    max_attempts=None, progress: Callable[[dict], None] | None = None,
    stop_requested: Callable[[], bool] | None = None,
    before_attempt: Callable[[], None] | None = None,
    on_attempt: Callable[[object | None, dict], None] | None = None,
):
    """Capture bounded fresh frames; save only one sufficiently clear face.

    The caller owns configuration; this function never imports Firebase, resolves
    employee records, updates the gallery or creates attendance events.
    """
    files = []
    attempts = 0
    stopped = False

    def report(image, value):
        # The optional preview observes this exact corrected frame. It must
        # never turn a saved sample into an error or block its acknowledgement.
        if on_attempt:
            try:
                on_attempt(image, {key: value[key] for key in
                                  ("status", "saved", "requested", "reason", "qualityIssue") if key in value})
            except Exception:
                pass
        if progress:
            progress(value)

    try:
        validate_photo_employee_id(employee_id)
        if type(count) is not int or not 1 <= count <= 100:
            raise ValueError("count phải từ 1 đến 100")
        if type(interval) not in (int, float) or not 0 <= interval <= 60:
            raise ValueError("interval phải từ 0 đến 60 giây")
        limit = count * 10 if max_attempts is None else max_attempts
        if type(limit) is not int or not count <= limit <= 10000:
            raise ValueError("max-attempts phải ít nhất bằng count và không quá 10000")
        if getattr(source, "is_live", True) is False:
            raise ValueError("capture cần camera hoặc webcam với khung ảnh mới")
        root = Path(photos_directory).resolve()
        directory = (root / employee_id).resolve()
        if directory.parent != root:
            raise ValueError("Thư mục ảnh phải nằm trong data/photos")
        directory.mkdir(parents=True, exist_ok=True)
        while len(files) < count and attempts < limit:
            if stop_requested and stop_requested():
                stopped = True
                break
            # A manual caller waits here for one explicit trigger. Waiting is
            # not an image attempt, and this callback owns no camera operation.
            if before_attempt:
                before_attempt()
                if stop_requested and stop_requested():
                    stopped = True
                    break
            attempts += 1
            attempt_image = None
            try:
                frame = source.read()
                attempt_image = frame.image
                quality = recognizer.inspect_quality(frame.image)
                if quality.face_count == 1 and quality.reason == "quality_ok":
                    if stop_requested and stop_requested():
                        stopped = True
                        break
                    path = _write_exclusive_jpeg(directory, _encode_jpeg(frame.image))
                    files.append(str(path))
                    report(frame.image, {"status": "CAPTURE_SAVED", "saved": len(files), "requested": count, "path": str(path)})
                else:
                    event = {"status": "CAPTURE_SKIP", "reason": quality.reason, "attempt": attempts,
                             "saved": len(files), "requested": count}
                    if quality.quality_issue is not None:
                        event["qualityIssue"] = quality.quality_issue
                    report(frame.image, event)
            except (FrameSourceError, FaceRecognitionError) as exc:
                report(attempt_image, {"status": "CAPTURE_SOURCE_ERROR", "message": str(exc), "attempt": attempts,
                                      "saved": len(files), "requested": count})
            if len(files) < count and attempts < limit:
                time.sleep(interval)
    except KeyboardInterrupt:
        stopped = True
    finally:
        source.close()
    return CaptureSummary(employee_id, count, tuple(files), attempts, stopped)
