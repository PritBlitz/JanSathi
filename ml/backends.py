"""The real models. Every heavy import is inside a function: the service starts in a moment, tests
need no packages, and a model loads only when first asked for.

Weights live under ML_MODELS_DIR. Nothing is written anywhere else, and audio stays in memory.
"""
from __future__ import annotations

import io
import json
import os
import threading
import time
import urllib.error
import urllib.request
import wave
from collections import OrderedDict
from pathlib import Path
from typing import Callable, Sequence

import textguard

MODELS = Path(os.environ.get("ML_MODELS_DIR", Path(__file__).with_name(".models")))
THREADS = int(os.environ.get("ML_THREADS", "4"))
HF_TOKEN = os.environ.get("HF_TOKEN") or None
LANGS = ("en", "hi", "bn", "ta", "te", "mr", "gu", "kn", "ml", "pa")


class Unavailable(Exception):
    """The model or its files are missing: the service answers 503, never a made-up result."""


class BadInput(Exception):
    """The caller sent something the model cannot use: the service answers 422."""


def fetch(repo: str, dest: str, files: Sequence[str]) -> Path:
    """Downloads files from Hugging Face once, into MODELS/dest."""
    root = MODELS / dest
    for name in files:
        target = root / name
        if target.exists():
            continue
        target.parent.mkdir(parents=True, exist_ok=True)
        request = urllib.request.Request(f"https://huggingface.co/{repo}/resolve/main/{name}")
        if HF_TOKEN:
            request.add_header("Authorization", f"Bearer {HF_TOKEN}")
        part = target.with_name(target.name + ".part")
        try:
            with urllib.request.urlopen(request, timeout=60) as reply, part.open("wb") as out:
                while chunk := reply.read(1 << 20):
                    out.write(chunk)
        except (urllib.error.URLError, OSError) as e:
            part.unlink(missing_ok=True)
            raise Unavailable(f"could not fetch {repo}/{name}: {e}") from e
        part.replace(target)
    return root


def ort_session(path: Path):
    import onnxruntime as ort

    options = ort.SessionOptions()
    options.intra_op_num_threads = THREADS
    options.inter_op_num_threads = 1
    return ort.InferenceSession(str(path), options, providers=["CPUExecutionProvider"])


class E5Embedder:
    """intfloat/multilingual-e5-small, quantised ONNX. Unit vectors, 384 dimensions."""

    repo = "Xenova/multilingual-e5-small"
    model_id = "intfloat/multilingual-e5-small"
    batch = 16

    def __init__(self) -> None:
        self.lock = threading.Lock()
        self.session = None
        self.tokenizer = None

    def load(self) -> None:
        with self.lock:
            if self.session is not None:
                return
            from tokenizers import Tokenizer

            root = fetch(self.repo, "e5-small", ["tokenizer.json", "config.json", "onnx/model_quantized.onnx"])
            tokenizer = Tokenizer.from_file(str(root / "tokenizer.json"))
            tokenizer.enable_truncation(max_length=512)
            tokenizer.enable_padding()
            self.tokenizer = tokenizer
            self.session = ort_session(root / "onnx/model_quantized.onnx")
            self.inputs = {i.name for i in self.session.get_inputs()}

    def __call__(self, texts: Sequence[str], kind: str) -> list:
        import numpy as np

        self.load()
        prefix = "query: " if kind == "query" else "passage: "  # the model was trained with these
        order = sorted(range(len(texts)), key=lambda i: len(texts[i]))
        vectors: list = [None] * len(texts)
        for start in range(0, len(order), self.batch):
            ids = order[start:start + self.batch]
            encoded = self.tokenizer.encode_batch([prefix + texts[i] for i in ids])
            mask = np.array([e.attention_mask for e in encoded], dtype=np.int64)
            feed = {"input_ids": np.array([e.ids for e in encoded], dtype=np.int64), "attention_mask": mask}
            if "token_type_ids" in self.inputs:
                feed["token_type_ids"] = np.zeros_like(mask)
            hidden = self.session.run(None, feed)[0]
            weights = mask[:, :, None].astype(np.float32)
            pooled = (hidden * weights).sum(axis=1) / np.clip(weights.sum(axis=1), 1e-9, None)
            pooled /= np.clip(np.linalg.norm(pooled, axis=1, keepdims=True), 1e-12, None)
            for row, i in enumerate(ids):
                vectors[i] = pooled[row].tolist()
        return vectors


