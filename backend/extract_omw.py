"""Extract translation pairs (English -> target) from Open Multilingual WordNet
into seed_cards.sql. Cross-language mapping goes through the ILI; the card theme
comes from the English synset's lexfile. Runs locally.

Deps: pip install wn wordfreq
"""

import wn
from wordfreq import zipf_frequency

# English hub: source words, ILI and themes (lexfile).
# Use 'omw-en' (PWN 3.0), NOT 'oewn' whose lexfile() is None.
EN_LEXICON = "omw-en"
SOURCE_LANG = "eng"

# Installs all ~30 OMW wordnets at once.
DOWNLOADS = ["omw:1.4"]

# DB code -> wn lexicon id (wn 1.1.x uses no ':1.4' suffix).
TARGET_LEXICONS = {
    "spa": "omw-es",
    "fra": "omw-fr",
    "por": "omw-pt",
    "nld": "omw-nl",
    "arb": "omw-arb",
}

# Keep only reasonably common English words (Zipf ~1 rare .. ~8 very common).
# 3.0 drops obscure terms like "Animalia" (1.71) but keeps "apple" (4.76).
MIN_ZIPF = 3.0

WF_LANG = {"eng": "en", "spa": "es", "fra": "fr", "por": "pt", "nld": "nl", "arb": "ar"}

THEME_MAP = {
    "noun.food":       "food",
    "noun.animal":     "animals",
    "noun.plant":      "plants",
    "noun.body":       "body",
    "noun.artifact":   "objects",
    "noun.location":   "places",
    "noun.person":     "people",
    "noun.substance":  "materials",
    "noun.time":       "time",
    "noun.possession": "money",
    "noun.feeling":    "emotions",
}

OUTPUT_FILE = "seed_cards.sql"


def single_words(forms):
    out = []
    for w in forms:
        w = (w or "").strip()
        if w and " " not in w and w.isalpha():
            out.append(w)
    return out


def zipf_safe(word, wf_lang):
    # None when the language has no tokenizer (e.g. ja without MeCab).
    try:
        return zipf_frequency(word, wf_lang)
    except Exception:
        return None


def best_source(forms):
    best, best_z = None, -1.0
    for w in single_words(forms):
        z = zipf_safe(w, "en") or 0.0
        if z > best_z:
            best, best_z = w, z
    return best, best_z


def best_target(forms, wf_lang):
    cands = single_words(forms)
    if not cands:
        return None
    ranked = sorted(((zipf_safe(w, wf_lang) or -1.0), w) for w in cands)
    return ranked[-1][1]


def main():
    for spec in DOWNLOADS:
        try:
            wn.download(spec)
        except Exception as e:
            print(f"(info) '{spec}' already present: {e}")

    en = wn.Wordnet(EN_LEXICON)
    seen = set()
    rows = []

    for es in en.synsets(pos="n"):
        theme = THEME_MAP.get(es.lexfile())
        if theme is None:
            continue

        src, src_z = best_source(es.lemmas())
        if not src or src_z < MIN_ZIPF:
            continue

        for lang, lex_id in TARGET_LEXICONS.items():
            cand = []
            for t in es.translate(lexicon=lex_id):
                cand += t.lemmas()
            tgt = best_target(cand, WF_LANG.get(lang, lang))
            if not tgt:
                continue

            key = (src.lower(), lang, tgt.lower())
            if key in seen:
                continue
            seen.add(key)
            rows.append((src, lang, tgt, theme))

    with open(OUTPUT_FILE, "w", encoding="utf-8") as f:
        for src, lang, tgt, theme in rows:
            s = src.replace("'", "''")
            t = tgt.replace("'", "''")
            f.write(
                "INSERT INTO cards "
                "(owner_id, source_lang, target_lang, word_source, word_target, theme, origin) "
                f"VALUES (NULL, '{SOURCE_LANG}', '{lang}', '{s}', '{t}', '{theme}', 'seed');\n"
            )

    from collections import Counter
    per_lang = Counter(lang for _, lang, _, _ in rows)
    print(f"{len(rows)} cards -> {OUTPUT_FILE}")
    for lang, n in per_lang.most_common():
        print(f"  {lang}: {n}")


if __name__ == "__main__":
    main()
