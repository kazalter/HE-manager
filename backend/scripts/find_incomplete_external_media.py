"""Read-only audit: list external-download manga Media rows whose folder is
NOT a complete download. These are the bogus records the old auto-adopt bug
created for half-finished downloads (plus any folder later partially deleted).

Run from the backend directory:  python scripts/find_incomplete_external_media.py
Pass --delete to actually remove the listed rows (asks nothing; use after review).
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app import database, models  # noqa: E402
from app.main import is_external_download_complete  # noqa: E402


def main(do_delete: bool) -> None:
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass
    db = database.SessionLocal()
    try:
        source_types = [
            row[0]
            for row in db.query(models.ExternalFavoriteSource.source_type).distinct().all()
            if row[0]
        ]
        if not source_types:
            print("No external favorite sources found; nothing to audit.")
            return

        media_rows = (
            db.query(models.Media)
            .filter(
                models.Media.media_type == "manga",
                models.Media.source_site.in_(source_types),
            )
            .all()
        )

        dirty = []
        for media in media_rows:
            path = media.absolute_path
            complete = is_external_download_complete(path)
            if complete:
                continue
            exists = bool(path) and os.path.isdir(path)
            page_count = 0
            if exists:
                from app import scanner

                page_count = scanner.count_manga_pages(path, ".dir") or 0
            dirty.append((media, exists, page_count))

        print(f"Scanned {len(media_rows)} external manga rows "
              f"({', '.join(source_types)}).")
        print(f"Found {len(dirty)} INCOMPLETE / bogus rows:\n")
        for media, exists, page_count in dirty:
            print(f"  id={media.id}  site={media.source_site}  "
                  f"pages_on_disk={page_count}  dir_exists={exists}")
            print(f"     title : {media.title}")
            print(f"     path  : {media.absolute_path}")
            print(f"     url   : {media.source_url}")
            print()

        if not dirty:
            print("Nothing to clean. The library is consistent.")
            return

        if not do_delete:
            print("DRY RUN. Re-run with --delete to remove these rows "
                  "(folders on disk are kept so the download can resume).")
            return

        media_ids = [m.id for m, _, _ in dirty]
        db.query(models.XMediaItem).filter(
            models.XMediaItem.library_media_id.in_(media_ids)
        ).update({models.XMediaItem.library_media_id: None}, synchronize_session=False)
        db.query(models.DuplicateCandidate).filter(
            (models.DuplicateCandidate.existing_media_id.in_(media_ids))
            | (models.DuplicateCandidate.candidate_media_id.in_(media_ids))
        ).delete(synchronize_session=False)
        for media, _, _ in dirty:
            db.delete(media)
        db.commit()
        print(f"Deleted {len(media_ids)} bogus rows. "
              f"Partial folders on disk were left intact for resume.")
    finally:
        db.close()


if __name__ == "__main__":
    main("--delete" in sys.argv)
