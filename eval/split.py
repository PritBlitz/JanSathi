#!/usr/bin/env python3
"""Marks each item of eval/rag/*.jsonl as "dev" or "heldout", stratified by language, answerable
or not, and batch, by a hash of its id. Items already marked keep their mark, so new items never
move old ones. Tune on dev only. Run: python eval/split.py
"""
import hashlib
import json
from collections import defaultdict
from pathlib import Path

for path in sorted(Path(__file__).with_name("rag").glob("*.jsonl")):
    items = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
    groups = defaultdict(list)
    for item in items:
        if "split" not in item:
            groups[(bool(item["expected_schemes"]), item.get("set"))].append(item)
    for group in groups.values():
        group.sort(key=lambda i: hashlib.sha256(i["id"].encode()).hexdigest())
        for n, item in enumerate(group):
            item["split"] = "dev" if n % 2 == 0 else "heldout"
    path.write_text("".join(json.dumps(i, ensure_ascii=False) + "\n" for i in items), encoding="utf-8", newline="\n")
    counts = defaultdict(int)
    for item in items:
        counts[item["split"], "answerable" if item["expected_schemes"] else "unanswerable"] += 1
    print(path.name, dict(sorted(counts.items())))
