"""Assemble only known cache blocks; discard them when the frozen final digest fails."""
import hashlib


def assemble(target, parts, offsets, entry):
    if any(type(offset) is not int or offset < 0 for offset in offsets):
        raise ValueError("invalid cache block locator")
    with target.open("wb") as stream:
        for offset in offsets:
            stream.write((parts / str(offset)).read_bytes())
    with target.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    if target.stat().st_size != entry["bytes"] or digest != entry["sha256"]:
        # Only these task-owned block names are removed, never the whole directory.
        for offset in offsets:
            (parts / str(offset)).unlink(missing_ok=True)
        target.unlink(missing_ok=True)
        raise ValueError("assembled weight digest mismatch; cached blocks discarded, rerun to download fresh")
