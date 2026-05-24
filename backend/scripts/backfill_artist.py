"""One-shot backfill of Media.artist for pre-existing manga.

The scanner does NOT populate Media.artist (see comment in main.py around
the artist column definition: "Backfill is opportunistic — happens next
time the row is rescanned or imported; old rows stay NULL until then").

That left ~all old manga with artist=NULL, so "want works by author X"
recommendations couldn't field-match. This script walks every manga and
runs manga_artist.parse_artist(title) on it, writing back any non-empty
result. Idempotent: only fills NULL/empty, never overwrites.

Run from the backend directory:
    python scripts/backfill_artist.py            # DRY RUN — reports counts
    python scripts/backfill_artist.py --apply    # actually writes
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app import database, manga_artist, models  # noqa: E402


def main(do_apply: bool) -> None:
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass

    db = database.SessionLocal()
    try:
        rows = (
            db.query(models.Media)
            .filter(
                models.Media.media_type == "manga",
                models.Media.is_missing == False,  # noqa: E712
            )
            .all()
        )
        had_artist = sum(1 for m in rows if (m.artist or "").strip())
        empty = [m for m in rows if not (m.artist or "").strip()]

        parsed_hits = 0
        parsed_misses = 0
        sample_hits: list[tuple[int, str, str]] = []
        sample_misses: list[tuple[int, str]] = []
        for media in empty:
            artist = manga_artist.parse_artist(media.title)
            if artist:
                parsed_hits += 1
                if do_apply:
                    media.artist = artist
                if len(sample_hits) < 8:
                    sample_hits.append((media.id, media.title or "", artist))
            else:
                parsed_misses += 1
                if len(sample_misses) < 8:
                    sample_misses.append((media.id, media.title or ""))

        if do_apply:
            db.commit()

        mode = "APPLIED" if do_apply else "DRY RUN"
        print(f"[{mode}] total manga: {len(rows)}")
        print(f"[{mode}]   already had artist: {had_artist}")
        print(f"[{mode}]   artist was empty:   {len(empty)}")
        print(f"[{mode}]     -> parser found:  {parsed_hits}"
              f" ({'written' if do_apply else 'to write'})")
        print(f"[{mode}]     -> parser failed: {parsed_misses}"
              " (no leading bracket / non-doujin format)")

        if sample_hits:
            print("\nSample of parsed hits:")
            for mid, title, artist in sample_hits:
                title_snip = title[:80].replace("\n", " ")
                print(f"  id={mid:>5} artist={artist!r:<25} title={title_snip!r}")

        if sample_misses:
            print("\nSample of titles the parser couldn't classify:")
            for mid, title in sample_misses:
                title_snip = title[:80].replace("\n", " ")
                print(f"  id={mid:>5} title={title_snip!r}")

        if not do_apply:
            print("\nDRY RUN — nothing written. Re-run with --apply to commit.")
        else:
            print("\nDone. Artist backfill committed.")
    finally:
        db.close()


if __name__ == "__main__":
    main("--apply" in sys.argv)
