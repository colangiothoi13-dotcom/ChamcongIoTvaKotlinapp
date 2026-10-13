"""Local setup checks. No attendance writes or Firebase requests."""

import hashlib
from importlib import metadata
import json
from pathlib import Path
import sqlite3
import tempfile
from urllib import parse, request

from .events import identifier
from .source import _NoRedirect


MODEL_HASHES = {
    "detectorModel": "8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4",
    "recognizerModel": "0ba9fbfa01b5270c96627c4ef784da859931e02f04419c829e83484087c34e79",
}


def _path(config, value):
    path = Path(value)
    return path if path.is_absolute() else config["_base"] / path


def _valid_token(value, minimum, maximum=120):
    return (isinstance(value, str) and minimum <= len(value) <= maximum
            and "REPLACE" not in value and value.isascii()
            and all(33 <= ord(character) <= 126 for character in value))


def _camera_health(config):
    camera = config["camera"]
    parsed = parse.urlsplit(camera["url"])
    if (parsed.scheme not in ("http", "https") or not parsed.hostname
            or parsed.username is not None or parsed.password is not None
            or parsed.fragment or parsed.query or parsed.path != "/capture"):
        raise ValueError("Camera URL phải là http(s)://IP/capture, không kèm token")
    health_url = parse.urlunsplit((parsed.scheme, parsed.netloc, "/health", "", ""))
    req = request.Request(health_url, headers={
        "X-Camera-Token": camera["token"], "Cache-Control": "no-store",
    })
    with request.build_opener(_NoRedirect()).open(req, timeout=5) as reply:
        if reply.getcode() != 200 or reply.headers.get_content_type() != "application/json":
            raise ValueError("Camera chưa sẵn sàng")
        body = reply.read(8193)
        if len(body) > 8192:
            raise ValueError("Phản hồi camera không hợp lệ")
    health = json.loads(body)
    if not isinstance(health, dict) or health.get("cameraReady") is not True or health.get("wifiConnected") is not True:
        raise ValueError("Camera hoặc Wi-Fi chưa sẵn sàng")
    return health


