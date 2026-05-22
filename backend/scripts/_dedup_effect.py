"""Read-only: how good is dedup *right now*.

Clusters same-type same-normalized-title media by phash distance (0 = visually
identical), counts redundant copies + reclaimable bytes (walks manga dirs since
file_size is 0 for .dir), and cross-checks against the existing
duplicate_status to see what the current SHA-1 system already flags vs misses.
"""
import os
import sqlite3
import sys
from collections import defaultdict

BACKEND = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, BACKEND)
from app.dedup.normalize import normalize_title  # noqa: E402

DB = os.path.join(BACKEND, "app", "library.db")


def ham(a, b):
    return bin(int(a, 16) ^ int(b, 16)).count("1")


def best(fa, fb):
    ds = [ham(fa[k], fb[k]) for k in ("pf", "pm", "pl") if fa[k] and fb[k]]
    return min(ds) if ds else None


def disk_size(path):
    try:
        if os.path.isdir(path):
            t = 0
            for r, _, fs in os.walk(path):
                for f in fs:
                    try:
                        t += os.path.getsize(os.path.join(r, f))
                    except OSError:
                        pass
            return t
        return os.path.getsize(path)
    except OSError:
        return 0


c = sqlite3.connect(DB)
c.row_factory = sqlite3.Row
rows = c.execute(
    """SELECT m.id, m.media_type, m.title, m.normalized_title, m.absolute_path,
              m.duplicate_status,
              f.phash_first pf, f.phash_middle pm, f.phash_last pl
       FROM media m LEFT JOIN media_fingerprints f ON f.media_id=m.id
       WHERE COALESCE(m.is_missing,0)=0"""
).fetchall()

by = defaultdict(list)
info = {}
for r in rows:
    info[r["id"]] = r
    if not (r["pf"] or r["pm"] or r["pl"]):
        continue
    key = (r["media_type"] or "?",
           (r["normalized_title"] or "").strip() or normalize_title(r["title"] or ""))
    if key[1]:
        by[key].append(r)

# Union-find over distance-0 pairs.
parent = {}
def find(x):
    parent.setdefault(x, x)
    while parent[x] != x:
        parent[x] = parent[parent[x]]
        x = parent[x]
    return x
def union(a, b):
    parent[find(a)] = find(b)

THRESH = 0
near = 0
for (mt, _k), members in by.items():
    n = len(members)
    for i in range(n):
        for j in range(i + 1, n):
            d = best(members[i], members[j])
            if d is not None and d <= THRESH:
                union(members[i]["id"], members[j]["id"])
            if d is not None and 1 <= d <= 4:
                near += 1

clusters = defaultdict(list)
for mid in list(parent):
    clusters[find(mid)].append(mid)
clusters = {k: v for k, v in clusters.items() if len(v) >= 2}

total_clusters = len(clusters)
total_items = sum(len(v) for v in clusters.values())
redundant = total_items - total_clusters
reclaim = 0
already_flagged = missed = 0
by_type = defaultdict(lambda: [0, 0])  # type -> [clusters, redundant]

for members in clusters.values():
    sizes = sorted(
        ((disk_size(info[m]["absolute_path"]), m) for m in members), reverse=True
    )
    mt = info[members[0]]["media_type"]
    by_type[mt][0] += 1
    by_type[mt][1] += len(members) - 1
    for sz, m in sizes[1:]:  # keep the largest copy, rest are reclaimable
        reclaim += sz
        st = info[m]["duplicate_status"]
        if st in ("strong_duplicate", "suspected_duplicate", "weak_suspected"):
            already_flagged += 1
        else:
            missed += 1

ds = defaultdict(int)
for r in rows:
    ds[r["duplicate_status"]] += 1

print(f"library: {len(rows)} media (not missing), phash coverage 100%")
print(f"existing duplicate_status: {dict(ds)}")
print()
print(f"phash-identical (distance {THRESH}) clusters: {total_clusters}")
print(f"  media in clusters : {total_items}")
print(f"  redundant copies  : {redundant}  (reclaimable)")
print(f"  reclaimable size  : {reclaim/1024/1024/1024:.2f} GB")
for mt, (cl, rd) in sorted(by_type.items()):
    print(f"    {mt}: {cl} clusters, {rd} redundant")
print()
print(f"of {redundant} redundant copies, current SHA-1 system:")
print(f"  already flags as dup : {already_flagged}")
print(f"  MISSES (still 'unique'/'checking'): {missed}")
print(f"near-dups (distance 1-4, not bit-identical) pairs: {near}")
print(f"stuck in 'checking' (never classified): {ds.get('checking',0)}")
