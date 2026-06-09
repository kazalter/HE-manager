import json
import os
from typing import Optional

BACKEND_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
CONFIG_DIR = os.path.join(BACKEND_DIR, "instance")
CONFIG_PATH = os.path.join(CONFIG_DIR, "external_config.json")


def _read_config() -> dict:
    try:
        with open(CONFIG_PATH, "r", encoding="utf-8") as f:
            data = json.load(f)
            return data if isinstance(data, dict) else {}
    except (OSError, json.JSONDecodeError):
        return {}


def _write_config(data: dict) -> None:
    os.makedirs(CONFIG_DIR, exist_ok=True)
    with open(CONFIG_PATH, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)


def get_global_proxy() -> Optional[str]:
    """Get the global proxy URL. Falls back to HE_BD2_PROXY env vars if not set."""
    config = _read_config()
    proxy = config.get("proxy")
    if proxy:
        return proxy.strip() or None
    return None


def update_global_proxy(proxy: Optional[str]) -> Optional[str]:
    """Update the global proxy URL."""
    config = _read_config()
    if proxy is not None:
        val = proxy.strip()
        if val:
            config["proxy"] = val
        else:
            config.pop("proxy", None)
    _write_config(config)
    return get_global_proxy()
