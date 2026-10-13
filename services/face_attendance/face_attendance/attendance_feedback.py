"""Short-lived local feedback for the most recent face attendance scan."""

from datetime import datetime, timezone
import re
import threading


_FACE_SCAN_MESSAGES = {
    "no_face": ("IDLE", "Đưa một người vào khung hình để quét."),
    "matched": ("CHECKING", "Đã nhận diện khuôn mặt; đang xác nhận ổn định."),
    "unknown": ("NO_MATCH", "Chưa khớp khuôn mặt đã đăng ký. Nhìn thẳng và giữ yên khoảng 5 giây."),
    "ambiguous": ("NO_MATCH", "Khuôn mặt chưa phân biệt rõ với hồ sơ khác. Nhìn thẳng và thử lại."),
    "no_profiles": ("NO_MATCH", "Chưa có hồ sơ khuôn mặt đang hoạt động tại trạm."),
    "low_quality": ("WAITING", "Mặt chưa đủ rõ. Tăng ánh sáng, bỏ vật che mặt và giữ yên."),
}


class FaceAttendanceFeedback:
    """Expose only scan state and the latest confirmed result, never photos or vectors."""

    def __init__(self, *, clock=None):
        self.clock = clock or (lambda: datetime.now(timezone.utc))
        self._lock = threading.Lock()
        self.reset()

    def _stamp(self):
        return self.clock().isoformat()

    @staticmethod
    def _name(value):
        if not isinstance(value, str):
            return ""
        # Remove control characters before passing text through HTTP/JSON to the UI/LCD.
        return re.sub(r"[\x00-\x1f\x7f]", "", value).strip()[:120]

    def reset(self):
        now = self._stamp()
        with self._lock:
            self._scan = {"status": "IDLE", "message": "Đưa một người vào khung hình để quét.", "updatedAt": now}
            self._attendance = {
                "status": "NONE", "employeeName": "", "message": "Chưa có lượt chấm công khuôn mặt gần đây.",
                "updatedAt": now,
            }

    def observe(self, result):
        status, message = _FACE_SCAN_MESSAGES.get(
            result.reason, ("WAITING", "Chưa nhận diện được mặt rõ. Chỉ để một người trong ảnh và giữ yên."))
        if result.face_count > 1:
            status, message = "WAITING", "Chỉ để một người trước camera rồi giữ yên khoảng 5 giây."
        elif result.reason == "matched":
            status, message = "CHECKING", "Đã nhận diện khuôn mặt; đang xác nhận ổn định."
        with self._lock:
            self._scan = {"status": status, "message": message, "updatedAt": self._stamp()}

    def begin(self):
        with self._lock:
            self._scan = {
                "status": "WRITING", "message": "Đã nhận diện; đang xác nhận và ghi lượt chấm công.",
                "updatedAt": self._stamp(),
            }
            self._attendance = {
                "status": "PENDING", "employeeName": "",
                "message": "Đang xác nhận lượt với hệ thống.", "updatedAt": self._stamp(),
            }

    def record_result(self, event, result):
        if event.payload.get("method") != "FACE" or not isinstance(result, dict):
            return
        status = result.get("status")
        name = self._name(result.get("employeeName"))
        if status in {"ACCEPTED_SYNCED", "ACCEPTED_DEMO"}:
            label = "ACCEPTED"
            message = ("Đã ghi công trên Firebase." if status == "ACCEPTED_SYNCED"
                       else "Đã ghi lượt trong dữ liệu mẫu; chưa lưu lên Firebase.")
        elif status in {"DUPLICATE_SYNCED", "DUPLICATE_DEMO"}:
            label = "DUPLICATE"
            message = "Đã gộp với lượt vân tay/khuôn mặt gần đây; không tạo lượt thứ hai."
        elif status == "QUEUED":
            label = "PENDING"
            message = "Đã nhận diện; đang chờ đồng bộ, chưa xác nhận đã ghi công."
        elif status == "REJECTED":
            label = "REJECTED"
            message = "Không ghi công được. Kiểm tra hồ sơ nhân viên hoặc liên hệ quản trị viên."
        else:
            return
        with self._lock:
            stamp = self._stamp()
            self._attendance = {"status": label, "employeeName": name, "message": message, "updatedAt": stamp}
            self._scan = {"status": label, "message": message, "updatedAt": stamp}

    def get(self):
        with self._lock:
            return {"scan": dict(self._scan), "attendance": dict(self._attendance)}
