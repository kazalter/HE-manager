"""X (Twitter) one-click import module.

Architecture:
- archive.py: parse user-uploaded X data archive (data/like.js) into post records
- client.py: fetch tweet media via the public syndication endpoint (no auth)
- importer.py: background job state machine (pause/resume/cancel/retry)
- storage.py: download media files and create media library entries
"""
