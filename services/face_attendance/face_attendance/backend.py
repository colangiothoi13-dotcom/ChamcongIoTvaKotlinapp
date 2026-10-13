from datetime import timedelta
import hashlib

from .events import attendance_document, response


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

    def __init__(self, client, duplicate_seconds=180):
        self.client, self.window = client, duplicate_seconds

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
                    result = response(event, "DUPLICATE_SYNCED", matches[0].to_dict()["attendanceId"])
                else:
                    result = response(event, "ACCEPTED_SYNCED", event.key)
                    revision = (gate_state.to_dict() or {}).get("revision", 0) + 1
                    tx.set(gate, {"revision": revision, "employeeId": employee_id,
                                  "stationId": p["stationId"], "updatedAt": firestore.SERVER_TIMESTAMP})
                    tx.create(history.document(event.key), {"attendanceId": event.key,
                                                            "capturedAt": event.captured_at})
                    tx.create(db.collection("attendance").document(event.key),
                              attendance_document(event, employee_id, employee.get("fullName", ""), received_at))
            tx.create(receipt_ref, {"digest": event.digest, "result": result,
                                   "sourceEventId": p["sourceEventId"], "deviceId": p["deviceId"],
                                   "method": p["method"], "capturedAt": event.captured_at,
                                   "receivedAt": received_at})
            return result

        return commit(db.transaction())


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
