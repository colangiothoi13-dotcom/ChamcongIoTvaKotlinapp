from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import hmac
import json
import threading
import sqlite3
import logging
import re

from .events import Event, InvalidEvent, response
from .capture_preview import valid_preview_key


def make_server(gateway, host, port, admin_token, device_token, *, preview=None, face_status=None, allow_face_events=True):
    if min(len(admin_token), len(device_token)) < 24 or "REPLACE" in admin_token or "REPLACE" in device_token:
        raise ValueError("Đặt token riêng dài ít nhất 24 ký tự trước khi chạy server")

    class Handler(BaseHTTPRequestHandler):
        def setup(self):
            super().setup()
            self.connection.settimeout(10)

        def log_message(self, format, *args):
            # HTTP routes/status only. No tokens or request bodies in logs.
            return

        def send_json(self, status, data):
            content = json.dumps(data, ensure_ascii=False).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(content)))
            self.end_headers()
            self.wfile.write(content)

        def authorized(self, admin=False):
            name, token = ("X-Admin-Token", admin_token) if admin else ("X-Device-Token", device_token)
            return hmac.compare_digest(self.headers.get(name, ""), token)

        def do_GET(self):
            if self.path == "/v1/face-status":
                if not self.authorized(admin=True):
                    self.send_json(401, {"status": "REJECTED", "reason": "UNAUTHORIZED"})
                    return
                if face_status is None:
                    self.send_json(503, {"status": "UNAVAILABLE", "message": "Chưa chạy station nhận diện khuôn mặt"})
                    return
                self.send_json(200, face_status())
            elif self.path.startswith("/v1/enrollment-preview/"):
                if not self.authorized(admin=True):
                    self.send_json(401, {"status": "REJECTED", "reason": "UNAUTHORIZED"})
                    return
                match = re.fullmatch(r"/v1/enrollment-preview/([A-Za-z0-9_-]{1,128})/([1-9][0-9]{0,2})", self.path)
                key = (match[1], int(match[2])) if match else (None, None)
                record = preview.get(*key) if preview is not None and valid_preview_key(*key) else None
                if record is None:
                    self.send_json(404, {"error": "NOT_FOUND"})
                    return
                self.send_response(200)
                self.send_header("Content-Type", "image/jpeg")
                self.send_header("Cache-Control", "no-store")
                self.send_header("X-Content-Type-Options", "nosniff")
                self.send_header("Content-Length", str(len(record.jpeg)))
                self.send_header("X-Enrollment-Request", record.request_id)
                self.send_header("X-Capture-Sequence", str(record.sequence))
                self.send_header("X-Capture-Reason", record.reason)
                self.send_header("X-Capture-Saved", str(record.saved_count))
                self.send_header("X-Capture-Total", str(record.sample_count))
                self.end_headers()
                self.wfile.write(record.jpeg)
            elif self.path == "/health":
                try:
                    self.send_json(200, {"status": "OK", "mode": gateway.backend.mode,
                                         "pending": gateway.outbox.pending_count()})
                except (OSError, sqlite3.Error):
                    self.send_json(503, {"status": "UNAVAILABLE", "reason": "STORAGE_ERROR"})
            else:
                self.send_json(404, {"error": "NOT_FOUND"})

        def do_POST(self):
            # Drain a bounded body before an early response; unread POST bytes can
            # make Windows reset the connection before the peer receives the ACK.
            data = None
            try:
                length = int(self.headers.get("Content-Length", "0"))
                if not 0 <= length <= 16384:
                    raise ValueError()
                raw = self.rfile.read(length)
                if len(raw) != length:
                    raise ValueError()
            except (ValueError, OSError):
                self.send_json(400, {"status":"REJECTED", "reason":"INVALID_BODY_SIZE"})
                return
            route = self.path
            if route not in ("/v1/fingerprint", "/v1/face", "/v1/flush"):
                self.send_json(404, {"error": "NOT_FOUND"})
                return
            if not self.authorized(admin=route != "/v1/fingerprint"):
                self.send_json(401, {"status": "REJECTED", "reason": "UNAUTHORIZED"})
                return
            if route == "/v1/face" and not allow_face_events:
                # Metadata-only requests contain no image to check. The local
                # camera worker submits directly after the shared guard; this
                # route must not bypass an enabled accessory policy.
                self.send_json(422, {"status": "REJECTED", "reason": "ACCESSORY_CHECK_REQUIRED",
                                     "message": "Chấm bằng mặt cần được camera trên laptop kiểm tra phụ kiện."})
                return
            try:
                if route == "/v1/flush":
                    self.send_json(200, {"results": gateway.flush()})
                    return
                if length == 0:
                    raise InvalidEvent("INVALID_BODY_SIZE")
                data = json.loads(raw)
                event = Event.parse(data, "FINGERPRINT" if route == "/v1/fingerprint" else "FACE")
                result = gateway.submit(event)
                code = 202 if result["status"] == "QUEUED" else 200
                if result["status"] == "REJECTED":
                    code = 422
                self.send_json(code, result)
            except (InvalidEvent, ValueError, UnicodeError) as exc:
                reason = str(exc) if isinstance(exc, InvalidEvent) else "INVALID_JSON"
                result = {"status": "REJECTED", "reason": reason}
                if isinstance(data, dict) and isinstance(data.get("sourceEventId"), str):
                    result["sourceEventId"] = data["sourceEventId"][:160]
                self.send_json(409 if reason == "EVENT_ID_CONFLICT" else 400, result)
            except (OSError, sqlite3.Error):
                result = {"status": "QUEUED", "reason": "NOT_STORED"}
                if isinstance(data, dict) and isinstance(data.get("sourceEventId"), str):
                    result["sourceEventId"] = data["sourceEventId"][:160]
                self.send_json(503, result)

    server = ThreadingHTTPServer((host, port), Handler)
    server.daemon_threads = True
    return server


def _sync_loop(gateway, stop, interval=5):
    while not stop.wait(interval):
        try:
            gateway.flush()
        except (OSError, sqlite3.Error) as exc:
            # A transient local storage failure must not kill the only retry
            # worker. Avoid logging database paths, request data or credentials.
            logging.getLogger(__name__).warning("Local sync retry waiting (%s)", type(exc).__name__)


def run_server(server, gateway):
    stop = threading.Event()

    worker = threading.Thread(target=_sync_loop, args=(gateway, stop), daemon=True)
    worker.start()
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        stop.set()
        server.server_close()
