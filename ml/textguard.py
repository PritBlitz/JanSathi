"""Text handling around the models, standard library only, so it is tested without any weights.

Translation guard: split into sentences, keep amounts, URLs and acronyms out of the model's way,
and reject any output whose numbers differ from the source. A wrong amount in a scheme is worse
than an English sentence, so a sentence that fails is never shipped.

Speech: MMS voices have no digits, so numbers are spelt out first.
"""
from __future__ import annotations

import re
import unicodedata
from collections import Counter

# Ends of sentences: Latin stop, danda, question and exclamation marks.
_SENTENCE_END = re.compile(r"(?<=[.!?।॥])\s+(?=[^\s])")
# "Rs. 500", "e.g. x", "No. 5", "U.P. state": the stop does not end the sentence.
_NOT_AN_END = re.compile(r"(?:\b(?:Rs|No|Dr|Mr|Mrs|Ms|Shri|Smt|St|vs|etc|e\.g|i\.e)|\b[A-Z])\.$")

URL = re.compile(r"https?://\S+|www\.\S+|\b[a-z0-9-]+(?:\.[a-z0-9-]+)*\.(?:gov\.in|nic\.in|org\.in|co\.in|in|org|com)\b(?:/\S*)?", re.I)
NUMBER = re.compile(r"\d[\d,]*(?:\.\d+)?")
# Scheme acronyms and codes: PM-KISAN, NSAP, KCC, e-KYC, MGNREGA, 10+2 stays with NUMBER.
ACRONYM = re.compile(r"\b(?:[a-z]-)?[A-Z][A-Z0-9]+(?:-[A-Z0-9]+)*\b(?:-[A-Z]\b)?")


def sentences(text: str) -> list[str]:
    """Splits text into sentences. Joining the parts with a space gives the text back."""
    parts: list[str] = []
    for piece in _SENTENCE_END.split(text.strip()):
        if parts and _NOT_AN_END.search(parts[-1]):
            parts[-1] += " " + piece
        else:
            parts.append(piece)
    return [p for p in parts if p]


def ascii_digits(text: str) -> str:
    """Devanagari, Bengali, Tamil and other decimal digits as 0-9."""
    return "".join(str(unicodedata.digit(c)) if c.isdigit() and not c.isascii() and unicodedata.category(c) == "Nd" else c for c in text)


def numbers(text: str) -> Counter:
    """Every number in the text, grouping commas removed: 2,50,000 and 250000 are the same."""
    found = (m.group().replace(",", "").rstrip(".") for m in NUMBER.finditer(ascii_digits(URL.sub(" ", text))))
    return Counter(n for n in found if n)


def same_numbers(source: str, output: str) -> bool:
    return numbers(source) == numbers(output)


def protected_terms(text: str) -> list[str]:
    """URLs and acronyms that must appear unchanged in a translation."""
    urls = [m.group().rstrip(".,;)") for m in URL.finditer(text)]
    bare = URL.sub(" ", text)
    return urls + [m.group() for m in ACRONYM.finditer(bare)]


def missing_terms(source: str, output: str) -> list[str]:
    return [t for t in protected_terms(source) if t not in output]


def mask(text: str) -> tuple[str, list[str]]:
    """Swaps URLs and acronyms for <0>, <1>…: left in place, models spell PM-KISAN in the target
    script. Sarvam-Translate carries these markers through unchanged."""
    terms: list[str] = []

    def hide(m: re.Match) -> str:
        term = m.group().rstrip(".,;)")
        terms.append(term)
        return f"<{len(terms) - 1}>" + m.group()[len(term):]

    return ACRONYM.sub(hide, URL.sub(hide, text)), terms


def unmask(text: str, terms: list[str]) -> str | None:
    """Puts the terms back; None unless every marker came back exactly once."""
    found = [int(ascii_digits(m)) for m in re.findall(r"<\s*(\d+)\s*>", text)]
    if sorted(found) != list(range(len(terms))):
        return None
    return re.sub(r"<\s*(\d+)\s*>", lambda m: terms[int(ascii_digits(m.group(1)))], text)


def looks_broken(source: str, output: str) -> bool:
    """Empty, copied, or runaway output: the usual ways a translation model fails on a long line."""
    if not output.strip() or output.strip() == source.strip():
        return bool(re.search(r"[A-Za-z]{4}", source))
    words = output.split()
    if len(words) >= 8 and max(Counter(words).values()) > len(words) // 2:
        return True
    return len(output) > 4 * len(source) + 40


