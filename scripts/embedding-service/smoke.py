"""Actual frozen Linux service smoke including full limits; only aggregate metadata is printed."""
import argparse
import json
import time
import urllib.request
import uuid
from spec import SPEC, FINGERPRINT
from contract import validate

parser = argparse.ArgumentParser()
parser.add_argument("--origin", default="http://127.0.0.1:8091")
args = parser.parse_args()
if not args.origin.startswith("http://127.0.0.1:"):
    raise ValueError("loopback origin required")


def call(path, data=None):
    request = urllib.request.Request(args.origin+path, data=None if data is None else json.dumps(data).encode(), headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=305) as response:
        raw = response.read(1048577)
    if len(raw) > 1048576:
        raise ValueError("response limit")
    value = json.loads(raw)
    if value.get("fingerprint") != FINGERPRINT or value.get("spec") != SPEC["id"]:
        raise ValueError("fingerprint mismatch")
    return raw, value


readiness = time.monotonic()+90
while True:
    try:
        call("/spec")
        break
    except OSError:
        if time.monotonic()>=readiness:
            raise
        time.sleep(1)
for mode, texts, expected in (
    ("short", ["项目文档按用户和项目隔离。", "Spring transaction rollback"], None),
    ("6000", ["x "*5998+"x"], [6000]),
    ("4x1500", ["x "*1498+"x"]*4, [1500]*4),
):
    _, counted = call("/tokenize", {"spec": SPEC["id"], "input": texts})
    counts = counted["counts"]
    if expected is not None and counts != expected:
        raise ValueError("boundary count mismatch")
    operation = str(uuid.uuid4())
    started = time.monotonic()
    raw, response = call("/embeddings", {"spec": SPEC["id"], "operation_id": operation, "input": texts})
    validate(raw, model=SPEC["model"], inputs=len(texts), dimensions=1024, tokens=sum(counts))
    _, ended = call("/operations/"+operation)
    if ended["state"] != "SUCCEEDED":
        raise ValueError("operation completion mismatch")
    print(json.dumps({"mode": mode, "tokens": counts, "dimensions": 1024, "unit_norm": True, "seconds": round(time.monotonic()-started, 3), "fingerprint": FINGERPRINT}), flush=True)
