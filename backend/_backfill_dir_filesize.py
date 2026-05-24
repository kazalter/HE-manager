"""One-off: backfill `Media.file_size` for folder-form rows that were created
when the scanner hard-coded `file_size=0` (manga directories + ASMR/audio
works + WNACG-downloaded manga). New scans now use `scanner.directory_size()`,
this script catches everything already in the DB.

Single-file media is left alone — its `file_size` was always correct.

Idempotent: re-runs only touch rows whose computed size differs from what's
stored, and prints a per-type summary.
"""
from __future__ import annotations

import os
import sys
from collections import defaultdict

from app.database import SessionLocal
from app import models, scanner


def main(dry_run: bool = False) -> int:
    db = SessionLocal()
    try:
        # Only folder-form rows. extension == '.dir' is the scanner's marker
        # for folder-as-Media. Other media_type='manga' rows (zip/cbz) keep
        # their real file_size.
        rows = (
            db.query(models.Media)
            .filter(models.Media.extension == ".dir")
            .all()
        )
        print(f"Inspecting {len(rows)} folder-form rows…")

        per_type_before: dict[str, int] = defaultdict(int)
        per_type_after: dict[str, int] = defaultdict(int)
        updated = 0
        missing = 0
        unchanged = 0

        for m in rows:
            per_type_before[m.media_type] += int(m.file_size or 0)
            path = m.absolute_path
            if not path or not os.path.isdir(path):
                missing += 1
                per_type_after[m.media_type] += int(m.file_size or 0)
                continue
            new_size = scanner.directory_size(path)
            per_type_after[m.media_type] += new_size
            if new_size == (m.file_size or 0):
                unchanged += 1
                continue
            if not dry_run:
                m.file_size = new_size
            updated += 1

        if not dry_run:
            db.commit()

        def gb(b: int) -> str:
            return f"{b / 1024 ** 3:.2f} GB"

        print()
        print(f"Updated:   {updated}")
        print(f"Unchanged: {unchanged}")
        print(f"Missing on disk (skipped): {missing}")
        print()
        print("Per-type totals (folder-form rows only):")
        types = sorted(set(per_type_before) | set(per_type_after))
        for t in types:
            before = per_type_before.get(t, 0)
            after = per_type_after.get(t, 0)
            print(f"  {t:8s}  {gb(before):>10s}  →  {gb(after):>10s}  (Δ {gb(after - before)})")

        if dry_run:
            print()
            print("(dry-run; no changes committed)")
        return 0
    finally:
        db.close()


if __name__ == "__main__":
    dry = "--dry-run" in sys.argv
    raise SystemExit(main(dry_run=dry))
