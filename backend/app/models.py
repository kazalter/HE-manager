from sqlalchemy import Column, Integer, String, DateTime, ForeignKey, Table, Boolean, LargeBinary, Text, UniqueConstraint
from sqlalchemy.orm import relationship
from datetime import datetime
from .database import Base

# Many-to-Many relationship table for Media and Tags
media_tags = Table(
    "media_tag",
    Base.metadata,
    Column("media_id", Integer, ForeignKey("media.id"), primary_key=True),
    Column("tag_id", Integer, ForeignKey("tags.id"), primary_key=True),
)

class Folder(Base):
    __tablename__ = "folders"

    id = Column(Integer, primary_key=True, index=True)
    path = Column(String, unique=True, index=True)
    status = Column(String, default="idle")  # idle, scanning, error
    # auto / manga / video / image
    # / audio       — single-file audio (one Media per .mp3/.wav/etc., scanner walks tree)
    # / audio_work  — folder-of-audio (one Media per work folder; identified by
    #                 tracks.json / source.txt / direct audio files. ASMR
    #                 downloads also use this mode.)
    scan_mode = Column(String, default="auto")
    thumbnail_enabled = Column(Boolean, default=True)
    thumbnail_interval = Column(Integer, default=1)
    last_scanned_at = Column(DateTime, nullable=True)
    
    media_items = relationship("Media", back_populates="folder", cascade="all, delete-orphan")

class Media(Base):
    __tablename__ = "media"

    id = Column(Integer, primary_key=True, index=True)
    folder_id = Column(Integer, ForeignKey("folders.id"), index=True)
    title = Column(String, index=True)
    relative_path = Column(String)
    absolute_path = Column(String, unique=True)
    media_type = Column(String, index=True)  # 'video', 'manga', 'image', or 'audio'
    extension = Column(String)
    file_size = Column(Integer)
    cover_path = Column(String, nullable=True)  # Path to the generated thumbnail
    duration = Column(Integer, nullable=True)  # Video duration in seconds
    width = Column(Integer, nullable=True)
    height = Column(Integer, nullable=True)
    page_count = Column(Integer, nullable=True)
    rating = Column(Integer, default=0)
    favorite = Column(Boolean, default=False)
    view_status = Column(String, default="unviewed")  # unviewed, viewing, viewed
    progress = Column(Integer, default=0)
    last_opened_at = Column(DateTime, nullable=True)
    source_url = Column(String, nullable=True)
    source_site = Column(String, nullable=True)
    is_missing = Column(Boolean, default=False)
    missing_since = Column(DateTime, nullable=True)
    cover_time_ms = Column(Integer, nullable=True)
    cover_source = Column(String, nullable=True) # e.g., 'first_valid_frame', 'fallback_10_percent', 'manual'
    # Doujin-style author parsed from manga title via manga_artist.parse_artist.
    # Used by creators.py to surface manga artists alongside X authors in
    # /creators. Nullable because non-manga media + titles without a leading
    # bracket have no artist; indexed for the creators GROUP-BY scan.
    artist = Column(String, nullable=True, index=True)
    created_at = Column(DateTime, default=datetime.utcnow)
    # Local-file dedup fields. `duplicate_status` is one of:
    # 'unique'              — confirmed unique
    # 'checking'            — fingerprint job pending; UI hides from main library
    # 'strong_duplicate'    — confirmed dup of an existing entry; UI hides from main library, exposed via dedup management
    # 'suspected_duplicate' — needs user confirmation; UI hides from main library
    # 'weak_suspected'      — visible in library but flagged
    normalized_title = Column(String, index=True, nullable=True)
    duplicate_status = Column(String, index=True, default="unique")

    folder = relationship("Folder", back_populates="media_items")
    tags = relationship("Tag", secondary=media_tags, back_populates="media_items")
    fingerprint = relationship(
        "MediaFingerprint",
        back_populates="media",
        uselist=False,
        cascade="all, delete-orphan",
    )
    ai_profile = relationship(
        "MangaAIProfile",
        back_populates="media",
        uselist=False,
        cascade="all, delete-orphan",
    )
    metadata_profile = relationship(
        "MangaMetadataProfile",
        back_populates="media",
        uselist=False,
        cascade="all, delete-orphan",
    )

