import unittest

from app import asmr_source


class AsmrSourceParseTest(unittest.TestCase):
    def test_parse_marked_works_basic(self):
        payload = {
            "works": [
                {
                    "id": 12345,
                    "source_id": "RJ012345",
                    "title": "Sample ASMR Work",
                    "mainCoverUrl": "https://cdn.example.test/RJ012345/main.jpg",
                    "circle": {"name": "Test Circle"},
                    "vas": [{"id": "1", "name": "CV A"}, {"name": "CV B"}],
                },
                {
                    # No source_id -> RJ falls back to numeric id; circle from `name`.
                    "id": 678,
                    "title": "No RJ Work",
                    "thumbnailCoverUrl": "https://cdn.example.test/678/thumb.jpg",
                    "name": "Fallback Circle",
                },
            ],
            "pagination": {"totalCount": 2, "pageSize": 96, "currentPage": 1},
        }

        items = asmr_source.parse_marked_works(payload)
        self.assertEqual(len(items), 2)

        first = items[0]
        self.assertEqual(first.external_id, "RJ012345")
        self.assertEqual(first.title, "Sample ASMR Work")
        self.assertEqual(first.url, "https://www.asmr.one/work/RJ012345")
        self.assertEqual(first.cover_url, "https://cdn.example.test/RJ012345/main.jpg")
        self.assertEqual(first.category_name, "Test Circle")
        self.assertEqual(first.va_names, ["CV A", "CV B"])

        second = items[1]
        self.assertEqual(second.external_id, "RJ678")
        self.assertEqual(second.category_name, "Fallback Circle")
        self.assertEqual(second.cover_url, "https://cdn.example.test/678/thumb.jpg")
        self.assertEqual(second.va_names, [])  # no `vas` -> empty, never None

    def test_va_names_dedupes_skips_blank_and_non_dict(self):
        payload = {"works": [{
            "id": 1, "source_id": "RJ1", "title": "t",
            "vas": [
                {"name": "CV A"},
                {"name": "  CV A  "},   # same name, whitespace -> deduped
                {"name": ""},           # blank -> skipped
                "not-a-dict",           # wrong type -> skipped
                {"name": "CV C"},
            ],
        }]}
        items = asmr_source.parse_marked_works(payload)
        self.assertEqual(items[0].va_names, ["CV A", "CV C"])

    def test_parse_marked_works_data_key_and_skips_invalid(self):
        # Some responses use `data` instead of `works`; entries without any id
        # are skipped rather than crashing the whole sync.
        payload = {"data": [{"title": "ghost, no id"}, {"id": 9, "source_id": "RJ9", "title": "ok"}]}
        items = asmr_source.parse_marked_works(payload)
        self.assertEqual([i.external_id for i in items], ["RJ9"])

    def test_candidate_bases_dedupes_and_normalizes(self):
        bases = asmr_source.candidate_bases("api.asmr-200.com/")
        self.assertEqual(bases[0], "https://api.asmr-200.com")
        # No duplicates even though the preferred base is also a built-in mirror.
        self.assertEqual(len(bases), len(set(bases)))
        for mirror in asmr_source.API_MIRRORS:
            self.assertIn(asmr_source.normalize_api_base(mirror), bases)

    def test_parse_mirrors_splits_normalizes_dedupes(self):
        raw = "api.asmr-200.com/\nhttps://api.asmr.one , api.asmr-200.com\n\n  "
        self.assertEqual(
            asmr_source.parse_mirrors(raw),
            ["https://api.asmr-200.com", "https://api.asmr.one"],
        )
        self.assertEqual(asmr_source.parse_mirrors(""), [])
        self.assertEqual(asmr_source.parse_mirrors(None), [])

    def test_candidate_bases_uses_custom_pool_over_builtin(self):
        bases = asmr_source.candidate_bases(
            "api.asmr.one", ["https://m1.test", "https://m2.test"]
        )
        self.assertEqual(
            bases, ["https://api.asmr.one", "https://m1.test", "https://m2.test"]
        )
        # No custom pool -> built-in mirrors are still the fallback.
        for mirror in asmr_source.API_MIRRORS:
            self.assertIn(
                asmr_source.normalize_api_base(mirror),
                asmr_source.candidate_bases("api.asmr.one"),
            )

    def test_extract_playlist_id_from_url_and_raw(self):
        url = "https://asmr.one/playlist?id=eba7cfd1-ce38-4c63-bb0a-f80d29baf3b5"
        self.assertEqual(
            asmr_source.extract_playlist_id(url),
            "eba7cfd1-ce38-4c63-bb0a-f80d29baf3b5",
        )
        self.assertEqual(
            asmr_source.extract_playlist_id("eba7cfd1-ce38-4c63-bb0a-f80d29baf3b5"),
            "eba7cfd1-ce38-4c63-bb0a-f80d29baf3b5",
        )
        self.assertEqual(asmr_source.extract_playlist_id(""), "")

    def test_parse_unwraps_playlist_work_wrapper(self):
        # Playlist endpoint sometimes nests the work under a "work" key.
        payload = {"works": [{"work": {"id": 5, "source_id": "RJ5", "title": "wrapped"}}]}
        items = asmr_source.parse_marked_works(payload)
        self.assertEqual([i.external_id for i in items], ["RJ5"])
        self.assertEqual(items[0].title, "wrapped")

    def test_api_error_has_friendly_playlist_hint(self):
        # The mapping the network layer uses to turn the raw API error code
        # into a message the user can act on.
        self.assertIn("本人", asmr_source._API_ERROR_HINTS["playlist.playlistNotFound"])
        err = asmr_source.AsmrApiError(404, "playlist.playlistNotFound", "x")
        self.assertEqual(err.status, 404)
        self.assertEqual(err.api_code, "playlist.playlistNotFound")

    def test_flatten_tracks_audio_only_keeps_folder_nesting(self):
        tree = [
            {"type": "folder", "title": "mp3", "children": [
                {"type": "audio", "title": "1.a.mp3", "mediaDownloadUrl": "http://x/1", "size": 10, "duration": 5.0},
                {"type": "image", "title": "cover.jpg", "mediaDownloadUrl": "http://x/c"},
            ]},
            {"type": "audio", "title": "2.b.wav", "mediaDownloadUrl": "http://x/2", "duration": 3},
            {"type": "audio", "title": "no-url"},  # dropped: no download url
        ]
        tracks = asmr_source.flatten_tracks(tree, kinds=("audio",))
        self.assertEqual([(t.title, t.rel_dir, t.kind) for t in tracks],
                         [("1.a.mp3", "mp3", "audio"), ("2.b.wav", "", "audio")])
        self.assertEqual(tracks[0].duration, 5.0)
        self.assertEqual(tracks[1].duration, 3.0)

    def test_flatten_tracks_can_include_images(self):
        tree = [{"type": "image", "title": "c.jpg", "mediaDownloadUrl": "http://x/c"}]
        self.assertEqual(len(asmr_source.flatten_tracks(tree, kinds=("audio", "image"))), 1)
        self.assertEqual(len(asmr_source.flatten_tracks(tree, kinds=("audio",))), 0)

    def test_safe_segment_strips_path_chars(self):
        self.assertEqual(asmr_source._safe_segment('a/b:c*?"<>|.mp3'), "a_b_c______.mp3")
        self.assertEqual(asmr_source._safe_segment("   "), "_")

    def test_filter_audio_tracks_modes_and_zero_fallback(self):
        def trk(title):
            return asmr_source.AsmrTrack(title=title, download_url="u", rel_dir="", kind="audio")

        mp3, wav, flac = trk("01.mp3"), trk("01.wav"), trk("02.flac")
        tracks = [mp3, wav, flac]

        self.assertEqual(asmr_source.filter_audio_tracks(tracks, "all"), tracks)
        self.assertEqual(asmr_source.filter_audio_tracks(tracks, None), tracks)
        self.assertEqual(asmr_source.filter_audio_tracks(tracks, "no_wav"), [mp3, flac])
        self.assertEqual(asmr_source.filter_audio_tracks(tracks, "mp3_only"), [mp3])
        # Would filter to zero -> keep original so the work still downloads.
        wav_only = [wav]
        self.assertEqual(asmr_source.filter_audio_tracks(wav_only, "mp3_only"), wav_only)

    def test_rel_dir_se_kind_heuristic(self):
        kind = asmr_source._rel_dir_se_kind
        # token alone or affirmative -> the with-SE mix
        self.assertEqual(kind("SE"), "with")
        self.assertEqual(kind("SE有り"), "with")
        self.assertEqual(kind("効果音あり"), "with")
        self.assertEqual(kind("ノイズ入り"), "with")
        self.assertEqual(kind("環境音"), "with")
        # negation -> the clean variant
        self.assertEqual(kind("SE無し"), "no")
        self.assertEqual(kind("効果音なし"), "no")
        self.assertEqual(kind("SE抜き"), "no")
        self.assertEqual(kind("ノイズなし"), "no")
        self.assertEqual(kind("SE無し/サンプル"), "no")
        # No SE token, or Latin "se" glued inside a word (the [a-z] guard keeps
        # "nose"/"base"/"noSE" from false-matching) -> neutral, kept by both
        # modes. Acceptable: real asmr.one labels are CJK ("SE無し" etc.).
        self.assertEqual(kind(""), "neutral")
        self.assertEqual(kind("noSE/01"), "neutral")
        self.assertEqual(kind("nose"), "neutral")
        self.assertEqual(kind("bonus"), "neutral")
        # Regression (RJ01109928): the circle glues SE onto the format word with
        # no separator. The old `(?![a-z])` guard rejected these (M/W after SE),
        # so se_only fell back to "keep everything". Now they classify.
        self.assertEqual(kind("■03_本篇『含SEMP3』"), "with")
        self.assertEqual(kind("■01_本篇『含SEWAV』"), "with")
        self.assertEqual(kind("■04_本篇『無SEMP3』"), "no")
        self.assertEqual(kind("■02_本篇『無SEWAV』"), "no")
        self.assertEqual(kind("■09_試聽用音聲"), "neutral")
        # Glued SE only matches before a real audio ext, not arbitrary words.
        self.assertEqual(kind("Settings"), "neutral")
        self.assertEqual(kind("session2"), "neutral")

    def test_filter_audio_versions_modes_and_zero_fallback(self):
        def trk(rel_dir):
            return asmr_source.AsmrTrack(title="01.mp3", download_url="u", rel_dir=rel_dir, kind="audio")

        se, clean, plain = trk("SE有り"), trk("SE無し"), trk("")
        tracks = [se, clean, plain]

        self.assertEqual(asmr_source.filter_audio_versions(tracks, "all"), tracks)
        self.assertEqual(asmr_source.filter_audio_versions(tracks, None), tracks)
        # no_se drops the with-SE folder, keeps clean + neutral
        self.assertEqual(asmr_source.filter_audio_versions(tracks, "no_se"), [clean, plain])
        # se_only keeps only the with-SE folder
        self.assertEqual(asmr_source.filter_audio_versions(tracks, "se_only"), [se])
        # No SE split at all -> se_only would be empty -> keep everything.
        no_split = [plain]
        self.assertEqual(asmr_source.filter_audio_versions(no_split, "se_only"), no_split)

    def test_rj01109928_mp3_only_plus_se_only_yields_one_folder(self):
        # End-to-end of the user's first favourite: 6 folders, 51 audio files.
        # mp3_only then se_only must land on exactly the 含SEMP3 folder (10).
        def trk(rel_dir, title):
            return asmr_source.AsmrTrack(
                title=title, download_url="u", rel_dir=rel_dir, kind="audio"
            )

        tracks = []
        for folder, ext, n in [
            ("■03_本篇『含SEMP3』", "mp3", 10),
            ("■01_本篇『含SEWAV』", "wav", 10),
            ("■04_本篇『無SEMP3』", "mp3", 10),
            ("■02_本篇『無SEWAV』", "wav", 10),
            ("■09_試聽用音聲", "mp3", 10),
            ("■10_促銷影片(日文)", "mp4", 1),
        ]:
            tracks += [trk(folder, f"{i:02d}.{ext}") for i in range(1, n + 1)]
        self.assertEqual(len(tracks), 51)

        # Order matches prepare_asmr_download_plan: format filter, then version.
        mp3 = asmr_source.filter_audio_tracks(tracks, "mp3_only")
        self.assertEqual(len(mp3), 30)  # 含SEMP3 + 無SEMP3 + 試聽
        both = asmr_source.filter_audio_versions(mp3, "se_only")
        self.assertEqual(len(both), 10)
        self.assertEqual({t.rel_dir for t in both}, {"■03_本篇『含SEMP3』"})

    def test_collect_subtitle_nodes_nested_and_filtered(self):
        tree = [
            {"type": "folder", "title": "mp3", "children": [
                {"type": "audio", "title": "01 トラック.mp3", "mediaDownloadUrl": "http://x/a"},
                {"type": "text", "title": "01 トラック.lrc", "mediaDownloadUrl": "http://x/l"},
                {"type": "image", "title": "cover.jpg", "mediaDownloadUrl": "http://x/c"},
            ]},
            {"type": "text", "title": "readme.txt", "mediaDownloadUrl": "http://x/t"},  # not a subtitle
            {"title": "root.vtt", "mediaStreamUrl": "http://x/v"},  # type missing, matched by ext
            {"type": "text", "title": "no-url.srt"},  # dropped: no url
        ]
        subs = asmr_source.collect_subtitle_nodes(tree)
        self.assertEqual(
            [(s.rel_dir, s.stem, s.ext, s.download_url) for s in subs],
            [("mp3", "01 トラック", "lrc", "http://x/l"), ("", "root", "vtt", "http://x/v")],
        )

    def test_pagination_total_pages_ceil(self):
        self.assertEqual(
            asmr_source._pagination_total_pages({"pagination": {"totalCount": 97, "pageSize": 96}}, 96),
            2,
        )
        self.assertIsNone(asmr_source._pagination_total_pages({}, 96))


if __name__ == "__main__":
    unittest.main()
