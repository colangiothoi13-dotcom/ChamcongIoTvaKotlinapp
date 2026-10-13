from dataclasses import dataclass
from datetime import datetime, timezone
import hashlib
import json
import math
import re


class InvalidEvent(ValueError):
    pass


def identifier(value, field):
    if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9_.:-]{1,160}", value):
        raise InvalidEvent(f"{field} không hợp lệ")
    return value


def utc_time(value):
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        if parsed.tzinfo is None or parsed.utcoffset().total_seconds() != 0:
            raise ValueError()
        return parsed.astimezone(timezone.utc)
    except (AttributeError, TypeError, ValueError):
        raise InvalidEvent("capturedAt phải là thời điểm UTC có Z hoặc +00:00") from None


@dataclass(frozen=True)
class Event:
    payload: dict

    @classmethod
    def parse(cls, data, method):
        if not isinstance(data, dict) or method not in ("FACE", "FINGERPRINT"):
            raise InvalidEvent("Sai kiểu sự kiện")
        p = {key: identifier(data.get(key), key) for key in
             ("sourceEventId", "deviceId", "stationId")}
        p["capturedAt"] = utc_time(data.get("capturedAt")).isoformat()
        p["method"] = method
        if method == "FINGERPRINT":
            for key, lower, upper in (("templateId", 1, 65535), ("confidence", 0, 65535)):
                value = data.get(key)
                if type(value) is not int or not lower <= value <= upper:
                    raise InvalidEvent(f"{key} không hợp lệ")
                p[key] = value
        else:
            p["employeeId"] = identifier(data.get("employeeId"), "employeeId")
            version = data.get("profileVersion")
            score = data.get("faceSimilarity")
            if type(version) is not int or version < 1:
                raise InvalidEvent("profileVersion không hợp lệ")
            if type(score) not in (int, float) or not math.isfinite(score) or not -1 <= score <= 1:
                raise InvalidEvent("faceSimilarity không hợp lệ")
            p.update(profileVersion=version, faceSimilarity=float(score))
        return cls(p)

    @property
    def captured_at(self):
        return utc_time(self.payload["capturedAt"])

    @property
    def key(self):
        p = self.payload
        return hashlib.sha256(f"{p['deviceId']}\0{p['sourceEventId']}".encode()).hexdigest()

    @property
    def digest(self):
        return hashlib.sha256(self.json.encode()).hexdigest()

    @property
    def json(self):
        return json.dumps(self.payload, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def response(event, status, attendance_id=None, reason=None):
    result = {"sourceEventId": event.payload["sourceEventId"], "status": status}
    if attendance_id:
        result["attendanceId"] = attendance_id
    if reason:
        result["reason"] = reason
    return result


def attendance_document(event, employee_id, employee_name, received_at):
    p = event.payload
    doc = {"employeeId": employee_id, "employeeName": employee_name,
           "deviceId": p["deviceId"], "stationId": p["stationId"],
           "sourceEventId": p["sourceEventId"], "method": p["method"],
           "timestamp": event.captured_at, "capturedAt": event.captured_at,
           "receivedAt": received_at, "type": "SCAN", "status": "PENDING",
           "resolutionStatus": "PENDING", "syncStatus": "PENDING_SYNC", "verified": True}
    for key in ("templateId", "confidence", "faceSimilarity", "profileVersion"):
        if key in p:
            doc[key] = p[key]
    return doc
