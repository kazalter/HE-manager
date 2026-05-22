"""P1 read-only observation: pHash Hamming-distance distribution.

Does NOT change any dedup judgment. For every same-media_type group sharing a
normalized title (the same candidate filter the worker uses), it computes the
Hamming distance between the best available phash sample of each pair and prints
a histogram. Use it to pick the P2 thresholds (plan: <=6 suspected, <=10 weak).

Run:  python backend/scripts/_phash_distribution.py
Note: phash is NULL until the dedup worker reprocesses entries (a library
rescan backfills it — fingerprint_cache_is_fresh now treats NULL phash as stale).
This is a throwaway inspection script (like _space_analyze.py); delete anytime.
"""
import os
import sqlite3
import sys
from collections import defaultdict

BACKEND = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, BACKEND)
from app.dedup.normalize import normalize_title  # noqa: E402

DB = os.path.join(BACKEND, "app", "library.db")


def hamming_hex(a: str, b: str) -> int:
    return bin(int(a, 16) ^ int(b, 16)).count("1")


def best_distance(fa, fb) -> int | None:
    """Min Hamming over the sample positions both sides have a phash for."""
    dists = []
    for ka, kb in (
        ("pf", "pf"), ("pm", "pm"), ("pl", "pl"),
    ):
        if fa[ka] and fb[kb]:
            try:
                dists.append(hamming_hex(fa[ka], fb[kb]))
            except ValueError:
                pass
    return min(dists) if dists else None


def main() -> None:
    c = sqlite3.connect(DB)
    c.row_factory = sqlite3.Row
    rows = c.execute(
        """
        SELECT m.id, m.media_type, m.title, m.normalized_title, m.is_missing,
               f.phash_first AS pf, f.phash_middle AS pm, f.phash_last AS pl
        FROM media m
        LEFT JOIN media_fingerprints f ON f.media_id = m.id
        WHERE COALESCE(m.is_missing, 0) = 0
        """
    ).fetchall()

    total = len(rows)
    with_fp = sum(1 for r in rows if r["pf"] or r["pm"] or r["pl"])
    print(f"media (not missing): {total}")
    print(f"  with at least one phash sample: {with_fp}"
          f"  ({100*with_fp/total:.1f}%)" if total else "")
    if with_fp == 0:
        print("\n  phash not populated yet. Trigger a library rescan so the dedup")
        print("  worker recomputes fingerprints (NULL phash is now treated as")
        print("  stale), then re-run this script.")
        return

    groups: dict[tuple, list] = defaultdict(list)
    for r in rows:
        mt = r["media_type"] or "?"
        key = (r["normalized_title"] or "").strip() or normalize_title(r["title"] or "")
        if not key:
            continue
        groups[(mt, key)].append(
            {"id": r["id"], "pf": r["pf"], "pm": r["pm"], "pl": r["pl"]}
        )

    buckets = [
        ("0  (identical)", lambda d: d == 0),
        ("1-2", lambda d: 1 <= d <= 2),
        ("3-4", lambda d: 3 <= d <= 4),
        ("5-6  (<= suspected)", lambda d: 5 <= d <= 6),
        ("7-10 (<= weak)", lambda d: 7 <= d <= 10),
        ("11-16", lambda d: 11 <= d <= 16),
        ("17-32", lambda d: 17 <= d <= 32),
        ("33+", lambda d: d >= 33),
    ]
    hist = defaultdict(lambda: defaultdict(int))  # media_type -> bucket -> n
    pairs = defaultdict(int)
    no_phash_pair = defaultdict(int)

    for (mt, _key), members in groups.items():
        n = len(members)
        if n < 2:
            continue
        for i in range(n):
            for j in range(i + 1, n):
                pairs[mt] += 1
                d = best_distance(members[i], members[j])
                if d is None:
                    no_phash_pair[mt] += 1
                    continue
                for label, pred in buckets:
                    if pred(d):
                        hist[mt][label] += 1
                        break

    print("\nHamming-distance distribution among same-type same-title pairs")
    print("(best of first/middle/last; lower = more likely a near-dup)\n")
    for mt in sorted(hist):
        tot = pairs[mt]
        print(f"[{mt}]  {tot} candidate pairs"
              f"  ({no_phash_pair[mt]} unscorable: a side has no phash)")
        for label, _pred in buckets:
            n = hist[mt][label]
            if n:
                bar = "#" * min(50, n)
                print(f"  {label:<22}{n:>6}  {bar}")
        print()


if __name__ == "__main__":
    main()
