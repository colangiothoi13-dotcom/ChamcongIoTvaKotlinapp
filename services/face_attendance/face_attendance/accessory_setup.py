"""Local configuration for the optional, shared enrollment/attendance guard."""

from pathlib import Path


def accessories_enabled(config):
    section = config.get("accessories", {"enabled": False})
    if not isinstance(section, dict) or type(section.get("enabled", False)) is not bool:
        raise ValueError("accessories.enabled phải là true hoặc false")
    return section.get("enabled", False)


def create_accessory_guard(config):
    if not accessories_enabled(config):
        return None
    section = config["accessories"]
    block_hat = section.get("blockHat", False)
    if type(block_hat) is not bool:
        raise ValueError("accessories.blockHat phải là true hoặc false")
    from .accessories import AccessoryGuard

    def path(field):
        value = section.get(field)
        if not isinstance(value, str) or not value.strip():
            raise ValueError(f"Thiếu accessories.{field}; chạy download_models.py rồi kiểm tra cấu hình")
        target = Path(value)
        return target if target.is_absolute() else config["_base"] / target

    # Loading is lazy. A missing/corrupt model blocks face processing without
    # disabling the station's independent fingerprint HTTP service.
    return AccessoryGuard(path("attributeModel"), path("hatModel") if block_hat else None,
                          head_model=path("headModel") if block_hat else None, block_hat=block_hat,
                          threshold=section.get("threshold", 0.5),
                          uncertainty_margin=section.get("uncertaintyMargin", 0.1))
