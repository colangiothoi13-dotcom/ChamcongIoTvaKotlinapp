"""Admin-requested enrollment. Firestore stores metadata, never face vectors.

One station owns the camera and gallery. Commands from an earlier station
session are failed rather than unexpectedly collecting a different person.
"""

from copy import deepcopy
from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import Path
import queue
import tempfile
import threading
import time
import uuid

from .capture import capture_samples, validate_photo_employee_id
from .events import identifier
from .feedback import accessory_block_message
from .recognition import sharpness_diagnostics


TERMINAL = {"COMPLETED", "FAILED", "CANCELLED"}
GUIDED_CAPTURE_PLAN = "FRONT_LEFT_RIGHT_V1"


class CommandRejected(RuntimeError):
    def __init__(self, code):
        self.code = code
        super().__init__(code)


def _version(value):
    return value if type(value) is int and value >= 0 else 0


def _sample_count(data):
    """Six guided shots are opt-in; the original ten-photo contract remains."""
    count = data.get("sampleCount")
    plan = data.get("capturePlan")
    if type(count) is not int:
        raise CommandRejected("INVALID_SAMPLE_COUNT")
    if data.get("action") == "DELETE":
        valid = count == 0 and "capturePlan" not in data
    else:
        valid = (data.get("action") == "ENROLL" and (
            (count == 10 and "capturePlan" not in data)
            or (count == 6 and plan == GUIDED_CAPTURE_PLAN and data.get("captureMode") == "MANUAL")))
    if not valid:
        raise CommandRejected("INVALID_SAMPLE_COUNT")
    return count


