from datetime import datetime, timedelta, timezone
import hashlib
import re

from .events import attendance_document, response


def _utc_now():
    return datetime.now(timezone.utc)


def resolve_identity(event, registry):
    p = event.payload
    if p["method"] == "FINGERPRINT":
        mapping = registry.get("fingerprintMappings", {}).get(str(p["templateId"]))
        if not mapping or mapping.get("enabled") is not True:
            return None, "FINGERPRINT_DISABLED"
        employee_id = mapping.get("employeeId")
        employee = registry.get("employees", {}).get(employee_id)
        if employee and (employee.get("fingerprintTemplateId") != p["templateId"] or
                         employee.get("fingerprintDeviceId", "GATE-01") != p["deviceId"]):
            return None, "FINGERPRINT_MAPPING_CHANGED"
    else:
        employee_id = p["employeeId"]
        profile = registry.get("faceProfiles", {}).get(employee_id)
        if not profile or profile.get("enabled") is not True:
            return None, "FACE_DISABLED"
        if profile.get("employeeId") != employee_id or profile.get("version") != p["profileVersion"]:
            return None, "FACE_PROFILE_CHANGED"
        employee = registry.get("employees", {}).get(employee_id)
    if not employee or employee.get("active") is not True:
        return None, "EMPLOYEE_INACTIVE"
    return (employee_id, employee), None


class FirestoreBackend:
    mode = "firebase"

    def __init__(self, client, duplicate_seconds=180, *, face_door=None):
        self.client, self.window = client, duplicate_seconds
        self.face_door = validate_face_door(face_door)

    def _face_door_command(self, tx, event, firestore, employee_name):
        """Read the shared device slot before attendance writes, never reopen on replay.

        The clock is delivery time, deliberately independent of the durable
        outbox's received_at. An old queued scan may record attendance but must
        not open a door when Internet access returns.
        """
        policy = self.face_door
        if not policy["enabled"] or event.payload["method"] != "FACE":
            return None, None, None
        now = _utc_now()
        age = (now - event.captured_at).total_seconds()
        if age < -3:
            return "SKIPPED_CLOCK_SKEW", None, None
        if age > policy["maxEventAgeSeconds"]:
            return "SKIPPED_EXPIRED", None, None
        device_id = policy["targetDeviceId"]
        device_ref = self.client.collection("devices").document(device_id)
        device = device_ref.get(transaction=tx).to_dict() or {}
        if device.get("firmwareVersion") not in policy["requiredFirmware"]:
            return "SKIPPED_UNSUPPORTED_FIRMWARE", None, None
        heartbeat = device.get("lastHeartbeat")
        if (device.get("deviceId") != device_id or device.get("status") != "ONLINE"
                or device.get("wifiStatus") == "OFFLINE" or device.get("online") is False
                or device.get("active") is False or not isinstance(heartbeat, datetime)
                or heartbeat.tzinfo is None):
            return "SKIPPED_OFFLINE", None, None
        heartbeat_age = (now - heartbeat).total_seconds()
        if heartbeat_age < -3 or heartbeat_age > policy["heartbeatMaxAgeSeconds"]:
            return "SKIPPED_OFFLINE", None, None
        capabilities = device.get("capabilities")
        if not isinstance(capabilities, (list, tuple)) or "door" not in capabilities:
            return "SKIPPED_NO_DOOR", None, None
        command_ref = self.client.collection("deviceCommands").document(device_id)
        command = command_ref.get(transaction=tx)
        if command.exists:
            current = command.to_dict() or {}
            # Even a terminal fingerprint command can still be waiting for its
            # employee/mapping update. Never replace it until applied is true.
            if (current.get("status") not in {"COMPLETED", "FAILED", "CANCELLED"}
                    or current.get("applied") is not True):
                return "SKIPPED_BUSY", None, None
        data = {"requestId": "face-door-" + event.key, "deviceId": device_id,
                "type": "OPEN_DOOR", "status": "REQUESTED", "applied": True,
                "createdAt": firestore.SERVER_TIMESTAMP,
                "expiresAt": now + timedelta(seconds=policy["commandTTLSeconds"]),
                "source": "FACE_ATTENDANCE", "attendanceId": event.key,
                "employeeName": str(employee_name or "").strip()[:120], "message": ""}
        return "REQUESTED", command_ref, data

    def deliver(self, event, received_at):
        from google.cloud import firestore
        from google.cloud.firestore_v1.base_query import FieldFilter
        db, p = self.client, event.payload
        receipt_ref = db.collection("attendanceReceipts").document(event.key)

        @firestore.transactional
        def commit(tx):
            receipt = receipt_ref.get(transaction=tx)
            if receipt.exists:
                data = receipt.to_dict()
                if data.get("digest") != event.digest:
                    return response(event, "REJECTED", reason="EVENT_ID_CONFLICT")
                return data["result"]

            registry = {"employees": {}, "fingerprintMappings": {}, "faceProfiles": {}}
            if p["method"] == "FINGERPRINT":
                mapping = db.collection("fingerprintMappings").document(str(p["templateId"])).get(transaction=tx)
                if mapping.exists:
                    registry["fingerprintMappings"][str(p["templateId"])] = mapping.to_dict()
                employee_id = (mapping.to_dict() or {}).get("employeeId")
            else:
                employee_id = p["employeeId"]
                profile = db.collection("faceProfiles").document(employee_id).get(transaction=tx)
                if profile.exists:
                    registry["faceProfiles"][employee_id] = profile.to_dict()
            if employee_id:
                employee = db.collection("employees").document(employee_id).get(transaction=tx)
                if employee.exists:
                    registry["employees"][employee_id] = employee.to_dict()
            identity, reason = resolve_identity(event, registry)
            if reason:
                result = response(event, "REJECTED", reason=reason)
            else:
                employee_id, employee = identity
                employee_name = employee.get("fullName")
                if not isinstance(employee_name, str) or not employee_name.strip():
                    employee_name = employee_id
                gate_id = hashlib.sha256(f"{p['stationId']}\0{employee_id}".encode()).hexdigest()
                gate = db.collection("attendanceGates").document(gate_id)
                gate_state = gate.get(transaction=tx)
                history = gate.collection("events")
                lower = event.captured_at - timedelta(seconds=self.window)
                upper = event.captured_at + timedelta(seconds=self.window)
                query = history.where(filter=FieldFilter("capturedAt", ">=", lower)).where(
                    filter=FieldFilter("capturedAt", "<=", upper)).order_by("capturedAt").limit(1)
                matches = list(query.stream(transaction=tx))
                if matches:
                    result = response(event, "DUPLICATE_SYNCED", matches[0].to_dict()["attendanceId"],
                                      employee_name=employee_name)
                else:
                    result = response(event, "ACCEPTED_SYNCED", event.key, employee_name=employee_name)
                    door_status, command_ref, command_data = self._face_door_command(
                        tx, event, firestore, employee_name)
                    document = attendance_document(event, employee_id, employee.get("fullName", ""), received_at)
                    if door_status:
                        result["doorCommandStatus"] = door_status
                        document["doorCommandStatus"] = door_status
                    # All device and command reads above participate in the
                    # same transaction as attendance and its immutable receipt.
                    if command_ref is not None:
                        tx.set(command_ref, command_data)
                    revision = (gate_state.to_dict() or {}).get("revision", 0) + 1
                    tx.set(gate, {"revision": revision, "employeeId": employee_id,
                                  "stationId": p["stationId"], "updatedAt": firestore.SERVER_TIMESTAMP})
                    tx.create(history.document(event.key), {"attendanceId": event.key,
                                                            "capturedAt": event.captured_at})
                    tx.create(db.collection("attendance").document(event.key), document)
            tx.create(receipt_ref, {"digest": event.digest, "result": result,
                                   "sourceEventId": p["sourceEventId"], "deviceId": p["deviceId"],
                                   "method": p["method"], "capturedAt": event.captured_at,
                                   "receivedAt": received_at})
            return result

        return commit(db.transaction())


