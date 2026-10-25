#!/usr/bin/env python3
"""Re-check the official sources of every scheme in content/schemes/.

For each source URL: check robots.txt, fetch politely (one request every 5 s per
host, identifying User-Agent), normalise, hash with sha256 and compare with the
recorded content_hash. See docs/KNOWLEDGE_BASE.md §5.

Usage:
  python ingest/recheck.py                 # check all schemes, report changes
  python ingest/recheck.py pm-kisan        # check one scheme
  python ingest/recheck.py --update pm-kisan
                                           # write fetched_at + content_hash for that scheme
  python ingest/recheck.py --hash-file page.html
                                           # hash a copy you fetched by hand (same normalisation)

Exit code: 0 if nothing changed, 1 if any source changed or failed, 2 on usage errors.
Only the standard library is used. This job never sets verified_at: only a human reviewer does.
"""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import html
import json
import re
import sys
import time
import urllib.error
import urllib.request
import urllib.robotparser
from pathlib import Path
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parent.parent
SCHEMES = ROOT / "content" / "schemes"
REPORTS = ROOT / "ingest" / "reports"
USER_AGENT = "JanSathi-bot/0.1 (+https://github.com/PritBlitz/JanSathi)"
DELAY_SECONDS = 5.0
TIMEOUT = 60

# Lines of page chrome that change on every visit and must not trigger a review.
VOLATILE = re.compile(
    r"visitor|last updated|updated on|page views|hits\s*:|copyright|©|\bdate\s*:\s*\d", re.I
)

_last_hit: dict[str, float] = {}
_robots: dict[str, urllib.robotparser.RobotFileParser | None] = {}


def normalise_html(raw: str) -> str:
    text = re.sub(r"(?is)<(script|style|noscript|nav|footer|header)\b.*?</\1>", " ", raw)
    text = re.sub(r"(?is)<br\s*/?>|</(p|li|tr|h[1-6]|div|td|th)>", "\n", text)
    text = html.unescape(re.sub(r"(?s)<[^>]+>", " ", text))
    lines = []
    for line in text.splitlines():
        line = re.sub(r"\s+", " ", line).strip()
        if line and not VOLATILE.search(line):
            lines.append(line)
    return "\n".join(lines)


def content_hash(body: bytes, content_type: str) -> str:
    """PDFs and other binaries: hash of the bytes. HTML: hash of the normalised visible text."""
    is_html = "html" in content_type or body[:512].lstrip().lower().startswith((b"<!doctype html", b"<html"))
    if is_html:
        data = normalise_html(body.decode("utf-8", errors="replace")).encode("utf-8")
    else:
        data = body
    return "sha256:" + hashlib.sha256(data).hexdigest()


def robots_allows(url: str) -> bool:
    parts = urlsplit(url)
    base = f"{parts.scheme}://{parts.netloc}"
    if base not in _robots:
        rp = urllib.robotparser.RobotFileParser()
        rp.set_url(base + "/robots.txt")
        try:
            polite_wait(parts.netloc)
            rp.read()
            _robots[base] = rp
        except Exception:
            _robots[base] = None  # no readable robots.txt: allowed
    rp = _robots[base]
    return True if rp is None else rp.can_fetch(USER_AGENT, url)


def polite_wait(host: str) -> None:
    wait = DELAY_SECONDS - (time.monotonic() - _last_hit.get(host, -1e9))
    if wait > 0:
        time.sleep(wait)
    _last_hit[host] = time.monotonic()


def fetch(url: str) -> tuple[bytes, str]:
    polite_wait(urlsplit(url).netloc)
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Accept": "*/*"})
    with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
        return resp.read(), resp.headers.get("Content-Type", "")


def check_source(src: dict) -> dict:
    url = src.get("url")
    result = {"id": src.get("id"), "url": url, "recorded": src.get("content_hash")}
    if not url:
        return {**result, "status": "no_url"}
    if src.get("fetch") == "manual":
        return {**result, "status": "manual", "note": src.get("fetch_note", "")}
    if not robots_allows(url):
        return {**result, "status": "disallowed_by_robots"}
    try:
        body, ctype = fetch(url)
    except urllib.error.HTTPError as e:
        return {**result, "status": "failed", "error": f"HTTP {e.code}"}
    except Exception as e:  # network, TLS, timeout
        return {**result, "status": "failed", "error": str(e)[:200]}
    new = content_hash(body, ctype)
    if not src.get("content_hash"):
        status = "new"
    elif new == src["content_hash"]:
        status = "unchanged"
    else:
        status = "changed"
    return {**result, "status": status, "hash": new}


def load(slug: str) -> tuple[Path, dict]:
    path = SCHEMES / f"{slug}.json"
    if not path.exists():
        sys.exit(f"no such scheme: {slug}")
    return path, json.loads(path.read_text(encoding="utf-8"))


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("slugs", nargs="*", help="schemes to check (default: all)")
    ap.add_argument("--update", metavar="SLUG", help="write fetched_at and content_hash for this scheme")
    ap.add_argument("--hash-file", metavar="PATH", help="print the content hash of a local file and exit")
    args = ap.parse_args()

    if args.hash_file:
        body = Path(args.hash_file).read_bytes()
        ctype = "text/html" if args.hash_file.lower().endswith((".html", ".htm")) else ""
        print(content_hash(body, ctype))
        return 0

    slugs = [args.update] if args.update else (args.slugs or sorted(p.stem for p in SCHEMES.glob("*.json")))
    today = dt.date.today().isoformat()
    report = {"run_at": dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds"), "schemes": {}}
    problems = 0

    for slug in slugs:
        path, scheme = load(slug)
        results = [check_source(s) for s in scheme.get("sources", [])]
        report["schemes"][slug] = results
        for r in results:
            mark = {"unchanged": "ok", "new": "new", "manual": "manual"}.get(r["status"], "!!")
            print(f"[{mark:>6}] {slug}/{r['id']}: {r['status']} {r.get('error', '')}".rstrip())
            if r["status"] in ("changed", "failed", "disallowed_by_robots"):
                problems += 1
        if args.update:
            by_id = {r["id"]: r for r in results}
            for src in scheme["sources"]:
                r = by_id.get(src["id"], {})
                if r.get("status") in ("new", "unchanged", "changed"):
                    src["fetched_at"] = today
                    src["content_hash"] = r["hash"]
            path.write_text(json.dumps(scheme, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            print(f"updated {path.relative_to(ROOT)} (verified_at is unchanged; a reviewer sets it)")

    REPORTS.mkdir(parents=True, exist_ok=True)
    out = REPORTS / f"recheck-{today}.json"
    out.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"report: {out.relative_to(ROOT)}")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
