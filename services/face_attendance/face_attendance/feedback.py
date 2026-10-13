"""Local accessory feedback; no employee identity, photo or model score."""

from datetime import datetime, timezone
import threading


ACCESSORY_MESSAGES = {
    "mask": "Phát hiện khẩu trang. Tháo khẩu trang trước khi nhận diện khuôn mặt.",
    "hat": "Phát hiện mũ hoặc đồ che đầu. Tháo mũ và đồ che đầu trước khi nhận diện khuôn mặt.",
    "glasses": "Phát hiện kính. Tháo kính, kể cả kính cận hoặc kính râm, trước khi nhận diện khuôn mặt.",
    "accessory_uncertain": "Chưa xác định rõ mặt có phụ kiện hay không. Tháo khẩu trang, mũ và kính; tăng ánh sáng rồi thử lại.",
    "accessory_head_out_of_frame": "Chưa thấy toàn bộ đầu để kiểm tra mũ. Lùi lại, giữ cả đỉnh đầu và khuôn mặt trong ảnh.",
}


def accessory_block_message(quality_issue):
    """Return guidance only for an accessory gate's known rejection codes."""
    return ACCESSORY_MESSAGES.get(quality_issue)


class FaceBlockFeedback:
    """One RAM snapshot shared with an authenticated local status endpoint.

    Every observed frame refreshes freshness, but repeated rejections produce
    one console notification. This object never mutates the recognition result
    or presence gate, so a rejected face cannot act as an empty frame.
    """

    def __init__(self, emit, *, enabled=False, clock=None):
        self.emit = emit
        self.enabled = enabled is True
        self.clock = clock or (lambda: datetime.now(timezone.utc))
        self._lock = threading.Lock()
        self._value = {}
        self.reset()

    def _set(self, status, message, issue=None):
        with self._lock:
            previous = self._value
            self._value = {
                "status": status,
                "qualityIssue": issue,
                "message": message,
                "updatedAt": self.clock().isoformat(),
                "accessoryGateEnabled": self.enabled,
            }
            return previous, dict(self._value)

    def reset(self, status="IDLE", message=None):
        self._set(status, message or "Đưa một người vào khung hình để kiểm tra khuôn mặt.")

    def get(self):
        with self._lock:
            return dict(self._value)

    def observe(self, result):
        issue = result.quality_issue
        block = accessory_block_message(issue) if result.reason == "low_quality" else None
        if block:
            previous, current = self._set("FACE_BLOCKED", block, issue)
            if (previous.get("status"), previous.get("qualityIssue")) != ("FACE_BLOCKED", issue):
                self.emit(current)
            return
        if result.face_count == 0 and result.reason == "no_face":
            previous, current = self._set("IDLE", "Đưa một người vào khung hình để kiểm tra khuôn mặt.")
            if previous.get("status") == "FACE_BLOCKED":
                self.emit({**current, "status": "FACE_CLEARED", "message": "Người đã rời khung hình. Chờ lượt tiếp theo."})
            return
        if result.face_count == 1 and result.reason in {
                "quality_ok", "feature_extracted", "matched", "no_profiles", "unknown", "ambiguous"}:
            message = ("Đã qua kiểm tra phụ kiện. Giữ mặt rõ trong khung hình để nhận diện."
                       if self.enabled else "Giữ mặt rõ trong khung hình để nhận diện.")
            previous, current = self._set("FACE_READY", message)
            if previous.get("status") == "FACE_BLOCKED":
                self.emit(current)
            return
        self._set("FACE_WAITING", "Chưa kiểm tra được mặt rõ. Chỉ để một người trong ảnh, đủ sáng và giữ yên.")
