"""Exclusive model identity and enforced Linux cgroup resource envelope."""
import os
import platform
import json
import importlib.metadata
from pathlib import Path


def exclusive(journal):
    stream = Path(str(journal)+".lock").open("a+b")
    stream.seek(0)
    if os.name == "nt":
        import msvcrt
        if stream.read(1) == b"":
            stream.write(b"0")
            stream.flush()
        stream.seek(0)
        msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
    else:
        import fcntl
        fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
    return stream


def verify_linux_envelope():
    if os.name != "posix":
        raise RuntimeError("The supervised service requires the frozen Linux container runtime")
    if platform.python_version() != "3.13.5":
        raise RuntimeError("Python runtime mismatch")
    for wheel in json.loads((Path(__file__).parent / "runtime-linux.lock.json").read_text())["wheels"]:
        if importlib.metadata.version(wheel["name"]) != wheel["version"]:
            raise RuntimeError("Linux runtime dependency mismatch: " + wheel["name"])
    memory = Path("/sys/fs/cgroup/memory.max").read_text().strip()
    cpu = Path("/sys/fs/cgroup/cpu.max").read_text().split()
    pids = Path("/sys/fs/cgroup/pids.max").read_text().strip()
    if memory == "max" or not 0 < int(memory) <= 8589934592 or cpu[0] == "max" or int(cpu[0])/int(cpu[1]) > 4 or pids == "max" or int(pids) > 128:
        raise RuntimeError("Model resource limits must be at most 8 GiB / 4 CPUs / 128 PIDs")
