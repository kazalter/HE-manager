"""P2 one-off: re-run duplicate classification over the whole library with the
new pHash-aware classify(). The live worker only processes newly-scanned items,
so existing entries (incl. the 41 pHash-identical copies SHA-1 missed and the
13 stuck 'checking') never get re-evaluated otherwise.

Reuses the worker's building blocks (classify / _ensure_fingerprint /
_lite_from_record / _upsert_candidate) but does its own normalized-title
grouping so: (a) coverage is complete (not gated by the candidate-side status
filter), (b) each unordered pair is classified exactly once and upserted in a
single canonical direction (smaller id = existing) — no doubled pairs.

Read-only on media files; writes duplicate_candidates + media.duplicate_status.
Throwaway maintenance script; delete after the dedup UI looks right.
"""
import os
import sys
import time
from collections import defaultdict

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from app import database, models  # noqa: E402
from app.dedup import worker  # noqa: E402
from app.dedup.classify import classify, LEVEL_UNIQUE, _LEVEL_RANK  # noqa: E402
from app.dedup.normalize import normalize_title  # noqa: E402


def main() -> None:
    db = database.SessionLocal()
    t0 = time.time()
    try:
        media = (
            db.query(models.Media)
            .filter(models.Media.is_missing == False)  # noqa: E712
            .order_by(models.Media.id)
            .all()
        )
        groups = defaultdict(list)
        for m in media:
            key = (m.media_type or "?",
                   (m.normalized_title or "").strip() or normalize_title(m.title or ""))
            if key[1]:
                groups[key].append(m)

        best = {}  # media_id -> level (highest seen)
        kept = set()  # (existing_id, candidate_id) classified non-unique this run
        lite_cache = {}

        def lite(m):
            if m.id not in lite_cache:
                fp = worker._ensure_fingerprint(db, m)
                lite_cache[m.id] = worker._lite_from_record(m, fp) if fp else None
            return lite_cache[m.id]

        pairs_made = 0
        groups_with_dups = 0
        multi = [(k, v) for k, v in groups.items() if len(v) >= 2]
        print(f"{len(media)} media, {len(multi)} same-title groups to scan",
              flush=True)

        for gi, (_key, members) in enumerate(multi):
            if gi % 200 == 0:
                print(f"  group {gi}/{len(multi)}  pairs={pairs_made}"
                      f"  {time.time()-t0:.0f}s", flush=True)
            members.sort(key=lambda m: m.id)
            hit = False
            for i in range(len(members)):
                la = lite(members[i])
                if la is None:
                    continue
                for j in range(i + 1, len(members)):
                    lb = lite(members[j])
                    if lb is None:
                        continue
                    level, sim, reasons = classify(la, lb)
                    if level == LEVEL_UNIQUE:
                        continue
                    hit = True
                    lo, hi = members[i].id, members[j].id  # canonical direction
                    worker._upsert_candidate(db, lo, hi, level, sim, reasons)
                    kept.add((lo, hi))
                    pairs_made += 1
                    for mid in (lo, hi):
                        if _LEVEL_RANK[level] > _LEVEL_RANK.get(best.get(mid, LEVEL_UNIQUE), 0):
                            best[mid] = level
            if hit:
                groups_with_dups += 1
            db.commit()

        # Prune stale 'pending' pairs that no longer classify as a dup (e.g.
        # vetoed by pHash). Never touch resolved pairs — the user's decisions
        # (merged / kept_both / ignored / replaced) are sticky.
        RESOLVED = ("merged", "kept_both", "ignored", "replaced")
        pruned = 0
        for p in db.query(models.DuplicateCandidate).filter(
            models.DuplicateCandidate.status.notin_(RESOLVED)
        ).all():
            if (p.existing_media_id, p.candidate_media_id) not in kept:
                db.delete(p)
                pruned += 1
        db.commit()

        # Recompute media.duplicate_status from scratch (also clears the stuck
        # 'checking' rows). Pair *resolution* state lives on the pair, not here.
        reset = 0
        for m in media:
            new_status = best.get(m.id, "unique")
            if m.duplicate_status != new_status:
                m.duplicate_status = new_status
                reset += 1
        db.commit()
        print(f"\nfinished in {time.time()-t0:.0f}s")
        print(f"  pairs upserted        : {pairs_made}")
        print(f"  stale pairs pruned    : {pruned}")
        print(f"  groups with dups      : {groups_with_dups}")
        print(f"  media status changed  : {reset}")
    finally:
        db.close()


if __name__ == "__main__":
    main()
