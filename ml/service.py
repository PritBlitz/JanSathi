#!/usr/bin/env python3
"""JanSathi internal ML service: embeddings, speech and translation (see ml/README.md).

The HTTP layer needs only the standard library; the models are in backends.py and load on first
use. A missing model answers 503: this service never returns made-up vectors, audio or text.
Nothing is logged and nothing is written: text and audio live only for the length of a request.

  python ml/service.py [--host 127.0.0.1] [--port 8090]
"""
from __future__ import annotations

import argparse
import hmac
import json
import os
import threading
from dataclasses import dataclass, field
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Callable, Sequence
from urllib.parse import parse_qs, urlsplit

import backends
import textguard
from backends import BadInput, Unavailable

MAX_TEXTS = 256
MAX_BODY = 2_000_000
MAX_DRAIN = 32_000_000  # past this the connection is simply closed
MAX_AUDIO = 10_000_000
MAX_TTS_CHARS = 2000
MAX_TRANSLATE_CHARS = 5000
LANGS = backends.LANGS
Encoder = Callable[[Sequence[str], str], list]


@dataclass
class Backends:
    """Each one is a plain function, so tests can swap in a stand-in."""

    embed: Encoder | None = None
    tts: Callable[[str, str], bytes] | None = None
    stt: Callable[[bytes, str], str] | None = None
    translate: Callable[..., list] | None = None
    tts_languages: Callable[[], list] = list
    stt_languages: Callable[[], list] = list
    models: dict = field(default_factory=dict)
    # CPU-bound work: more at once only makes everyone slower.
    gate: threading.BoundedSemaphore = field(default_factory=lambda: threading.BoundedSemaphore(int(os.environ.get("ML_MAX_CONCURRENT", "2"))))


def guarded(work: Callable[[], tuple[int, object]]) -> tuple[int, object]:
    try:
        return work()
    except Unavailable as e:
        return 503, {"error": str(e)}
    except ImportError:
        return 503, {"error": "model packages not installed: pip install -r ml/requirements.txt"}
    except BadInput as e:
        return 422, {"error": str(e)}
    except Exception as e:  # the type only: a message could quote the text
        return 500, {"error": f"model failure: {type(e).__name__}"}


def embed(payload: object, encoder: Encoder | None) -> tuple[int, object]:
    """The whole of /v1/embed apart from HTTP. Returns the status and the JSON body."""
    if not isinstance(payload, dict):
        return 400, {"error": "send a JSON object"}
    texts, kind = payload.get("texts"), payload.get("kind", "passage")
    if not isinstance(texts, list) or not texts or len(texts) > MAX_TEXTS or not all(isinstance(t, str) for t in texts):
        return 400, {"error": f"texts must be 1 to {MAX_TEXTS} strings"}
    if kind not in ("query", "passage"):
        return 400, {"error": "kind must be query or passage"}
    if encoder is None:
        return 503, {"error": "no embedding model configured"}
    return guarded(lambda: (200, {"vectors": encoder(texts, kind)}))


def tts(payload: object, voice: Callable[[str, str], bytes] | None) -> tuple[int, object]:
    if not isinstance(payload, dict):
        return 400, {"error": "send a JSON object"}
    text, lang = payload.get("text"), payload.get("lang")
    if not isinstance(text, str) or not text.strip() or len(text) > MAX_TTS_CHARS:
        return 400, {"error": f"text must be 1 to {MAX_TTS_CHARS} characters"}
    if lang not in LANGS:
        return 400, {"error": "unknown language"}
    if voice is None:
        return 503, {"error": "no speech model configured"}
    return guarded(lambda: (200, voice(text, lang)))


def stt(audio: bytes, lang: str | None, recogniser: Callable[[bytes, str], str] | None) -> tuple[int, object]:
    if not audio:
        return 400, {"error": "send the recording as the request body"}
    if lang not in LANGS:
        return 400, {"error": "unknown language"}
    if recogniser is None:
        return 503, {"error": "no speech model configured"}
    return guarded(lambda: (200, {"text": recogniser(audio, lang)}))


def guarded_translation(texts: list[str], source: str, target: str, engine: Callable[..., list], attempts: int = 3) -> list[str | None]:
    """Sentence by sentence, all texts in one batch. A text is None unless all its sentences pass."""
    owners, parts = [], []
    for n, text in enumerate(texts):
        for sentence in textguard.sentences(text):
            owners.append(n)
            parts.append(sentence)
    masked = [textguard.mask(p) for p in parts]
    done: list[str | None] = [None] * len(parts)
    for attempt in range(attempts):
        todo = [i for i, d in enumerate(done) if d is None]
        if not todo:
            break
        for i, out in zip(todo, engine([masked[i][0] for i in todo], source, target, attempt)):
            out = textguard.unmask(out, masked[i][1])
            if out is not None and textguard.same_numbers(parts[i], out) and not textguard.missing_terms(parts[i], out) and not textguard.looks_broken(parts[i], out):
                done[i] = out
    joined: list[str | None] = []
    for n in range(len(texts)):
        mine = [d for o, d in zip(owners, done) if o == n]
        joined.append(None if not mine or None in mine else " ".join(mine))  # type: ignore[arg-type]
    return joined


