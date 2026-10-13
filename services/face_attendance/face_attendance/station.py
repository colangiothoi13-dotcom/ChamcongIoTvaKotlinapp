"""One launcher for camera attendance, fingerprint API and app registration."""

import sqlite3
import threading
import time
import uuid

from .enrollment import CommandRejected, EnrollmentWorker, FirestoreEnrollmentStore
from .capture_preview import CapturePreviewStore
from .attendance_feedback import FaceAttendanceFeedback
from .events import Event
from .feedback import FaceBlockFeedback
from .presence import PresenceGate
from .recognition import FaceRecognitionError, RecognitionResult
from .server import _sync_loop, make_server
from .source import FrameSourceError, UrlFrameSource, WebcamFrameSource


def run_station(config, gateway, face, data_dir, emit, *, webcam=None):
    """The main thread alone touches camera/OpenCV/gallery.

    Firestore's watch just queues IDs. While registration runs, this thread
    cannot submit camera attendance. Fingerprint HTTP remains available.
    """
    stop = threading.Event()
    presence = PresenceGate(stable_frames=config["recognition"]["stableFrames"],
                            empty_frames=config["recognition"]["emptyFrames"])
    source = WebcamFrameSource(webcam) if webcam is not None else UrlFrameSource(
        config["camera"]["url"], config["camera"]["token"], rotation=config["camera"].get("rotation", 0))
    preview = CapturePreviewStore()
    feedback = FaceBlockFeedback(emit, enabled=config.get("accessories", {}).get("enabled") is True)
    attendance_feedback = FaceAttendanceFeedback()
    gateway.result_listener = attendance_feedback.record_result

    def status_snapshot():
        return {**feedback.get(), **attendance_feedback.get()}

    server = make_server(gateway, config["host"], config["port"], config["adminToken"], config["deviceToken"],
                         preview=preview, face_status=status_snapshot, allow_face_events=not feedback.enabled)
    store = worker = None
    threads = []
    camera_ready = threading.Event()
    heartbeat_lost = threading.Event()

    try:
        if config["mode"] == "firebase":
            # Share the established Admin SDK client; never embed service keys
            # or camera/device secrets in Firestore heartbeat/commands.
            store = FirestoreEnrollmentStore(gateway.backend.client, config["stationId"], config["faceDeviceId"])
            store.start()
            worker = EnrollmentWorker(store, face, source, data_dir, stop, emit=emit, preview=preview)
            worker.recover()

            def heartbeat():
                # A bounded lease means a disconnected old process cannot keep
                # enrolling while the app targets a replacement station.
                last_success = time.monotonic()
                while not stop.wait(30):
                    try:
                        store.heartbeat(camera_ready.is_set())
                        last_success = time.monotonic()
                    except CommandRejected:
                        heartbeat_lost.set()
                        stop.set()
                        return
                    except Exception:
                        emit({"status": "HEARTBEAT_RETRY", "message": "Chưa cập nhật được kết nối Firebase"})
                        if time.monotonic() - last_success >= 60:
                            heartbeat_lost.set()
                            stop.set()
                            return

            threads.append(threading.Thread(target=heartbeat, daemon=True))

        threads.extend([
            threading.Thread(target=server.serve_forever, kwargs={"poll_interval": 0.2}, daemon=True),
            threading.Thread(target=_sync_loop, args=(gateway, stop), daemon=True),
        ])
        for thread in threads:
            thread.start()
        emit({"status": "STATION_STARTED", "mode": config["mode"], "stationId": config["stationId"],
              "host": config["host"], "port": server.server_port,
              "accessoryGateEnabled": feedback.enabled,
              "message": "Rời khung hình trước khi chấm; đăng ký khuôn mặt bằng app Admin khi station online"})
        heartbeat_dirty = True
        last_heartbeat_attempt = -float("inf")
        while not stop.is_set():
            if worker:
                request_id = store.next_command()
                if request_id:
                    presence.reset()
                    feedback.reset("REGISTERING", "Đang đăng ký khuôn mặt. Xem hướng dẫn trong yêu cầu đăng ký.")
                    try:
                        worker.run_one(request_id)
                    finally:
                        # Registration photos must never count as an attendance
                        # visit. A real empty sequence is required afterward.
                        presence.reset()
                        feedback.reset()
                    continue
            try:
                frame = source.read()
                if not camera_ready.is_set():
                    heartbeat_dirty = True
                camera_ready.set()
                result = face.recognize(frame.image)
                if stop.is_set():
                    break
                feedback.observe(result)
                attendance_feedback.observe(result)
                candidate = presence.observe(result)
                if candidate:
                    candidate.update(sourceEventId="face-" + uuid.uuid4().hex,
                                     deviceId=config["faceDeviceId"], stationId=config["stationId"],
                                     capturedAt=frame.captured_at)
                    event = Event.parse(candidate, "FACE")
                    attendance_feedback.begin()
                    # Preserve the same event until a durable local write
                    # succeeds, including while an enrollment is waiting.
                    while not stop.is_set():
                        try:
                            result = gateway.submit(event)
                            if result.get("status") == "QUEUED":
                                attendance_feedback.record_result(event, result)
                            emit(result)
                            break
                        except (OSError, sqlite3.Error):
                            emit({"status": "NOT_STORED", "message": "Đang thử lưu lại lượt chấm"})
                            try:
                                gateway.flush()
                            except (OSError, sqlite3.Error):
                                pass
                            stop.wait(2)
            except (FrameSourceError, FaceRecognitionError):
                presence.reset()
                if camera_ready.is_set():
                    heartbeat_dirty = True
                camera_ready.clear()
                feedback.reset("UNAVAILABLE", "Không lấy hoặc xử lý được ảnh camera. Chưa thể kiểm tra phụ kiện.")
                attendance_feedback.observe(RecognitionResult(reason="unavailable"))
                emit({"status": "SOURCE_ERROR", "message": "Không lấy hoặc xử lý được ảnh camera"})
                stop.wait(1)
            if store and heartbeat_dirty and time.monotonic() - last_heartbeat_attempt >= 5:
                last_heartbeat_attempt = time.monotonic()
                try:
                    store.heartbeat(camera_ready.is_set())
                    heartbeat_dirty = False
                except CommandRejected:
                    raise RuntimeError("Station đã đổi phiên; dừng tiến trình cũ")
                except Exception:
                    # Periodic heartbeat below handles prolonged failure; do
                    # not print arbitrary SDK errors carrying credential URLs.
                    heartbeat_dirty = False
            stop.wait(max(0.1, float(config["recognition"]["pollSeconds"])))
        if heartbeat_lost.is_set():
            raise RuntimeError("Station mất kết nối Firebase hoặc đã đổi phiên; chạy lại khi mạng ổn định")
    except KeyboardInterrupt:
        pass
    except Exception as exc:
        if type(exc).__module__.startswith(("google", "firebase")):
            raise RuntimeError("Không kết nối được Firebase; kiểm tra xác thực Admin SDK và mạng trên laptop") from None
        raise
    finally:
        stop.set()
        feedback.reset("STOPPED", "Dịch vụ camera đã dừng.")
        preview.close()
        # No capture/gallery work lives in a detached daemon: Ctrl+C waits for
        # this main thread's cancellation/rollback before closing endpoints.
        if any(thread.is_alive() for thread in threads):
            server.shutdown()
        server.server_close()
        source.close()
        for thread in threads:
            thread.join(timeout=6)
        if store:
            try:
                store.close()
            except Exception:
                emit({"status": "STATION_STOPPED", "message": "Đã dừng; trạng thái online sẽ hết hạn trong tối đa 90 giây"})
