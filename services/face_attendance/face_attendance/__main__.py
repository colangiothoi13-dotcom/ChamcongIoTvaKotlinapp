import argparse
from datetime import datetime, timezone, timedelta
import json
from pathlib import Path
import secrets
import tempfile
import time
import uuid
import sys
import sqlite3
import hashlib

from .backend import FirestoreBackend, create_firestore_client
from .events import Event, identifier
from .storage import DemoBackend, Gateway, Outbox


ROOT = Path(__file__).resolve().parents[1]


def emit(value):
    print(json.dumps(value, ensure_ascii=False, default=str), flush=True)


def load_config(path):
    path = Path(path).resolve()
    config = json.loads(path.read_text(encoding="utf-8-sig"))
    config["_base"] = path.parent
    if config.get("mode") not in ("demo", "firebase"):
        raise ValueError("mode phải là demo hoặc firebase")
    return config


def local_path(config, value):
    path = Path(value)
    return path if path.is_absolute() else config["_base"] / path


def build_gateway(config):
    data = local_path(config, config["dataDir"])
    if config["mode"] == "firebase":
        backend = FirestoreBackend(create_firestore_client(config["projectId"]), face_door=config.get("faceDoor"))
    else:
        registry = json.loads(local_path(config, config["registryFile"]).read_text(encoding="utf-8-sig"))
        backend = DemoBackend(data / "demo.sqlite", registry)
    namespace = [config["mode"], config["projectId"] if config["mode"] == "firebase" else "local",
                 config["stationId"], config["fingerprintDeviceId"]]
    namespace_hash = hashlib.sha256(json.dumps(namespace, separators=(",", ":")).encode()).hexdigest()[:16]
    return Gateway(Outbox(data / f"outbox-{config['mode']}-{namespace_hash}.sqlite"), backend,
                   station_id=config["stationId"], device_id=config["fingerprintDeviceId"],
                   minimum_face_similarity=config["recognition"]["threshold"])


def recognizer(config, *, quality_only=False):
    from .recognition import FaceRecognizer
    from .accessory_setup import create_accessory_guard
    r = config["recognition"]
    return FaceRecognizer(local_path(config, r["detectorModel"]), local_path(config, r["recognizerModel"]),
                          None if quality_only else local_path(config, r["gallery"]), threshold=r["threshold"],
                          ambiguity_margin=r["ambiguityMargin"], min_face_size=r["minFaceSize"],
                          min_sharpness=r.get("minSharpness", 50),
                          sharpness_max_size=r.get("sharpnessMaxSize"),
                          accessory_guard=create_accessory_guard(config))


def demo():
    registry = json.loads((ROOT / "demo_registry.example.json").read_text(encoding="utf-8"))
    with tempfile.TemporaryDirectory(prefix="chamcong-demo-") as directory:
        backend = DemoBackend(Path(directory) / "demo.sqlite", registry)
        gateway = Gateway(Outbox(Path(directory) / "outbox.sqlite"), backend,
                          station_id="GATE-01", device_id="GATE-01")
        now = datetime.now(timezone.utc) - timedelta(seconds=10)
        first = {"sourceEventId":"demo-finger-1","deviceId":"GATE-01","stationId":"GATE-01",
                 "templateId":1,"confidence":100,"capturedAt":now.isoformat()}
        second = {"sourceEventId":"demo-face-1","deviceId":"CAM-01","stationId":"GATE-01",
                  "employeeId":"demo-01","profileVersion":1,"faceSimilarity":0.85,
                  "capturedAt":(now+timedelta(seconds=1)).isoformat()}
        third = dict(second, sourceEventId="demo-face-2", employeeId="demo-02")
        for method, value in (("FINGERPRINT", first), ("FACE", second), ("FINGERPRINT", first), ("FACE", third)):
            emit(gateway.submit(Event.parse(value, method)))
        emit({"mode":"demo", "attendanceCount":backend.count(), "expected":2,
              "message":"Chỉ mô phỏng; không ghi Firebase và không nhận diện ảnh thật"})
        if backend.count() != 2:
            raise RuntimeError("Demo chống trùng không đạt")


