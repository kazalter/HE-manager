import os
import tempfile
import unittest

from PIL import Image
from sqlalchemy import create_engine, event
from sqlalchemy.orm import sessionmaker

import numpy as np

from app import ai_config, manga_metadata, manga_profiles, manga_search, manga_vector, models, recommendations


class RecommendationTest(unittest.TestCase):
    def setUp(self):
        self.engine = create_engine("sqlite:///:memory:", connect_args={"check_same_thread": False})
        event.listen(self.engine, "connect", self._enable_foreign_keys)
        models.Base.metadata.create_all(bind=self.engine)
        self.Session = sessionmaker(autocommit=False, autoflush=False, bind=self.engine)
        self._old_config_dir = ai_config.CONFIG_DIR
        self._old_config_path = ai_config.CONFIG_PATH
        self._old_env = {key: os.environ.get(key) for key in ("DEEPSEEK_API_KEY", "DEEPSEEK_MODEL", "DEEPSEEK_API_BASE")}
        for key in self._old_env:
            os.environ.pop(key, None)
        self._config_tmp = tempfile.TemporaryDirectory()
        ai_config.CONFIG_DIR = self._config_tmp.name
        ai_config.CONFIG_PATH = os.path.join(self._config_tmp.name, "deepseek.json")
        self._tag_cache = {}

    def tearDown(self):
        ai_config.CONFIG_DIR = self._old_config_dir
        ai_config.CONFIG_PATH = self._old_config_path
        for key, value in self._old_env.items():
            if value is None:
                os.environ.pop(key, None)
            else:
                os.environ[key] = value
        self._config_tmp.cleanup()

    @staticmethod
    def _enable_foreign_keys(dbapi_connection, connection_record):
        cursor = dbapi_connection.cursor()
        cursor.execute("PRAGMA foreign_keys=ON")
        cursor.close()

    def test_recommend_manga_honors_avoid_tags(self):
        db = self.Session()
        try:
            folder = models.Folder(path="D:\\Manga", scan_mode="manga")
            good = models.Media(
                folder=folder,
                title="画风精细的短篇",
                relative_path="good.cbz",
                absolute_path="D:\\Manga\\good.cbz",
                media_type="manga",
                extension=".cbz",
                file_size=1024,
                page_count=24,
                rating=4,
            )
            good.tags.append(models.Tag(name="短篇"))
            avoided = models.Media(
                folder=folder,
                title="黑暗剧情短篇",
                relative_path="avoid.cbz",
                absolute_path="D:\\Manga\\avoid.cbz",
                media_type="manga",
                extension=".cbz",
                file_size=1024,
                page_count=20,
                rating=5,
            )
            db.add(folder)
            db.commit()

            result = recommendations.recommend_manga(
                db=db,
                query="想看画风精细短篇",
                limit=5,
                avoid_tags=["黑暗"],
                preferred_tags=[],
            )

            ids = [item["media"].id for item in result["recommendations"]]
            self.assertIn(good.id, ids)
            self.assertNotIn(avoided.id, ids)
        finally:
            db.close()

    def test_deepseek_config_is_saved_without_exposing_key(self):
        saved = ai_config.update_deepseek_config(
            api_key="sk-test",
            model="deepseek-chat",
            base_url="https://api.deepseek.com/",
        )

        self.assertEqual(saved["api_key"], "sk-test")
        self.assertEqual(saved["base_url"], "https://api.deepseek.com")
        self.assertTrue(saved["key_saved"])

        cleared = ai_config.update_deepseek_config(clear_api_key=True)
        self.assertFalse(cleared["key_saved"])
        self.assertEqual(cleared["api_key"], "")

    def test_manga_profile_analyzes_sampled_pages(self):
        db = self.Session()
        try:
            with tempfile.TemporaryDirectory() as tmp:
                for idx, color in enumerate([(245, 245, 245), (20, 20, 20), (200, 200, 210)], start=1):
                    Image.new("RGB", (240, 320), color=color).save(os.path.join(tmp, f"{idx:03d}.jpg"))

                folder = models.Folder(path=tmp, scan_mode="manga")
                media = models.Media(
                    folder=folder,
                    title="sample manga",
                    relative_path=".",
                    absolute_path=tmp,
                    media_type="manga",
                    extension=".dir",
                    file_size=1024,
                    page_count=3,
                    rating=0,
                )
                db.add(folder)
                db.commit()

                profile = manga_profiles.analyze_media(db, media, sample_count=3)
                db.commit()

                self.assertEqual(profile.media_id, media.id)
                self.assertTrue(profile.content_summary)
                self.assertGreaterEqual(len(manga_profiles.json_loads(profile.sampled_pages, [])), 1)
                self.assertIn("aggregate", manga_profiles.json_loads(profile.visual_features, {}))
        finally:
            db.close()

    def _tag(self, db, name):
        """Get-or-create with a session-scoped cache (db.autoflush=False here)."""
        cache = getattr(self, "_tag_cache", None)
        if cache is None:
            cache = {}
            self._tag_cache = cache
        if name in cache:
            return cache[name]
        tag = models.Tag(name=name)
        db.add(tag)
        cache[name] = tag
        return tag

    def _make_manga(
        self,
        db,
        folder,
        title,
        *,
        tags=(),
        artist=None,
        rating=0,
        favorite=False,
        view_status="unviewed",
        page_count=24,
    ):
        media = models.Media(
            folder=folder,
            title=title,
            relative_path=f"{title}.cbz",
            absolute_path=f"D:\\Manga\\{title}.cbz",
            media_type="manga",
            extension=".cbz",
            file_size=1024,
            page_count=page_count,
            rating=rating,
            favorite=favorite,
            view_status=view_status,
            artist=artist,
        )
        for tag_name in tags:
            media.tags.append(self._tag(db, tag_name))
        return media

    def test_tag_match_beats_unrelated_high_rating(self):
        """A query token hitting a tag should outrank a 5-star manga with no tag link.

        Also verifies that under a specific query, unrelated high-rated items
        are *excluded* — we no longer pad the result list with zero-relevance
        picks just to hit `limit`. The 5-star "战斗" manga doesn't share any
        token with "治愈" so it should not appear at all.
        """
        db = self.Session()
        try:
            folder = models.Folder(path="D:\\Manga", scan_mode="manga")
            relevant = self._make_manga(db, folder, "无标签匹配的故事", tags=["治愈"], rating=2)
            unrelated_star = self._make_manga(db, folder, "完全无关的高分作", tags=["战斗"], rating=5)
            db.add(folder)
            db.commit()

            result = recommendations.recommend_manga(
                db=db,
                query="想看治愈系",
                limit=5,
                avoid_tags=[],
                preferred_tags=[],
            )
            order = [item["media"].id for item in result["recommendations"]]
            self.assertEqual(order[0], relevant.id, "tag-relevant manga must come first")
            self.assertNotIn(unrelated_star.id, order,
                             "unrelated high-rated manga must not pad the list")
        finally:
            db.close()

    def test_artist_diversity_cap_limits_repeats(self):
        """Same artist should not flood the result list."""
        db = self.Session()
        try:
            folder = models.Folder(path="D:\\Manga", scan_mode="manga")
            for i in range(5):
                self._make_manga(
                    db, folder, f"治愈短篇{i}", tags=["治愈", "短篇"],
                    artist="同一作者", rating=5,
                )
            self._make_manga(db, folder, "另一作者的治愈作", tags=["治愈"], artist="别的作者", rating=3)
            db.add(folder)
            db.commit()

            result = recommendations.recommend_manga(
                db=db,
                query="想看治愈短篇",
                limit=8,
                avoid_tags=[],
                preferred_tags=[],
            )
            artists = [item["media"].artist for item in result["recommendations"]]
            self.assertLessEqual(artists.count("同一作者"), recommendations.ARTIST_DIVERSITY_CAP)
            self.assertIn("别的作者", artists)
        finally:
            db.close()

    def test_avoid_terms_do_not_match_on_summary_text(self):
        """Old bug: avoid='黑暗' filtered out manga whose AI summary mentioned the word.

        The new logic only consults tag/meta_tag/parody/artist/title, so a summary
        containing the avoid term must NOT cause filtering.
        """
        db = self.Session()
        try:
            folder = models.Folder(path="D:\\Manga", scan_mode="manga")
            media = self._make_manga(db, folder, "明亮温暖的故事", tags=["治愈"], rating=4)
            db.add(folder)
            db.commit()
            # Simulate an AI profile whose summary happens to contain '黑暗'
            profile = models.MangaAIProfile(
                media=media,
                content_summary="画面明亮，与常见的黑暗题材完全相反",
                style_tags="[]", story_tags="[]", tone_tags="[]",
                recommendation_keywords="[]",
            )
            db.add(profile)
            db.commit()

            result = recommendations.recommend_manga(
                db=db,
                query="温暖的故事",
                limit=5,
                avoid_tags=["黑暗"],
                preferred_tags=[],
            )
            ids = [item["media"].id for item in result["recommendations"]]
            self.assertIn(media.id, ids, "avoid term in summary should NOT filter the manga")
        finally:
            db.close()

    def test_viewed_penalty_demotes_already_read(self):
        """Two similar mangas; the unread one should rank above the viewed one."""
        db = self.Session()
        try:
            folder = models.Folder(path="D:\\Manga", scan_mode="manga")
            fresh = self._make_manga(db, folder, "没看过的治愈短篇", tags=["治愈", "短篇"], rating=3)
            seen = self._make_manga(
                db, folder, "已读过的治愈短篇", tags=["治愈", "短篇"],
                rating=3, view_status="viewed",
            )
            db.add(folder)
            db.commit()

            result = recommendations.recommend_manga(
                db=db,
                query="治愈短篇",
                limit=5,
                avoid_tags=[],
                preferred_tags=[],
            )
            order = [item["media"].id for item in result["recommendations"]]
            self.assertLess(order.index(fresh.id), order.index(seen.id))
        finally:
            db.close()

    def test_empty_result_when_no_field_matches_query(self):
        """Query tokens hit no field → return [] + an explanatory message.

        Old bug: 'I want mignon's works' would return noise because the system
        fell back to high-rated manga even though nothing matched the actual ask.
        """
        db = self.Session()
        try:
            folder = models.Folder(path="D:\\Manga", scan_mode="manga")
            self._make_manga(db, folder, "完全不相关的标题", tags=["短篇"], rating=5)
            db.add(folder)
            db.commit()

            result = recommendations.recommend_manga(
                db=db,
                query="想看作者zzzzzzz9999的作品",
                limit=5,
                avoid_tags=[],
                preferred_tags=["zzzzzzz9999"],  # force a positive_term that won't match
            )
            self.assertEqual(result["recommendations"], [])
            self.assertIsNotNone(result["message"])
            self.assertIn("zzzzzzz9999", result["message"])
        finally:
            db.close()

    def test_browse_mode_when_no_positive_terms(self):
        """No positive tokens at all → fall through to rating-based browse, not empty."""
        db = self.Session()
        try:
            folder = models.Folder(path="D:\\Manga", scan_mode="manga")
            self._make_manga(db, folder, "高评分的作品", rating=5)
            self._make_manga(db, folder, "低评分的作品", rating=1)
            db.add(folder)
            db.commit()

            result = recommendations.recommend_manga(
                db=db,
                query="推荐一些作品",  # all stopwords after tokenize → no positive tokens
                limit=5,
                avoid_tags=[],
                preferred_tags=[],
            )
            self.assertGreater(len(result["recommendations"]), 0,
                               "browse mode must still return something")
        finally:
            db.close()

    def test_tokenize_handles_chinese_and_latin(self):
        toks = set(manga_search.tokenize("治愈系 short story 黑暗"))
        # CJK fallback path produces both unigrams and bigrams; jieba path produces words.
        # Either way, the substantive words should be present somehow.
        self.assertTrue(any("治愈" in t for t in toks) or "治" in toks)
        self.assertIn("short", toks)
        self.assertIn("story", toks)
        self.assertTrue(any("黑暗" in t for t in toks) or "黑" in toks)
        # 1-char Latin and stopwords are dropped
        self.assertNotIn("a", toks)
        self.assertNotIn("想看", toks)

    # ------------------------------------------------------------------
    # Phase 2 — vector retrieval + RRF fusion
    # ------------------------------------------------------------------

    def test_serialize_deserialize_vec_roundtrip(self):
        original = np.array([0.1, -0.2, 0.3, 0.4], dtype=np.float32)
        blob = manga_vector.serialize_vec(original)
        # Each float32 is 4 bytes.
        self.assertEqual(len(blob), 4 * len(original))
        restored = manga_vector.deserialize_vec(blob, dim=len(original))
        self.assertTrue(np.array_equal(restored, original))

    def test_deserialize_vec_rejects_wrong_dim(self):
        blob = manga_vector.serialize_vec(np.array([0.1, 0.2, 0.3], dtype=np.float32))
        # Asking for the wrong dimension must return None, not silently
        # reinterpret the bytes.
        self.assertIsNone(manga_vector.deserialize_vec(blob, dim=384))

    def test_rank_by_query_returns_cosine_order(self):
        query = np.array([1.0, 0.0, 0.0], dtype=np.float32)
        candidates = [
            (10, np.array([0.9, 0.1, 0.0], dtype=np.float32)),  # most similar
            (20, np.array([0.0, 1.0, 0.0], dtype=np.float32)),  # orthogonal
            (30, np.array([0.7, 0.3, 0.0], dtype=np.float32)),  # second-most similar
        ]
        ranked = manga_vector.rank_by_query(query, candidates, top_k=3)
        order = [mid for mid, _sim in ranked]
        self.assertEqual(order, [10, 30, 20])

    def test_compose_doc_text_pulls_title_artist_tags(self):
        db = self.Session()
        try:
            folder = models.Folder(path="D:\\Manga", scan_mode="manga")
            media = self._make_manga(
                db, folder, "测试漫画标题", tags=["短篇", "治愈"], artist="测试作者",
            )
            db.add(folder)
            db.commit()
            text = manga_vector.compose_doc_text(media)
            self.assertIn("测试漫画标题", text)
            self.assertIn("测试作者", text)
            self.assertIn("短篇", text)
            self.assertIn("治愈", text)
        finally:
            db.close()

    def test_vector_boosts_when_bm25_has_at_least_one_match(self):
        """Vector augments BM25, it doesn't replace it.

        Design: vector retrieval is gated on BM25 having at least one
        match somewhere in the library. The rationale (see _vector_ranks
        docstring) is that adding cosine-nearest neighbours when BM25
        finds *nothing* surfaces noise rather than rescue — see the
        mignon-not-in-library case. So this test:

        1. Library has *two* manga. One weakly matches BM25 on "温馨",
           anchoring the query as "something exists for this".
        2. A second manga shares no tokens with the query but a
           hand-crafted embedding makes it cosine-close.
        3. Both should appear in the result — BM25 picks up the first,
           vector picks up the second.
        """
        db = self.Session()
        try:
            folder = models.Folder(path="D:\\Manga", scan_mode="manga")
            # Anchor: title contains '温馨' so BM25 finds it on the query token.
            anchor = self._make_manga(db, folder, "温馨日常的小故事", rating=2)
            # Target: no shared tokens with query — only embedding makes it close.
            target = self._make_manga(db, folder, "标题完全无关", tags=["治愈"], rating=3)
            db.add(folder)
            db.commit()

            dim = manga_vector.VECTOR_DIM
            target_vec = np.zeros(dim, dtype=np.float32)
            target_vec[0] = 1.0
            profile = models.MangaAIProfile(
                media=target,
                embedding=manga_vector.serialize_vec(target_vec),
                embedding_model=manga_vector.MODEL_NAME,
                style_tags="[]", story_tags="[]", tone_tags="[]",
                recommendation_keywords="[]",
            )
            db.add(profile)
            db.commit()

            original_encode = manga_vector.encode_query
            manga_vector.encode_query = lambda q: target_vec.copy()
            try:
                result = recommendations.recommend_manga(
                    db=db, query="温馨日常",
                    limit=5, avoid_tags=[], preferred_tags=["温馨"],
                )
            finally:
                manga_vector.encode_query = original_encode

            ids = [item["media"].id for item in result["recommendations"]]
            self.assertIn(anchor.id, ids, "BM25 anchor must appear")
            self.assertIn(target.id, ids,
                          "vector should boost the target once BM25 has any match")
        finally:
            db.close()

    def test_vector_does_not_rescue_when_bm25_misses_completely(self):
        """When BM25 finds nothing, vector does NOT add candidates.

        This is the mignon-not-in-library guarantee: no real keyword match
        means an honest empty result, not a list of cosine-noise neighbours.
        """
        db = self.Session()
        try:
            folder = models.Folder(path="D:\\Manga", scan_mode="manga")
            # No manga shares any token with "noexistword" — BM25 finds zero.
            target = self._make_manga(db, folder, "完全不相关的标题", tags=["短篇"], rating=5)
            db.add(folder)
            db.commit()

            # Even with a hand-crafted embedding that would otherwise boost it,
            # vector retrieval must be skipped because BM25 has no match.
            dim = manga_vector.VECTOR_DIM
            target_vec = np.zeros(dim, dtype=np.float32)
            target_vec[0] = 1.0
            profile = models.MangaAIProfile(
                media=target,
                embedding=manga_vector.serialize_vec(target_vec),
                embedding_model=manga_vector.MODEL_NAME,
                style_tags="[]", story_tags="[]", tone_tags="[]",
                recommendation_keywords="[]",
            )
            db.add(profile)
            db.commit()

            original_encode = manga_vector.encode_query
            manga_vector.encode_query = lambda q: target_vec.copy()
            try:
                result = recommendations.recommend_manga(
                    db=db, query="noexistword",
                    limit=5, avoid_tags=[], preferred_tags=["noexistword"],
                )
            finally:
                manga_vector.encode_query = original_encode

            self.assertEqual(result["recommendations"], [],
                             "no BM25 match must yield empty, not vector noise")
            self.assertIsNotNone(result["message"])
        finally:
            db.close()

    def test_vector_rank_does_not_override_avoid_filter(self):
        """Even if the embedding makes a manga semantically close, an avoid_tag
        match in tags/title must still filter it out before vector pass.
        """
        db = self.Session()
        try:
            folder = models.Folder(path="D:\\Manga", scan_mode="manga")
            blocked = self._make_manga(
                db, folder, "黑暗系恐怖故事", tags=["黑暗"], rating=4,
            )
            db.add(folder)
            db.commit()

            dim = manga_vector.VECTOR_DIM
            target_vec = np.zeros(dim, dtype=np.float32)
            target_vec[0] = 1.0
            profile = models.MangaAIProfile(
                media=blocked,
                embedding=manga_vector.serialize_vec(target_vec),
                embedding_model=manga_vector.MODEL_NAME,
                style_tags="[]", story_tags="[]", tone_tags="[]",
                recommendation_keywords="[]",
            )
            db.add(profile)
            db.commit()

            original_encode = manga_vector.encode_query
            manga_vector.encode_query = lambda q: target_vec.copy()
            try:
                result = recommendations.recommend_manga(
                    db=db, query="想看类似的", limit=5,
                    avoid_tags=["黑暗"], preferred_tags=["类似"],
                )
            finally:
                manga_vector.encode_query = original_encode

            ids = [item["media"].id for item in result["recommendations"]]
            self.assertNotIn(blocked.id, ids)
        finally:
            db.close()

    def test_manga_metadata_profile_parses_title_and_source_item(self):
        db = self.Session()
        try:
            folder = models.Folder(path="D:\\Manga", scan_mode="manga")
            source = models.ExternalFavoriteSource(source_type="wnacg", name="WNACG", favorites_url="https://example.test")
            item = models.ExternalFavoriteItem(
                source=source,
                source_type="wnacg",
                external_id="123",
                title="[Circle (Artist)] Clean Title (Original) [中文]",
                url="https://www.wnacg.com/photos-index-aid-123.html",
                category_name="Favorites",
            )
            media = models.Media(
                folder=folder,
                title="[Circle (Artist)] Clean Title (Original) [中文]",
                relative_path="book",
                absolute_path="D:\\Manga\\book",
                media_type="manga",
                extension=".dir",
                file_size=1024,
                page_count=28,
                source_url=item.url,
                source_site="wnacg",
            )
            db.add_all([folder, source])
            db.commit()

            profile = manga_metadata.build_metadata_profile(db, media)
            db.commit()
            tags = manga_metadata.json_loads(profile.external_tags, [])

            self.assertEqual(profile.media_id, media.id)
            self.assertEqual(profile.language, "中文")
            self.assertIn("length:短篇", tags)
            self.assertTrue(profile.source_matches)
            self.assertGreaterEqual(profile.confidence, 60)
        finally:
            db.close()


if __name__ == "__main__":
    unittest.main()
