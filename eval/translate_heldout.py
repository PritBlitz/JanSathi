#!/usr/bin/env python3
"""Machine-translates the English held-out questions into other languages, for measuring retrieval
in languages with no hand-written evaluation set. A proxy: real questions would be better.

  python eval/translate_heldout.py [--url http://127.0.0.1:8090] [--langs ta,bn]

Writes eval/rag-translated/<lang>.jsonl, read by EmbeddingEvalTest.
"""
import argparse
import json
import urllib.request
from pathlib import Path

HERE = Path(__file__).parent

ap = argparse.ArgumentParser()
ap.add_argument("--url", default="http://127.0.0.1:8090")
ap.add_argument("--langs", default="ta,bn")
args = ap.parse_args()
items = [json.loads(l) for l in (HERE / "rag/en.jsonl").read_text(encoding="utf-8").splitlines() if l.strip()]
items = [i for i in items if i["split"] == "heldout"]
(HERE / "rag-translated").mkdir(exist_ok=True)
for lang in args.langs.split(","):
    out = []
    for start in range(0, len(items), 8):
        batch = items[start:start + 8]
        body = json.dumps({"texts": [i["question"] for i in batch], "source": "en", "target": lang}).encode()
        request = urllib.request.Request(args.url + "/v1/translate", body, {"Content-Type": "application/json"})
        with urllib.request.urlopen(request, timeout=3600) as reply:
            texts = json.load(reply)["translations"]
        for item, text in zip(batch, texts):
            if text:
                out.append({**item, "id": item["id"].replace("en-", f"{lang}-"), "lang": lang, "question": text})
    (HERE / "rag-translated" / f"{lang}.jsonl").write_text("".join(json.dumps(i, ensure_ascii=False) + "\n" for i in out), encoding="utf-8", newline="\n")
    print(lang, len(out), "of", len(items))
