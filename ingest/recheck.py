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
  python ingest/recheck.py --dismiss pm-kisan/portal --by NAME --note "only the instalment news changed"
                                           # a reviewer read the change and no fact is affected
  add --snapshot to any of these to save the extracted text to content/snapshots/<hash>.txt
  (PDF text needs PyMuPDF: run with `python -E -P` so the user site-packages are visible)

A check of all schemes also writes content/source-status.json: what the build needs to warn users
(a cited source that changed makes its scheme stale in both engines) without editing scheme files,
because any edit to one voids its reviews. Sources whose host's robots.txt forbids us are recorded
as manual_check, not errors: a person looks at them, and they are never fetched.

Exit code: 0 if nothing needs a person, 1 if a source changed or failed or a scheme is past its
re-verification interval, 2 on usage errors.
Only the standard library is needed (PyMuPDF is optional, for PDF snapshots). This job never
sets verified_at: tools/publish.mjs does, after two independent reviews (docs/REVIEW.md).
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
SNAPSHOTS = ROOT / "content" / "snapshots"
STATUS_FILE = ROOT / "content" / "source-status.json"
DEFAULT_REVERIFY_DAYS = 180
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
    if is_html(body, content_type):
        data = normalise_html(body.decode("utf-8", errors="replace")).encode("utf-8")
    else:
        data = body
    return "sha256:" + hashlib.sha256(data).hexdigest()


def is_html(body: bytes, content_type: str) -> bool:
    return "html" in content_type or body[:512].lstrip().lower().startswith((b"<!doctype html", b"<html"))


def extract_text(body: bytes, content_type: str) -> str | None:
    """Readable text of a source, used for snapshots and quote checks. None if it can't be extracted."""
    if is_html(body, content_type):
        return normalise_html(body.decode("utf-8", errors="replace"))
    if body[:5] == b"%PDF-":
        try:
            import pymupdf  # optional
        except ImportError:
            return None
        with pymupdf.open(stream=body, filetype="pdf") as doc:
            return "\n".join(f"[page {i + 1}]\n{page.get_text()}" for i, page in enumerate(doc))
    try:
        return body.decode("utf-8")
    except UnicodeDecodeError:
        return None


def save_snapshot(hash_value: str, body: bytes, content_type: str) -> str:
    text = extract_text(body, content_type)
    if text is None:
        return "no text (scanned PDF or PyMuPDF missing)"
    SNAPSHOTS.mkdir(parents=True, exist_ok=True)
    out = SNAPSHOTS / f"{hash_value.split(':', 1)[1]}.txt"
    out.write_text(text, encoding="utf-8", newline="\n")
    return str(out.relative_to(ROOT))


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


def stable_hash(text: str) -> str:
    """Hash of a page without its numbers: running totals and dates change daily and mean nothing."""
    words = re.sub(r"\d[\d,./:-]*", " ", text)
    return "sha256:" + hashlib.sha256(" ".join(words.split()).encode("utf-8")).hexdigest()


def recorded_stable_hash(content_hash_value: str | None) -> str | None:
    """stable_hash of the snapshot reviewers read, if there is one."""
    if not content_hash_value:
        return None
    path = SNAPSHOTS / f"{content_hash_value.split(':', 1)[-1]}.txt"
    return stable_hash(path.read_text(encoding="utf-8")) if path.exists() else None


def next_entry(previous: dict | None, result: dict, today: str) -> dict | None:
    """The source-status entry after a check. None removes it. Pure, so it can be tested."""
    status = result["status"]
    if status == "failed":
        return previous  # a bad day at the host says nothing about the page
    if status == "disallowed_by_robots":
        since = previous["since"] if previous and previous["status"] == "manual_check" else today
        return {"status": "manual_check", "since": since}
    if status == "changed":
        if previous and previous["status"] == "dismissed" and previous["stable_hash"] == result.get("stable_hash"):
            return previous
        since = previous["since"] if previous and previous["status"] == "changed" else today
        return {"status": "changed", "since": since}
    return None  # unchanged, counters_only, manual, new


def reverify_due(scheme: dict, today: dt.date) -> list[str]:
    """Hard-rule sources past the scheme's interval: the same test as RULES_ENGINE §9.5."""
    if scheme.get("status") != "published":
        return []
    days = scheme.get("reverify_days", DEFAULT_REVERIFY_DAYS)
    sources = {s.get("id"): s for s in scheme.get("sources", [])}
    due = []
    for rule in scheme.get("rules", []):
        src = sources.get(rule.get("source"), {})
        verified = src.get("verified_at")
        if rule.get("hard") and verified and (today - dt.date.fromisoformat(verified[:10])).days > days and src["id"] not in due:
            due.append(src["id"])
    return due