class Tag(Base):
    __tablename__ = "tags"

    id = Column(Integer, primary_key=True, index=True)
    name = Column(String, unique=True, index=True)

    media_items = relationship("Media", secondary=media_tags, back_populates="tags")


class MangaAIProfile(Base):
    __tablename__ = "manga_ai_profiles"

    id = Column(Integer, primary_key=True, index=True)
    media_id = Column(Integer, ForeignKey("media.id"), unique=True, index=True)
    content_summary = Column(Text, nullable=True)
    style_tags = Column(Text, nullable=True)
    story_tags = Column(Text, nullable=True)
    tone_tags = Column(Text, nullable=True)
    recommendation_keywords = Column(Text, nullable=True)
    sampled_pages = Column(Text, nullable=True)
    ocr_text = Column(Text, nullable=True)
    visual_features = Column(Text, nullable=True)
    analyzer_version = Column(String, default="v1")
    source_mtime = Column(Integer, nullable=True)
    # Dense text embedding for semantic retrieval (Phase 2 / RAG).
    # Stored as raw float32 bytes — encode via np.asarray(..., dtype=np.float32).tobytes(),
    # decode via np.frombuffer(blob, dtype=np.float32). Dimension is fixed by the
    # model in use (see manga_vector.MODEL_NAME); if you swap models, re-run
    # backfill_embeddings.py to rewrite every row.
    embedding = Column(LargeBinary, nullable=True)
    # Which model produced the blob above. Lets the vector layer skip / re-embed
    # rows that were encoded by a different (incompatible-dim) model.
    embedding_model = Column(String, nullable=True)
    created_at = Column(DateTime, default=datetime.utcnow)
    updated_at = Column(DateTime, default=datetime.utcnow, onupdate=datetime.utcnow)

    media = relationship("Media", back_populates="ai_profile")


class MangaMetadataProfile(Base):
    __tablename__ = "manga_metadata_profiles"

    id = Column(Integer, primary_key=True, index=True)
    media_id = Column(Integer, ForeignKey("media.id"), unique=True, index=True)
    normalized_title = Column(String, index=True, nullable=True)
    parsed_title = Column(String, nullable=True)
    parsed_artist = Column(String, nullable=True)
    parsed_circle = Column(String, nullable=True)
    parody = Column(String, nullable=True)
    language = Column(String, nullable=True)
    external_tags = Column(Text, nullable=True)
    external_summary = Column(Text, nullable=True)
    source_matches = Column(Text, nullable=True)
    confidence = Column(Integer, default=0)
    analyzer_version = Column(String, default="metadata-v1")
    source_signature = Column(String, nullable=True)
    created_at = Column(DateTime, default=datetime.utcnow)
    updated_at = Column(DateTime, default=datetime.utcnow, onupdate=datetime.utcnow)

    media = relationship("Media", back_populates="metadata_profile")


class User(Base):
    __tablename__ = "users"

    id = Column(Integer, primary_key=True, index=True)
    username = Column(String, unique=True, index=True)
    password_hash = Column(String)
    is_admin = Column(Boolean, default=False)
    is_active = Column(Boolean, default=True)
    created_at = Column(DateTime, default=datetime.utcnow)

    tokens = relationship("AccessToken", back_populates="user", cascade="all, delete-orphan")


class AccessToken(Base):
    __tablename__ = "access_tokens"

    id = Column(Integer, primary_key=True, index=True)
    token_hash = Column(String, unique=True, index=True)
    user_id = Column(Integer, ForeignKey("users.id"))
    created_at = Column(DateTime, default=datetime.utcnow)
    last_used_at = Column(DateTime, nullable=True)
    revoked = Column(Boolean, default=False)

    user = relationship("User", back_populates="tokens")


