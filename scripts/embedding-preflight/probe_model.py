"""Optional offline CPU smoke, not a model server or a retrieval-quality benchmark."""
import argparse
import ctypes
import importlib.metadata
import json
import os
from pathlib import Path
import platform
import threading
import time

from assets import HERE, verify_model

parser = argparse.ArgumentParser()
parser.add_argument("model_dir", type=Path)
mode = parser.add_mutually_exclusive_group()
mode.add_argument("--boundary", action="store_true")
mode.add_argument("--batch-boundary", action="store_true")
parser.add_argument("--explicit-causal-mask", action="store_true", help="Avoid CPU GQA math fallback using a boolean causal/padding mask")
args = parser.parse_args()
os.environ["HF_HUB_OFFLINE"] = "1"
os.environ["TRANSFORMERS_OFFLINE"] = "1"
os.environ["TOKENIZERS_PARALLELISM"] = "false"
for package, version in (("transformers", "4.57.1"), ("torch", "2.8.0"), ("tokenizers", "0.22.1"), ("safetensors", "0.6.2")):
    if importlib.metadata.version(package) != version:
        raise RuntimeError("wrong inference runtime: " + package)
verify_model(args.model_dir)
import torch
import torch.nn.functional as F
from transformers import AutoModel, AutoTokenizer

torch.set_num_threads(4)
torch.set_num_interop_threads(1)
spec = json.loads((HERE / "environment.json").read_text())["spec"]
tokenizer = AutoTokenizer.from_pretrained(args.model_dir, padding_side="left", local_files_only=True, trust_remote_code=False)
started = time.monotonic()
model = AutoModel.from_pretrained(args.model_dir, local_files_only=True, trust_remote_code=False,
                                dtype=torch.float32, attn_implementation="sdpa").to("cpu").eval()
load_seconds = time.monotonic() - started
texts = (["x " * 5998 + "x"] if args.boundary else
         ["x " * 1498 + "x"] * 4 if args.batch_boundary else
         ["项目文档按用户和项目隔离。", "@Transactional public void save(Project p) { mapper.insert(p); }",
          spec["query_prefix"] + "Spring 事务失败如何回滚？"])
counts = [len(tokenizer.encode(text, add_special_tokens=True)) for text in texts]
if (len(texts) > spec["max_batch_inputs"] or sum(counts) > spec["max_batch_tokens"]
        or max(counts) > spec["max_input_tokens"] or len(texts) * max(counts) > spec["max_padded_tokens"]):
    raise ValueError("input protection limit exceeded; never truncate")
batch = tokenizer(texts, padding=True, truncation=False, return_tensors="pt")
if batch["attention_mask"].sum(dim=1).tolist() != counts:
    raise ValueError("pre-call token count differs")
original_mask = batch["attention_mask"].clone()
if args.explicit_causal_mask:
    length = batch["input_ids"].shape[1]
    causal = torch.ones(length, length, dtype=torch.bool).tril()
    # True means attend. Explicit mask preserves causal and left-padding semantics,
    # and the frozen Transformers SDPA wrapper repeats K/V instead of CPU GQA math.
    batch["attention_mask"] = causal[None, None, :, :] & batch["attention_mask"][:, None, None, :].bool()
started = time.monotonic()
def expired():
    print("CPU probe exceeded 300-second inference deadline", flush=True)
    os._exit(2)
watchdog = threading.Timer(300, expired)
watchdog.daemon = True
watchdog.start()
try:
    with torch.inference_mode():
        hidden = model(**batch, use_cache=False).last_hidden_state
        vectors = F.normalize(hidden[:, -1, :], p=2, dim=1)
finally:
    watchdog.cancel()
seconds = time.monotonic() - started
if vectors.shape != (len(texts), 1024) or not torch.isfinite(vectors).all():
    raise ValueError("model vector shape or numbers invalid")
if not torch.allclose(vectors.norm(dim=1), torch.ones(len(texts)), atol=1e-5):
    raise ValueError("model normalization failed")
mask_comparison = None
if args.explicit_causal_mask and not (args.boundary or args.batch_boundary):
    batch["attention_mask"] = original_mask
    with torch.inference_mode():
        baseline = F.normalize(model(**batch, use_cache=False).last_hidden_state[:, -1, :], p=2, dim=1)
    mask_comparison = float((vectors - baseline).abs().max())
    if not torch.allclose(vectors, baseline, atol=1e-5, rtol=1e-5):
        raise ValueError("explicit causal/padding mask changed embeddings")
peak = None
if os.name == "nt":
    class Counters(ctypes.Structure):
        _fields_ = [("cb", ctypes.c_ulong), ("PageFaultCount", ctypes.c_ulong)] + [
            (name, ctypes.c_size_t) for name in ("PeakWorkingSetSize", "WorkingSetSize", "QuotaPeakPagedPoolUsage",
            "QuotaPagedPoolUsage", "QuotaPeakNonPagedPoolUsage", "QuotaNonPagedPoolUsage", "PagefileUsage", "PeakPagefileUsage")]
    info = Counters()
    info.cb = ctypes.sizeof(info)
    get_process = ctypes.windll.kernel32.GetCurrentProcess
    get_process.restype = ctypes.c_void_p
    memory = ctypes.windll.psapi.GetProcessMemoryInfo
    memory.argtypes = [ctypes.c_void_p, ctypes.POINTER(Counters), ctypes.c_ulong]
    if not memory(get_process(), ctypes.byref(info), info.cb):
        raise OSError("cannot measure peak working set")
    peak = info.PeakWorkingSetSize
else:
    import resource
    peak = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss * (1 if platform.system() == "Darwin" else 1024)
print(json.dumps({"spec": spec["id"], "mode": "6000-token-boundary" if args.boundary else
                  "4x1500-token-boundary" if args.batch_boundary else "short-smoke",
                  "device": "cpu", "dtype": "float32", "threads": 4, "python": platform.python_version(),
                  "explicit_causal_mask": args.explicit_causal_mask,
                  "short_mask_comparison_max_abs_error": mask_comparison,
                  "token_counts": counts, "dimensions": 1024, "normalized": True,
                  "load_seconds": round(load_seconds, 3), "inference_seconds": round(seconds, 3),
                  "peak_working_set_bytes": peak, "retrieval_quality_evaluated": False}))