def run_checks(config, include_camera=False):
    checks = []

    def add(name, status, message):
        checks.append({"name": name, "status": status, "message": message})

    try:
        if config.get("mode") not in ("demo", "firebase"):
            raise ValueError()
        for field in ("stationId", "fingerprintDeviceId", "faceDeviceId"):
            identifier(config.get(field), field)
        if type(config.get("port")) is not int or not 1 <= config["port"] <= 65535:
            raise ValueError()
        if not isinstance(config.get("host"), str) or not config["host"]:
            raise ValueError()
        if config["mode"] == "firebase":
            project = config.get("projectId")
            if not isinstance(project, str) or not project or project == "YOUR_FIREBASE_PROJECT_ID":
                raise ValueError()
        add("config", "OK", "Chế độ, điểm chấm và thiết bị đã cấu hình")
    except (ValueError, KeyError, TypeError):
        add("config", "ERROR", "Kiểm tra mode, projectId, host, port và các mã thiết bị")

    token_ok = (_valid_token(config.get("adminToken"), 24)
                and _valid_token(config.get("deviceToken"), 24)
                and config.get("adminToken") != config.get("deviceToken"))
    add("gateway_tokens", "OK" if token_ok else "ERROR",
        "Token riêng đã cấu hình" if token_ok else "Dùng hai token riêng, ASCII, dài 24–120 ký tự")
    camera = config.get("camera")
    camera_token_ok = isinstance(camera, dict) and _valid_token(camera.get("token"), 16)
    add("camera_token", "OK" if camera_token_ok else "ERROR",
        "Token camera đã cấu hình" if camera_token_ok else "Đặt token camera ASCII dài 16–120 ký tự")

    dependencies_ok = True
    for package in ("opencv-python-headless", "numpy", "firebase-admin"):
        try:
            version = metadata.version(package)
            add(package, "OK", "Đã cài " + version)
        except metadata.PackageNotFoundError:
            dependencies_ok = False
            add(package, "ERROR", "Chưa cài; cài requirements.txt bằng cùng Python chạy dịch vụ")

    models_ok = True
    recognition = config.get("recognition", {})
    if not isinstance(recognition, dict):
        recognition = {}
    for field, expected_hash in MODEL_HASHES.items():
        try:
            digest = hashlib.sha256()
            with _path(config, recognition[field]).open("rb") as stream:
                for block in iter(lambda: stream.read(1024 * 1024), b""):
                    digest.update(block)
            if digest.hexdigest() != expected_hash:
                raise ValueError()
            add(field, "OK", "Mô hình đúng SHA256 đã kiểm chứng")
        except (OSError, ValueError, KeyError, TypeError):
            models_ok = False
            add(field, "ERROR", "Mô hình thiếu hoặc sai; chạy download_models.py")

    gallery_ok = False
    if models_ok:
        try:
            from .recognition import FaceRecognizer
            face = FaceRecognizer(_path(config, recognition["detectorModel"]),
                                  _path(config, recognition["recognizerModel"]),
                                  _path(config, recognition["gallery"]),
                                  threshold=recognition["threshold"],
                                  ambiguity_margin=recognition["ambiguityMargin"],
                                  min_face_size=recognition["minFaceSize"],
                                  min_sharpness=recognition.get("minSharpness", 50))
            gallery_ok = bool(face.profiles)
            add("gallery", "OK" if gallery_ok else "WARN",
                f"Có {len(face.profiles)} hồ sơ local" if gallery_ok else "Chưa có hồ sơ; chụp ảnh rồi chạy enroll")
        except (OSError, ValueError, KeyError, TypeError, RuntimeError):
            add("gallery", "ERROR", "Gallery hoặc ngưỡng nhận diện lỗi; kiểm tra cấu hình và đăng ký lại")
    else:
        add("gallery", "WARN", "Chưa kiểm tra gallery vì mô hình chưa sẵn sàng")

    try:
        directory = _path(config, config["dataDir"])
        directory.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix=".storage-check-", dir=directory) as temporary:
            db = sqlite3.connect(Path(temporary) / "check.sqlite")
            try:
                db.execute("CREATE TABLE probe(value INTEGER)")
                db.execute("INSERT INTO probe VALUES (1)")
                db.commit()
            finally:
                db.close()
        add("storage", "OK", "Thư mục dữ liệu ghi SQLite được; không thay đổi hàng đợi")
    except (OSError, sqlite3.Error, ValueError, KeyError, TypeError):
        add("storage", "ERROR", "Không ghi được SQLite; kiểm tra dung lượng và quyền thư mục dữ liệu")

    if config.get("mode") == "demo":
        try:
            registry = json.loads(_path(config, config["registryFile"]).read_text(encoding="utf-8-sig"))
            if not isinstance(registry.get("employees"), dict):
                raise ValueError()
            add("registry", "OK", f"Có {len(registry['employees'])} nhân viên demo")
        except (OSError, ValueError, KeyError, TypeError, AttributeError):
            add("registry", "ERROR", "Registry demo thiếu hoặc lỗi; xem cấu hình registryFile")
    else:
        add("firebase", "WARN", "Kiểm tra này không kết nối Firebase; xác thực và liên kết được kiểm tra khi đồng bộ")

    camera_ready = None
    if include_camera:
        try:
            if not camera_token_ok:
                raise ValueError()
            health = _camera_health(config)
            camera_ready = True
            add("camera", "OK", "Camera và Wi-Fi phản hồi sẵn sàng")
            if health.get("psram") is not True:
                add("psram", "WARN", "Camera chưa có PSRAM; ảnh QVGA cần kiểm tra độ rõ khuôn mặt")
        except Exception:
            # Network and parser errors can contain URLs/headers: never echo them.
            camera_ready = False
            add("camera", "ERROR", "Chưa kết nối được camera; kiểm tra IP, Wi-Fi, token và /health")
    else:
        add("camera", "WARN", "Chạy doctor --camera sau khi có board và đã điền IP")

    return {"ok": not any(check["status"] == "ERROR" for check in checks),
            "mode": config.get("mode"), "checks": checks,
            "readyForRecognition": models_ok and dependencies_ok and gallery_ok,
            "cameraReady": camera_ready}
