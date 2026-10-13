"""One short-lived manual capture JPEG in RAM; never an image directory/API."""

from dataclasses import dataclass
import re
import threading
import time

from .capture import _encode_jpeg


MAX_PREVIEW_BYTES = 5 * 1024 * 1024
PREVIEW_REASONS = frozenset({"quality_ok", "no_face", "multiple", "low_quality", "invalid_image", "camera_error"})
_REQUEST_ID = re.compile(r"[A-Za-z0-9_-]{1,128}\Z")


def valid_preview_key(request_id, sequence):
    return (isinstance(request_id, str) and _REQUEST_ID.fullmatch(request_id) is not None
            and type(sequence) is int and 1 <= sequence <= 100)


@dataclass(frozen=True)
class CapturePreview:
    request_id: str
    sequence: int
    jpeg: bytes
    reason: str
    saved_count: int
    sample_count: int
    expires_at: float


class CapturePreviewStore:
    """Keep the latest attempt only, scoped to an explicitly opened request.

    Publishing is optional display work: encoding failures clear the stale image
    and return False. They must never change enrollment's photo acceptance/ACK.
    """

    def __init__(self, *, ttl=300, clock=time.monotonic, encoder=_encode_jpeg,
                 timer_factory=threading.Timer):
        if type(ttl) not in (int, float) or not 0 < ttl <= 300:
            raise ValueError("Preview TTL must be between 0 and 300 seconds")
        self._ttl, self._clock, self._encoder = float(ttl), clock, encoder
        self._timer_factory = timer_factory
        self._lock = threading.Lock()
        self._request_id = None
        self._record = None
        self._generation = 0
        self._timer = None
        self._closed = False

    def _discard_locked(self):
        self._generation += 1
        self._record = None
        if self._timer:
            self._timer.cancel()
            self._timer = None

    def begin(self, request_id):
        if not valid_preview_key(request_id, 1):
            raise ValueError("Invalid enrollment preview request")
        with self._lock:
            self._discard_locked()
            self._request_id = None if self._closed else request_id

    def clear(self, request_id=None):
        with self._lock:
            if request_id is None or request_id == self._request_id:
                self._discard_locked()
                self._request_id = None

    def close(self):
        with self._lock:
            self._closed = True
            self._discard_locked()
            self._request_id = None

    def _expire(self, generation):
        with self._lock:
            # A canceled timer may already be waiting on this lock. It cannot
            # remove the image of a later attempt or a replacement request.
            if generation == self._generation:
                self._discard_locked()

    def publish(self, request_id, sequence, image, *, reason, saved_count, sample_count=10):
        if (not valid_preview_key(request_id, sequence) or not isinstance(reason, str) or reason not in PREVIEW_REASONS
                or type(sample_count) is not int or sample_count not in (6, 10)
                or type(saved_count) is not int or not 0 <= saved_count <= sample_count):
            self.clear(request_id)
            return False
        with self._lock:
            if self._closed or request_id != self._request_id:
                return False
            self._discard_locked()
            generation = self._generation
        if image is None or reason == "camera_error":
            # Keep request ownership, but remove the earlier attempt immediately.
            return False
        try:
            jpeg = self._encoder(image)
            if (not isinstance(jpeg, bytes) or not 4 <= len(jpeg) <= MAX_PREVIEW_BYTES
                    or not jpeg.startswith(b"\xff\xd8") or not jpeg.endswith(b"\xff\xd9")):
                return False
            record = CapturePreview(request_id, sequence, jpeg, reason, saved_count, sample_count,
                                    self._clock() + self._ttl)
        except Exception:
            return False
        with self._lock:
            if self._closed or generation != self._generation or request_id != self._request_id:
                return False
            self._record = record
            timer = self._timer_factory(self._ttl, self._expire, args=(generation,))
            timer.daemon = True
            self._timer = timer
            timer.start()
        return True

    def get(self, request_id, sequence):
        if not valid_preview_key(request_id, sequence):
            return None
        with self._lock:
            record = self._record
            if record is None:
                return None
            if self._clock() >= record.expires_at:
                self._discard_locked()
                return None
            if record.request_id != request_id or record.sequence != sequence:
                return None
            return record