FACE_DOOR_FIRMWARE = frozenset({
    "spark-anonymous-v17-face-door-ttl", "spark-gateway-v18-face-door-ttl",
    "spark-anonymous-v18-face-name-door-ttl", "spark-gateway-v19-face-name-door-ttl",
})


def validate_face_door(value):
    """Only firmware with its own expiry check may receive automatic commands."""
    if value is None:
        return {"enabled": False}
    if not isinstance(value, dict) or type(value.get("enabled")) is not bool:
        raise ValueError("faceDoor.enabled phải là true hoặc false")
    policy = dict(value)
    if not policy["enabled"]:
        return {"enabled": False}
    target = policy.get("targetDeviceId")
    if not isinstance(target, str) or not re.fullmatch(r"[A-Za-z0-9_.:-]{1,160}", target):
        raise ValueError("faceDoor.targetDeviceId không hợp lệ")
    firmware = policy.get("requiredFirmware")
    if isinstance(firmware, str):
        firmware = [firmware]
    if (not isinstance(firmware, (list, tuple)) or not firmware
            or any(not isinstance(item, str) or item not in FACE_DOOR_FIRMWARE for item in firmware)):
        raise ValueError("faceDoor.requiredFirmware phải là bản firmware hỗ trợ hạn lệnh cửa")
    policy["requiredFirmware"] = tuple(firmware)
    for field, default, lower, upper in (("maxEventAgeSeconds", 15, 1, 15),
                                         ("commandTTLSeconds", 15, 1, 15),
                                         ("heartbeatMaxAgeSeconds", 90, 1, 90)):
        value = policy.get(field, default)
        if type(value) is not int or not lower <= value <= upper:
            raise ValueError(f"faceDoor.{field} phải là số nguyên từ {lower} đến {upper}")
        policy[field] = value
    return policy


def create_firestore_client(project_id):
    # Lazy import lets the offline demo and core tests run with standard Python only.
    import os
    if os.environ.get("FIRESTORE_EMULATOR_HOST"):
        from google.cloud import firestore
        from google.auth.credentials import AnonymousCredentials
        return firestore.Client(project=project_id, credentials=AnonymousCredentials())
    import firebase_admin
    from firebase_admin import firestore
    try:
        app_name = "face-attendance-" + hashlib.sha256(project_id.encode()).hexdigest()[:16]
        try:
            app = firebase_admin.get_app(app_name)
        except ValueError:
            app = firebase_admin.initialize_app(options={"projectId": project_id}, name=app_name)
        return firestore.client(app)
    except Exception as exc:
        # Missing/invalid ADC errors do not inherit RuntimeError. Convert at
        # this boundary so launcher users get guidance rather than an SDK
        # traceback that may include credential paths or remote URLs.
        if type(exc).__module__.startswith(("google", "firebase")):
            raise RuntimeError("Chưa kết nối được Firebase; kiểm tra khóa Admin SDK riêng trên laptop và mạng") from None
        raise
