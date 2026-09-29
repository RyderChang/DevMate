"""Select Linux wheels from cached official release metadata; never change versions to make installation pass."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
base = Path(__file__).resolve().parent
windows = json.loads((ROOT / "scripts/embedding-preflight/runtime-windows.lock.json").read_text())
entries = []
for package in windows["wheels"]:
    if package["name"] == "torch":
        entries.append({"name": "torch", "version": "2.8.0+cpu", "filename": "torch-2.8.0+cpu-cp313-cp313-manylinux_2_28_x86_64.whl",
                        "sha256": "8f81dedb4c6076ec325acc3b47525f9c550e5284a18eae1d9061c543f7b6e7de",
                        "bytes": 183917315,
                        "url": "https://download.pytorch.org/whl/cpu/torch-2.8.0%2Bcpu-cp313-cp313-manylinux_2_28_x86_64.whl"})
        continue
    data = json.loads((ROOT / "tmp/dev-019/pypi-cache" / (package["name"]+"-"+package["version"]+".json")).read_text())
    compatible = []
    for wheel in data["urls"]:
        parts = wheel["filename"].removesuffix(".whl").split("-")
        if wheel["packagetype"] != "bdist_wheel":
            continue
        python, abi, platform = parts[-3:]
        if (python in ("py3", "py2.py3") and abi == "none" and platform == "any"
            or (python == "cp313" or python in ("cp38", "cp39", "cp310", "cp311", "cp312") and abi == "abi3")
            and "manylinux" in platform and "x86_64" in platform):
            compatible.append(wheel)
    if not compatible:
        raise RuntimeError("No frozen Linux wheel: " + package["name"])
    entry = sorted(compatible, key=lambda x: x["filename"])[0]
    entries.append({"name": package["name"], "version": package["version"], "filename": entry["filename"],
                    "sha256": entry["digests"]["sha256"], "url": entry["url"], "bytes": entry["size"]})
(base / "runtime-linux.lock.json").write_text(json.dumps({"python": "3.13.5", "platform": "linux-amd64-cp313", "source": "official PyPI release metadata and PyTorch CPU index, 2026-09-28", "wheels": entries}, indent=2)+"\n")
(base / "requirements-linux.txt").write_text("# Frozen Linux CPython 3.13 CPU wheels. --no-deps --require-hashes\n" + "\n".join(e["name"]+" @ "+e["url"]+" --hash=sha256:"+e["sha256"] for e in entries)+"\n")
print("Frozen Linux wheels:", len(entries))
