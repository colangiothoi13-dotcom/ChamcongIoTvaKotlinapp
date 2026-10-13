import hashlib
from io import BytesIO
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from download_models import download_model


class DownloadModelsTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name)
        self.content = b"test model bytes (not a real ONNX)"
        self.model = {
            "filename": "test.onnx", "url": "https://example.invalid/test.onnx",
            "sha256": hashlib.sha256(self.content).hexdigest(), "size": len(self.content),
        }

    def response(self, data):
        stream = BytesIO(data)
        stream.headers = {}
        return stream

    def test_verified_bytes_saved_and_second_call_does_not_download(self):
        with patch("download_models.request.urlopen", return_value=self.response(self.content)) as fetch:
            self.assertEqual(download_model(self.model, self.path)["status"], "downloaded_verified")
            self.assertEqual(download_model(self.model, self.path)["status"], "already_verified")
        fetch.assert_called_once()
        self.assertEqual((self.path / "test.onnx").read_bytes(), self.content)

    def test_hash_mismatch_truncation_and_oversize_never_publish_model(self):
        invalid_content = (
            bytes([self.content[0] ^ 1]) + self.content[1:], self.content[:-1], self.content + b"extra",
        )
        for data in invalid_content:
            with patch("download_models.request.urlopen", return_value=self.response(data)):
                with self.assertRaises(ValueError):
                    download_model(self.model, self.path)
            self.assertFalse((self.path / "test.onnx").exists())
            self.assertEqual(list(self.path.glob("*.download")), [])

    def test_corrupt_existing_model_is_not_overwritten(self):
        target = self.path / "test.onnx"
        target.write_bytes(b"user's existing corrupt model")
        with patch("download_models.request.urlopen") as fetch:
            with self.assertRaises(ValueError):
                download_model(self.model, self.path)
        fetch.assert_not_called()
        self.assertEqual(target.read_bytes(), b"user's existing corrupt model")


if __name__ == "__main__":
    unittest.main()