class ExternalFavoriteSource(Base):
    __tablename__ = "external_favorite_sources"

    id = Column(Integer, primary_key=True, index=True)
    source_type = Column(String, index=True, default="wnacg")
    name = Column(String, default="WNACG")
    favorites_url = Column(String)
    cookie = Column(Text, nullable=True)
    download_root_path = Column(String, nullable=True)
    status = Column(String, default="idle")  # idle, syncing, ok, error
    last_synced_at = Column(DateTime, nullable=True)
    last_error = Column(Text, nullable=True)
    created_at = Column(DateTime, default=datetime.utcnow)
    updated_at = Column(DateTime, default=datetime.utcnow, onupdate=datetime.utcnow)
    # ASMR-only fields (NULL for wnacg sources). Adding columns here instead of
    # a separate table keeps `/external/sources` polymorphic — the frontend
    # already filters by source_type. Reuse: favorites_url=api_base for asmr,
    # cookie=bearer_token (after login; not the raw password — we don't store
    # passwords, just the token we got back). api_mirrors is a newline-separated
    # list per the existing parse_mirrors helper.
    api_mirrors = Column(Text, nullable=True)
    audio_format_filter = Column(String, nullable=True)   # 'all' | 'no_wav' | 'mp3_only'
    audio_version_filter = Column(String, nullable=True)  # 'all' | 'no_se' | 'se_only'
    username = Column(String, nullable=True)              # asmr.one account name (for re-login if token expires)
    playlist_url = Column(String, nullable=True)          # opt-in: pull from a playlist instead of "marked"

    items = relationship("ExternalFavoriteItem", back_populates="source", cascade="all, delete-orphan")

    @property
    def cookie_saved(self):
        return bool(self.cookie)


class ExternalFavoriteItem(Base):
    __tablename__ = "external_favorite_items"
    __table_args__ = (
        UniqueConstraint("source_id", "external_id", name="uq_external_favorite_source_external_id"),
    )

    id = Column(Integer, primary_key=True, index=True)
    source_id = Column(Integer, ForeignKey("external_favorite_sources.id"))
    source_type = Column(String, index=True, default="wnacg")
    external_id = Column(String, index=True)
    title = Column(String)
    url = Column(String)
    cover_url = Column(String, nullable=True)
    category_id = Column(String, nullable=True)
    category_name = Column(String, nullable=True)
    sync_position = Column(Integer, nullable=True)
    last_seen_at = Column(DateTime, default=datetime.utcnow)
    created_at = Column(DateTime, default=datetime.utcnow)
    updated_at = Column(DateTime, default=datetime.utcnow, onupdate=datetime.utcnow)

    source = relationship("ExternalFavoriteSource", back_populates="items")


class XImportSource(Base):
    __tablename__ = "x_import_sources"

    id = Column(Integer, primary_key=True, index=True)
    name = Column(String, default="X 喜欢导入")
    download_root_path = Column(String, nullable=True)
    last_archive_name = Column(String, nullable=True)
    last_archive_imported_at = Column(DateTime, nullable=True)
    last_sync_at = Column(DateTime, nullable=True)
    last_cursor = Column(String, nullable=True)
    cookie = Column(Text, nullable=True)
    created_at = Column(DateTime, default=datetime.utcnow)
    updated_at = Column(DateTime, default=datetime.utcnow, onupdate=datetime.utcnow)

    posts = relationship("XPost", back_populates="source", cascade="all, delete-orphan")

    @property
    def cookie_saved(self):
        return bool(self.cookie)