def enroll(config, employee_id, images, version):
    identifier(employee_id, "employeeId")
    if config["mode"] == "firebase":
        client = create_firestore_client(config["projectId"])
        employee = client.collection("employees").document(employee_id).get()
        if not employee.exists or employee.to_dict().get("active") is not True:
            raise ValueError("Nhân viên không tồn tại hoặc đã vô hiệu hóa")
        existing = client.collection("faceProfiles").document(employee_id).get()
        if existing.exists and existing.to_dict().get("version", 0) >= version:
            raise ValueError("Đăng ký lại cần tăng version")
    else:
        registry_file = local_path(config, config["registryFile"])
        registry = json.loads(registry_file.read_text(encoding="utf-8-sig"))
        if registry.get("employees", {}).get(employee_id, {}).get("active") is not True:
            raise ValueError("Nhân viên demo không tồn tại hoặc đã vô hiệu hóa")
    metadata = recognizer(config).enroll(employee_id, images, version)
    profile = {"employeeId": employee_id, "enabled": True, "version": version,
               "enrolledAt": datetime.now(timezone.utc)}
    if config["mode"] == "firebase":
        # Gallery is local; this collection contains metadata only.
        client.collection("faceProfiles").document(employee_id).set(profile)
    else:
        registry.setdefault("faceProfiles", {})[employee_id] = dict(profile, enrolledAt=profile["enrolledAt"].isoformat())
        temporary = registry_file.with_suffix(".tmp")
        temporary.write_text(json.dumps(registry, ensure_ascii=False, indent=2), encoding="utf-8")
        temporary.replace(registry_file)
    emit(metadata)


def poll(config, webcam):
    from .presence import PresenceGate
    from .feedback import FaceBlockFeedback
    from .source import UrlFrameSource, WebcamFrameSource, FrameSourceError
    from .recognition import FaceRecognitionError
    gateway, face = build_gateway(config), recognizer(config)
    r = config["recognition"]
    presence = PresenceGate(stable_frames=r["stableFrames"], empty_frames=r["emptyFrames"])
    feedback = FaceBlockFeedback(emit, enabled=config.get("accessories", {}).get("enabled") is True)
    source = WebcamFrameSource(webcam) if webcam is not None else UrlFrameSource(
        config["camera"]["url"], config["camera"]["token"], rotation=config["camera"].get("rotation", 0))
    emit({"mode":config["mode"], "message":"Rời khung hình trước khi bắt đầu; Ctrl+C để dừng"})
    try:
        while True:
            try:
                frame = source.read()
                result = face.recognize(frame.image)
                feedback.observe(result)
                candidate = presence.observe(result)
                if candidate:
                    candidate.update(sourceEventId="face-"+uuid.uuid4().hex,
                                     deviceId=config["faceDeviceId"], stationId=config["stationId"],
                                     capturedAt=frame.captured_at)
                    event = Event.parse(candidate, "FACE")
                    try:
                        emit(gateway.submit(event))
                    except (OSError, sqlite3.Error):
                        # Do not drop a recognized event merely because the durable write failed.
                        while True:
                            emit({"status":"NOT_STORED", "message":"Chưa lưu được lượt; đang thử lại"})
                            # A full outbox must be allowed to sync older events
                            # before retrying this exact recognized visit.
                            try:
                                gateway.flush()
                            except (OSError, sqlite3.Error):
                                pass
                            time.sleep(2)
                            try:
                                emit(gateway.submit(event))
                                break
                            except (OSError, sqlite3.Error):
                                continue
                gateway.flush()
            except (FrameSourceError, FaceRecognitionError) as exc:
                presence.reset()
                feedback.reset(status="UNAVAILABLE", message="Chưa kiểm tra được khuôn mặt; không ghi lượt chấm.")
                emit({"status":"SOURCE_ERROR", "message":str(exc)})
                time.sleep(1)
            except (OSError, sqlite3.Error):
                # Events already in the outbox remain durable if syncing its
                # local result fails. Keep camera/presence state unchanged.
                emit({"status":"STORAGE_ERROR", "message":"Không truy cập được hàng đợi; đang thử lại"})
                time.sleep(1)
            time.sleep(max(0.1, float(r["pollSeconds"])))
    except KeyboardInterrupt:
        pass
    finally:
        source.close()


def capture(config, employee_id, count, interval, webcam, max_attempts=None):
    from .capture import capture_samples
    from .source import UrlFrameSource, WebcamFrameSource
    # Registration collection does not create a gateway or contact Firebase.
    face = recognizer(config, quality_only=True)
    source = WebcamFrameSource(webcam) if webcam is not None else UrlFrameSource(
        config["camera"]["url"], config["camera"]["token"], rotation=config["camera"].get("rotation", 0))
    result = capture_samples(
        source, face, local_path(config, config["dataDir"]) / "photos", employee_id,
        count=count, interval=interval, max_attempts=max_attempts, progress=emit,
    )
    emit(result.to_dict())
    if len(result.files) != count:
        raise SystemExit(1)


