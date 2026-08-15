"""
extract_omw.py  (basato sulla libreria moderna `wn`)
----------------------------------------------------
Estrae coppie di traduzione (inglese -> lingua target) da Open Multilingual
WordNet usando la libreria `wn`. La traduzione tra lingue passa automaticamente
dall'ILI (l'indice interlingua), quindi funziona con qualsiasi wordnet allineato.
Il TEMA di ogni carta viene dal lexfile del synset inglese (es. 'noun.food').

Gira in LOCALE sul tuo PC. Produce `seed_cards.sql` da caricare poi su MySQL.

Uso:
    pip install -r requirements.txt
    python extract_omw.py
"""

import wn
from wordfreq import zipf_frequency

# --- Configurazione -------------------------------------------------------
# Hub inglese: fornisce concetti, ILI e temi (lexfile). L'inglese e' anche la
# lingua base mostrata sulla flashcard.
# NB: usiamo 'omw-en' (PWN 3.0) e NON 'oewn': su oewn lexfile() e' None (niente temi).
EN_LEXICON = "omw-en"        # OMW English (espone lexfile per tutti i nomi)
SOURCE_LANG = "eng"          # come lo salviamo nel DB (source_lang delle carte)

# Progetti da scaricare in `wn` (una volta sola). "omw:1.4" installa in blocco
# tutti i wordnet OMW (~30 lingue, arabo incluso), omw-en compreso.
DOWNLOADS = ["omw:1.4"]

# Lingue target: "codice salvato nel DB" -> id del lexicon in `wn` (wn 1.1.x: senza ':1.4').
# Per vedere gli id ESATTI installati sul tuo PC:  python -c "import wn; [print(l.id, l.language) for l in wn.lexicons()]"
# Per aggiungere un wordnet da GitHub (formato WN-LMF): wn.download("<url .xml>") una volta, poi metti qui il suo id.
TARGET_LEXICONS = {
    "spa": "omw-es",
    "fra": "omw-fr",
    "por": "omw-pt",
    "nld": "omw-nl",
    "jpn": "omw-ja",
    # "arb": "omw-arb",   # l'arabo e' gia' dentro omw:1.4
}

# Filtro QUALITA': teniamo solo parole inglesi abbastanza comuni (scala Zipf di
# wordfreq: ~1 raro, ~8 comunissimo). 3.0 scarta i termini tecnici/oscuri tipo
# "Animalia" (1.71) ma tiene "pest" (3.64), "apple" (4.76), "dog" (5.10).
MIN_ZIPF = 3.0

# Codici lingua per wordfreq (per ordinare le traduzioni per frequenza).
WF_LANG = {"eng": "en", "spa": "es", "fra": "fr", "por": "pt", "nld": "nl", "jpn": "ja"}

# lexfile di WordNet -> tema dell'app (esportiamo solo questi).
THEME_MAP = {
    "noun.food":       "cibo",
    "noun.animal":     "animali",
    "noun.plant":      "piante",
    "noun.body":       "corpo",
    "noun.artifact":   "oggetti",
    "noun.location":   "luoghi",
    "noun.person":     "persone",
    "noun.substance":  "materiali",
    "noun.time":       "tempo",
    "noun.possession": "denaro",
    "noun.feeling":    "emozioni",
}

OUTPUT_FILE = "seed_cards.sql"
# --------------------------------------------------------------------------


def single_words(forms):
    """Solo parole singole (no spazi, no simboli). isalpha() e' unicode-aware."""
    out = []
    for w in forms:
        w = (w or "").strip()
        if w and " " not in w and w.isalpha():
            out.append(w)
    return out


def zipf_safe(word, wf_lang):
    """Frequenza Zipf; None se la lingua non ha il tokenizer (es. ja senza MeCab)."""
    try:
        return zipf_frequency(word, wf_lang)
    except Exception:
        return None


def best_source(forms):
    """Parola inglese single-word piu' frequente e la sua frequenza Zipf."""
    best, best_z = None, -1.0
    for w in single_words(forms):
        z = zipf_safe(w, "en") or 0.0
        if z > best_z:
            best, best_z = w, z
    return best, best_z


def best_target(forms, wf_lang):
    """Traduzione single-word piu' frequente; se manca il tokenizer, la prima."""
    cands = single_words(forms)
    if not cands:
        return None
    ranked = sorted(((zipf_safe(w, wf_lang) or -1.0), w) for w in cands)
    return ranked[-1][1]


def main():
    # 1) Scarica hub + wordnet (idempotente: se gia' presente, la ignoriamo).
    for spec in DOWNLOADS:
        try:
            wn.download(spec)
        except Exception as e:
            print(f"(info) '{spec}' gia' presente o non riscaricato: {e}")

    print("Lexicon installati:")
    for lex in wn.lexicons():
        print(f"   {lex.id:16} {lex.language}")
    print()

    en = wn.Wordnet(EN_LEXICON)

    seen = set()                 # dedup su (parola_eng, target_lang, parola_target)
    rows = []                    # (src, target_lang, tgt, theme)
    no_lexfile = 0

    for es in en.synsets(pos="n"):
        lexfile = es.lexfile()
        if lexfile is None:
            no_lexfile += 1
            continue
        theme = THEME_MAP.get(lexfile)
        if theme is None:
            continue

        src, src_z = best_source(es.lemmas())
        if not src or src_z < MIN_ZIPF:      # scarta concetti con parola inglese rara/oscura
            continue

        for lang, lex_id in TARGET_LEXICONS.items():
            # raccogli i lemmi da TUTTI i synset tradotti, poi prendi il migliore
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
        f.write("-- Carte generate da Open Multilingual WordNet (libreria wn)\n")
        f.write(f"-- {SOURCE_LANG} -> {list(TARGET_LEXICONS.keys())}, {len(rows)} carte\n")
        f.write("SET NAMES utf8mb4;\n\n")
        for src, lang, tgt, theme in rows:
            s = src.replace("'", "''")
            t = tgt.replace("'", "''")
            f.write(
                "INSERT INTO cards "
                "(owner_id, source_lang, target_lang, word_source, word_target, theme, origin) "
                f"VALUES (NULL, '{SOURCE_LANG}', '{lang}', '{s}', '{t}', '{theme}', 'seed');\n"
            )

    # Riepilogo per lingua e per tema.
    from collections import Counter
    if no_lexfile:
        print(f"ATTENZIONE: {no_lexfile} synset senza lexfile (temi non disponibili). "
              f"Se sono TROPPI, prova un altro EN_LEXICON.\n")
    per_lang = Counter(lang for _, lang, _, _ in rows)
    print(f"Scritte {len(rows)} carte in {OUTPUT_FILE}\n")
    for lang, n in per_lang.most_common():
        print(f"{SOURCE_LANG} -> {lang}: {n} carte")
        by_theme = Counter(theme for _, l, _, theme in rows if l == lang)
        for theme, tn in by_theme.most_common():
            print(f"    {theme:12} {tn}")


if __name__ == "__main__":
    main()
