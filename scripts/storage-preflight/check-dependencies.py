"""Verify the resolved Maven graph and every JAR against the reviewed lock file."""
import hashlib
import json
import os
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parent


def resolved():
    tree = (ROOT / "target/dependency-tree.txt").read_text(encoding="utf-8")
    paths = [Path(value.strip()) for value in
             (ROOT / "target/classpath.txt").read_text(encoding="utf-8").strip().split(os.pathsep)]
    result = {}
    for line in tree.splitlines()[1:]:
        match = re.search(r"([\w.-]+):([\w.-]+):jar:([\w.-]+):(compile|runtime|test)$", line)
        if not match:
            raise RuntimeError("Unsupported dependency entry; review the graph instead of ignoring it")
        group, artifact, version, scope = match.groups()
        suffix = f"/{group.replace('.', '/')}/{artifact}/{version}/{artifact}-{version}.jar"
        candidates = [path for path in paths if path.as_posix().endswith(suffix)]
        if len(candidates) != 1:
            raise RuntimeError(f"Missing or ambiguous resolved JAR: {group}:{artifact}:{version}")
        result[f"{group}:{artifact}:{version}:{scope}"] = hashlib.sha256(candidates[0].read_bytes()).hexdigest()
    if len(result) != len(paths):
        raise RuntimeError("Dependency graph and classpath differ")
    return dict(sorted(result.items()))


if __name__ == "__main__":
    expected = json.loads((ROOT / "dependencies.lock.json").read_text(encoding="utf-8"))
    actual = resolved()
    if actual != expected:
        raise SystemExit("Dependency lock mismatch; review versions, scopes and hashes before updating the lock")
    print(f"PASS: {len(actual)} Maven dependencies match locked versions, scopes and SHA-256 values")
