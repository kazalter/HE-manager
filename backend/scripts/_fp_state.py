import os, sqlite3
DB = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "app", "library.db")
c = sqlite3.connect(DB); c.row_factory = sqlite3.Row
fp_total = c.execute("SELECT COUNT(*) FROM media_fingerprints").fetchone()[0]
with_sha = c.execute("SELECT COUNT(*) FROM media_fingerprints WHERE hash_first IS NOT NULL").fetchone()[0]
with_ph = c.execute("SELECT COUNT(*) FROM media_fingerprints WHERE phash_first IS NOT NULL").fetchone()[0]
latest = c.execute("SELECT MAX(computed_at) FROM media_fingerprints").fetchone()[0]
ds = c.execute("SELECT duplicate_status, COUNT(*) FROM media GROUP BY duplicate_status").fetchall()
print(f"media_fingerprints rows : {fp_total}")
print(f"  with sha hash_first   : {with_sha}")
print(f"  with phash_first      : {with_ph}")
print(f"  latest computed_at    : {latest}")
print("media.duplicate_status  :", {r[0]: r[1] for r in ds})
