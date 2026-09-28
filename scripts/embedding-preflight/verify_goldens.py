"""Reproduce goldens with official fast and independent slow tokenizers; never rewrite them."""
import argparse
import importlib.metadata
import json
from pathlib import Path
import unicodedata

from assets import HERE, verify_model

parser = argparse.ArgumentParser()
parser.add_argument("model_dir", type=Path)
args = parser.parse_args()
if importlib.metadata.version("transformers") != "4.57.1":
    raise RuntimeError("wrong official tokenizer wrapper")
verify_model(args.model_dir)
from transformers import AutoTokenizer

fast = AutoTokenizer.from_pretrained(args.model_dir, local_files_only=True, trust_remote_code=False)
slow = AutoTokenizer.from_pretrained(args.model_dir, local_files_only=True, trust_remote_code=False, use_fast=False)
spec = json.loads((HERE / "environment.json").read_text())["spec"]
cases = json.loads((HERE / "fixtures/tokenizer-cases.json").read_text())["cases"]
for case in cases:
    text = (spec["query_prefix"] if case["role"] == "query" else "") + case["text"]
    actual = fast.encode(text, add_special_tokens=True)
    independent = slow.encode(unicodedata.normalize("NFC", text), add_special_tokens=False) + [151643]
    if actual != independent or actual != case["ids"] or len(actual) != case["tokens"]:
        raise ValueError("official golden differs: " + case["name"])
fixture = json.loads((HERE / "fixtures/embedding-request.json").read_text())
counts = [len(fast.encode(text)) for text in fixture["request"]["input"]]
if counts != fixture["token_counts"] or sum(counts) != fixture["expected_tokens"]:
    raise ValueError("HTTP token fixture differs")
print(json.dumps({"official_cross_checked_cases": len(cases), "http_tokens": sum(counts)}))