ISO3 = {"en": "eng", "hi": "hin", "bn": "ben", "ta": "tam", "te": "tel", "mr": "mar", "gu": "guj", "kn": "kan", "ml": "mal", "pa": "pan"}


def spell_number(value: int, lang: str) -> str | None:
    """Number words where we have them right; None means digit by digit, clumsy but never wrong.
    num2words has no Hindi, Tamil, Marathi, Gujarati, Malayalam or Punjabi, and gets lakhs wrong in
    Telugu and Kannada ("one one lakh").
    """
    if lang == "hi":
        return textguard.hindi_number(value)
    if lang in ("en", "bn"):
        try:
            from num2words import num2words

            return num2words(value, lang="en_IN" if lang == "en" else lang)
        except (ImportError, NotImplementedError):
            return None
    return None


class MmsTts:
    """facebook/mms-tts-*, exported to ONNX by export_tts.py. Keeps a few voices in memory."""

    rate = 16000
    keep = int(os.environ.get("ML_TTS_KEEP", "3"))

    def __init__(self) -> None:
        self.lock = threading.Lock()
        self.loaded: OrderedDict = OrderedDict()

    @staticmethod
    def folder(lang: str) -> Path:
        return MODELS / "mms-tts" / ISO3[lang]

    def languages(self) -> list:
        return [l for l in LANGS if (self.folder(l) / "model.onnx").exists()]

    def voice(self, lang: str):
        with self.lock:
            if lang in self.loaded:
                self.loaded.move_to_end(lang)
                return self.loaded[lang]
            folder = self.folder(lang)
            if not (folder / "model.onnx").exists():
                raise Unavailable(f"no voice for {lang}: run ml/export_tts.py")
            vocab = json.loads((folder / "vocab.json").read_text(encoding="utf-8"))
            voice = (ort_session(folder / "model.onnx"), vocab)
            self.loaded[lang] = voice
            while len(self.loaded) > self.keep:
                self.loaded.popitem(last=False)
            return voice

    @staticmethod
    def token_ids(text: str, vocab: dict) -> list:
        """What VitsTokenizer does for these voices: lower case, known characters, blanks between."""
        ids = [vocab[c] for c in text.lower() if c in vocab]
        spaced = [0] * (len(ids) * 2 + 1)
        spaced[1::2] = ids
        return spaced

    def __call__(self, text: str, lang: str) -> bytes:
        import numpy as np

        session, vocab = self.voice(lang)
        pieces = []
        pause = np.zeros(int(self.rate * 0.18), dtype=np.float32)
        for sentence in textguard.sentences(textguard.for_speech(text, lang, spell_number)):
            ids = self.token_ids(sentence, vocab)
            if len(ids) < 3:
                continue
            feed = {"input_ids": np.array([ids], dtype=np.int64)}
            if "attention_mask" in {i.name for i in session.get_inputs()}:
                feed["attention_mask"] = np.ones_like(feed["input_ids"])
            pieces += [session.run(None, feed)[0].reshape(-1), pause]
        if not pieces:
            raise BadInput("nothing in this text can be spoken in this language")
        audio = np.clip(np.concatenate(pieces), -1, 1)
        out = io.BytesIO()
        with wave.open(out, "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(self.rate)
            w.writeframes((audio * 32767).astype("<i2").tobytes())
        return out.getvalue()


class WhisperStt:
    """faster-whisper (CTranslate2, int8). The size comes from ML_STT_MODEL."""

    max_seconds = 60

    def __init__(self) -> None:
        self.size = os.environ.get("ML_STT_MODEL", "small")
        self.beam = int(os.environ.get("ML_STT_BEAM", "1"))
        # Only languages where a round trip came back readable (ml/README.md). In the rest Whisper
        # small writes nonsense, sometimes in another script: worse than asking people to type.
        self.langs = [l for l in os.environ.get("ML_STT_LANGS", "en,hi,ta").split(",") if l in LANGS]
        self.lock = threading.Lock()
        self.model = None

    def load(self):
        with self.lock:
            if self.model is None:
                from faster_whisper import WhisperModel

                root = fetch(f"Systran/faster-whisper-{self.size}", f"whisper-{self.size}", ["config.json", "model.bin", "tokenizer.json", "vocabulary.txt"])
                self.model = WhisperModel(str(root), device="cpu", compute_type="int8", cpu_threads=THREADS)
            return self.model

    def __call__(self, audio: bytes, lang: str) -> str:
        import av

        if lang not in self.langs:
            raise BadInput("speech recognition is not offered in this language")
        model = self.load()
        try:
            segments, info = model.transcribe(
                io.BytesIO(audio), language=lang, beam_size=self.beam, condition_on_previous_text=False, vad_filter=True,
                # Whisper small can loop on Bengali; the default retries at higher temperatures then
                # cost a minute. The usual repetition settings are no help: Indic text is byte-level
                # tokens that repeat by nature, and they took Hindi from 16% to 48% character errors.
                temperature=0.0)
        except av.FFmpegError as e:
            raise BadInput("could not decode the audio") from e
        if info.duration > self.max_seconds:
            raise BadInput(f"recordings are limited to {self.max_seconds} seconds")
        return " ".join(s.text.strip() for s in segments).strip()


NLLB_CODES = {"en": "eng_Latn", "hi": "hin_Deva", "bn": "ben_Beng", "ta": "tam_Taml", "te": "tel_Telu", "mr": "mar_Deva", "gu": "guj_Gujr", "kn": "kan_Knda", "ml": "mal_Mlym", "pa": "pan_Guru"}
NLLB_REPOS = {
    "nllb-600m": ("JustFrederik/nllb-200-distilled-600M-ct2-int8", "facebook/nllb-200-distilled-600M"),
    "nllb-1.3b": ("OpenNMT/nllb-200-distilled-1.3B-ct2-int8", "facebook/nllb-200-distilled-1.3B"),
}


class Nllb:
    """NLLB-200 distilled through CTranslate2, int8."""

    def __init__(self, name: str = "nllb-600m") -> None:
        self.name = name
        self.model_id = NLLB_REPOS[name][1]
        self.lock = threading.Lock()
        self.engine = None

    def load(self):
        with self.lock:
            if self.engine is None:
                import ctranslate2
                from tokenizers import Tokenizer

                root = fetch(NLLB_REPOS[self.name][0], self.name, ["config.json", "model.bin", "tokenizer.json"])
                # The tools pick their vocabulary file by name; both spellings exist on the Hub.
                for vocabulary in ("shared_vocabulary.json", "shared_vocabulary.txt"):
                    try:
                        fetch(NLLB_REPOS[self.name][0], self.name, [vocabulary])
                        break
                    except Unavailable:
                        continue
                self.tokenizer = Tokenizer.from_file(str(root / "tokenizer.json"))
                self.engine = ctranslate2.Translator(str(root), device="cpu", compute_type="int8", intra_threads=THREADS)
            return self.engine

    def __call__(self, texts: Sequence[str], source: str, target: str, attempt: int = 0) -> list:
        engine = self.load()
        batch = [[NLLB_CODES[source]] + self.tokenizer.encode(t, add_special_tokens=False).tokens + ["</s>"] for t in texts]
        results = engine.translate_batch(
            batch,
            target_prefix=[[NLLB_CODES[target]]] * len(texts),
            beam_size=4 if attempt == 0 else 8,
            repetition_penalty=1.1 if attempt == 0 else 1.0,
            no_repeat_ngram_size=4,
            max_decoding_length=256,
            max_batch_size=16,
        )
        out = []
        for result in results:
            tokens = [t for t in result.hypotheses[0][1:] if t not in ("</s>", "<s>")]
            ids = [self.tokenizer.token_to_id(t) for t in tokens]
            out.append(self.tokenizer.decode([i for i in ids if i is not None], skip_special_tokens=True))
        return out


SARVAM_NAMES = {"en": "English", "hi": "Hindi", "bn": "Bengali", "ta": "Tamil", "te": "Telugu", "mr": "Marathi", "gu": "Gujarati", "kn": "Kannada", "ml": "Malayalam", "pa": "Punjabi"}


class Sarvam:
    """sarvamai/sarvam-translate served by llama.cpp (ML_LLAMA_URL), so this image stays small."""

    model_id = "sarvamai/sarvam-translate"

    def __init__(self) -> None:
        self.url = os.environ.get("ML_LLAMA_URL", "http://llama:8091").rstrip("/")
        self.parallel = int(os.environ.get("ML_LLAMA_PARALLEL", "4"))

    def one(self, text: str, target: str, attempt: int) -> str:
        # The published GGUFs carry no chat template (llama.cpp falls back to ChatML and the model
        # then writes nonsense), so the Gemma turn format is spelt out here.
        prompt = (f"<start_of_turn>user\nTranslate the text below to {SARVAM_NAMES[target]}.\n\n{text}<end_of_turn>\n"
                  "<start_of_turn>model\n")
        body = {"prompt": prompt, "temperature": 0.01 if attempt == 0 else 0.3, "n_predict": 96 + 3 * len(text), "stop": ["<end_of_turn>"]}
        request = urllib.request.Request(self.url + "/completion", json.dumps(body).encode(), {"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(request, timeout=900) as reply:
                return json.load(reply)["content"].strip()
        except (urllib.error.URLError, OSError, KeyError, ValueError) as e:
            raise Unavailable(f"the llama.cpp server failed: {e}") from e

    def __call__(self, texts: Sequence[str], source: str, target: str, attempt: int = 0) -> list:
        from concurrent.futures import ThreadPoolExecutor

        with ThreadPoolExecutor(self.parallel) as pool:
            return list(pool.map(lambda t: self.one(t, target, attempt), texts))


INDIC_CODES = {"en": "eng_Latn", "hi": "hin_Deva", "bn": "ben_Beng", "ta": "tam_Taml", "te": "tel_Telu", "mr": "mar_Deva", "gu": "guj_Gujr", "kn": "kan_Knda", "ml": "mal_Mlym", "pa": "pan_Guru"}


class IndicTrans2:
    """ai4bharat/indictrans2-en-indic-dist-200M. Gated, so it needs HF_TOKEN after accepting the
    terms, and torch, so it needs requirements-indictrans2.txt on top of the image. Not run here:
    no token was available when this was written.
    """

    model_id = "ai4bharat/indictrans2-en-indic-dist-200M"

    def __init__(self) -> None:
        self.lock = threading.Lock()
        self.model = None

    def load(self):
        with self.lock:
            if self.model is None:
                if not HF_TOKEN:
                    raise Unavailable("IndicTrans2 is gated: set HF_TOKEN")
                try:
                    from IndicTransToolkit.processor import IndicProcessor
                    from transformers import AutoModelForSeq2SeqLM, AutoTokenizer
                except ImportError as e:
                    raise Unavailable("pip install -r ml/requirements-indictrans2.txt") from e
                self.tokenizer = AutoTokenizer.from_pretrained(self.model_id, trust_remote_code=True, token=HF_TOKEN, cache_dir=MODELS / "hf")
                self.model = AutoModelForSeq2SeqLM.from_pretrained(self.model_id, trust_remote_code=True, token=HF_TOKEN, cache_dir=MODELS / "hf")
                self.processor = IndicProcessor(inference=True)
            return self.model

    def __call__(self, texts: Sequence[str], source: str, target: str, attempt: int = 0) -> list:
        import torch

        model = self.load()
        src, tgt = INDIC_CODES[source], INDIC_CODES[target]
        batch = self.processor.preprocess_batch(list(texts), src_lang=src, tgt_lang=tgt)
        inputs = self.tokenizer(batch, truncation=True, padding="longest", return_tensors="pt")
        with torch.no_grad():
            generated = model.generate(**inputs, num_beams=5, max_length=256)
        decoded = self.tokenizer.batch_decode(generated, skip_special_tokens=True, clean_up_tokenization_spaces=True)
        return self.processor.postprocess_batch(decoded, lang=tgt)


Translator = Callable[..., list]


def translator(name: str) -> Translator:
    if name in NLLB_REPOS:
        return Nllb(name)
    if name == "sarvam":
        return Sarvam()
    if name == "indictrans2":
        return IndicTrans2()
    raise SystemExit(f"Unknown ML_TRANSLATE_MODEL: {name}")


def timed(work: Callable, *args):
    start = time.perf_counter()
    return work(*args), time.perf_counter() - start