def translate(payload: object, engine: Callable[..., list] | None, model: str | None = None) -> tuple[int, object]:
    """`translations[i]` is null when no attempt kept the numbers and terms of `texts[i]`."""
    if not isinstance(payload, dict):
        return 400, {"error": "send a JSON object"}
    texts, source, target = payload.get("texts"), payload.get("source", "en"), payload.get("target")
    if not isinstance(texts, list) or not texts or len(texts) > MAX_TEXTS or not all(isinstance(t, str) and 0 < len(t) <= MAX_TRANSLATE_CHARS for t in texts):
        return 400, {"error": f"texts must be 1 to {MAX_TEXTS} strings of at most {MAX_TRANSLATE_CHARS} characters"}
    if source not in LANGS or target not in LANGS or source == target:
        return 400, {"error": "source and target must be two different languages"}
    if engine is None:
        return 503, {"error": "no translation model configured"}
    return guarded(lambda: (200, {"model": model, "translations": guarded_translation(texts, source, target, engine)}))


def status(models: Backends) -> dict:
    return {"tts": models.tts_languages(), "stt": models.stt_languages(), "models": models.models}


def authorised(header: str | None, token: str | None) -> bool:
    return not token or hmac.compare_digest(header or "", f"Bearer {token}")


def handler(models: Backends, token: str | None) -> type[BaseHTTPRequestHandler]:
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def reply(self, code: int, body: object) -> None:
            kind = "audio/wav" if isinstance(body, bytes) else "application/json"
            raw = body if isinstance(body, bytes) else json.dumps(body).encode("utf-8")
            self.send_response(code)
            self.send_header("Content-Type", kind)
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)

        def do_GET(self) -> None:
            path = urlsplit(self.path).path
            if path == "/healthz":
                return self.reply(200, {"status": "ok"})
            if path != "/v1/status":
                return self.reply(404, {"error": "not found"})
            if not authorised(self.headers.get("Authorization"), token):
                return self.reply(401, {"error": "bad token"})
            self.reply(200, status(models))

        def do_POST(self) -> None:
            url = urlsplit(self.path)
            if url.path not in ("/v1/embed", "/v1/tts", "/v1/stt", "/v1/translate"):
                self.close_connection = True
                return self.reply(404, {"error": "not found"})
            if not authorised(self.headers.get("Authorization"), token):
                self.close_connection = True  # the body is unread, so this connection cannot be reused
                return self.reply(401, {"error": "bad token"})
            body = self.body(MAX_AUDIO if url.path == "/v1/stt" else MAX_BODY)
            if body is None:
                self.close_connection = True
                return self.reply(413, {"error": "too large or malformed"})
            if url.path == "/v1/stt":
                lang = parse_qs(url.query).get("lang", [None])[0]
                return self.reply(*self.run(lambda: stt(body, lang, models.stt)))
            try:
                payload = json.loads(body)
            except ValueError:
                return self.reply(400, {"error": "send valid JSON"})
            if url.path == "/v1/embed":
                self.reply(*embed(payload, models.embed))  # short, and a question is waiting: never behind speech
            elif url.path == "/v1/tts":
                self.reply(*self.run(lambda: tts(payload, models.tts)))
            else:
                self.reply(*self.run(lambda: translate(payload, models.translate, models.models.get("translate"))))

        def body(self, limit: int) -> bytes | None:
            """The request body, with a length or chunked (the JDK client sends chunked)."""
            try:
                if "chunked" in (self.headers.get("Transfer-Encoding") or "").lower():
                    data = bytearray()
                    while True:
                        size = int(self.rfile.readline(64).split(b";")[0], 16)
                        if size == 0:
                            while self.rfile.readline(1024).strip():
                                pass  # trailers
                            return bytes(data)
                        if len(data) + size > limit:
                            return None
                        data += self.rfile.read(size)
                        self.rfile.readline(8)
                length = int(self.headers.get("Content-Length") or 0)
            except ValueError:
                return None
            if length < 0:
                return None
            if length > limit:
                self.drain(length)
                return None
            return self.rfile.read(length)

        def drain(self, length: int) -> None:
            """Reads and drops an oversized body, so the client gets the 413 and not a broken pipe."""
            left = min(length, MAX_DRAIN)
            while left > 0:
                got = self.rfile.read(min(left, 65536))
                if not got:
                    return
                left -= len(got)

        @staticmethod
        def run(work: Callable[[], tuple[int, object]]) -> tuple[int, object]:
            with models.gate:
                return work()

        def log_message(self, *args: object) -> None:
            pass  # texts can be people's questions; nothing is logged

    return Handler


def real_backends() -> Backends:
    embedder = backends.E5Embedder()
    voices = backends.MmsTts()
    whisper = backends.WhisperStt()
    name = os.environ.get("ML_TRANSLATE_MODEL", "sarvam")
    engine = backends.translator(name)
    return Backends(
        embed=embedder, tts=voices, stt=whisper, translate=engine,
        tts_languages=voices.languages, stt_languages=lambda: whisper.langs,
        models={"embed": embedder.model_id, "tts": "facebook/mms-tts", "stt": f"faster-whisper-{whisper.size}", "translate": engine.model_id},
    )


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=8090)
    args = ap.parse_args()
    server = ThreadingHTTPServer((args.host, args.port), handler(real_backends(), os.environ.get("ML_SERVICE_TOKEN")))
    server.serve_forever()


if __name__ == "__main__":
    main()
