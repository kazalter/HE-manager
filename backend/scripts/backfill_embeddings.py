"""One-shot backfill of dense embeddings for existing manga.

Phase 2 adds an `embedding` column to manga_ai_profiles. Brand-new rows get
encoded inside `manga_profiles.analyze_media`, but pre-existing rows (or
rows whose embedding was produced by a different model) need this script.

What it does
============
1. Walks every visible manga in the library.
2. For each manga without a fresh embedding (NULL, or `embedding_model` !=
   the current MODEL_NAME), composes the canonical text from existing
   profile + metadata fields and encodes it.
3. Creates a MangaAIProfile row on the fly for manga that don't have one
   yet — the embedding doesn't need OCR or visual analysis to be useful;
   title + tags + metadata alone already drives semantic retrieval.

Run from the backend directory:
    python scripts/backfill_embeddings.py            # DRY RUN — counts only
    python scripts/backfill_embeddings.py --apply    # actually writes
"""
import os
import sys
import time
from datetime import datetime

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app import database, manga_vector, models  # noqa: E402


BATCH = 32  # encode in batches; one matmul over ~32 strings beats per-string calls


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

        # Partition: needs encoding vs already fresh
        to_encode: list[models.Media] = []
        fresh = 0
        for media in rows:
            profile = media.ai_profile
            if (
                profile
                and profile.embedding
                and profile.embedding_model == manga_vector.MODEL_NAME
            ):
                fresh += 1
                continue
            to_encode.append(media)

        print(f"[{'APPLY' if do_apply else 'DRY RUN'}] manga total: {len(rows)}")
        print(f"  already fresh (current model): {fresh}")
        print(f"  needs encoding:                {len(to_encode)}")
        print(f"  model:                         {manga_vector.MODEL_NAME}")
        print(f"  vector dim:                    {manga_vector.VECTOR_DIM}")

        if not to_encode:
            print("\nNothing to do.")
            return

        if not do_apply:
            print("\nDRY RUN — nothing written. Re-run with --apply to commit.")
            return

        # Warm up the model once outside the loop so the timing makes sense.
        print("\nLoading embedding model (first run downloads ~117MB)...")
        t0 = time.time()
        manga_vector.get_model()
        print(f"  model ready in {time.time() - t0:.1f}s")

        # Compose all texts up front so we can batch-encode (much faster than
        # encode_text per row).
        texts = [manga_vector.compose_doc_text(m) for m in to_encode]

        total = len(to_encode)
        encoded = 0
        t0 = time.time()
        for start in range(0, total, BATCH):
            batch_media = to_encode[start:start + BATCH]
            batch_texts = texts[start:start + BATCH]
            vecs = manga_vector.encode_batch(batch_texts)
            for media, vec in zip(batch_media, vecs):
                profile = media.ai_profile
                if profile is None:
                    profile = models.MangaAIProfile(media=media)
                    db.add(profile)
                profile.embedding = manga_vector.serialize_vec(vec)
                profile.embedding_model = manga_vector.MODEL_NAME
                profile.updated_at = datetime.utcnow()
                encoded += 1
            db.commit()
            elapsed = time.time() - t0
            rate = encoded / max(0.001, elapsed)
            print(f"  encoded {encoded}/{total}  ({rate:.1f} rows/s)")

        print(f"\nDone. {encoded} embeddings written in {time.time() - t0:.1f}s.")
    finally:
        db.close()


if __name__ == "__main__":
    main("--apply" in sys.argv)