def _pose_guidance(saved):
    """Operator guidance only: acceptance does not infer or certify face yaw."""
    pose = min(saved // 2, 2)
    direction = (
        "Nhìn thẳng camera.",
        "Quay nhẹ đầu sang trái của nhân viên, vẫn để camera thấy cả hai mắt.",
        "Quay nhẹ đầu sang phải của nhân viên, vẫn để camera thấy cả hai mắt.",
    )[pose]
    return f"Bước {pose + 1}/3: {direction} Chụp ảnh {saved % 2 + 1}/2 ở góc này."


def _capture_mode(data, *, pending=False):
    """Missing mode is the original Android automatic contract."""
    count = _sample_count(data)
    if "captureMode" not in data:
        if any(key in data for key in ("captureRequestSeq", "captureConsumedSeq")):
            raise CommandRejected("INVALID_CAPTURE_SEQUENCE")
        return "AUTO"
    if data.get("captureMode") != "MANUAL" or data.get("action") != "ENROLL":
        raise CommandRejected("INVALID_CAPTURE_MODE")
    requested, consumed = data.get("captureRequestSeq"), data.get("captureConsumedSeq")
    saved = data.get("samplesCaptured")
    if (type(requested) is not int or type(consumed) is not int
            or not 0 <= consumed <= requested <= 100 or requested - consumed > 1
            or type(saved) is not int or not 0 <= saved <= min(count, consumed)
            or (pending and (requested != 0 or consumed != 0 or saved != 0))):
        raise CommandRejected("INVALID_CAPTURE_SEQUENCE")
    return "MANUAL"


def _quality_explanation(issue, sharpness_score=None, sharpness_required=None):
    if issue == "blurry":
        metrics = sharpness_diagnostics(sharpness_score, sharpness_required)
        detected = (f"Đã thấy mặt, nhưng độ nét {metrics['sharpnessScore']:.1f} chưa đạt mức "
                    f"{metrics['sharpnessRequired']:.1f}." if metrics else
                    "Đã thấy mặt, nhưng ảnh chưa đủ nét.")
        return detected + " Giữ yên, tăng sáng và kiểm tra ống kính."
    return {
        "face_out_of_frame": "Khuôn mặt bị cắt ở mép ảnh. Lùi lại và đưa toàn bộ mặt vào giữa khung hình.",
        "face_too_small": "Khuôn mặt còn nhỏ trong ảnh. Đến gần hơn, vẫn giữ toàn bộ mặt trong khung hình.",
        "low_confidence": "Các nét mặt chưa rõ. Giữ mặt không bị che và tăng ánh sáng.",
        "invalid_detection": "Chưa xác định rõ khuôn mặt. Đưa toàn bộ mặt vào giữa khung hình rồi thử lại.",
    }.get(issue, accessory_block_message(issue))


def _sharpness_metadata(score=None, required=None, metric=None):
    try:
        metrics = sharpness_diagnostics(score, required, metric)
    except ValueError as exc:
        raise CommandRejected("INVALID_SHARPNESS_DIAGNOSTICS") from exc
    return {"captureLastSharpness": metrics.get("sharpnessScore"),
            "captureSharpnessRequired": metrics.get("sharpnessRequired"),
            "captureSharpnessMetric": metrics.get("sharpnessMetric")}


def _capture_message(saved, reason, quality_issue=None, *, sample_count=10, capture_plan=None,
                     sharpness_score=None, sharpness_required=None):
    if reason == "quality_ok":
        next_step = ("Đang chuẩn bị lưu mẫu." if saved == sample_count else
                     _pose_guidance(saved) if capture_plan == GUIDED_CAPTURE_PLAN else
                     "Đổi nhẹ góc mặt rồi bấm Chụp ảnh tiếp.")
        return f"Đã lưu {saved}/{sample_count} ảnh rõ mặt. " + next_step
    explanation = {
        "no_face": "Chưa thấy khuôn mặt. Đưa toàn bộ mặt vào khung hình.",
        "multiple": "Có nhiều người trong ảnh. Chỉ để một người trước camera.",
        "low_quality": "Ảnh mặt chưa đủ rõ. Tăng ánh sáng, đến gần hơn và giữ yên.",
        "invalid_image": "Ảnh camera không hợp lệ. Kiểm tra camera rồi chụp lại.",
        "camera_error": "Chưa lấy được ảnh camera. Kiểm tra nguồn và Wi-Fi rồi chụp lại.",
    }.get(reason, "Ảnh chưa đạt yêu cầu. Giữ mặt rõ trong khung hình rồi chụp lại.")
    if reason == "low_quality":
        explanation = _quality_explanation(quality_issue, sharpness_score, sharpness_required) or explanation
    guidance = f" {_pose_guidance(saved)}" if capture_plan == GUIDED_CAPTURE_PLAN else ""
    return f"Đã lưu {saved}/{sample_count} ảnh. {explanation} Bấm Chụp ảnh để thử lại.{guidance}"


class FirestoreEnrollmentStore:
    """A small SDK boundary with transactions for commands and shared locks."""

    def __init__(self, client, station_id, device_id, *, clock=None):
        self.client = client
        self.station_id = identifier(station_id, "stationId")
        self.device_id = identifier(device_id, "deviceId")
        self.clock = clock or (lambda: datetime.now(timezone.utc))
        self.session_id = uuid.uuid4().hex
        self.station = client.collection("faceStations").document(station_id)
        self.pending = queue.Queue()
        self._queued = set()
        self._queue_lock = threading.Lock()
        self._observed_id = None
        self.watch = None

    def _transaction(self, callback):
        from google.cloud import firestore
        return firestore.transactional(callback)(self.client.transaction())

    def start(self):
        from google.cloud import firestore

        def acquire(tx):
            snapshot = self.station.get(transaction=tx)
            existing = snapshot.to_dict() or {}
            # Lease ownership is decided against Firestore's read clock, so a
            # laptop clock offset cannot take over another live station.
            now = getattr(snapshot, "read_time", None) or self.clock()
            seen = existing.get("lastSeenAt")
            if (existing.get("sessionId") and isinstance(seen, datetime)
                    and now - seen < timedelta(seconds=90)):
                raise CommandRejected("STATION_ALREADY_RUNNING")
            tx.set(self.station, {
                "stationId": self.station_id, "faceDeviceId": self.device_id,
                "mode": "firebase", "sessionId": self.session_id,
                "startedAt": firestore.SERVER_TIMESTAMP,
                "lastSeenAt": firestore.SERVER_TIMESTAMP,
                "cameraReady": False,
            }, merge=True)
            return existing.get("pendingCommandId")

        prior_command = self._transaction(acquire)
        # A process killed during capture/finalization must never resume taking
        # photos automatically. The station then restores its local journal.
        commands = self.client.collection("faceEnrollmentCommands")
        if prior_command:
            snapshot = commands.document(prior_command).get()
            data = snapshot.to_dict() or {}
            if data.get("status") in {"CAPTURING", "FINALIZING"} and data.get("sessionId") != self.session_id:
                self.finish(snapshot.id, "FAILED", "STATION_RESTARTED")

        def changed(snapshots, changes, read_time):
            for snapshot in snapshots:
                self._queue_request((snapshot.to_dict() or {}).get("pendingCommandId"))

        # A document watch costs reads on changes rather than polling a query
        # every second and needs no composite index on the free Spark plan.
        self.watch = self.station.on_snapshot(changed)

    def _queue_request(self, request_id):
        with self._queue_lock:
            if request_id != self._observed_id:
                self._observed_id = request_id
                if request_id and request_id not in self._queued:
                    self._queued.add(request_id)
                    self.pending.put(request_id)

    def next_command(self):
        try:
            request_id = self.pending.get_nowait()
        except queue.Empty:
            return None
        with self._queue_lock:
            self._queued.discard(request_id)
        return request_id

    def _refs(self, request_id, data):
        return (self.client.collection("faceEnrollmentCommands").document(request_id),
                self.client.collection("faceEnrollmentLocks").document(data["employeeId"]),
                self.client.collection("employees").document(data["employeeId"]),
                self.client.collection("users").document(data["requestedBy"]),
                self.client.collection("faceProfiles").document(data["employeeId"]))

    def _validate(self, request_id, data, station, lock, employee, requester, *, pending=False):
        if (data.get("requestId") != request_id or data.get("stationId") != self.station_id
                or data.get("deviceId") != self.device_id):
            raise CommandRejected("INVALID_COMMAND")
        if data.get("sessionId") != self.session_id or station.get("sessionId") != self.session_id:
            raise CommandRejected("STATION_SESSION_CHANGED")
        if station.get("pendingCommandId") != request_id or lock.get("commandId") != request_id:
            raise CommandRejected("COMMAND_LOCK_CHANGED")
        if lock.get("stationId") != self.station_id or lock.get("employeeId") != data.get("employeeId"):
            raise CommandRejected("COMMAND_LOCK_CHANGED")
        if data.get("cancelRequested") is True:
            raise CommandRejected("CANCELLED")
        if employee.get("active") is not True:
            raise CommandRejected("EMPLOYEE_INACTIVE")
        if requester.get("active") is not True or requester.get("role") != "ADMIN":
            raise CommandRejected("REQUESTER_NOT_ADMIN")
        if data.get("action") not in {"ENROLL", "DELETE"}:
            raise CommandRejected("INVALID_ACTION")
        _sample_count(data)
        _capture_mode(data, pending=pending)
        if pending:
            requested = data.get("requestedAt")
            now = self.clock()
            # The session UUID already excludes commands from previous runs.
            # Comparing with local process startup would reject a valid server
            # timestamp whenever this laptop's clock is slightly ahead.
            if (not isinstance(requested, datetime) or requested.tzinfo is None
                    or requested > now + timedelta(seconds=30)
                    or now - requested > timedelta(minutes=5)):
                raise CommandRejected("REQUEST_EXPIRED")

    def claim(self, request_id):
        identifier(request_id, "requestId")
        command = self.client.collection("faceEnrollmentCommands").document(request_id)

        def claim(tx):
            snapshot = command.get(transaction=tx)
            data = snapshot.to_dict() or {}
            if not snapshot.exists or data.get("status") != "PENDING":
                return None
            if data.get("stationId") != self.station_id or data.get("deviceId") != self.device_id:
                return None
            validate_photo_employee_id(data.get("employeeId"))
            identifier(data.get("requestedBy"), "requestedBy")
            _, lock_ref, employee_ref, user_ref, _ = self._refs(request_id, data)
            station = self.station.get(transaction=tx).to_dict() or {}
            lock = lock_ref.get(transaction=tx).to_dict() or {}
            employee = employee_ref.get(transaction=tx).to_dict() or {}
            user = user_ref.get(transaction=tx).to_dict() or {}
            self._validate(request_id, data, station, lock, employee, user, pending=True)
            tx.update(command, {"status": "CAPTURING", "startedAt": self.clock(),
                                "samplesCaptured": 0, "message": (
                                    (_pose_guidance(0) if data.get("capturePlan") == GUIDED_CAPTURE_PLAN else
                                     "Đứng một mình trước camera rồi bấm Chụp ảnh để lấy từng ảnh mẫu.")
                                    if _capture_mode(data) == "MANUAL" else
                                    "Đứng một mình trước camera, nhìn thẳng rồi xoay nhẹ mặt")})
            return data

        try:
            return self._transaction(claim)
        except (CommandRejected, ValueError) as exc:
            code = exc.code if isinstance(exc, CommandRejected) else "INVALID_COMMAND"
            self.finish(request_id, "CANCELLED" if code == "CANCELLED" else "FAILED", code)
            return None

    def check_active(self, request_id):
        data = self.client.collection("faceEnrollmentCommands").document(request_id).get().to_dict() or {}
        if data.get("status") != "CAPTURING" or data.get("sessionId") != self.session_id:
            raise CommandRejected("COMMAND_CHANGED")
        if data.get("cancelRequested") is True:
            raise CommandRejected("CANCELLED")
        return data

    def progress(self, request_id, count, *, sharpness_score=None, sharpness_required=None, sharpness_metric=None):
        metrics = _sharpness_metadata(sharpness_score, sharpness_required, sharpness_metric)
        data = self.check_active(request_id)
        total = _sample_count(data)
        if type(count) is not int or not 0 <= count <= total:
            raise CommandRejected("INVALID_CAPTURE_SEQUENCE")
        self.client.collection("faceEnrollmentCommands").document(request_id).update({
            "samplesCaptured": count, "message": f"Đã thu {count}/{total} ảnh rõ mặt",
            "captureLastReason": "quality_ok", "captureLastQualityIssue": None,
            **metrics,
        })

    def reject_capture(self, request_id, saved, quality_issue, *, sharpness_score=None, sharpness_required=None,
                       sharpness_metric=None):
        """Feedback for an AUTO rejection; never increments photo progress.

        The worker deduplicates/throttles this metadata update. Validate the
        command and shared locks in the same transaction as the feedback.
        """
        metrics = _sharpness_metadata(sharpness_score, sharpness_required, sharpness_metric)
        explanation = _quality_explanation(quality_issue, sharpness_score, sharpness_required)
        if explanation is None:
            raise CommandRejected("INVALID_QUALITY_ISSUE")
        command = self.client.collection("faceEnrollmentCommands").document(request_id)

        def reject(tx):
            data = command.get(transaction=tx).to_dict() or {}
            if data.get("status") != "CAPTURING" or _capture_mode(data) != "AUTO":
                raise CommandRejected("COMMAND_CHANGED")
            _, lock_ref, employee_ref, user_ref, _ = self._refs(request_id, data)
            station = self.station.get(transaction=tx).to_dict() or {}
            lock = lock_ref.get(transaction=tx).to_dict() or {}
            employee = employee_ref.get(transaction=tx).to_dict() or {}
            user = user_ref.get(transaction=tx).to_dict() or {}
            self._validate(request_id, data, station, lock, employee, user)
            if type(saved) is not int or saved != data.get("samplesCaptured"):
                raise CommandRejected("INVALID_CAPTURE_SEQUENCE")
            tx.update(command, {
                "captureLastReason": "low_quality", "captureLastQualityIssue": quality_issue,
                "message": f"Đã lưu {saved}/{data['sampleCount']} ảnh. {explanation} Hệ thống sẽ thử lại khi mặt đạt yêu cầu.",
                **metrics,
            })

        self._transaction(reject)

    def watch_command(self, request_id, callback):
        """Only active manual work attaches this document watch; no polling."""
        command = self.client.collection("faceEnrollmentCommands").document(request_id)

        def changed(snapshots, changes, read_time):
            callback((snapshots[-1].to_dict() or {}) if snapshots else {})

        return command.on_snapshot(changed)

    def _active_manual(self, tx, request_id, sequence):
        command = self.client.collection("faceEnrollmentCommands").document(request_id)
        data = command.get(transaction=tx).to_dict() or {}
        if data.get("status") != "CAPTURING" or _capture_mode(data) != "MANUAL":
            raise CommandRejected("COMMAND_CHANGED")
        _, lock_ref, employee_ref, user_ref, _ = self._refs(request_id, data)
        station = self.station.get(transaction=tx).to_dict() or {}
        lock = lock_ref.get(transaction=tx).to_dict() or {}
        employee = employee_ref.get(transaction=tx).to_dict() or {}
        user = user_ref.get(transaction=tx).to_dict() or {}
        self._validate(request_id, data, station, lock, employee, user)
        if (type(sequence) is not int or data["captureRequestSeq"] != sequence
                or data["captureConsumedSeq"] != sequence - 1 or data["samplesCaptured"] >= data["sampleCount"]):
            raise CommandRejected("INVALID_CAPTURE_SEQUENCE")
        return command, data

    def check_manual_request(self, request_id, sequence):
        self._transaction(lambda tx: self._active_manual(tx, request_id, sequence))

    def acknowledge_capture(self, request_id, sequence, saved, reason, quality_issue=None, *,
                            sharpness_score=None, sharpness_required=None, sharpness_metric=None):
        """Accept/reject one click atomically; clients never acknowledge it."""
        metrics = _sharpness_metadata(sharpness_score, sharpness_required, sharpness_metric)
        def acknowledge(tx):
            command, data = self._active_manual(tx, request_id, sequence)
            expected = data["samplesCaptured"] + (1 if reason == "quality_ok" else 0)
            if type(saved) is not int or saved != expected or saved > data["sampleCount"]:
                raise CommandRejected("INVALID_CAPTURE_SEQUENCE")
            tx.update(command, {"captureConsumedSeq": sequence, "samplesCaptured": saved,
                                "captureLastReason": reason, "captureLastQualityIssue": quality_issue,
                                **metrics,
                                "message": _capture_message(saved, reason, quality_issue,
                                                            sample_count=data["sampleCount"],
                                                            capture_plan=data.get("capturePlan"),
                                                            sharpness_score=sharpness_score,
                                                            sharpness_required=sharpness_required)})

        self._transaction(acknowledge)

    def begin_finalizing(self, request_id, local_version):
        command = self.client.collection("faceEnrollmentCommands").document(request_id)

        def finalize(tx):
            data = command.get(transaction=tx).to_dict() or {}
            if data.get("status") != "CAPTURING":
                raise CommandRejected("COMMAND_CHANGED")
            _, lock_ref, employee_ref, user_ref, profile_ref = self._refs(request_id, data)
            station = self.station.get(transaction=tx).to_dict() or {}
            lock = lock_ref.get(transaction=tx).to_dict() or {}
            employee = employee_ref.get(transaction=tx).to_dict() or {}
            user = user_ref.get(transaction=tx).to_dict() or {}
            profile = profile_ref.get(transaction=tx).to_dict() or {}
            self._validate(request_id, data, station, lock, employee, user)
            if (_capture_mode(data) == "MANUAL" and
                    (data["samplesCaptured"] != data["sampleCount"] or data["captureRequestSeq"] != data["captureConsumedSeq"])):
                raise CommandRejected("CAPTURE_INCOMPLETE")
            version = max(_version(local_version), _version(profile.get("version"))) + 1
            tx.update(command, {"status": "FINALIZING", "profileVersion": version,
                                "message": "Đang lưu mẫu khuôn mặt"})
            return version

        return self._transaction(finalize)

    def complete(self, request_id, version, action):
        from google.cloud import firestore
        command = self.client.collection("faceEnrollmentCommands").document(request_id)

        def commit(tx):
            data = command.get(transaction=tx).to_dict() or {}
            if data.get("status") != "FINALIZING" or data.get("profileVersion") != version:
                raise CommandRejected("COMMAND_CHANGED")
            _, lock_ref, employee_ref, user_ref, profile_ref = self._refs(request_id, data)
            station = self.station.get(transaction=tx).to_dict() or {}
            lock = lock_ref.get(transaction=tx).to_dict() or {}
            employee = employee_ref.get(transaction=tx).to_dict() or {}
            user = user_ref.get(transaction=tx).to_dict() or {}
            self._validate(request_id, data, station, lock, employee, user)
            profile = {"employeeId": data["employeeId"], "enabled": action == "ENROLL", "version": version,
                       "stationId": self.station_id, "deviceId": self.device_id, "updatedAt": self.clock()}
            if action == "ENROLL":
                profile["enrolledAt"] = self.clock()
                profile["sampleCount"] = data["sampleCount"]
                profile["capturePlan"] = data.get("capturePlan", firestore.DELETE_FIELD)
            tx.set(profile_ref, profile, merge=True)
            tx.update(command, {"status": "COMPLETED", "completedAt": self.clock(), "errorCode": "",
                                "message": "Đã đăng ký khuôn mặt" if action == "ENROLL" else "Đã xóa mẫu khuôn mặt"})
            tx.update(self.station, {"pendingCommandId": firestore.DELETE_FIELD, "updatedAt": self.clock()})
            tx.delete(lock_ref)

        self._transaction(commit)

    def result(self, request_id):
        return self.client.collection("faceEnrollmentCommands").document(request_id).get().to_dict() or {}

    def finish(self, request_id, status, error_code):
        from google.cloud import firestore
        command = self.client.collection("faceEnrollmentCommands").document(request_id)

        def finish(tx):
            data = command.get(transaction=tx).to_dict() or {}
            if not data or data.get("status") in TERMINAL:
                return
            if data.get("stationId") != self.station_id or data.get("deviceId") != self.device_id:
                return
            station = self.station.get(transaction=tx).to_dict() or {}
            # Read every dependency before issuing transaction writes.
            lock_ref = None
            lock = {}
            try:
                identifier(data.get("employeeId"), "employeeId")
                lock_ref = self.client.collection("faceEnrollmentLocks").document(data["employeeId"])
                lock = lock_ref.get(transaction=tx).to_dict() or {}
            except ValueError:
                pass
            messages = {
                "CANCELLED": "Đã hủy đăng ký khuôn mặt", "REQUEST_EXPIRED": "Yêu cầu đã hết hạn, hãy đăng ký lại",
                "STATION_SESSION_CHANGED": "Laptop đã khởi động lại, hãy đăng ký lại",
                "STATION_RESTARTED": "Laptop đã dừng giữa chừng, hãy đăng ký lại",
                "CAPTURE_INCOMPLETE": f"Chưa đủ {data.get('sampleCount', 10)} ảnh rõ mặt; kiểm tra ánh sáng và đứng một mình",
                "CAPTURE_IDLE_TIMEOUT": "Đã chờ chụp ảnh quá 5 phút. Hãy đăng ký lại và bấm Chụp ảnh.",
                "EMPLOYEE_INACTIVE": "Nhân viên đã ngừng hoạt động",
                "REQUESTER_NOT_ADMIN": "Tài khoản yêu cầu không còn quyền quản lý",
            }
            tx.update(command, {"status": status, "completedAt": self.clock(), "errorCode": error_code,
                                "message": messages.get(error_code, "Không lưu được mẫu mặt; kiểm tra laptop và thử lại")})
            if station.get("pendingCommandId") == request_id:
                tx.update(self.station, {"pendingCommandId": firestore.DELETE_FIELD, "updatedAt": self.clock()})
            if lock_ref and lock.get("commandId") == request_id and lock.get("stationId") == self.station_id:
                tx.delete(lock_ref)

        self._transaction(finish)

    def heartbeat(self, camera_ready):
        from google.cloud import firestore

        def beat(tx):
            station = self.station.get(transaction=tx).to_dict() or {}
            if station.get("sessionId") != self.session_id:
                raise CommandRejected("STATION_SESSION_CHANGED")
            tx.update(self.station, {"lastSeenAt": firestore.SERVER_TIMESTAMP, "cameraReady": bool(camera_ready),
                                     "mode": "firebase"})
            return station.get("pendingCommandId")
        # The lease read also recovers a queued request if a watch reconnect
        # missed a change. No extra Firestore read is needed.
        self._queue_request(self._transaction(beat))

    def close(self):
        from google.cloud import firestore

        if self.watch:
            self.watch.unsubscribe()
            self.watch = None
        # A stopped station is immediately unavailable to the app. Never take
        # ownership back from a replacement process.
        def stop(tx):
            station = self.station.get(transaction=tx).to_dict() or {}
            if station.get("sessionId") == self.session_id:
                tx.update(self.station, {"cameraReady": False,
                                        "sessionId": firestore.DELETE_FIELD,
                                        "lastSeenAt": firestore.SERVER_TIMESTAMP})
        self._transaction(stop)


class _ManualCapture:
    """Watch metadata; the station main thread consumes exactly one click.

    The started sequence is local and advances before camera access, so an old
    snapshot or a lost acknowledgement never replays a frame capture.
    """
    def __init__(self, store, request_id, command, stop, idle_timeout=300):
        self.store, self.request_id, self.command, self.stop = store, request_id, command, stop
        self.idle_timeout = idle_timeout
        self.condition = threading.Condition()
        self.latest = None
        self.started_sequence = 0
        self.pending_sequence = None
        self.watch = store.watch_command(request_id, self._changed)

    def _changed(self, data):
        with self.condition:
            self.latest = deepcopy(data)
            self.condition.notify_all()

    def _check(self, data):
        if self.stop.is_set():
            raise CommandRejected("STATION_STOPPED")
        if data is None:
            return
        if data.get("cancelRequested") is True:
            raise CommandRejected("CANCELLED")
        if (data.get("status") != "CAPTURING"
                or data.get("sessionId") != self.command.get("sessionId")
                or data.get("requestId") != self.request_id
                or data.get("sampleCount") != self.command.get("sampleCount")
                or data.get("capturePlan") != self.command.get("capturePlan")
                or _capture_mode(data) != "MANUAL"):
            raise CommandRejected("COMMAND_CHANGED")

    def cancelled(self):
        with self.condition:
            self._check(self.latest)
        return False

    def wait_for_click(self):
        deadline = time.monotonic() + self.idle_timeout
        with self.condition:
            while True:
                self._check(self.latest)
                data = self.latest
                if data is not None:
                    requested, consumed = data["captureRequestSeq"], data["captureConsumedSeq"]
                    if requested > self.started_sequence + 1 or consumed > self.started_sequence:
                        raise CommandRejected("INVALID_CAPTURE_SEQUENCE")
                    if requested == self.started_sequence + 1 and consumed == self.started_sequence:
                        self.started_sequence = requested
                        self.pending_sequence = requested
                        break
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise CommandRejected("CAPTURE_IDLE_TIMEOUT")
                # The Event may be set by heartbeat loss or Ctrl+C. A bounded
                # local wait notices it without any Firestore get/poll loop.
                self.condition.wait(min(remaining, 0.2))
        self.store.check_manual_request(self.request_id, self.pending_sequence)

    def acknowledge(self, value):
        if self.pending_sequence is None:
            raise CommandRejected("INVALID_CAPTURE_SEQUENCE")
        reason = ("quality_ok" if value["status"] == "CAPTURE_SAVED" else
                  "camera_error" if value["status"] == "CAPTURE_SOURCE_ERROR" else value.get("reason", "invalid_image"))
        issue = value.get("qualityIssue")
        metrics = ({"sharpness_score": value.get("sharpnessScore"),
                    "sharpness_required": value.get("sharpnessRequired"),
                    "sharpness_metric": value.get("sharpnessMetric")}
                   if any(key in value for key in ("sharpnessScore", "sharpnessRequired", "sharpnessMetric")) else {})
        if issue is None:
            self.store.acknowledge_capture(self.request_id, self.pending_sequence, value["saved"], reason, **metrics)
        else:
            self.store.acknowledge_capture(self.request_id, self.pending_sequence, value["saved"], reason, issue, **metrics)
        self.pending_sequence = None

    def close(self):
        if self.watch:
            watch, self.watch = self.watch, None
            watch.unsubscribe()


class _BorrowedSource:
    """capture_samples closes its input; the station owns this shared source."""
    is_live = True

    def __init__(self, source):
        self.source = source

    def read(self):
        return self.source.read()

    def close(self):
        pass


class EnrollmentWorker:
    def __init__(self, store, recognizer, source, data_dir, stop, *, emit=None, interval=1.0,
                 manual_idle_timeout=300, preview=None):
        self.store, self.face, self.source = store, recognizer, source
        self.data_dir, self.stop = Path(data_dir), stop
        self.emit = emit or (lambda value: None)
        self.interval = interval
        self.manual_idle_timeout = manual_idle_timeout
        self.preview = preview
        self.journal = self.data_dir / "enrollment-journal.json"

    def _journal(self, value):
        self.data_dir.mkdir(parents=True, exist_ok=True)
        temporary = None
        try:
            with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=self.data_dir, suffix=".tmp", delete=False) as stream:
                temporary = stream.name
                json.dump(value, stream, ensure_ascii=False, allow_nan=False)
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temporary, self.journal)
        finally:
            if temporary and Path(temporary).exists():
                Path(temporary).unlink()

    def _restore(self, data):
        profiles = dict(self.face.profiles)
        employee_id = data["employeeId"]
        if data.get("previousProfile") is None:
            profiles.pop(employee_id, None)
        else:
            profiles[employee_id] = data["previousProfile"]
        self.face._save_gallery(profiles)
        self.face.profiles = profiles

    def recover(self):
        if not self.journal.exists():
            return
        data = json.loads(self.journal.read_text(encoding="utf-8"))
        validate_photo_employee_id(data["employeeId"])
        result = self.store.result(data["requestId"])
        if not (result.get("status") == "COMPLETED" and result.get("profileVersion") == data["version"]):
            self._restore(data)
            self.store.finish(data["requestId"], "FAILED", "STATION_RESTARTED")
        self.journal.unlink()

    def run_one(self, request_id):
        """Called between attendance frames: no recognition runs concurrently."""
        command = self.store.claim(request_id)
        if not command:
            return False
        employee_id, action = command["employeeId"], command["action"]
        previous = deepcopy(self.face.profiles.get(employee_id))
        journal = None
        finalized = False
        manual = None
        last_auto_issue = None
        last_auto_feedback = -float("inf")

        def cancelled():
            if self.stop.is_set():
                raise CommandRejected("STATION_STOPPED")
            if manual:
                manual.cancelled()
            else:
                self.store.check_active(request_id)
            return False

        def progress(value):
            nonlocal last_auto_issue, last_auto_feedback
            metrics = ({"sharpness_score": value.get("sharpnessScore"),
                        "sharpness_required": value.get("sharpnessRequired"),
                        "sharpness_metric": value.get("sharpnessMetric")}
                       if any(key in value for key in ("sharpnessScore", "sharpnessRequired", "sharpnessMetric")) else {})
            if manual:
                manual.acknowledge(value)
            elif value.get("status") == "CAPTURE_SAVED":
                self.store.progress(request_id, value["saved"], **metrics)
                last_auto_issue = None
            elif value.get("status") == "CAPTURE_SKIP" and _quality_explanation(value.get("qualityIssue")):
                now = time.monotonic()
                issue = value["qualityIssue"]
                diagnostic = sharpness_diagnostics(metrics.get("sharpness_score"), metrics.get("sharpness_required"),
                                                    metrics.get("sharpness_metric"))
                feedback_key = (issue, round(diagnostic["sharpnessScore"], 1) if diagnostic else None,
                                diagnostic.get("sharpnessRequired"), diagnostic.get("sharpnessMetric"))
                if feedback_key != last_auto_issue and now - last_auto_feedback >= 5:
                    self.store.reject_capture(request_id, value["saved"], issue, **metrics)
                    last_auto_issue, last_auto_feedback = feedback_key, now
            self.emit({key: value[key] for key in ("status", "saved", "requested", "reason", "qualityIssue",
                                                 "sharpnessScore", "sharpnessRequired", "sharpnessMetric") if key in value})

        def preview_attempt(image, value):
            reason = ("quality_ok" if value["status"] == "CAPTURE_SAVED" else
                      "camera_error" if value["status"] == "CAPTURE_SOURCE_ERROR" else value.get("reason", "invalid_image"))
            self.preview.publish(request_id, manual.pending_sequence, image, reason=reason,
                                 saved_count=value["saved"], sample_count=command["sampleCount"])

        try:
            files = ()
            if action == "ENROLL":
                sample_count = _sample_count(command)
                if _capture_mode(command) == "MANUAL":
                    manual = _ManualCapture(self.store, request_id, command, self.stop,
                                            self.manual_idle_timeout)
                    if self.preview:
                        try:
                            self.preview.begin(request_id)
                        except Exception:
                            pass
                result = capture_samples(_BorrowedSource(self.source), self.face,
                                         self.data_dir / "photos", employee_id, count=sample_count,
                                         interval=0 if manual else self.interval, max_attempts=100,
                                         progress=progress, stop_requested=cancelled,
                                         before_attempt=manual.wait_for_click if manual else None,
                                         on_attempt=preview_attempt if manual and self.preview else None)
                if result.stopped:
                    self.stop.set()
                if result.stopped or self.stop.is_set():
                    raise CommandRejected("STATION_STOPPED")
                if len(result.files) != sample_count:
                    raise CommandRejected("CAPTURE_INCOMPLETE")
                # Use exactly this request's fresh photos, never old folder files.
                files = result.files
            cancelled()
            version = self.store.begin_finalizing(request_id, _version((previous or {}).get("profileVersion")))
            if self.stop.is_set():
                raise CommandRejected("STATION_STOPPED")
            journal = {"requestId": request_id, "employeeId": employee_id,
                       "version": version, "previousProfile": previous, "action": action}
            self._journal(journal)
            if action == "ENROLL":
                self.face.enroll(employee_id, files, version)
            else:
                updated = dict(self.face.profiles)
                updated.pop(employee_id, None)
                self.face._save_gallery(updated)
                self.face.profiles = updated
            if self.stop.is_set():
                raise CommandRejected("STATION_STOPPED")
            try:
                self.store.complete(request_id, version, action)
                finalized = True
            except Exception:
                # SDK can lose the acknowledgement AFTER its atomic commit.
                # If even this read fails, retain the journal and stop the
                # station; startup recovery decides without collecting again.
                result = self.store.result(request_id)
                finalized = result.get("status") == "COMPLETED" and result.get("profileVersion") == version
                if not finalized:
                    raise
            self.emit({"status": "COMPLETED", "employeeId": employee_id, "profileVersion": version})
        except BaseException as exc:
            if journal and not finalized:
                try:
                    result = self.store.result(request_id)
                except Exception as uncertain:
                    raise RuntimeError("Chưa xác nhận được kết quả đăng ký; khởi động lại station khi Firebase kết nối lại") from uncertain
                if result.get("status") == "COMPLETED" and result.get("profileVersion") == journal["version"]:
                    finalized = True
                else:
                    self._restore(journal)
            if not finalized:
                code = exc.code if isinstance(exc, CommandRejected) else "ENROLLMENT_FAILED"
                status = "CANCELLED" if code in {"CANCELLED", "STATION_STOPPED"} or isinstance(exc, KeyboardInterrupt) else "FAILED"
                self.store.finish(request_id, status, code)
                self.emit({"status": status, "employeeId": employee_id, "errorCode": code})
            if isinstance(exc, (KeyboardInterrupt, SystemExit)):
                raise
        finally:
            if manual:
                manual.close()
                if self.preview and not finalized:
                    try:
                        self.preview.clear(request_id)
                    except Exception:
                        pass
            if journal and finalized:
                self.journal.unlink(missing_ok=True)
        # A safely rolled-back failure can clear the journal too. An uncertain
        # Firestore result above exits by exception and retains it for recovery.
        if self.journal.exists():
            self.journal.unlink()
        return True