# Words a voice reads for a rupee sign and a percent sign.
_RUPEE = {"en": "rupees", "hi": "रुपये", "bn": "টাকা", "ta": "ரூபாய்", "te": "రూపాయలు", "mr": "रुपये", "gu": "રૂપિયા", "kn": "ರೂಪಾಯಿ", "ml": "രൂപ", "pa": "ਰੁਪਏ"}
_PERCENT = {"en": "percent", "hi": "प्रतिशत", "bn": "শতাংশ", "ta": "சதவீதம்", "te": "శాతం", "mr": "टक्के", "gu": "ટકા", "kn": "ಶೇಕಡಾ", "ml": "ശതമാനം", "pa": "ਪ੍ਰਤੀਸ਼ਤ"}
_DIGITS = {
    "en": "zero one two three four five six seven eight nine",
    "hi": "शून्य एक दो तीन चार पांच छह सात आठ नौ",
    "bn": "শূন্য এক দুই তিন চার পাঁচ ছয় সাত আট নয়",
    "ta": "பூஜ்யம் ஒன்று இரண்டு மூன்று நான்கு ஐந்து ஆறு ஏழு எட்டு ஒன்பது",
    "te": "సున్నా ఒకటి రెండు మూడు నాలుగు ఐదు ఆరు ఏడు ఎనిమిది తొమ్మిది",
    "mr": "शून्य एक दोन तीन चार पाच सहा सात आठ नऊ",
    "gu": "શૂન્ય એક બે ત્રણ ચાર પાંચ છ સાત આઠ નવ",
    "kn": "ಸೊನ್ನೆ ಒಂದು ಎರಡು ಮೂರು ನಾಲ್ಕು ಐದು ಆರು ಏಳು ಎಂಟು ಒಂಬತ್ತು",
    "ml": "പൂജ്യം ഒന്ന് രണ്ട് മൂന്ന് നാല് അഞ്ച് ആറ് ഏഴ് എട്ട് ഒമ്പത്",
    "pa": "ਸਿਫ਼ਰ ਇੱਕ ਦੋ ਤਿੰਨ ਚਾਰ ਪੰਜ ਛੇ ਸੱਤ ਅੱਠ ਨੌਂ",
}


_HI_0_99 = (
    "शून्य एक दो तीन चार पाँच छह सात आठ नौ दस ग्यारह बारह तेरह चौदह पंद्रह सोलह सत्रह अठारह उन्नीस "
    "बीस इक्कीस बाईस तेईस चौबीस पच्चीस छब्बीस सत्ताईस अट्ठाईस उनतीस तीस इकतीस बत्तीस तैंतीस चौंतीस पैंतीस छत्तीस सैंतीस अड़तीस उनतालीस "
    "चालीस इकतालीस बयालीस तैंतालीस चौवालीस पैंतालीस छियालीस सैंतालीस अड़तालीस उनचास पचास इक्यावन बावन तिरपन चौवन पचपन छप्पन सत्तावन अट्ठावन उनसठ "
    "साठ इकसठ बासठ तिरसठ चौंसठ पैंसठ छियासठ सड़सठ अड़सठ उनहत्तर सत्तर इकहत्तर बहत्तर तिहत्तर चौहत्तर पचहत्तर छिहत्तर सतहत्तर अठहत्तर उन्यासी "
    "अस्सी इक्यासी बयासी तिरासी चौरासी पचासी छियासी सत्तासी अट्ठासी नवासी नब्बे इक्यानवे बानवे तिरानवे चौरानवे पचानवे छियानवे सत्तानवे अट्ठानवे निन्यानवे"
).split()


def hindi_number(value: int) -> str:
    """Whole numbers below 100 crore in the Indian system: 250000 is "दो लाख पचास हज़ार"."""
    if value < 100:
        return _HI_0_99[value]
    words = []
    for size, name in ((10_000_000, "करोड़"), (100_000, "लाख"), (1000, "हज़ार"), (100, "सौ")):
        if value >= size:
            words += [hindi_number(value // size), name]
            value %= size
    if value:
        words.append(_HI_0_99[value])
    return " ".join(words)


def spell_digits(number: str, lang: str) -> str:
    words = _DIGITS[lang].split()
    return " ".join(words[int(c)] for c in number if c.isdigit())


def for_speech(text: str, lang: str, spell=None) -> str:
    """Text a voice with no digits can read. `spell(int, lang)` gives number words when it can."""
    text = URL.sub(" ", ascii_digits(text))
    text = re.sub(r"(?:₹|\bRs\.?\s*)\s*(\d[\d,]*(?:\.\d+)?)(\s*(?:lakh|crore|लाख|करोड़))?", lambda m: f"{m.group(1)}{m.group(2) or ''} {_RUPEE[lang]}", text)
    text = text.replace("%", f" {_PERCENT[lang]}")

    def number(m: re.Match) -> str:
        raw = m.group().replace(",", "")
        whole = raw.split(".")[0]
        if spell and "." not in raw and len(whole) <= 9:
            said = spell(int(whole), lang)
            if said:
                return f" {said} "
        return f" {spell_digits(whole, lang)} " if "." not in raw else f" {spell_digits(whole, lang)} . {spell_digits(raw.split('.')[1], lang)} "

    return re.sub(r"\s+", " ", NUMBER.sub(number, text)).strip()
