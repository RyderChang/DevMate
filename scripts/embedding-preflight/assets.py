"""Verify pinned upstream assets before using model/tokenizer data."""
import gzip
import hashlib
import json
from pathlib import Path

HERE = Path(__file__).resolve().parent
ASSETS = HERE / "fixtures/qwen"
LOCK = json.loads((ASSETS / "model.lock.json").read_text())


def verify(raw, entry):
    if len(raw) != entry["bytes"] or hashlib.sha256(raw).hexdigest() != entry["sha256"]:
        raise ValueError("frozen model asset mismatch")


def bundled_tokenizer():
    for name, bundled in LOCK["bundled"].items():
        raw = (ASSETS / bundled["path"]).read_bytes()
        if bundled.get("gzip"):
            verify(raw, bundled)
            raw = gzip.decompress(raw)
        verify(raw, LOCK["files"][name])
    return gzip.decompress((ASSETS / "tokenizer.json.gz").read_bytes()).decode("utf-8")


def verify_model(folder):
    for name in ("config.json", "tokenizer.json", "tokenizer_config.json", "vocab.json", "merges.txt", "model.safetensors"):
        path = folder / name
        entry = LOCK["files"][name]
        if path.stat().st_size != entry["bytes"]:
            raise ValueError("frozen model file size mismatch")
        with path.open("rb") as stream:
            if hashlib.file_digest(stream, "sha256").hexdigest() != entry["sha256"]:
                raise ValueError("frozen model file digest mismatch")
