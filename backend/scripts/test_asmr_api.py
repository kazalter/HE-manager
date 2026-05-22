"""Standalone connectivity check for the asmr.one API client (⑥ P1).

Runs the *real* asmr_source code path against the live site so you can confirm
the API is reachable and the JSON shape matches, without starting the full app.
Nothing is written to the DB or disk.

Default = playlist mode (your "喜欢" list). Public playlists need no login;
for a private one set ASMR_USER / ASMR_PASS (or you'll be prompted).

Run from the backend directory:
    python scripts/test_asmr_api.py --playlist "https://asmr.one/playlist?id=...."
    python scripts/test_asmr_api.py --playlist "<uuid>" --pages 3
    python scripts/test_asmr_api.py --marks            # old mark-list mode
"""
import argparse
import os
import sys
from getpass import getpass

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app import asmr_source  # noqa: E402


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default=asmr_source.DEFAULT_API_BASE,
                        help="preferred API mirror (auto-falls back to others)")
    parser.add_argument("--playlist", default=os.environ.get("ASMR_PLAYLIST", ""),
                        help="playlist share URL or raw UUID")
    parser.add_argument("--marks", action="store_true",
                        help="use the old mark-list mode instead of a playlist")
    parser.add_argument("--filter", default="marked", choices=asmr_source.MARK_FILTERS)
    parser.add_argument("--pages", type=int, default=3)
    args = parser.parse_args()

    if not args.marks and not args.playlist:
        args.playlist = input("「喜欢」播放列表地址或 id: ").strip()

    # The "喜欢" playlist is owner-only, so credentials are always required.
    username = os.environ.get("ASMR_USER", "") or input("asmr.one 用户名: ").strip()
    password = os.environ.get("ASMR_PASS", "") or getpass("asmr.one 密码（不显示）: ")

    print(f"\n候选镜像顺序: {asmr_source.candidate_bases(args.base)}")

    print("\n[登录] /api/auth/me ...")
    try:
        token, working_base = asmr_source.login(args.base, username, password)
    except Exception as exc:  # noqa: BLE001 - diagnostic surface
        print(f"  ✗ 登录失败: {type(exc).__name__}: {exc}")
        return 1
    print(f"  ✓ 登录成功，镜像 {working_base}，token 长度 {len(token)}")

    if args.marks:
        print(f"\n[拉取] 标记列表 filter={args.filter} pages={args.pages} ...")
        fetch = lambda: asmr_source.fetch_marked_works(working_base, token, args.filter, args.pages)
    else:
        pid = asmr_source.extract_playlist_id(args.playlist)
        print(f"\n[拉取] 播放列表 id={pid} pages={args.pages} ...")
        fetch = lambda: asmr_source.fetch_playlist_works(working_base, token, pid, args.pages)

    try:
        works = fetch()
    except Exception as exc:  # noqa: BLE001
        print(f"  ✗ 拉取失败: {type(exc).__name__}: {exc}")
        print("    → 列表是私有的需要账号(设 ASMR_USER/ASMR_PASS)；或镜像全挂；"
              "或 JSON 字段名不同，把这行发我")
        return 1

    print(f"  ✓ 解析到 {len(works)} 个作品\n")
    for w in works[:12]:
        print(f"    {w.external_id:<14} | {(w.category_name or '-')[:18]:<18} | {w.title[:46]}")
    if len(works) > 12:
        print(f"    … 其余 {len(works) - 12} 个略")
    if not works:
        print("    （0 个：列表可能是私有的，或 get-playlist-works 的键名与预期不同——"
              "用浏览器登录后打开 working_base + /api/playlist/get-playlist-works?id=<id> 看字段发我）")
        return 1

    print("\n全部通过 ✅ —— P1 链路通了，可在应用里同步 / 推进 P2。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
