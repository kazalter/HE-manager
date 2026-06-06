r"""Import selected Brown Dust 2 visual assets as library images.

Default mode is a dry run. The importer is intentionally narrow: it only
considers character/special illustration PNGs, not the whole asset dump full
of icons, buttons, maps, and other UI pieces.

Run from anywhere:
    python backend/scripts/import_bd2_assets.py --repo D:\MediaSources\Brown-Dust-2-Asset
    python backend/scripts/import_bd2_assets.py --repo D:\MediaSources\Brown-Dust-2-Asset --apply

Optional:
    --limit 50          import a small smoke-test batch
    --include-faces     also import expression bust/face PNGs
    --include-npc       also import NPC illustration PNGs
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
from collections import Counter
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Iterable
from urllib.parse import quote

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app import database, models, scanner  # noqa: E402


BACKEND_DIR = Path(__file__).resolve().parents[1]
THUMBNAIL_DIR = BACKEND_DIR / ".thumbnails"
SOURCE_SITE = "bd2"
GITHUB_REPO = "https://github.com/myssal/Brown-Dust-2-Asset"


@dataclass(frozen=True)
class AssetKind:
    prefix: str
    kind: str
    label: str


@dataclass(frozen=True)
class AssetCandidate:
    path: Path
    rel: str
    kind: str
    kind_label: str
    title: str
    tags: tuple[str, ...]
    source_url: str | None


DEFAULT_KINDS = (
    AssetKind("ui/illust/illust_char", "standing", "standing"),
    AssetKind("ui/illust/illust_inven_char", "inventory", "inventory art"),
    AssetKind("ui/illust/illust_simple_char", "simple", "simple art"),
    AssetKind("ui/illust/illust_skill_char", "skill", "skill art"),
    AssetKind("ui/illust/illust_loop_char", "loop", "loop art"),
    AssetKind("ui/spec_illust", "special_illust", "special illustration"),
)
FACE_KINDS = (
    AssetKind("ui/illust/illust_face", "face", "face"),
)
NPC_KINDS = (
    AssetKind("ui/illust/illust_npc", "npc", "NPC"),
    AssetKind("ui/illust/illust_npcFace", "npc_face", "NPC face"),
)


def _configure_stdout() -> None:
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass


def repo_commit(repo_root: Path) -> str | None:
    try:
        result = subprocess.run(
            ["git", "-C", str(repo_root), "rev-parse", "HEAD"],
            check=True,
            capture_output=True,
            text=True,
            timeout=10,
        )
    except Exception:
        return None
    commit = result.stdout.strip()
    return commit or None


def load_char_info(repo_root: Path) -> dict[str, dict[str, str]]:
    """Return costume id -> metadata from CharInfo(Dropped).json.

    The upstream file currently has at least one missing comma near
    `censored_spine`, so we apply a small repair before parsing. If future
    edits introduce other JSON issues, the importer still works with filenames.
    """
    info_path = repo_root / "CharInfo(Dropped).json"
    if not info_path.exists():
        return {}

    text = info_path.read_text(encoding="utf-8-sig", errors="replace")
    text = re.sub(
        r'("censored_spine"\s*:\s*"[^"]+")\s*("cutscene"\s*:)',
        r"\1,\n        \2",
        text,
    )
    try:
        rows = json.loads(text)
    except json.JSONDecodeError as exc:
        print(f"! Could not parse CharInfo(Dropped).json: {exc}")
        return {}

    out: dict[str, dict[str, str]] = {}
    for char in rows if isinstance(rows, list) else []:
        char_name = str(char.get("charName") or "").strip()
        rarity = char.get("rarity")
        for costume in char.get("costumes") or []:
            costume_id = str(costume.get("costumeId") or "").strip()
            if not costume_id:
                continue
            out[costume_id] = {
                "char_id": str(char.get("charId") or "").strip(),
                "char_name": char_name,
                "rarity": str(rarity or "").strip(),
                "costume_id": costume_id,
                "costume_name": str(costume.get("costumeName") or "").strip(),
                "release_date": str(costume.get("releaseDate") or "").strip(),
                "spine": str(costume.get("spine") or "").strip(),
                "cutscene": str(costume.get("cutscene") or "").strip(),
            }
    return out


def selected_kinds(include_faces: bool, include_npc: bool) -> tuple[AssetKind, ...]:
    kinds = list(DEFAULT_KINDS)
    if include_faces:
        kinds.extend(FACE_KINDS)
    if include_npc:
        kinds.extend(NPC_KINDS)
    return tuple(kinds)


def iter_pngs(root: Path, prefix: str) -> Iterable[Path]:
    base = root / Path(prefix)
    if not base.exists():
        return
    for path in sorted(base.rglob("*.png")):
        if path.is_file():
            yield path


def costume_id_from_stem(stem: str) -> str | None:
    match = re.search(r"(?:char|face)(\d{6})", stem, re.IGNORECASE)
    if match:
        return match.group(1)
    return None


def variant_suffix(stem: str, costume_id: str | None) -> str:
    if not costume_id:
        return ""
    idx = stem.lower().find(costume_id.lower())
    if idx < 0:
        return ""
    suffix = stem[idx + len(costume_id):].strip("_- ")
    return suffix


def special_title(stem: str) -> str:
    text = stem.replace("_", " ").replace("-", " ")
    text = re.sub(r"sepcialillust", "Special Illust", text, flags=re.IGNORECASE)
    text = re.sub(r"specialillust", "Special Illust", text, flags=re.IGNORECASE)
    text = re.sub(r"\s+", " ", text).strip()
    return f"BD2 - {text}" if text else "BD2 - Special Illustration"


def title_and_tags(
    stem: str,
    kind: AssetKind,
    char_info: dict[str, dict[str, str]],
) -> tuple[str, tuple[str, ...]]:
    tags: list[str] = ["BD2", f"BD2:{kind.kind}"]
    costume_id = costume_id_from_stem(stem)
    meta = char_info.get(costume_id or "")

    if meta:
        char_name = meta.get("char_name") or "Unknown"
        costume_name = meta.get("costume_name") or costume_id or "Unknown"
        suffix = variant_suffix(stem, costume_id)
        title = f"BD2 - {char_name} - {costume_name} - {kind.label}"
        if suffix:
            title += f" ({suffix})"
        tags.extend([f"BD2:{char_name}", f"BD2:{costume_name}"])
        if meta.get("rarity"):
            tags.append(f"BD2:{meta['rarity']}star")
        return title, tuple(dict.fromkeys(tags))

    if kind.kind == "special_illust":
        return special_title(stem), tuple(dict.fromkeys(tags))

    pretty = stem.replace("_", " ").replace("-", " ")
    pretty = re.sub(r"\s+", " ", pretty).strip()
    return f"BD2 - {pretty or stem} - {kind.label}", tuple(dict.fromkeys(tags))


def github_url(commit: str | None, rel: str) -> str | None:
    if not commit:
        return None
    return f"{GITHUB_REPO}/blob/{commit}/{quote(rel, safe='/')}"


def discover_candidates(
    repo_root: Path,
    char_info: dict[str, dict[str, str]],
    commit: str | None,
    include_faces: bool,
    include_npc: bool,
) -> list[AssetCandidate]:
    out: list[AssetCandidate] = []
    for kind in selected_kinds(include_faces, include_npc):
        for path in iter_pngs(repo_root, kind.prefix):
            rel = path.relative_to(repo_root).as_posix()
            title, tags = title_and_tags(path.stem, kind, char_info)
            out.append(
                AssetCandidate(
                    path=path,
                    rel=rel,
                    kind=kind.kind,
                    kind_label=kind.label,
                    title=title,
                    tags=tags,
                    source_url=github_url(commit, rel),
                )
            )
    return out


def get_or_create_folder(db, repo_root: Path, do_apply: bool) -> models.Folder | None:
    root_str = str(repo_root.resolve())
    folder = db.query(models.Folder).filter(models.Folder.path == root_str).first()
    if folder or not do_apply:
        return folder
    folder = models.Folder(
        path=root_str,
        status="idle",
        scan_mode="bd2_asset",
        thumbnail_enabled=True,
        thumbnail_interval=1,
        last_scanned_at=datetime.now(),
    )
    db.add(folder)
    db.flush()
    return folder


def get_or_create_tag(db, name: str) -> models.Tag | None:
    name = (name or "").strip()
    if not name:
        return None
    tag = db.query(models.Tag).filter(models.Tag.name == name).first()
    if tag:
        return tag
    tag = models.Tag(name=name)
    db.add(tag)
    db.flush()
    return tag


def attach_tags(db, media: models.Media, tag_names: Iterable[str]) -> None:
    for name in tag_names:
        tag = get_or_create_tag(db, name)
        if tag is not None and tag not in media.tags:
            media.tags.append(tag)


def thumbnail_name(path: Path) -> str:
    digest = hashlib.md5(str(path).encode("utf-8")).hexdigest()[:12]
    return f"thumb_bd2_{digest}.jpg"


def ensure_thumbnail(candidate: AssetCandidate) -> str | None:
    THUMBNAIL_DIR.mkdir(parents=True, exist_ok=True)
    name = thumbnail_name(candidate.path)
    target = THUMBNAIL_DIR / name
    if target.exists() and target.stat().st_size > 0:
        return name
    if scanner.get_image_thumbnail(str(candidate.path), str(target)):
        return name
    return None


def import_assets(
    repo_root: Path,
    candidates: list[AssetCandidate],
    do_apply: bool,
    refresh_thumbnails: bool,
) -> None:
    db = database.SessionLocal()
    try:
        folder = get_or_create_folder(db, repo_root, do_apply)
        existing = {
            path: media
            for path, media in db.query(models.Media.absolute_path, models.Media).all()
        }

        stats = Counter()
        kind_counts = Counter(candidate.kind for candidate in candidates)
        samples: list[AssetCandidate] = []

        for candidate in candidates:
            stats["candidates"] += 1
            absolute = str(candidate.path.resolve())
            current = existing.get(absolute)
            if current:
                stats["existing"] += 1
                if do_apply:
                    current.is_missing = False
                    current.missing_since = None
                    current.title = candidate.title
                    current.source_site = SOURCE_SITE
                    current.source_url = candidate.source_url
                    current.relative_path = candidate.rel
                    try:
                        current.file_size = candidate.path.stat().st_size
                    except OSError:
                        pass
                    metadata = scanner.get_image_metadata(str(candidate.path))
                    current.width = metadata["width"]
                    current.height = metadata["height"]
                    if refresh_thumbnails or not current.cover_path:
                        current.cover_path = ensure_thumbnail(candidate)
                    attach_tags(db, current, candidate.tags)
                    stats["updated"] += 1
                continue

            stats["new"] += 1
            if len(samples) < 8:
                samples.append(candidate)
            if not do_apply:
                continue

            metadata = scanner.get_image_metadata(str(candidate.path))
            try:
                file_size = candidate.path.stat().st_size
            except OSError:
                stats["missing_on_disk"] += 1
                continue

            media = models.Media(
                folder_id=folder.id if folder else None,
                title=candidate.title,
                relative_path=candidate.rel,
                absolute_path=absolute,
                media_type="image",
                extension=candidate.path.suffix.lower(),
                file_size=file_size,
                width=metadata["width"],
                height=metadata["height"],
                source_site=SOURCE_SITE,
                source_url=candidate.source_url,
                is_missing=False,
                duplicate_status="unique",
            )
            media.cover_path = ensure_thumbnail(candidate)
            db.add(media)
            attach_tags(db, media, candidate.tags)
            existing[absolute] = media
            stats["inserted"] += 1

        if do_apply and folder:
            folder.last_scanned_at = datetime.now()
            db.commit()

        mode = "APPLIED" if do_apply else "DRY RUN"
        print(f"[{mode}] repo: {repo_root}")
        print(f"[{mode}] selected assets: {stats['candidates']}")
        print(f"[{mode}]   new:      {stats['new']}")
        print(f"[{mode}]   existing: {stats['existing']}")
        if do_apply:
            print(f"[{mode}]   inserted: {stats['inserted']}")
            print(f"[{mode}]   updated:  {stats['updated']}")
        if stats["missing_on_disk"]:
            print(f"[{mode}]   missing on disk: {stats['missing_on_disk']}")

        if kind_counts:
            print("\nBy kind:")
            for kind, count in sorted(kind_counts.items()):
                print(f"  {kind:<16} {count}")

        if samples:
            print("\nSample new assets:")
            for item in samples:
                print(f"  {item.kind:<16} {item.title}  [{item.rel}]")

        if not do_apply:
            print("\nDRY RUN - nothing written. Re-run with --apply to import.")
    finally:
        db.close()


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", required=True, help="Path to the Brown-Dust-2-Asset checkout")
    parser.add_argument("--apply", action="store_true", help="Write Media rows, tags, and thumbnails")
    parser.add_argument("--limit", type=int, default=0, help="Only process the first N selected assets")
    parser.add_argument("--include-faces", action="store_true", help="Also import ui/illust/illust_face")
    parser.add_argument("--include-npc", action="store_true", help="Also import NPC illustration folders")
    parser.add_argument(
        "--refresh-thumbnails",
        action="store_true",
        help="Regenerate thumbnails for existing imported assets",
    )
    return parser.parse_args()


def main() -> None:
    _configure_stdout()
    args = parse_args()
    repo_root = Path(args.repo).expanduser().resolve()
    if not repo_root.exists():
        raise SystemExit(f"Repository path does not exist: {repo_root}")
    if not (repo_root / "README.md").exists():
        print(f"! {repo_root} does not look like the repository root; continuing anyway.")

    commit = repo_commit(repo_root)
    char_info = load_char_info(repo_root)
    candidates = discover_candidates(
        repo_root=repo_root,
        char_info=char_info,
        commit=commit,
        include_faces=args.include_faces,
        include_npc=args.include_npc,
    )
    if args.limit and args.limit > 0:
        candidates = candidates[: args.limit]
    import_assets(repo_root, candidates, args.apply, args.refresh_thumbnails)


if __name__ == "__main__":
    main()
