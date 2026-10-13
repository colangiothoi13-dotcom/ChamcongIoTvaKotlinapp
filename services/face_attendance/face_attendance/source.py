"""Frame sources for ESP32 JPEG snapshots, a laptop webcam or one local image."""

from __future__ import annotations

from collections import deque
from dataclasses import dataclass
from datetime import datetime, timezone
import importlib
from pathlib import Path
import re
from typing import Any
from urllib import error, parse, request


class FrameSourceError(RuntimeError):
    """Acquisition failed or returned stale data. This is never an empty frame."""


@dataclass(frozen=True)
class Frame:
    image: Any
    captured_at: str
    sequence: str


def _decode(encoded: bytes) -> Any:
    try:
        cv = importlib.import_module("cv2")
        np = importlib.import_module("numpy")
    except ImportError as exc:
        raise FrameSourceError("Install the optional OpenCV/numpy recognition dependencies") from exc
    try:
        image = cv.imdecode(np.frombuffer(encoded, dtype=np.uint8), cv.IMREAD_COLOR)
    except Exception as exc:
        raise FrameSourceError("Image decoding failed") from exc
    if image is None or len(getattr(image, "shape", ())) != 3 or image.shape[2] != 3:
        raise FrameSourceError("Source did not return a readable color image")
    return image


def _rotate(image: Any, rotation: int) -> Any:
    """Correct the mounted camera before quality checks, saving or recognition."""
    if rotation == 0:
        return image
    try:
        cv = importlib.import_module("cv2")
        code = {
            90: cv.ROTATE_90_CLOCKWISE,
            180: cv.ROTATE_180,
            270: cv.ROTATE_90_COUNTERCLOCKWISE,
        }[rotation]
        result = cv.rotate(image, code)
    except Exception as exc:
        raise FrameSourceError("Cannot correct camera image orientation") from exc
    if result is None:
        raise FrameSourceError("Cannot correct camera image orientation")
    return result


class _NoRedirect(request.HTTPRedirectHandler):
    def redirect_request(self, req: Any, fp: Any, code: int, msg: str, headers: Any, newurl: str) -> None:
        # The configured camera token must stay on the configured endpoint.
        raise FrameSourceError("Camera redirects are not accepted; configure the final snapshot URL")


class UrlFrameSource:
    """Authenticated JPEG snapshots; requires fresh X-Frame-Id from firmware.

    UTC time is measured on the laptop immediately after receipt. X-Frame-Id is
    a camera boot nonce plus frame counter so a reconnect cannot replay a saved
    empty picture and accidentally rearm the presence gate.
    """

    is_live = True

    def __init__(self, url: str, token: str, *, timeout: float = 5, max_bytes: int = 2 * 1024 * 1024,
                 rotation: int = 0) -> None:
        parsed = parse.urlsplit(url)
        if (
            parsed.scheme not in {"http", "https"}
            or not parsed.hostname
            or parsed.username is not None or parsed.password is not None
            or parsed.fragment
        ):
            raise ValueError("Camera URL must be a fixed http(s) snapshot URL without embedded credentials")
        if not token or "\r" in token or "\n" in token:
            raise ValueError("Configure a camera token")
        if timeout <= 0 or max_bytes < 1:
            raise ValueError("Invalid camera timeout or image size limit")
        if type(rotation) is not int or rotation not in (0, 90, 180, 270):
            raise ValueError("Camera rotation must be an integer: 0, 90, 180 or 270")
        self.url = url
        self.token = token
        self.timeout = timeout
        self.max_bytes = max_bytes
        self.rotation = rotation
        self._opener = request.build_opener(_NoRedirect())
        self._recent: deque[str] = deque(maxlen=256)
        self._recent_ids: set[str] = set()

    def read(self) -> Frame:
        req = request.Request(self.url, headers={
            "X-Camera-Token": self.token,
            "Accept": "image/jpeg",
            "Cache-Control": "no-cache, no-store",
        })
        try:
            with self._opener.open(req, timeout=self.timeout) as response:
                if response.getcode() != 200:
                    raise FrameSourceError("Camera did not return HTTP 200")
                if response.headers.get_content_type() != "image/jpeg":
                    raise FrameSourceError("Camera did not return image/jpeg")
                frame_id = response.headers.get("X-Frame-Id", "")
                if not re.fullmatch(r"[A-Za-z0-9_.:-]{1,128}", frame_id):
                    raise FrameSourceError("Camera is missing a valid fresh X-Frame-Id")
                if frame_id in self._recent_ids:
                    raise FrameSourceError("Camera returned a stale frame")
                encoded = response.read(self.max_bytes + 1)
                if len(encoded) > self.max_bytes:
                    raise FrameSourceError("Camera JPEG exceeds configured size limit")
        except FrameSourceError:
            raise
        except (OSError, ValueError, error.URLError) as exc:
            # Do not include URL/token/body in errors that may be logged.
            raise FrameSourceError("Cannot retrieve camera snapshot") from exc
        captured_at = datetime.now(timezone.utc).isoformat()
        image = _rotate(_decode(encoded), self.rotation)
        if len(self._recent) == self._recent.maxlen:
            self._recent_ids.remove(self._recent[0])
        self._recent.append(frame_id)
        self._recent_ids.add(frame_id)
        return Frame(image, captured_at, frame_id)

    def close(self) -> None:
        pass


class WebcamFrameSource:
    is_live = True

    def __init__(self, index: int = 0) -> None:
        if type(index) is not int or index < 0:
            raise ValueError("Webcam index must be a nonnegative integer")
        self.index = index
        self._capture: Any = None
        self._sequence = 0

    def read(self) -> Frame:
        try:
            if self._capture is None:
                cv = importlib.import_module("cv2")
                capture = cv.VideoCapture(self.index)
                if not capture.isOpened():
                    capture.release()
                    raise FrameSourceError("Cannot open laptop webcam")
                self._capture = capture
            ok, image = self._capture.read()
            if not ok or image is None:
                raise FrameSourceError("Laptop webcam did not return a frame")
            self._sequence += 1
            return Frame(image, datetime.now(timezone.utc).isoformat(), f"webcam-{self._sequence}")
        except FrameSourceError:
            self.close()
            raise
        except Exception as exc:
            self.close()
            raise FrameSourceError("Laptop webcam acquisition failed") from exc

    def close(self) -> None:
        if self._capture is not None:
            self._capture.release()
            self._capture = None


class ImageFrameSource:
    """A single test image. It must never silently become a live attendance source."""

    is_live = False

    def __init__(self, image_path: str | Path, *, max_bytes: int = 20 * 1024 * 1024) -> None:
        if max_bytes < 1:
            raise ValueError("Invalid local image size limit")
        self.image_path = Path(image_path)
        self.max_bytes = max_bytes
        self._read = False

    def read(self) -> Frame:
        if self._read:
            raise FrameSourceError("Local test image has already been read")
        try:
            with self.image_path.open("rb") as stream:
                encoded = stream.read(self.max_bytes + 1)
        except OSError as exc:
            raise FrameSourceError("Cannot read local test image") from exc
        if len(encoded) > self.max_bytes:
            raise FrameSourceError("Local test image exceeds configured size limit")
        image = _decode(encoded)
        self._read = True
        return Frame(image, datetime.now(timezone.utc).isoformat(), "local-test-1")

    def close(self) -> None:
        pass
