import json
import os
from typing import Optional


DEFAULT_DEEPSEEK_BASE_URL = "https://api.deepseek.com"
DEFAULT_DEEPSEEK_MODEL = "deepseek-chat"

BACKEND_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
CONFIG_DIR = os.path.join(BACKEND_DIR, "instance")
CONFIG_PATH = os.path.join(CONFIG_DIR, "deepseek.json")


def _read_file_config() -> dict:
    try:
        with open(CONFIG_PATH, "r", encoding="utf-8") as f:
            data = json.load(f)
            return data if isinstance(data, dict) else {}
    except (OSError, json.JSONDecodeError):
        return {}


def _write_file_config(data: dict) -> None:
    os.makedirs(CONFIG_DIR, exist_ok=True)
    with open(CONFIG_PATH, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)


def get_deepseek_config() -> dict:
    file_config = _read_file_config()
    api_key = (os.getenv("DEEPSEEK_API_KEY") or file_config.get("api_key") or "").strip()
    model = (os.getenv("DEEPSEEK_MODEL") or file_config.get("model") or DEFAULT_DEEPSEEK_MODEL).strip()
    base_url = (os.getenv("DEEPSEEK_API_BASE") or file_config.get("base_url") or DEFAULT_DEEPSEEK_BASE_URL).strip()
    return {
        "api_key": api_key,
        "model": model or DEFAULT_DEEPSEEK_MODEL,
        "base_url": (base_url or DEFAULT_DEEPSEEK_BASE_URL).rstrip("/"),
        "key_saved": bool((file_config.get("api_key") or "").strip()),
        "env_key_present": bool((os.getenv("DEEPSEEK_API_KEY") or "").strip()),
    }


def update_deepseek_config(
    api_key: Optional[str] = None,
    model: Optional[str] = None,
    base_url: Optional[str] = None,
    clear_api_key: bool = False,
) -> dict:
    config = _read_file_config()

    if clear_api_key:
        config.pop("api_key", None)
    elif api_key is not None:
        key = api_key.strip()
        if key:
            config["api_key"] = key

    if model is not None:
        value = model.strip()
        config["model"] = value or DEFAULT_DEEPSEEK_MODEL

    if base_url is not None:
        value = base_url.strip().rstrip("/")
        config["base_url"] = value or DEFAULT_DEEPSEEK_BASE_URL

    _write_file_config(config)
    return get_deepseek_config()