def load_status() -> dict:
    if STATUS_FILE.exists():
        return json.loads(STATUS_FILE.read_text(encoding="utf-8"))
    return {
        "$comment": "Written by ingest/recheck.py; do not edit by hand. docs/KNOWLEDGE_BASE.md section 5.",
        "sources": {},
    }


def save_status(status: dict) -> None:
    status["sources"] = dict(sorted(status["sources"].items()))
    STATUS_FILE.write_text(json.dumps(status, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")


def dismiss(key: str, by: str, note: str) -> int:
    """A reviewer read the change and no fact depends on it. Holds until the words change again."""
    slug, _, source_id = key.partition("/")
    _, scheme = load(slug)
    src = next((s for s in scheme.get("sources", []) if s.get("id") == source_id), None)
    if src is None:
        sys.exit(f"no such source: {key}")
    if not robots_allows(src["url"]):
        sys.exit(f"{key}: robots.txt forbids fetching this page")
    body, ctype = fetch(src["url"])
    text = extract_text(body, ctype)
    if not text:
        sys.exit(f"{key}: no text to compare")
    status = load_status()
    today = dt.date.today().isoformat()
    status["sources"][key] = {"status": "dismissed", "since": today, "stable_hash": stable_hash(text), "by": by, "note": note}
    save_status(status)
    print(f"dismissed {key}")
    return 0


def check_source(src: dict, snapshot: bool = False) -> dict:
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
    if snapshot:
        result["snapshot"] = save_snapshot(new, body, ctype)
    if not src.get("content_hash"):
        status = "new"
    elif new == src["content_hash"]:
        status = "unchanged"
    else:
        status = "changed"
        text = extract_text(body, ctype)
        if text:
            result["stable_hash"] = stable_hash(text)
            if result["stable_hash"] == recorded_stable_hash(src["content_hash"]):
                status = "counters_only"
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
    ap.add_argument("--snapshot", action="store_true", help="save extracted text to content/snapshots/<hash>.txt")
    ap.add_argument("--dismiss", metavar="SLUG/SOURCE", help="record that a reviewer read this change and no fact is affected")
    ap.add_argument("--by", help="who dismissed it (with --dismiss)")
    ap.add_argument("--note", help="why it is safe (with --dismiss)")
    args = ap.parse_args()

    if args.dismiss:
        if not args.by or not args.note:
            ap.error("--dismiss needs --by and --note")
        return dismiss(args.dismiss, args.by, args.note)

    if args.hash_file:
        body = Path(args.hash_file).read_bytes()
        ctype = "text/html" if args.hash_file.lower().endswith((".html", ".htm")) else ""
        h = content_hash(body, ctype)
        print(h)
        if args.snapshot:
            print(save_snapshot(h, body, ctype), file=sys.stderr)
        return 0

    slugs = [args.update] if args.update else (args.slugs or sorted(p.stem for p in SCHEMES.glob("*.json")))
    today = dt.date.today().isoformat()
    report = {"run_at": dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds"), "schemes": {}, "reverify_due": {}}
    problems = 0
    status = load_status()

    for slug in slugs:
        path, scheme = load(slug)
        results = [check_source(s, args.snapshot) for s in scheme.get("sources", [])]
        report["schemes"][slug] = results
        for r in results:
            key = f"{slug}/{r['id']}"
            entry = next_entry(status["sources"].get(key), r, today)
            if entry is None:
                status["sources"].pop(key, None)
            else:
                status["sources"][key] = entry
            shown = entry["status"] if entry and r["status"] in ("changed", "disallowed_by_robots") else r["status"]
            mark = {"unchanged": "ok", "counters_only": "ok", "dismissed": "ok", "new": "new", "manual": "manual", "manual_check": "manual"}.get(shown, "!!")
            print(f"[{mark:>6}] {key}: {shown} {r.get('error', '')}".rstrip())
            if shown in ("changed", "failed"):
                problems += 1
        due = reverify_due(scheme, dt.date.today())
        if due:
            report["reverify_due"][slug] = due
            print(f"[   due] {slug}: past its re-verification interval ({', '.join(due)})")
            problems += 1
        if args.update:
            by_id = {r["id"]: r for r in results}
            for src in scheme["sources"]:
                r = by_id.get(src["id"], {})
                if r.get("status") in ("new", "unchanged", "changed"):
                    src["fetched_at"] = today
                    src["content_hash"] = r["hash"]
            path.write_text(json.dumps(scheme, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
            print(f"updated {path.relative_to(ROOT)} (verified_at is unchanged; a reviewer sets it)")

    if not args.update:
        save_status(status)
    REPORTS.mkdir(parents=True, exist_ok=True)
    out = REPORTS / f"recheck-{today}.json"
    out.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
    print(f"report: {out.relative_to(ROOT)}")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
