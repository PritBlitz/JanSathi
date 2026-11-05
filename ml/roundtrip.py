#!/usr/bin/env python3
"""Measures speech end to end against a running service: synthesise real scheme sentences, then
transcribe them back and compare. Run with the standard library only.

  python ml/roundtrip.py [--url http://127.0.0.1:8090] [--langs hi,ta] [--n 5]

Sentences come from frontend/public/locales/<lang>.json (the app's own strings) and hi/en scheme
text. Prints character error rate (CER) and timings. This measures Whisper on a synthetic voice:
it shows the pipeline works and how languages compare, not accuracy on real rural speech.
"""
import argparse
import json
import re
import sys
import time
import unicodedata
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
LANGS = ("en", "hi", "bn", "ta", "te", "mr", "gu", "kn", "ml", "pa")


def clean(text: str) -> str:
    text = unicodedata.normalize("NFC", text).lower()
    return "".join(c for c in text if c.isalpha() or unicodedata.category(c).startswith("M") or c == " ").strip()


def distance(a: str, b: str) -> int:
    row = list(range(len(b) + 1))
    for i, x in enumerate(a, 1):
        previous, row[0] = row[0], i
        for j, y in enumerate(b, 1):
            previous, row[j] = row[j], min(row[j] + 1, row[j - 1] + 1, previous + (x != y))
    return row[-1]


def sentences(lang: str, n: int) -> list:
    strings = json.loads((ROOT / f"frontend/public/locales/{lang}.json").read_text(encoding="utf-8"))
    english = json.loads((ROOT / "frontend/public/locales/en.json").read_text(encoding="utf-8"))
    # The same keys in every language, plain sentences only.
    keys = sorted(k for k, v in english.items() if isinstance(v, str) and 40 <= len(v) <= 110 and not re.search(r"[{}\d]", v) and isinstance(strings.get(k), str))
    step = max(1, len(keys) // n)
    return [strings[k] for k in keys[::step][:n]]


def post(url: str, body: bytes, kind: str):
    started = time.perf_counter()
    with urllib.request.urlopen(urllib.request.Request(url, body, {"Content-Type": kind}), timeout=600) as reply:
        data = reply.read()
    return data, time.perf_counter() - started


def main() -> None:
    sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", default="http://127.0.0.1:8090")
    ap.add_argument("--langs", default=",".join(LANGS))
    ap.add_argument("--n", type=int, default=5)
    ap.add_argument("--show", action="store_true")
    args = ap.parse_args()
    print("lang  cer   tts s/clip  stt s/clip  audio s/clip  stt realtime")
    for lang in args.langs.split(","):
        errors = chars = 0
        tts_time = stt_time = audio_time = 0.0
        texts = sentences(lang, args.n)
        for i, text in enumerate(texts):
            wav, t1 = post(args.url + "/v1/tts", json.dumps({"text": text, "lang": lang}).encode(), "application/json")
            reply, t2 = post(f"{args.url}/v1/stt?lang={lang}", wav, "application/octet-stream")
            heard = json.loads(reply)["text"]
            a, b = clean(text), clean(heard)
            errors += distance(a, b)
            chars += len(a)
            seconds = (len(wav) - 44) / 32000
            if i:  # the first pair pays for loading the models
                tts_time += t1
                stt_time += t2
                audio_time += seconds
            if args.show:
                print(f"  {text}\n  {heard}")
        count = max(1, len(texts) - 1)
        print(f"{lang:4}  {errors / max(1, chars):.2f}  {tts_time / count:9.2f}  {stt_time / count:9.2f}  {audio_time / count:11.1f}  {stt_time / max(0.1, audio_time):.2f}x", flush=True)


if __name__ == "__main__":
    main()