def main():
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description="Chấm công mặt/vân tay qua laptop")
    parser.add_argument("--config", default="config.json")
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("demo", help="Mô phỏng chống trùng, không cần camera/Firebase")
    commands.add_parser("init", help="Tạo cấu hình demo riêng và token ngẫu nhiên")
    commands.add_parser("serve", help="Chạy API nhận vân tay/mặt")
    station = commands.add_parser("station", help="Chạy camera, API vân tay và đăng ký khuôn mặt từ app trong một tiến trình")
    station.add_argument("--webcam", type=int)
    commands.add_parser("flush", help="Gửi lại hàng đợi bền vững")
    doctor = commands.add_parser("doctor", help="Kiểm tra cấu hình và môi trường, không ghi công")
    doctor.add_argument("--camera", action="store_true")
    e = commands.add_parser("enroll", help="Đăng ký mặt bằng ảnh thật trên laptop")
    e.add_argument("--employee-id", required=True)
    e.add_argument("--version", required=True, type=int)
    e.add_argument("--images", nargs="+", required=True)
    image = commands.add_parser("recognize", help="Thử ảnh; chỉ nhận diện, không ghi công")
    image.add_argument("--image", required=True)
    p = commands.add_parser("poll", help="Nhận diện camera/webcam; gửi lượt theo chế độ cấu hình")
    p.add_argument("--webcam", type=int)
    collecting = commands.add_parser("capture", help="Lưu ảnh mẫu từ camera/webcam, không đăng ký hoặc chấm công")
    collecting.add_argument("--employee-id", required=True)
    collecting.add_argument("--count", type=int, default=10)
    collecting.add_argument("--interval", type=float, default=1.0)
    collecting.add_argument("--webcam", type=int)
    collecting.add_argument("--max-attempts", type=int)
    args = parser.parse_args()
    if args.command == "demo":
        demo()
        return
    if args.command == "init":
        path = Path(args.config).resolve()
        if path.exists():
            raise ValueError("Cấu hình đã tồn tại; không ghi đè")
        config = json.loads((ROOT / "config.example.json").read_text(encoding="utf-8"))
        config.update(adminToken=secrets.token_urlsafe(32), deviceToken=secrets.token_urlsafe(32))
        config["camera"]["token"] = secrets.token_urlsafe(32)
        config["registryFile"] = "demo_registry.local.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        registry = path.parent / config["registryFile"]
        if not registry.exists():
            registry.write_bytes((ROOT / "demo_registry.example.json").read_bytes())
        path.write_text(json.dumps(config, ensure_ascii=False, indent=2), encoding="utf-8")
        emit({"message":"Đã tạo cấu hình demo và token riêng", "config":str(path)})
        return
    config = load_config(args.config)
    if args.command == "enroll":
        enroll(config, args.employee_id, args.images, args.version)
    elif args.command == "capture":
        capture(config, args.employee_id, args.count, args.interval, args.webcam, args.max_attempts)
    elif args.command == "doctor":
        from .diagnostics import run_checks
        report = run_checks(config, args.camera)
        emit(report)
        if not report["ok"]:
            raise SystemExit(1)
    elif args.command == "recognize":
        emit(recognizer(config).recognize_path(args.image).to_dict())
    elif args.command == "poll":
        poll(config, args.webcam)
    elif args.command == "flush":
        emit(build_gateway(config).flush())
    elif args.command == "serve":
        from .server import make_server, run_server
        from .accessory_setup import accessories_enabled
        gateway = build_gateway(config)
        server = make_server(gateway, config["host"], config["port"], config["adminToken"], config["deviceToken"],
                             allow_face_events=not accessories_enabled(config))
        emit({"message":"Server đang chạy", "mode":config["mode"], "host":config["host"], "port":server.server_port})
        run_server(server, gateway)
    elif args.command == "station":
        from .station import run_station
        run_station(config, build_gateway(config), recognizer(config),
                    local_path(config, config["dataDir"]), emit, webcam=args.webcam)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, ImportError, RuntimeError, sqlite3.Error) as exc:
        # SDK exceptions may carry sensitive URLs/config, so do not echo arbitrary cloud errors.
        if isinstance(exc, sqlite3.Error):
            message = "Không truy cập được SQLite; kiểm tra dung lượng và quyền thư mục dữ liệu"
        elif type(exc).__module__.startswith(("google", "firebase")):
            message = "Không kết nối được Firebase; kiểm tra cấu hình xác thực trên laptop"
        else:
            message = str(exc)
        raise SystemExit(message)
