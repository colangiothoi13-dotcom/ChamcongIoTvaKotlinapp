from pathlib import Path
import unittest
from unittest.mock import patch

from face_attendance.accessory_setup import create_accessory_guard


class AccessorySetupTests(unittest.TestCase):
    def config(self):
        return {"_base": Path("service"), "accessories": {
            "enabled": True, "blockHat": True, "attributeModel": "data/face.onnx", "hatModel": "data/hat.onnx",
            "headModel": "data/head.onnx", "threshold": 0.5, "uncertaintyMargin": 0.1}}

    def test_old_or_explicitly_disabled_config_does_not_create_guard(self):
        with patch("face_attendance.accessories.AccessoryGuard") as guard:
            self.assertIsNone(create_accessory_guard({}))
            self.assertIsNone(create_accessory_guard({"accessories": {"enabled": False}}))
        guard.assert_not_called()

    def test_model_paths_are_resolved_locally_and_loading_is_deferred(self):
        config = self.config()
        with patch("face_attendance.accessories.AccessoryGuard") as guard:
            result = create_accessory_guard(config)
        guard.assert_called_once_with(Path("service/data/face.onnx"), Path("service/data/hat.onnx"),
                                      head_model=Path("service/data/head.onnx"), block_hat=True,
                                      threshold=0.5, uncertainty_margin=0.1)
        self.assertIs(result, guard.return_value)
        guard.return_value.check_runtime.assert_not_called()

    def test_invalid_enable_and_missing_model_configuration_are_rejected(self):
        for value in (None, [], "true", {"enabled": 1}):
            with self.subTest(value=value), self.assertRaises(ValueError):
                create_accessory_guard({"accessories": value})
        config = self.config()
        del config["accessories"]["headModel"]
        with self.assertRaises(ValueError):
            create_accessory_guard(config)

    def test_hat_check_is_off_by_default_and_does_not_require_unused_models(self):
        config = self.config()
        for field in ("blockHat", "hatModel", "headModel"):
            del config["accessories"][field]
        with patch("face_attendance.accessories.AccessoryGuard") as guard:
            create_accessory_guard(config)
        guard.assert_called_once_with(Path("service/data/face.onnx"), None, head_model=None, block_hat=False,
                                      threshold=0.5, uncertainty_margin=0.1)

    def test_non_boolean_hat_policy_is_rejected(self):
        for value in (None, 1, "false", []):
            config = self.config()
            config["accessories"]["blockHat"] = value
            with self.subTest(value=value), self.assertRaises(ValueError):
                create_accessory_guard(config)
