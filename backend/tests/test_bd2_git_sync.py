"""BD2 git-based sync — unit tests for the parse/spawn/proxy helpers.

These tests exercise the parts that are independent of the running backend
process: the CharInfo parser, the sparse-checkout path builder, the proxy
URL/config writer, and the progress-line regex.  The live git clone/pull
flow itself is covered by the existing manual smoke test in he.ps1 — running
real `git clone` in a test would either need network access or expensive
mocking, both of which are out of scope for this unit suite.
"""

import os
import subprocess
import tempfile
import unittest

from app import main as main_mod


SAMPLE_CHARINFO = """
[
  {
    "charName": "Anya",
    "costumes": [
      {"spine": "char1234", "cutscene": "cutscene_99", "censored_spine": "char1234_c"},
      {"spine": "char1234_s2", "cutscene": "cutscene_99_b"}
    ],
    "prestigeSkin": {"spine": "char1234_p"},
    "guest": {"interact": "illust_anya_guest"}
  },
  {
    "charName": "Lathel",
    "costumes": [{"spine": "char9999"}]
  }
]
"""


# Malformed object: missing comma between censored_spine and cutscene.
# Mirrors the typo the upstream repo carries, and that the regex patches.
MALFORMED_CHARINFO = """
[
  {
    "charName": "Anya",
    "costumes": [
      {
        "spine": "char1234",
        "censored_spine": "char1234_c"
        "cutscene": "cutscene_99"
      }
    ]
  }
]
"""


class Bd2CharInfoParseTest(unittest.TestCase):
    def test_female_spine_dirs_extracts_all_spine_kinds(self):
        dirs = main_mod._bd2_female_spine_dirs(SAMPLE_CHARINFO)
        self.assertEqual(
            set(dirs),
            {
                "char1234",
                "char1234_c",
                "cutscene_99",
                "char1234_s2",
                "cutscene_99_b",
                "char1234_p",
                "illust_anya_guest",
            },
        )

    def test_male_characters_filtered_out(self):
        # Lathel is in _BD2_MALE_CHARACTERS — his char9999 spine must drop.
        self.assertNotIn("char9999", main_mod._bd2_female_spine_dirs(SAMPLE_CHARINFO))

    def test_malformed_json_is_patched(self):
        # Without the regex fix this would raise json.JSONDecodeError.
        dirs = main_mod._bd2_female_spine_dirs(MALFORMED_CHARINFO)
        # The spine/cutscene/censored_spine iteration order in the parser
        # is fixed (spine → cutscene → censored_spine) so the append order
        # is deterministic.
        self.assertEqual(dirs, ["char1234", "cutscene_99", "char1234_c"])


class Bd2SparsePathsTest(unittest.TestCase):
    def test_paths_grouped_by_top_level_dir(self):
        # _bd2_sparse_paths returns the *full* path list (one per
        # directory or top-level file).  The cone-mode rewrite into
        # .git/info/sparse-checkout is a separate concern, exercised by
        # the integration smoke test rather than this unit test.
        paths = main_mod._bd2_sparse_paths(
            ["char1234", "cutscene_99", "illust_anya_guest", "char5678"]
        )
        self.assertIn("CharInfo(Dropped).json", paths)
        self.assertIn("spine/char/char1234", paths)
        self.assertIn("spine/char/char5678", paths)
        self.assertIn("spine/cutscenes/cutscene_99", paths)
        self.assertIn("spine/illust/illust_anya_guest", paths)
        self.assertEqual(len(paths), 5)


class Bd2AtlasAliasTest(unittest.TestCase):
    def test_non_ascii_region_gets_spine41_signed_byte_alias(self):
        atlas = """char000206.png
size:2048,2048
format:RGBA8888
other_수위_nostick
bounds:429,2,233,216
rotate:90
shoulder(R)
bounds:218,852,69,76
"""
        patched = main_mod._bd2_atlas_with_spine41_aliases(atlas)
        self.assertIn("other_수위_nostick\nbounds:429,2,233,216", patched)
        self.assertIn("other_￬ﾈﾘ￬ﾜﾄ_nostick\nbounds:429,2,233,216", patched)
        self.assertEqual(patched.count("shoulder(R)"), 1)

    def test_ascii_regions_are_left_alone(self):
        atlas = """char000001.png
size:128,128
body
bounds:0,0,64,64
"""
        patched = main_mod._bd2_atlas_with_spine41_aliases(atlas)
        self.assertEqual(patched, atlas)