class XPost(Base):
    __tablename__ = "x_posts"
    __table_args__ = (
        UniqueConstraint("source_id", "tweet_id", name="uq_x_post_source_tweet"),
    )

    id = Column(Integer, primary_key=True, index=True)
    source_id = Column(Integer, ForeignKey("x_import_sources.id"))
    tweet_id = Column(String, index=True)
    url = Column(String)
    author_screen_name = Column(String, index=True, nullable=True)
    author_name = Column(String, nullable=True)
    posted_at = Column(DateTime, nullable=True)
    full_text = Column(Text, nullable=True)
    media_count = Column(Integer, default=0)
    has_media = Column(Boolean, default=False)
    status = Column(String, index=True, default="pending")  # pending, fetched, downloading, completed, failed, skipped
    error_message = Column(Text, nullable=True)
    last_attempt_at = Column(DateTime, nullable=True)
    completed_at = Column(DateTime, nullable=True)
    discovered_at = Column(DateTime, default=datetime.utcnow)
    archive_name = Column(String, nullable=True)

    source = relationship("XImportSource", back_populates="posts")
    media_items = relationship("XMediaItem", back_populates="post", cascade="all, delete-orphan")


class MediaFingerprint(Base):
    __tablename__ = "media_fingerprints"

    id = Column(Integer, primary_key=True, index=True)
    media_id = Column(Integer, ForeignKey("media.id"), unique=True, index=True)
    media_type = Column(String, index=True)
    file_size = Column(Integer, nullable=True)
    page_count = Column(Integer, nullable=True)
    duration = Column(Integer, nullable=True)
    width = Column(Integer, nullable=True)
    height = Column(Integer, nullable=True)
    # Sampled hashes (truncated SHA-1). Used per type:
    #   manga / image-set: first / middle / last page
    #   video: first / mid / end frame
    #   single image: hash_first holds the full-file SHA-1
    hash_first = Column(String, nullable=True)
    hash_middle = Column(String, nullable=True)
    hash_last = Column(String, nullable=True)
    # Cache invalidation key. If (path, size, mtime) match, fingerprint is reused.
    source_path = Column(String, nullable=True)
    source_mtime = Column(Integer, nullable=True)
    computed_at = Column(DateTime, default=datetime.utcnow, onupdate=datetime.utcnow)

    media = relationship("Media", back_populates="fingerprint")


class DuplicateCandidate(Base):
    __tablename__ = "duplicate_candidates"
    __table_args__ = (
        UniqueConstraint("existing_media_id", "candidate_media_id", name="uq_dup_pair"),
    )

    id = Column(Integer, primary_key=True, index=True)
    # The "older" / kept entry; the candidate is the newly-discovered one.
    existing_media_id = Column(Integer, ForeignKey("media.id"), index=True)
    candidate_media_id = Column(Integer, ForeignKey("media.id"), index=True)
    level = Column(String, index=True)  # strong_duplicate, suspected_duplicate, weak_suspected
    similarity = Column(Integer, default=0)  # 0-100
    reason = Column(Text, nullable=True)
    status = Column(String, index=True, default="pending")  # pending, merged, kept_both, ignored, replaced
    resolved_at = Column(DateTime, nullable=True)
    resolution_note = Column(Text, nullable=True)
    created_at = Column(DateTime, default=datetime.utcnow)
    updated_at = Column(DateTime, default=datetime.utcnow, onupdate=datetime.utcnow)


class XMediaItem(Base):
    __tablename__ = "x_media_items"
    __table_args__ = (
        UniqueConstraint("post_id", "media_index", name="uq_x_media_post_index"),
    )

    id = Column(Integer, primary_key=True, index=True)
    post_id = Column(Integer, ForeignKey("x_posts.id"))
    media_index = Column(Integer, default=0)
    media_type = Column(String, index=True)  # photo, video, animated_gif
    remote_url = Column(String)
    local_path = Column(String, nullable=True)
    file_size = Column(Integer, nullable=True)
    width = Column(Integer, nullable=True)
    height = Column(Integer, nullable=True)
    duration_ms = Column(Integer, nullable=True)
    library_media_id = Column(Integer, ForeignKey("media.id"), nullable=True)
    status = Column(String, index=True, default="pending")  # pending, downloaded, failed, skipped
    error_message = Column(Text, nullable=True)
    downloaded_at = Column(DateTime, nullable=True)

    post = relationship("XPost", back_populates="media_items")
