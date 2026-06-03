r"""One-shot DB path rewrite for the Windows -> Linux migration.

Rewrites the stored absolute Windows paths (drive `X:\...`) to their Linux
mount location and flips backslashes to forward slashes, across every column
that holds a filesystem path. Runs against a COPY of library.db (never the
Windows original). Idempotent-ish: re-running on already-Linux paths is a
no-op because they no longer match the `X:\` drive pattern.

Usage (on the VM):  python3 _migrate_paths_to_linux.py /path/to/library.db /mnt/hdd
  arg1 = db file, arg2 = the Linux mount that the old drive root now lives at.
"""
import os
import sqlite3
import sys

DB = sys.argv[1] if len(sys.argv) > 1 else "/home/user1/HE_manager/data/library.db"
LINUX_ROOT = (sys.argv[2] if len(sys.argv) > 2 else "/mnt/hdd").rstrip("/")


def to_linux_abs(p):
    """`E:\\hhh\\a\\b` -> `/mnt/hdd/hhh/a/b`. Any drive letter maps to the same
    Linux root (this library only ever used E:). Already-Linux paths pass
    through untouched."""
    if not p:
        return p
    if len(p) >= 3 and p[1] == ":" and p[2] == "\\":
        return (LINUX_ROOT + "/" + p[3:].replace("\\", "/")).replace("//", "/")
    return p.replace("\\", "/")


def to_linux_rel(p):
    return p.replace("\\", "/") if p else p


con = sqlite3.connect(DB)
con.row_factory = sqlite3.Row
cur = con.cursor()


def has_table(t):
    return cur.execute(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", (t,)
    ).fetchone() is not None


def cols(t):
    return [r[1] for r in cur.execute(f"PRAGMA table_info({t})")]


def rewrite(table, col, fn):
    if not has_table(table):
        print(f"  (skip {table}: no such table)")
        return
    if col not in cols(table):
        print(f"  (skip {table}.{col}: no such column)")
        return
    rows = cur.execute(
        f"SELECT rowid AS rid, {col} AS v FROM {table} WHERE {col} IS NOT NULL AND {col} <> ''"
    ).fetchall()
    n = 0
    for r in rows:
        new = fn(r["v"])
        if new != r["v"]:
            cur.execute(f"UPDATE {table} SET {col}=? WHERE rowid=?", (new, r["rid"]))
            n += 1
    print(f"  {table}.{col}: {n}/{len(rows)} rewritten")


print("=== rewrite ===")
rewrite("folders", "path", to_linux_abs)
rewrite("media", "absolute_path", to_linux_abs)
rewrite("media", "relative_path", to_linux_rel)
rewrite("external_favorite_sources", "download_root_path", to_linux_abs)
rewrite("x_import_sources", "download_root_path", to_linux_abs)
con.commit()

print("=== folders after ===")
for r in cur.execute("SELECT id, scan_mode, path FROM folders ORDER BY id"):
    print(f"  #{r['id']} [{r['scan_mode']}] {r['path']}")

print("=== existence check (sample up to 300 media) ===")
ok = miss = 0
samples = []
for r in cur.execute(
    "SELECT absolute_path AS p FROM media WHERE absolute_path IS NOT NULL AND absolute_path <> '' LIMIT 300"
):
    if os.path.exists(r["p"]):
        ok += 1
    else:
        miss += 1
        if len(samples) < 6:
            samples.append(r["p"])
print(f"  exists: {ok}   missing: {miss}")
for s in samples:
    print("   MISS:", s)

con.close()