class Bd2ProgressRegexTest(unittest.TestCase):
    def test_receiving_objects_line(self):
        main_mod._BD2_DOWNLOAD_STATE.clear()
        main_mod._bd2_apply_progress_line(
            "Receiving objects:  45% (1234/2700), 5.00 MiB | 3.50 MiB/s"
        )
        st = main_mod._BD2_DOWNLOAD_STATE
        self.assertEqual(st["pct"], 45)
        self.assertEqual(st["objects_done"], 1234)
        self.assertEqual(st["objects_total"], 2700)
        self.assertAlmostEqual(st["mb"], 5.0, places=1)
        self.assertAlmostEqual(st["speed_mb_s"], 3.5, places=2)
        self.assertEqual(st["phase"], "Receiving objects")

    def test_resolving_deltas_no_throughput(self):
        main_mod._BD2_DOWNLOAD_STATE.clear()
        main_mod._bd2_apply_progress_line("Resolving deltas:   12% (200/1700)")
        st = main_mod._BD2_DOWNLOAD_STATE
        self.assertEqual(st["pct"], 12)
        # No MiB reported on this line — speed/mb shouldn't be set.
        self.assertNotIn("mb", st)
        self.assertNotIn("speed_mb_s", st)

    def test_unrelated_line_is_silently_ignored(self):
        main_mod._BD2_DOWNLOAD_STATE.clear()
        main_mod._bd2_apply_progress_line("Cloning into 'foo'...")
        self.assertEqual(main_mod._BD2_DOWNLOAD_STATE, {})


class Bd2ProxyConfigTest(unittest.TestCase):
    def test_proxy_written_to_local_config(self):
        with tempfile.TemporaryDirectory() as tmp:
            subprocess.run(["git", "init", "--bare"], cwd=tmp, check=True, capture_output=True)
            # Lock env for the duration so we don't race other tests.
            old_host = os.environ.get("HE_BD2_PROXY_HOST", "127.0.0.1")
            old_port = os.environ.get("HE_BD2_PROXY_PORT", "7897")
            os.environ["HE_BD2_PROXY_HOST"] = "127.0.0.1"
            os.environ["HE_BD2_PROXY_PORT"] = "7897"
            try:
                main_mod._bd2_apply_git_proxy(tmp)
                cfg = subprocess.run(
                    ["git", "config", "--local", "--list"],
                    cwd=tmp,
                    capture_output=True,
                    text=True,
                    check=True,
                ).stdout
            finally:
                os.environ["HE_BD2_PROXY_HOST"] = old_host
                os.environ["HE_BD2_PROXY_PORT"] = old_port
        # The proxy host/port must appear for both http and https.
        self.assertIn("127.0.0.1:7897", cfg)
        self.assertIn("http.proxy", cfg)
        self.assertIn("https.proxy", cfg)

    def test_proxy_url_helper_reads_env_each_call(self):
        old_host = os.environ.get("HE_BD2_PROXY_HOST", "127.0.0.1")
        old_port = os.environ.get("HE_BD2_PROXY_PORT", "7897")
        os.environ["HE_BD2_PROXY_HOST"] = "10.0.0.1"
        os.environ["HE_BD2_PROXY_PORT"] = "1080"
        try:
            self.assertEqual(
                main_mod._bd2_proxy_url("socks5h"),
                "socks5h://10.0.0.1:1080",
            )
        finally:
            os.environ["HE_BD2_PROXY_HOST"] = old_host
            os.environ["HE_BD2_PROXY_PORT"] = old_port


class Bd2ClearLocksTest(unittest.TestCase):
    def test_stale_locks_removed(self):
        with tempfile.TemporaryDirectory() as tmp:
            git_dir = os.path.join(tmp, ".git")
            os.makedirs(git_dir)
            for name in ("index.lock", "HEAD.lock", "packed-refs.lock"):
                open(os.path.join(git_dir, name), "w").close()
            main_mod._bd2_clear_git_locks(tmp)
            remaining = sorted(os.listdir(git_dir))
            self.assertEqual(remaining, [])

    def test_noop_when_no_git_dir(self):
        with tempfile.TemporaryDirectory() as tmp:
            # Should not raise even when there's no .git.
            main_mod._bd2_clear_git_locks(tmp)


if __name__ == "__main__":
    unittest.main()
