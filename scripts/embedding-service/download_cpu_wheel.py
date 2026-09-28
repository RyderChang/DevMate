"""Bounded official CPU wheel download with frozen size/hash and recoverable block cache."""
import concurrent.futures
import hashlib
import json
from pathlib import Path
import time
import urllib.request

from spec import PREFLIGHT
from download_cache import assemble

ROOT = Path(__file__).resolve().parents[2]
lock = json.loads((Path(__file__).parent / "runtime-linux.lock.json").read_text())
entry = next(wheel for wheel in lock["wheels"] if wheel["name"] == "torch")
entry["bytes"] = 183917315
folder = ROOT / "tmp/dev-020/wheels"
folder.mkdir(parents=True, exist_ok=True)
target = folder / entry["filename"]
parts = folder / entry["sha256"]
parts.mkdir(exist_ok=True)
deadline = time.monotonic()+900
size = 1024*1024
origin = "https://download.pytorch.org/whl/cpu/torch-2.8.0%2Bcpu-cp313-cp313-manylinux_2_28_x86_64.whl"
if target.exists() and target.stat().st_size == entry["bytes"]:
    with target.open("rb") as stream:
        if hashlib.file_digest(stream, "sha256").hexdigest() == entry["sha256"]:
            print("Frozen CPU wheel already verified")
            raise SystemExit(0)


def block(offset):
    end = min(entry["bytes"]-1, offset+size-1)
    file = parts / str(offset)
    if file.exists() and file.stat().st_size == end-offset+1:
        return
    for attempt in range(3):
        if time.monotonic() >= deadline:
            raise TimeoutError("wheel download deadline")
        try:
            request = urllib.request.Request(origin+"?block="+str(offset), headers={"Range": f"bytes={offset}-{end}"})
            with urllib.request.urlopen(request, timeout=60) as response:
                raw = response.read(end-offset+2)
                if response.status != 206 or response.headers.get("Content-Range") != f"bytes {offset}-{end}/{entry['bytes']}" or len(raw) != end-offset+1:
                    raise ValueError("invalid official range")
            file.write_bytes(raw)
            return
        except (OSError, ValueError):
            if attempt == 2:
                raise


offsets = list(range(0, entry["bytes"], size))
with concurrent.futures.ThreadPoolExecutor(max_workers=8) as executor:
    for number, result in enumerate(concurrent.futures.as_completed([executor.submit(block, offset) for offset in offsets]), 1):
        result.result()
        if number % 25 == 0:
            print("Verified range", number, "/", len(offsets), flush=True)
assemble(target, parts, offsets, entry)
print("Official CPU wheel frozen size and SHA-256 verified", flush=True)
