from contextlib import contextmanager
from datetime import datetime, timezone
import json
from pathlib import Path
import sqlite3
import threading

from .events import Event, InvalidEvent, response


class Outbox:
    """Persist before trying cloud delivery. Each source ID has one immutable payload."""

    def __init__(self, path):
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with self.connect() as db:
            db.execute("PRAGMA journal_mode=WAL")
            db.execute("""CREATE TABLE IF NOT EXISTS outbox (
                id TEXT PRIMARY KEY, digest TEXT NOT NULL, payload TEXT NOT NULL,
                received_at TEXT NOT NULL, result TEXT)""")

    @contextmanager
    def connect(self):
        db = sqlite3.connect(self.path, timeout=30)
        db.row_factory = sqlite3.Row
        try:
            yield db
            db.commit()
        except BaseException:
            db.rollback()
            raise
        finally:
            db.close()

    def put(self, event):
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            row = db.execute("SELECT * FROM outbox WHERE id=?", (event.key,)).fetchone()
            if row:
                if row["digest"] != event.digest:
                    raise InvalidEvent("EVENT_ID_CONFLICT")
                return dict(row)
            count = db.execute("SELECT COUNT(*) FROM outbox WHERE result IS NULL").fetchone()[0]
            if count >= 10000:
                raise OSError("Hàng đợi laptop đã đầy")
            now = datetime.now(timezone.utc).isoformat()
            db.execute("INSERT INTO outbox VALUES (?,?,?,?,NULL)",
                       (event.key, event.digest, event.json, now))
            return {"id": event.key, "payload": event.json, "received_at": now, "result": None}

    def complete(self, key, result):
        with self.connect() as db:
            db.execute("UPDATE outbox SET result=? WHERE id=? AND result IS NULL",
                       (json.dumps(result, ensure_ascii=False), key))

    def pending(self, limit=100):
        with self.connect() as db:
            return [dict(r) for r in db.execute(
                "SELECT * FROM outbox WHERE result IS NULL ORDER BY received_at,id LIMIT ?", (limit,))]

    def pending_count(self):
        with self.connect() as db:
            return db.execute("SELECT COUNT(*) FROM outbox WHERE result IS NULL").fetchone()[0]


class Gateway:
    def __init__(self, outbox, backend, *, station_id, device_id, minimum_face_similarity=0.5):
        self.outbox, self.backend = outbox, backend
        self.station_id, self.device_id = station_id, device_id
        self.minimum_face_similarity = minimum_face_similarity
        self.lock = threading.RLock()
        self.result_listener = None

    def _notify_result(self, event, result):
        listener = self.result_listener
        if listener is None:
            return
        try:
            listener(event, result)
        except Exception as exc:
            import logging
            logging.getLogger(__name__).warning("Không cập nhật được trạng thái lượt (%s)", type(exc).__name__)

    def submit(self, event):
        p = event.payload
        if p["stationId"] != self.station_id:
            return response(event, "REJECTED", reason="UNKNOWN_STATION")
        if p["method"] == "FINGERPRINT" and p["deviceId"] != self.device_id:
            return response(event, "REJECTED", reason="UNKNOWN_DEVICE")
        if p["method"] == "FACE" and p["faceSimilarity"] < self.minimum_face_similarity:
            return response(event, "REJECTED", reason="FACE_NOT_CONFIDENT")
        if event.captured_at.timestamp() > datetime.now(timezone.utc).timestamp() + 30:
            return response(event, "REJECTED", reason="FUTURE_TIMESTAMP")
        with self.lock:
            row = self.outbox.put(event)
            if row["result"]:
                result = json.loads(row["result"])
                self._notify_result(event, result)
                return result
            return self._deliver(event, row)

    def _deliver(self, event, row):
        try:
            result = self.backend.deliver(event, datetime.fromisoformat(row["received_at"]))
        except Exception as exc:
            # Do not log request bodies, keys or credentials. Retain the durable event.
            import logging
            logging.getLogger(__name__).warning("Đồng bộ đang chờ (%s)", type(exc).__name__)
            return response(event, "QUEUED", reason="WAITING_FOR_SYNC")
        if result["status"] != "QUEUED":
            self.outbox.complete(event.key, result)
            self._notify_result(event, result)
        return result

    def flush(self):
        with self.lock:
            results = []
            for row in self.outbox.pending():
                data = json.loads(row["payload"])
                event = Event.parse(data, data["method"])
                result = self._deliver(event, row)
                results.append(result)
                if result["status"] == "QUEUED":
                    break
            return results


class DemoBackend:
    """SQLite-only simulation. Never claims that attendance reached Firebase."""

    mode = "demo"

    def __init__(self, path, registry, duplicate_seconds=180):
        self.path, self.registry = Path(path), registry
        self.window = duplicate_seconds
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with self.connect() as db:
            db.execute("CREATE TABLE IF NOT EXISTS demo_receipts(id TEXT PRIMARY KEY,digest TEXT,result TEXT)")
            db.execute("""CREATE TABLE IF NOT EXISTS demo_attendance(
                id TEXT PRIMARY KEY,employee TEXT,station TEXT,captured REAL,payload TEXT)""")

    @contextmanager
    def connect(self):
        db = sqlite3.connect(self.path, timeout=30)
        try:
            yield db
            db.commit()
        except BaseException:
            db.rollback()
            raise
        finally:
            db.close()

    def deliver(self, event, received_at):
        from .backend import resolve_identity
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            old = db.execute("SELECT digest,result FROM demo_receipts WHERE id=?", (event.key,)).fetchone()
            if old:
                if old[0] != event.digest:
                    return response(event, "REJECTED", reason="EVENT_ID_CONFLICT")
                return json.loads(old[1])
            identity, reason = resolve_identity(event, self.registry)
            if reason:
                result = response(event, "REJECTED", reason=reason)
            else:
                employee_id, employee = identity
                employee_name = employee.get("fullName")
                if not isinstance(employee_name, str) or not employee_name.strip():
                    employee_name = employee_id
                stamp, station = event.captured_at.timestamp(), event.payload["stationId"]
                old = db.execute("""SELECT id FROM demo_attendance WHERE employee=? AND station=?
                    AND captured BETWEEN ? AND ? ORDER BY captured,id LIMIT 1""",
                    (employee_id, station, stamp-self.window, stamp+self.window)).fetchone()
                if old:
                    result = response(event, "DUPLICATE_DEMO", old[0],
                                      employee_name=employee_name)
                else:
                    db.execute("INSERT INTO demo_attendance VALUES (?,?,?,?,?)",
                               (event.key, employee_id, station, stamp, event.json))
                    result = response(event, "ACCEPTED_DEMO", event.key,
                                      employee_name=employee_name)
            db.execute("INSERT INTO demo_receipts VALUES (?,?,?)",
                       (event.key, event.digest, json.dumps(result)))
            return result

    def count(self):
        with self.connect() as db:
            return db.execute("SELECT COUNT(*) FROM demo_attendance").fetchone()[0]
