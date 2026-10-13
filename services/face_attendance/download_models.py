"""Explicit one-time download of hash-pinned face and accessory ONNX models.

SHA256 and exact sizes were verified against the upstream Git LFS pointers:
https://raw.githubusercontent.com/opencv/opencv_zoo/main/models/face_detection_yunet/face_detection_yunet_2023mar.onnx
https://raw.githubusercontent.com/opencv/opencv_zoo/main/models/face_recognition_sface/face_recognition_sface_2021dec.onnx

URLs may follow the upstream main branch, but content hashes are immutable:
changed upstream bytes fail validation rather than silently replacing models.
Model licenses: each upstream model directory contains its LICENSE file.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import tempfile
from urllib import request


MAX_MODEL_BYTES = 64 * 1024 * 1024
MODELS = (
    {
        "filename": "face_detection_yunet_2023mar.onnx",
        "url": "https://media.githubusercontent.com/media/opencv/opencv_zoo/main/models/face_detection_yunet/face_detection_yunet_2023mar.onnx",
        "sha256": "8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4",
        "size": 232589,
        "license": "https://github.com/opencv/opencv_zoo/blob/main/models/face_detection_yunet/LICENSE",
    },
    {
        "filename": "face_recognition_sface_2021dec.onnx",
        "url": "https://media.githubusercontent.com/media/opencv/opencv_zoo/main/models/face_recognition_sface/face_recognition_sface_2021dec.onnx",
        "sha256": "0ba9fbfa01b5270c96627c4ef784da859931e02f04419c829e83484087c34e79",
        "size": 38696353,
        "license": "https://github.com/opencv/opencv_zoo/blob/main/models/face_recognition_sface/LICENSE",
    },
    {
        "filename": "face_attrib_net.onnx",
        "url": "https://github.com/yakhyo/face-attribute/releases/download/weights/face_attrib_net.onnx",
        "sha256": "1bf7c6453bec2fb28e0830f3a76dceb9ffd020124f87b28da5355940a7bc6e48",
        "size": 43296036,
        "license": "https://github.com/yakhyo/face-attribute/blob/6b10bc87e4350572eb101f18b624987bc1fdc080/LICENSE",
    },
    {
        "filename": "hhc_is_l_48x48.onnx",
        "url": "https://github.com/PINTO0309/HHC/releases/download/onnx/hhc_is_l_48x48.onnx",
        "sha256": "6c76c0e8d88dd8c6e5671992e850a2ebb3c5a374edf211d4fea31a39f7f2a825",
        "size": 6372214,
        "license": "https://github.com/PINTO0309/HHC/blob/60e2e57821e854e5407dda4e408824eaec4126dd/LICENSE",
    },
    {
        "filename": "yolomit_t_wholebody28_1x3x480x640.onnx",
        "url": "https://github.com/PINTO0309/HHC/releases/download/onnx/yolomit_t_wholebody28_1x3x480x640.onnx",
        "sha256": "e6e303752f94163f6f96dcbb9f3713e695221327982711f206557935f728e581",
        "size": 7803591,
        "license": "https://github.com/PINTO0309/YOLO/blob/wholebody/LICENSE",
    },
)


def _validate_file(path, model):
    if path.stat().st_size != model["size"]:
        return False
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest() == model["sha256"]


def download_model(model, output_directory):
    """Download to a temporary file; only verified bytes become the target model."""
    if not 0 < model["size"] <= MAX_MODEL_BYTES:
        raise ValueError("Model size exceeds the download limit")
    directory = Path(output_directory).resolve()
    directory.mkdir(parents=True, exist_ok=True)
    target = directory / model["filename"]
    if target.exists():
        if not _validate_file(target, model):
            raise ValueError(f"Existing model has wrong size/hash: {target}. Move it aside before retrying.")
        return {**model, "path": str(target), "status": "already_verified"}
    temporary_name = None
    try:
        req = request.Request(model["url"], headers={"User-Agent": "face-attendance-model-setup/1"})
        with request.urlopen(req, timeout=60) as response:
            declared_length = response.headers.get("Content-Length")
            if declared_length is not None and int(declared_length) != model["size"]:
                raise ValueError("Upstream model Content-Length does not match the pinned size")
            total = 0
            digest = hashlib.sha256()
            with tempfile.NamedTemporaryFile("wb", dir=directory, suffix=".download", delete=False) as stream:
                temporary_name = stream.name
                while True:
                    block = response.read(1024 * 1024)
                    if not block:
                        break
                    total += len(block)
                    if total > model["size"] or total > MAX_MODEL_BYTES:
                        raise ValueError("Upstream model exceeds the pinned download size")
                    digest.update(block)
                    stream.write(block)
                if total != model["size"] or digest.hexdigest() != model["sha256"]:
                    raise ValueError("Upstream model failed pinned SHA256/size validation")
                stream.flush()
                os.fsync(stream.fileno())
        os.replace(temporary_name, target)
        return {**model, "path": str(target), "status": "downloaded_verified"}
    finally:
        if temporary_name and Path(temporary_name).exists():
            Path(temporary_name).unlink()


def download_models(output_directory):
    return [download_model(model, output_directory) for model in MODELS]


def main():
    parser = argparse.ArgumentParser(description="Download verified face/accessory ONNX models once")
    parser.add_argument("--output", type=Path, default=Path(__file__).resolve().parent / "data" / "models")
    args = parser.parse_args()
    for result in download_models(args.output):
        print(json.dumps(result), flush=True)


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError) as exc:
        raise SystemExit(str(exc)) from None
