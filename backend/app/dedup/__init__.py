"""Local-file deduplication: title normalization, sampled fingerprints, candidate matching, merge.

Pipeline:
  1. scanner inserts new Media; if its normalized_title matches existing entries,
     mark duplicate_status='checking' and enqueue a fingerprint job.
  2. background worker computes sampled hashes (per-type) and classifies.
  3. results land in DuplicateCandidate; users review on /dedup page.

External-source dedup (X / WNACG / Pixiv) is handled by their own modules and is out of
scope for this package.
"""
