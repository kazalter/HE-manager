"""Parse the artist/circle from a doujinshi-style manga title.

Convention (wnacg / EH style), author lives at the front of the title:

    (Event) [Circle (Artist)] Title (Magazine) [scanlation] [tags]
    [Artist] Title ...
    [無修正][Artist] Title ...          # leading meta-tag brackets come first

We strip leading "(event)" groups, skip leading meta-tag brackets, then take
the first real "[...]" bracket. When it is "Circle (Artist)" the *parenthesised
artist* is the canonical name and the circle / boilerplate words (老師,
制作委員会, studio, …) are dropped — that is the "take the intersection" the
user asked for: works tagged "[鳥居座 (鳥居ヨシツナ)]" and "[鳥居ヨシツナ]"
both canonicalise to "鳥居ヨシツナ" and merge. A title with no leading bracket
(a magazine anthology like "コミックメガストア Vol.22") has no artist and
returns None, mirroring how X media without an author is skipped.

This is heuristic by nature; cross-script aliases (e.g. romaji "Torii
Yoshitsuna" vs "鳥居ヨシツナ") are deliberately NOT auto-merged — that is left
to manual correction.
"""
from __future__ import annotations

import re
from typing import Optional

# Bracket contents that are language / status / scanlation markers, never an
# author. Compared case-insensitively after stripping spaces. Kept small: only
# tokens that realistically show up as the *leading* bracket.
_META_TAGS = {
    "無修正", "無修", "無修正版", "無碼", "无修正", "无码",
    "dl版", "dl", "中国翻訳", "中國翻訳", "中国翻译", "翻訳",
    "中文", "中国語", "中国语", "中国", "chinese", "english",
    "decensored", "digital", "個人漢化", "漢化", "汉化",
    # Archive / compilation labels that sit where the author bracket would be.
    "汉化汇总", "汉化合集", "漢化彙總", "汉化杂图集",
    "pixiv fanbox", "fanbox", "pixiv",
}

# Date-style archive prefixes like "[2022.08]" / "[2024-05-24]" — never authors.
_DATE_BRACKET = re.compile(r"^\d{4}[.\-_/]\d{1,2}([.\-_/]\d{1,2})?$")

_EVENT_PREFIX = re.compile(r"^\s*[（(][^（）()]*[）)]\s*")
_LEADING_BRACKET = re.compile(r"^\s*[［\[]([^［\]\[］]+)[］\]]\s*")
# "Circle (Artist)" — the artist is the trailing parenthesised group.
_CIRCLE_ARTIST = re.compile(r"^(?P<circle>.+?)\s*[（(](?P<artist>[^（）()]+)[）)]\s*$")


def parse_artist(title: Optional[str]) -> Optional[str]:
    """Return the canonical artist name for a manga title, or None."""
    if not title:
        return None
    s = title.strip()

    # 1. Drop leading "(event)" groups: (C105), (COMIC1☆15), (例大祭) ...
    while True:
        stripped = _EVENT_PREFIX.sub("", s)
        if stripped == s:
            break
        s = stripped

    # 2. Skip leading meta-tag brackets ([無修正], [中国翻訳], …), then take the
    #    first remaining bracket as the author/circle bracket.
    inner: Optional[str] = None
    while True:
        m = _LEADING_BRACKET.match(s)
        if not m:
            break
        content = m.group(1).strip()
        if content.lower() in _META_TAGS or _DATE_BRACKET.match(content):
            s = s[m.end():]
            continue
        inner = content
        break

    if not inner:
        return None

    # 3. "Circle (Artist)" -> parenthesised artist is canonical; otherwise the
    #    bracket content itself is the artist.
    cm = _CIRCLE_ARTIST.match(inner)
    name = (cm.group("artist") if cm else inner).strip()
    return name or None
