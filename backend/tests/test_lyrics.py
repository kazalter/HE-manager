import unittest

from app import lyrics


class LrcParseTest(unittest.TestCase):
    def test_basic_and_frac_width(self):
        text = "[ti:song]\n[00:01.00]hello\n[00:03.5]half\n[01:02.250]later"
        out = lyrics.parse_lyrics(text, "lrc")
        self.assertEqual(out, [
            {"t": 1.0, "text": "hello"},
            {"t": 3.5, "text": "half"},
            {"t": 62.25, "text": "later"},
        ])

    def test_multi_timestamp_line_expands_and_sorts(self):
        out = lyrics.parse_lyrics("[00:10.00][00:02.00]repeat", "lrc")
        self.assertEqual(out, [
            {"t": 2.0, "text": "repeat"},
            {"t": 10.0, "text": "repeat"},
        ])

    def test_offset_shifts_earlier_and_clamps(self):
        # +1000ms => lyrics show 1s earlier; never below 0.
        out = lyrics.parse_lyrics("[offset:+1000]\n[00:00.50]a\n[00:05.00]b", "lrc")
        self.assertEqual(out, [{"t": 0.0, "text": "a"}, {"t": 4.0, "text": "b"}])

    def test_enhanced_word_stamps_stripped_blank_dropped(self):
        out = lyrics.parse_lyrics("[00:01.00]<00:01.00>wo<00:01.50>rd\n[00:02.00]", "lrc")
        self.assertEqual(out, [{"t": 1.0, "text": "word"}])


class VttSrtParseTest(unittest.TestCase):
    def test_vtt_header_cuenum_and_multiline_join(self):
        text = (
            "WEBVTT\n\n"
            "1\n00:00:01.000 --> 00:00:04.000\nline one\nline two\n\n"
            "2\n00:01:00.500 --> 00:01:03.000\nlater\n"
        )
        out = lyrics.parse_lyrics(text, "vtt")
        self.assertEqual(out, [
            {"t": 1.0, "text": "line one line two"},
            {"t": 60.5, "text": "later"},
        ])

    def test_srt_comma_decimal_and_hours(self):
        text = "1\n01:00:02,500 --> 01:00:05,000\nhi\n\n2\n01:00:06,000 --> 01:00:08,000\nbye\n"
        out = lyrics.parse_lyrics(text, ".srt")
        self.assertEqual(out, [
            {"t": 3602.5, "text": "hi"},
            {"t": 3606.0, "text": "bye"},
        ])

    def test_empty_and_unknown(self):
        self.assertEqual(lyrics.parse_lyrics("", "lrc"), [])
        self.assertEqual(lyrics.parse_lyrics("whatever", "txt"), [])
        self.assertEqual(lyrics.parse_lyrics("no timestamps here", "lrc"), [])


if __name__ == "__main__":
    unittest.main()
