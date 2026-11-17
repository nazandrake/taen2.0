#!/usr/bin/env python3
"""Builds the offline emoji search data: app/src/main/assets/emoji_search/<lang>.txt from Unicode CLDR annotations.

Input: the CLDR JSON files (https://github.com/unicode-org/cldr-json, tag 49.0.0, Unicode License v3), fetched on the
developer machine; the app itself has no network. Output per language, one emoji per line, in the order of the
keyboard's emoji palette: "<emoji><TAB><name><TAB><keyword>|<keyword>|..." (the CLDR name; the CLDR keywords and the
name, lower case).
Only emoji that are in the keyboard's own lists (assets/emoji/*.txt) are written, without skin-tone variants (the
keyboard applies the user's default skin tone), in the form the keyboard uses.

usage: gen-emoji-search.py <cldr-dir> <assets-dir>
  <cldr-dir> holds ann_<lang>.json (cldr-annotations-full/annotations/<lang>/annotations.json) and
  der_<lang>.json (cldr-annotations-derived-full/annotationsDerived/<lang>/annotations.json) for en and bg.
Run it with python3 -I. Output is deterministic.
"""
import json
import os
import re
import sys

LANGS = ["en", "bg"]
CATEGORY_FILES = [
    "SMILEYS_AND_EMOTION", "PEOPLE_AND_BODY", "ANIMALS_AND_NATURE", "FOOD_AND_DRINK", "TRAVEL_AND_PLACES",
    "ACTIVITIES", "OBJECTS", "SYMBOLS", "FLAGS",
]
SKIN_TONES = re.compile("[\U0001F3FB-\U0001F3FF]")
VS16 = "️"


def plain(e):
    """The emoji without variation selectors: the key under which CLDR and the keyboard lists are compared."""
    return e.replace(VS16, "")


def keyboard_emoji(assets):
    """The emoji of the keyboard's palette in palette order, without skin-tone variants. minApi.txt lists newer emoji by API level."""
    out = []
    seen = set()
    names = [f"{c}.txt" for c in CATEGORY_FILES]
    for name in names:
        with open(os.path.join(assets, "emoji", name), encoding="utf8") as f:
            for line in f:
                for token in line.split():
                    if SKIN_TONES.search(token):
                        continue
                    if plain(token) not in seen:
                        seen.add(plain(token))
                        out.append(token)
    # emoji added with newer Android versions are listed in minApi.txt after "<api> " prefixes, one line per API level
    with open(os.path.join(assets, "emoji", "minApi.txt"), encoding="utf8") as f:
        for line in f:
            parts = line.split()
            if not parts or not parts[0].isdigit():
                continue
            for token in parts[1:]:
                if SKIN_TONES.search(token):
                    continue
                if plain(token) not in seen:
                    seen.add(plain(token))
                    out.append(token)
    return out


def load(cldr, lang):
    entries = {}
    for prefix, root in (("ann", "annotations"), ("der", "annotationsDerived")):
        with open(os.path.join(cldr, f"{prefix}_{lang}.json"), encoding="utf8") as f:
            data = json.load(f)[root]["annotations"]
        for emoji, value in data.items():
            entries.setdefault(plain(emoji), value)
    return entries


def keywords(value):
    words = []
    for word in value.get("default", []) + value.get("tts", []):
        word = word.strip().lower()
        if word and word not in words:
            words.append(word)
    return words


def main():
    cldr, assets = sys.argv[1], sys.argv[2]
    emojis = keyboard_emoji(assets)
    outdir = os.path.join(assets, "emoji_search")
    os.makedirs(outdir, exist_ok=True)
    for lang in LANGS:
        entries = load(cldr, lang)
        lines = []
        missing = 0
        for emoji in emojis:
            value = entries.get(plain(emoji))
            if value is None:
                missing += 1
                continue
            words = keywords(value)
            name = (value.get("tts") or [""])[0].strip()
            if "|" in "".join(words) or "\t" in "".join(words + [name]):
                raise SystemExit(f"separator in a keyword of {emoji}")
            lines.append(f"{emoji}\t{name}\t{'|'.join(words)}")
        with open(os.path.join(outdir, f"{lang}.txt"), "w", encoding="utf8", newline="\n") as f:
            f.write("\n".join(lines) + "\n")
        print(f"{lang}: {len(lines)} emoji, {missing} of the keyboard's emoji without a CLDR annotation")


if __name__ == "__main__":
    main()
