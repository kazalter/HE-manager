"""One-shot backfill of namespace tags for pre-existing library media.

P2 only auto-tags media *as it is imported*. This walks the existing library
and applies the same rules retroactively:

  - X media   → `artist:<author_screen_name>`  (via XMediaItem.library_media_id
                                                 → XPost.author_screen_name)
  - ext manga → `source:<category_name>`        (ExternalFavoriteItem matched
                                                 by source_url + source_type)

Idempotent: re-running only adds tags that are still missing (uses the same
tagging.attach_tag dedup as the import hooks).

Run from the backend directory:
    python scripts/backfill_tags.py            # DRY RUN — reports counts only
    python scripts/backfill_tags.py --apply    # actually writes + commits
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app import database, external_sources, models, tagging  # noqa: E402


def _has_tag(media: models.Media, name: str, namespace: str) -> bool:
    name = (name or "").strip()
    return any(t.name == name and t.namespace == namespace for t in media.tags)


def main(do_apply: bool) -> None:
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass

    db = database.SessionLocal()
    try:
        # --- X authors -> artist: ---------------------------------------
        x_rows = (
            db.query(models.Media, models.XPost.author_screen_name)
            .join(models.XMediaItem, models.XMediaItem.library_media_id == models.Media.id)
            .join(models.XPost, models.XPost.id == models.XMediaItem.post_id)
            .filter(models.XPost.author_screen_name.isnot(None))
            .all()
        )
        x_seen, x_added = set(), 0
        for media, author in x_rows:
            author = (author or "").strip()
            if not author:
                continue
            x_seen.add(media.id)
            if _has_tag(media, author, "artist"):
                continue
            x_added += 1
            if do_apply:
                tagging.attach_tag(db, media, author, "artist")

        # --- external categories -> source: -----------------------------
        items = (
            db.query(models.ExternalFavoriteItem)
            .filter(models.ExternalFavoriteItem.category_name.isnot(None))
            .all()
        )
        s_seen, s_added = set(), 0
        for item in items:
            category = (item.category_name or "").strip()
            if not category or external_sources.is_bogus_wnacg_category(category):
                continue
            media = (
                db.query(models.Media)
                .filter(
                    models.Media.source_url == item.url,
                    models.Media.source_site == item.source_type,
                    models.Media.media_type == "manga",
                )
                .first()
            )
            if not media:
                continue
            s_seen.add(media.id)
            if _has_tag(media, category, "source"):
                continue
            s_added += 1
            if do_apply:
                tagging.attach_tag(db, media, category, "source")

        if do_apply:
            db.commit()

        mode = "APPLIED" if do_apply else "DRY RUN"
        print(f"[{mode}] X media scanned: {len(x_seen)}  "
              f"artist: tags {'added' if do_apply else 'to add'}: {x_added}")
        print(f"[{mode}] ext manga matched: {len(s_seen)}  "
              f"source: tags {'added' if do_apply else 'to add'}: {s_added}")
        if not do_apply:
            print("\nDRY RUN — nothing written. Re-run with --apply to commit.")
        else:
            print("\nDone. Backfill committed.")
    finally:
        db.close()


if __name__ == "__main__":
    main("--apply" in sys.argv)
