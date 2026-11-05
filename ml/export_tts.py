#!/usr/bin/env python3
"""One-off: export facebook/mms-tts-* to ONNX, so the service itself needs no torch.

  docker compose --profile ml-export run --rm ml-export [hin ben ...]

Writes MODELS/mms-tts/<iso3>/{model.onnx,vocab.json}. Existing voices are skipped.
Licence of the weights: CC-BY-NC 4.0 (see README.md).
"""
import json
import os
import shutil
import sys
import tempfile
from pathlib import Path

from huggingface_hub import hf_hub_download
from optimum.exporters.onnx import main_export

VOICES = ("eng", "hin", "ben", "tam", "tel", "mar", "guj", "kan", "mal", "pan")
MODELS = Path(os.environ.get("ML_MODELS_DIR", "/models"))


def export(iso: str) -> None:
    target = MODELS / "mms-tts" / iso
    if (target / "model.onnx").exists():
        print(iso, "already exported")
        return
    repo = f"facebook/mms-tts-{iso}"
    settings = json.loads(Path(hf_hub_download(repo, "tokenizer_config.json")).read_text(encoding="utf-8"))
    if settings.get("is_uroman") or settings.get("phonemize"):
        raise SystemExit(f"{repo} needs a text front end this service does not have")
    with tempfile.TemporaryDirectory() as tmp:
        # The voice samples durations at random, so output shapes differ from run to run and the
        # exporter's comparison with torch cannot pass. The round trip through Whisper checks it instead.
        main_export(model_name_or_path=repo, output=tmp, task="text-to-audio", device="cpu", do_validation=False)
        target.mkdir(parents=True, exist_ok=True)
        shutil.copy(hf_hub_download(repo, "vocab.json"), target / "vocab.json")
        shutil.copy(Path(tmp) / "model.onnx", target / "model.onnx.part")
        (target / "model.onnx.part").replace(target / "model.onnx")
    print(iso, "exported", (target / "model.onnx").stat().st_size // 1_000_000, "MB", flush=True)


if __name__ == "__main__":
    for voice in sys.argv[1:] or VOICES:
        export(voice)
