"""Frozen data contract, shared by supervisor and known local inference worker."""
import hashlib
import importlib.metadata
import json
from pathlib import Path
import sys

PREFLIGHT = Path(__file__).resolve().parents[1] / "embedding-preflight"
sys.path.insert(0, str(PREFLIGHT))
from assets import LOCK, verify_model, bundled_tokenizer

SPEC = json.loads((PREFLIGHT / "environment.json").read_text())["spec"]
CONTRACT = (SPEC["id"] + "|" + SPEC["revision"] + "|" + LOCK["files"]["model.safetensors"]["sha256"]
            + "|" + LOCK["files"]["tokenizer.json"]["sha256"]
            + "|tokenizers=0.22.1|transformers=4.57.1|torch=2.8.0|safetensors=0.6.2|cpu-f32-sdpa-mask-4|last-nonpad-l2|nfc-eos151643|6000/4/6000/6000")
FINGERPRINT = hashlib.sha256(CONTRACT.encode()).hexdigest()


def metadata():
    return {"spec": SPEC["id"], "fingerprint": FINGERPRINT}


def tokenizer():
    for name, version in (("tokenizers", "0.22.1"), ("transformers", "4.57.1"), ("torch", "2.8.0"), ("safetensors", "0.6.2")):
        if importlib.metadata.version(name).split("+")[0] != version:
            raise RuntimeError("frozen runtime mismatch")
    from tokenizers import Tokenizer
    value = Tokenizer.from_str(bundled_tokenizer())
    for case in json.loads((PREFLIGHT / "fixtures/tokenizer-cases.json").read_text(encoding="utf-8"))["cases"]:
        text = (SPEC["query_prefix"] if case["role"] == "query" else "") + case["text"]
        if value.encode(text).ids != case["ids"]:
            raise RuntimeError("tokenizer golden mismatch")
    return value


def inputs(body):
    if type(body) is not dict or body.get("spec") != SPEC["id"] or set(body) - {"spec", "input", "operation_id"}:
        raise ValueError("invalid contract")
    texts = body.get("input")
    if type(texts) is not list or not 1 <= len(texts) <= 4 or any(type(t) is not str or not t.strip() or len(t) > 24000 for t in texts):
        raise ValueError("invalid input")
    return texts


def counts(tokenizer, texts, *, inference=False):
    result = [len(tokenizer.encode(text).ids) for text in texts]
    if any(n < 1 or n > 6000 for n in result):
        raise ValueError("token limit")
    if inference and (sum(result) > 6000 or max(result) * len(result) > 6000):
        raise ValueError("batch limit")
    return result
