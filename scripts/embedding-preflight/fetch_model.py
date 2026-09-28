"""Explicit data-only download to ignored tmp; fixed upstream bytes, never execute/load them."""
import argparse
import concurrent.futures
import hashlib
from pathlib import Path
import time
import urllib.request
import uuid

from assets import HERE, LOCK, verify, verify_model
from download_cache import assemble

parser = argparse.ArgumentParser()
parser.add_argument("--mirror", action="store_true", help="Use Qwen's ModelScope mirror, accepting only the frozen HF digest")
args = parser.parse_args()
root = HERE.parents[1]
folder = root / "tmp/dev-019/frozen-model"
folder.mkdir(parents=True, exist_ok=True)
parts = folder / "weight-parts" / LOCK["files"]["model.safetensors"]["sha256"]
parts.mkdir(parents=True, exist_ok=True)
deadline = time.monotonic() + 1800
base = ("https://modelscope.cn/models/" + LOCK["repository"] + "/resolve/master/" if args.mirror else
        "https://huggingface.co/" + LOCK["repository"] + "/resolve/" + LOCK["revision"] + "/")


def read(name, maximum, headers=None):
    if time.monotonic() >= deadline:
        raise TimeoutError("download deadline exceeded")
    req = urllib.request.Request(base + name + "?preflight=" + uuid.uuid4().hex, headers=headers or {})
    with urllib.request.urlopen(req, timeout=90) as response:
        raw = response.read(maximum + 1)
        if len(raw) > maximum:
            raise ValueError("download too large")
        return raw, response.status, response.headers.get("Content-Range")


for name in ("config.json", "tokenizer.json", "tokenizer_config.json", "vocab.json", "merges.txt"):
    target = folder / name
    entry = LOCK["files"][name]
    try:
        verify(target.read_bytes(), entry)
    except (FileNotFoundError, ValueError):
        raw, _, _ = read(name, entry["bytes"])
        verify(raw, entry)
        target.write_bytes(raw)

entry = LOCK["files"]["model.safetensors"]
total, size = entry["bytes"], 32 * 1024 * 1024
target = folder / "model.safetensors"
valid = False
if target.exists() and target.stat().st_size == total:
    with target.open("rb") as stream:
        valid = hashlib.file_digest(stream, "sha256").hexdigest() == entry["sha256"]


def block(offset):
    end = min(total - 1, offset + size - 1)
    part = parts / str(offset)
    # Previous blocks may resume, but the assembled file is always re-hashed before use.
    if part.exists() and part.stat().st_size == end - offset + 1:
        return
    for attempt in range(3):
        try:
            raw, code, content_range = read("model.safetensors", end - offset + 1,
                                            {"Range": f"bytes={offset}-{end}"})
            if code != 206 or content_range != f"bytes {offset}-{end}/{total}" or len(raw) != end - offset + 1:
                raise ValueError("invalid upstream range")
            part.write_bytes(raw)
            return
        except (OSError, ValueError):
            if attempt == 2:
                raise


if not valid:
    offsets = list(range(0, total, size))
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as executor:
        for number, result in enumerate(concurrent.futures.as_completed([executor.submit(block, o) for o in offsets]), 1):
            result.result()
            print("weight block", number, "/", len(offsets), flush=True)
    assemble(target, parts, offsets, entry)
verify_model(folder)
print("verified frozen model in tmp/dev-019/frozen-model; not loaded")
