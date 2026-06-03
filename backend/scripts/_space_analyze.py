import sqlite3, os, sys, collections

DB = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "library.db")
c = sqlite3.connect(DB)
c.row_factory = sqlite3.Row

rows = c.execute(
    "SELECT id, absolute_path, media_type, extension, file_size, is_missing FROM media"
).fetchall()

# media_type -> stats
by_type = collections.defaultdict(lambda: {"count": 0, "bytes": 0})
# (media_type, ext) -> stats   (ext = real on-disk file extension, lowercased)
by_type_ext = collections.defaultdict(lambda: {"count": 0, "bytes": 0})

missing = 0
walked_dirs = 0
walked_files = 0
errors = 0

IMG_EXTS = {".jpg", ".jpeg", ".png", ".webp", ".gif", ".avif", ".bmp", ".jxl"}


def add(mt, ext, size):
    ext = (ext or "").lower()
    by_type[mt]["count"] += 1
    by_type[mt]["bytes"] += size
    by_type_ext[(mt, ext)]["count"] += 1
    by_type_ext[(mt, ext)]["bytes"] += size


total = len(rows)
for i, r in enumerate(rows):
    if i % 200 == 0:
        sys.stderr.write(f"  ...{i}/{total}\n")
    p = r["absolute_path"]
    mt = r["media_type"] or "?"
    if not p or not os.path.exists(p):
        missing += 1
        continue
    try:
        if os.path.isdir(p):
            walked_dirs += 1
            for root, _, files in os.walk(p):
                for fn in files:
                    fp = os.path.join(root, fn)
                    try:
                        sz = os.path.getsize(fp)
                    except OSError:
                        errors += 1
                        continue
                    walked_files += 1
                    e = os.path.splitext(fn)[1].lower()
                    add(mt, e, sz)
        else:
            walked_files += 1
            sz = os.path.getsize(p)
            e = os.path.splitext(p)[1].lower()
            add(mt, e, sz)
    except OSError:
        errors += 1


def gb(n):
    return n / (1024 ** 3)


def mb(n):
    return n / (1024 ** 2)


grand = sum(v["bytes"] for v in by_type.values())
print()
print("=" * 64)
print(f"DB rows: {total}  | missing/no-path: {missing}  | dirs walked: {walked_dirs}"
      f"  | files: {walked_files}  | errors: {errors}")
print(f"TOTAL ON DISK (tracked media): {gb(grand):.2f} GB")
print("=" * 64)
print()
print(f"{'media_type':<12}{'items':>8}{'size(GB)':>12}{'% of total':>12}")
print("-" * 44)
for mt, v in sorted(by_type.items(), key=lambda kv: -kv[1]["bytes"]):
    pct = 100 * v["bytes"] / grand if grand else 0
    print(f"{mt:<12}{v['count']:>8}{gb(v['bytes']):>12.2f}{pct:>11.1f}%")

print()
print("BY FORMAT within each media_type (top extensions):")
print("-" * 60)
for mt in sorted(by_type, key=lambda m: -by_type[m]["bytes"]):
    print(f"\n[{mt}]  total {gb(by_type[mt]['bytes']):.2f} GB")
    exts = [(e, s) for (m, e), s in by_type_ext.items() if m == mt]
    for e, s in sorted(exts, key=lambda kv: -kv[1]["bytes"])[:10]:
        share = 100 * s["bytes"] / by_type[mt]["bytes"] if by_type[mt]["bytes"] else 0
        print(f"  {e or '(noext)':<10}{s['count']:>8} files{mb(s['bytes']):>11.0f} MB"
              f"{share:>8.1f}%")
