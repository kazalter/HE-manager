"""把 HE 收藏推给独立下载中心(HE_downloader 的 gateway)的客户端。

方向 B（并存）：不替换 HE 内置下载，只多一条"用下载中心下"的路。解析仍在 HE
做（cookie/签名 URL 都在这边），把 [{url, rel_path, headers}] 作为一个**分组
任务** POST 给网关 `/jobs/batch`；文件落到 /mnt/hdd 的库目录，再由 HE 扫描/入库。

配置（环境变量，由 docker-compose 注入）：
  HE_DOWNLOADER_URL    网关地址，如 http://192.168.208.130:8011（空=未启用）
  HE_DOWNLOADER_TOKEN  网关 Bearer token（网关没设则留空）
"""
from __future__ import annotations

import os
from typing import Optional
from urllib.parse import urlsplit

# 复用项目已有的 curl_cffi（不引入 httpx，避免改 requirements 触发国内 pip 慢重装）。
# 推送目标是本机网关，不需要 TLS 指纹伪装，默认普通请求即可。
from curl_cffi import requests as cffi_requests

GATEWAY_URL = os.environ.get("HE_DOWNLOADER_URL", "").strip().rstrip("/")
GATEWAY_TOKEN = os.environ.get("HE_DOWNLOADER_TOKEN", "").strip()


class DownloaderPushError(RuntimeError):
    """推送到下载中心失败（未配置 / 连不上 / 网关拒绝）。"""


def is_configured() -> bool:
    return bool(GATEWAY_URL)


def _auth_headers() -> dict:
    return {"Authorization": f"Bearer {GATEWAY_TOKEN}"} if GATEWAY_TOKEN else {}


def url_ext(url: str, default: str = ".jpg") -> str:
    """从 URL 路径取扩展名（给图片这类需要预定文件名的场景）。"""
    ext = os.path.splitext(urlsplit(url).path)[1].lower()
    return ext if (ext and len(ext) <= 5) else default


def push_batch(
    name: str,
    dest_dir: str,
    files: list[dict],
    callback_url: Optional[str] = None,
    timeout: float = 30.0,
) -> dict:
    """提交一个分组任务，返回网关的 JobView（含 job id）。files: [{url, rel_path, headers?, optional?}]。"""
    if not GATEWAY_URL:
        raise DownloaderPushError("未配置下载中心地址（HE_DOWNLOADER_URL）")
    if not files:
        raise DownloaderPushError("没有可下载的文件")
    payload: dict = {"name": name, "dest_dir": dest_dir, "files": files}
    if callback_url:
        payload["callback_url"] = callback_url
    try:
        resp = cffi_requests.post(
            f"{GATEWAY_URL}/jobs/batch", json=payload, headers=_auth_headers(), timeout=timeout
        )
    except Exception as exc:  # noqa: BLE001 — 网络层错误统一包装
        raise DownloaderPushError(f"连不上下载中心：{exc}") from exc
    if resp.status_code != 200:
        detail = ""
        try:
            detail = resp.json().get("detail", "")
        except Exception:  # noqa: BLE001
            pass
        raise DownloaderPushError(f"下载中心拒绝（HTTP {resp.status_code}）：{detail or ''}")
    return resp.json()
