"""P1 one-off: backfill pHash for the existing library.

A normal rescan only fingerprints newly-discovered files, so it cannot fill
phash for the 2007 already-imported entries. This pushes existing media
through the *same* pipeline the dedup worker uses (fingerprint.fingerprint_
for_media + the worker's persist logic), mirroring worker._ensure_fingerprint.

Read-only on media files; only writes media_fingerprints.phash_*/hash_*.
Resumable: skips rows whose fingerprint is already fresh and has phash.
Throwaway inspection script (like _phash_distribution.py); delete anytime.

Run:   python backend/scripts/_phash_backfill.py [--types manga,image] [--limit N]
"""
import argparse
import os
import sys
import time
from datetime import datetime

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from app import database, models  # noqa: E402
from app.dedup import fingerprint  # noqa: E402


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--types", default="manga,image,video",
                    help="comma list of media_type to process")
    ap.add_argument("--limit", type=int, default=0, help="cap rows (0 = all)")
    args = ap.parse_args()
    types = [t.strip() for t in args.types.split(",") if t.strip()]

    db = database.SessionLocal()
    try:
        q = (
            db.query(models.Media)
            .filter(models.Media.media_type.in_(types))
            .filter(models.Media.is_missing == False)  # noqa: E712
            .order_by(models.Media.media_type, models.Media.id)
        )
        if args.limit:
            q = q.limit(args.limit)
        media_list = q.all()
        total = len(media_list)
        print(f"backfill phash for {total} media (types={types})", flush=True)

        done = skipped = failed = 0
        t0 = time.time()
        for i, media in enumerate(media_list):
            if i % 50 == 0:
                el = time.time() - t0
                print(f"  {i}/{total}  done={done} skip={skipped} fail={failed}"
                      f"  {el:.0f}s", flush=True)
            record = (
                db.query(models.MediaFingerprint)
                .filter(models.MediaFingerprint.media_id == media.id)
                .first()
            )
            if record and fingerprint.fingerprint_cache_is_fresh(record, media):
                skipped += 1
                continue
            result = fingerprint.fingerprint_for_media(media)
            if result is None:
                failed += 1
                continue
            if not record:
                record = models.MediaFingerprint(media_id=media.id)
                db.add(record)
            record.media_type = result.media_type
            record.file_size = result.file_size
            record.page_count = result.page_count
            record.duration = result.duration
            record.width = result.width
            record.height = result.height
            record.hash_first = result.hash_first
            record.hash_middle = result.hash_middle
            record.hash_last = result.hash_last
            record.phash_first = result.phash_first
            record.phash_middle = result.phash_middle
            record.phash_last = result.phash_last
            record.source_path = result.source_path
            record.source_mtime = result.source_mtime
            record.computed_at = datetime.utcnow()
            done += 1
            if done % 25 == 0:
                db.commit()
        db.commit()
        print(f"finished: done={done} skipped={skipped} failed={failed}"
              f"  in {time.time()-t0:.0f}s", flush=True)
    finally:
        db.close()


if __name__ == "__main__":
    main()
