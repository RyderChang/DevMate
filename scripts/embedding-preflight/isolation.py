"""Bounded Docker/HTTP helpers for uniquely labelled disposable contract probes."""
import json
from pathlib import Path
import secrets
import subprocess
import time
import urllib.error
import urllib.request
import uuid

HERE = Path(__file__).resolve().parent
ENV = json.loads((HERE / "environment.json").read_text())


def docker(*args, check=True, timeout=60):
    result = subprocess.run(["docker", *args], capture_output=True, text=True, timeout=timeout)
    if check and result.returncode:
        raise RuntimeError("Docker preflight command failed; no raw logs emitted")
    return result


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        raise ValueError("redirect rejected")


def request(base, method, path, data=None, timeout=5):
    if not base.startswith("http://127.0.0.1:") or not path.startswith("/"):
        raise ValueError("probe must use loopback")
    body = None if data is None else json.dumps(data, allow_nan=False).encode()
    req = urllib.request.Request(base + path, data=body, method=method,
                                 headers={"Content-Type": "application/json"})
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    try:
        with opener.open(req, timeout=timeout) as response:
            raw = response.read(1048577)
            if len(raw) > 1048576:
                raise ValueError("probe response too large")
            return response.status, json.loads(raw.decode("utf-8"))
    except urllib.error.HTTPError as error:
        return error.code, {}


class Isolation:
    def __init__(self):
        self.run = uuid.uuid4().hex[:12]
        self.label = "devmate.embedding-preflight=" + self.run
        self.qdrant = "devmate019-qdrant-" + self.run
        self.mysql = "devmate019-mysql-" + self.run
        self.volume = "devmate019-vector-" + self.run
        self.password = secrets.token_hex(24)
        self.created = []
        self.volume_created = False

    def start(self):
        for service in ("qdrant", "mysql"):
            data = json.loads(docker("image", "inspect", ENV[service]["image"]).stdout)[0]
            if data["Architecture"] != "amd64" or data["Os"] != "linux":
                raise RuntimeError("wrong Docker image platform")
        self.volume_created = True
        docker("volume", "create", "--label", self.label, self.volume)
        self.created.append(self.qdrant)
        docker("run", "-d", "--name", self.qdrant, "--label", self.label, "--platform", "linux/amd64",
               "--memory", "512m", "--cpus", "1", "--security-opt", "no-new-privileges",
               "--cap-drop", "ALL", "-p", "127.0.0.1::6333", "-v", self.volume + ":/qdrant/storage",
               "-e", "QDRANT__TELEMETRY_DISABLED=true", "-e", "QDRANT__LOG_LEVEL=WARN",
               "-e", "QDRANT__STORAGE__SNAPSHOTS_PATH=/qdrant/storage/snapshots", ENV["qdrant"]["image"])
        self.refresh_endpoint()
        self.created.append(self.mysql)
        docker("run", "-d", "--name", self.mysql, "--label", self.label, "--platform", "linux/amd64",
               "--memory", "1g", "--cpus", "1", "-e", "MYSQL_ROOT_PASSWORD=" + self.password,
               ENV["mysql"]["image"])
        self.wait_ready()
        version = self.sql("SELECT VERSION();").strip()
        if not version.startswith(ENV["mysql"]["version"]):
            raise RuntimeError("wrong MySQL version")
        if request(self.base, "GET", "/")[1].get("version") != ENV["qdrant"]["version"]:
            raise RuntimeError("wrong Qdrant version")

    def wait_ready(self):
        self.refresh_endpoint()
        deadline = time.monotonic() + 90
        while time.monotonic() < deadline:
            try:
                if request(self.base, "GET", "/")[0] == 200 and self.sql("SELECT 1;").strip() == "1":
                    return
            except (RuntimeError, OSError, urllib.error.URLError):
                pass
            time.sleep(0.5)
        raise RuntimeError("isolated services did not become ready")

    def refresh_endpoint(self):
        binding = docker("port", self.qdrant, "6333/tcp").stdout.strip()
        if not binding.startswith("127.0.0.1:") or "\n" in binding:
            raise RuntimeError("unexpected Qdrant bind")
        self.base = "http://" + binding

    def sql(self, sql):
        result = docker("exec", "-e", "MYSQL_PWD=" + self.password, self.mysql,
                        "mysql", "-uroot", "-N", "-B", "-e", sql, check=False, timeout=15)
        if result.returncode:
            raise RuntimeError("isolated SQL failed")
        return result.stdout

    def close(self):
        for name in reversed(self.created):
            info = docker("inspect", name, check=False)
            if info.returncode == 0:
                labels = json.loads(info.stdout)[0]["Config"]["Labels"] or {}
                if labels.get("devmate.embedding-preflight") != self.run:
                    raise RuntimeError("refusing to remove unowned container")
                docker("rm", "-f", "-v", name)
        if self.volume_created:
            info = docker("volume", "inspect", self.volume, check=False)
            if info.returncode:
                return
            labels = json.loads(info.stdout)[0]["Labels"] or {}
            if labels.get("devmate.embedding-preflight") != self.run:
                raise RuntimeError("refusing to remove unowned volume")
            docker("volume", "rm", self.volume)
