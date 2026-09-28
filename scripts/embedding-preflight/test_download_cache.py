"""Regression for resumable downloads stuck on a same-length corrupted block."""
import hashlib
from pathlib import Path
import tempfile
import unittest

from download_cache import assemble


class DownloadCacheContract(unittest.TestCase):
    def test_corrupt_same_size_cache_is_invalidated_and_next_run_recovers(self):
        base = Path(__file__).resolve().parents[2] / "tmp/dev-019/cache-tests"
        base.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=base) as folder:
            root = Path(folder)
            if not root.resolve().is_relative_to(base.resolve()):
                raise RuntimeError("temporary test directory escaped workspace")
            parts = root / "parts"
            parts.mkdir()
            target = root / "model.safetensors"
            unrelated = parts / "unrelated"
            unrelated.write_bytes(b"keep")
            (parts / "0").write_bytes(b"abc")
            (parts / "3").write_bytes(b"BAD")
            entry = {"bytes": 6, "sha256": hashlib.sha256(b"abcdef").hexdigest()}
            with self.assertRaises(ValueError):
                assemble(target, parts, [0, 3], entry)
            self.assertFalse((parts / "0").exists())
            self.assertFalse((parts / "3").exists())
            self.assertFalse(target.exists())
            self.assertEqual(b"keep", unrelated.read_bytes())
            # A subsequent downloader no longer finds apparently reusable corrupt parts.
            (parts / "0").write_bytes(b"abc")
            (parts / "3").write_bytes(b"def")
            assemble(target, parts, [0, 3], entry)
            self.assertEqual(b"abcdef", target.read_bytes())


if __name__ == "__main__":
    unittest.main()
