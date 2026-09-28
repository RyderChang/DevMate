"""Preflight response oracle, not a production Gateway or provider adapter."""
import json
import math
import struct


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate field")
        result[key] = value
    return result


def _integer(value):
    return type(value) is int and 0 <= value <= 2**63 - 1


def validate(raw, *, model, inputs, dimensions, tokens, max_bytes=1048576):
    if len(raw) > max_bytes:
        raise ValueError("response too large")
    response = json.loads(raw.decode("utf-8", errors="strict"), object_pairs_hook=_unique_object,
                          parse_constant=lambda value: (_ for _ in ()).throw(ValueError("non-JSON number")))
    if not isinstance(response, dict) or response.get("object") != "list" or response.get("model") != model:
        raise ValueError("model or response type")
    rows = response.get("data")
    if not isinstance(rows, list) or len(rows) != inputs:
        raise ValueError("vector count")
    ordered = {}
    for row in rows:
        if not isinstance(row, dict):
            raise ValueError("vector row")
        index = row.get("index")
        vector = row.get("embedding")
        if not _integer(index) or index >= inputs or index in ordered or row.get("object") != "embedding":
            raise ValueError("vector index")
        if not isinstance(vector, list) or len(vector) != dimensions:
            raise ValueError("dimension")
        if any(type(value) not in (int, float) or abs(value) > 3.4028234663852886e38
               or not math.isfinite(value) for value in vector):
            raise ValueError("invalid scalar")
        floats = [struct.unpack("!f", struct.pack("!f", value))[0] for value in vector]
        norm = math.hypot(*floats)
        if not math.isfinite(norm) or abs(norm - 1.0) > 0.001:
            raise ValueError("invalid norm")
        ordered[index] = vector
    usage = response.get("usage", {})
    if not isinstance(usage, dict) or not _integer(usage.get("total_tokens")) or usage["total_tokens"] != tokens:
        raise ValueError("usage")
    if "prompt_tokens" in usage and (not _integer(usage["prompt_tokens"]) or usage["prompt_tokens"] != tokens):
        raise ValueError("prompt usage")
    return [ordered[index] for index in range(inputs)]
