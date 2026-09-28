"""Build only the locked, local DEV-016 synthetic-data MinIO image. Never push it."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import urllib.request
import uuid

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
OUTPUT = ROOT / "tmp/dev-016"
LOCK = json.loads((HERE / "build-lock.json").read_text(encoding="utf-8"))


def digest(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def command(args, *, cwd=ROOT, env=None, timeout=180, log=None):
    if log:
        with log.open("w", encoding="utf-8") as output:
            result = subprocess.run(args, cwd=cwd, env=env, stdout=output, stderr=subprocess.STDOUT, timeout=timeout)
        if result.returncode:
            # This build uses only public source/binary/license files and no build secrets.
            if log.name == "image-build.log":
                print(log.read_text(encoding="utf-8", errors="replace")[-12000:])
            raise RuntimeError(f"Build failed; inspect local log {log.relative_to(ROOT)}")
        return ""
    result = subprocess.run(args, cwd=cwd, env=env, capture_output=True, text=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError(f"Command failed: {args[0]} {args[1]}; no image has been approved")
    return result.stdout.strip()


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    if not OUTPUT.resolve().is_relative_to((ROOT / "tmp").resolve()):
        raise RuntimeError("Output must stay inside the repository's ignored tmp directory")
    env = os.environ.copy()
    env.update(GOENV="off", GOOS="linux", GOARCH="amd64", GOAMD64="v1", CGO_ENABLED="0",
               GOTOOLCHAIN="local", GOTELEMETRY="off", GOFLAGS="", GOEXPERIMENT="", GOMAXPROCS="4",
               GOPROXY="https://proxy.golang.org", GOSUMDB="sum.golang.org", GOPRIVATE="", GONOSUMDB="")
    if command(["go", "version"], env=env).split()[2] != LOCK["goVersion"]:
        raise RuntimeError("Install the exact Go toolchain in build-lock.json; do not silently substitute another version")
    engine = command(["docker", "info", "--format", "{{.OSType}}/{{.Architecture}}"])
    if engine not in ("linux/x86_64", "linux/amd64"):
        raise RuntimeError("Only the locked Linux/amd64 test platform is supported")
    archive = OUTPUT / "minio-source.tar.gz"
    if not archive.exists():
        with urllib.request.urlopen(LOCK["sourceUrl"], timeout=60) as response, archive.open("wb") as output:
            shutil.copyfileobj(response, output)
    if digest(archive) != LOCK["sourceSha256"]:
        raise RuntimeError("Source archive SHA-256 mismatch; do not build or replace the lock automatically")
    source_parent = OUTPUT / "source"
    source_parent.mkdir(exist_ok=True)
    with tarfile.open(archive) as source_archive:
        source_archive.extractall(source_parent, filter="data")
    source = source_parent / ("minio-" + LOCK["sourceCommit"])
    flags = "-s -w " + " ".join("-X github.com/minio/minio/cmd." + key + "=" + value for key, value in {
        "Version": "2025-10-15T17:29:55Z", "ReleaseTag": "RELEASE.2025-10-15T17-29-55Z",
        "CommitID": LOCK["sourceCommit"], "ShortCommitID": LOCK["sourceCommit"][:12], "CopyrightYear": "2025"
    }.items())
    binary = OUTPUT / "minio"
    command(["go", "build", "-p", "4", "-mod=readonly", "-trimpath", "-buildvcs=false", "-ldflags", flags,
             "-o", str(binary), "."], cwd=source, env=env, timeout=1800, log=OUTPUT / "minio-build.log")
    if digest(binary) != LOCK["binarySha256"]:
        raise RuntimeError("MinIO binary differs from the reviewed SHA-256; stop before building an image")
    context = OUTPUT / "image-context"
    context.mkdir(exist_ok=True)
    for original, name in ((binary, "minio"), (source / "LICENSE", "LICENSE"), (HERE / "Minio.Dockerfile", "Dockerfile")):
        destination = context / name
        shutil.copyfile(original, destination)
        os.chmod(destination, 0o755 if name == "minio" else 0o644)
        os.utime(destination, (LOCK["sourceDateEpoch"], LOCK["sourceDateEpoch"]))
    metadata = OUTPUT / "image-metadata.json"
    image_archive = OUTPUT / "minio-image.tar"
    builder = "devmate016-" + uuid.uuid4().hex[:12]
    command(["docker", "buildx", "create", "--name", builder, "--driver", "docker-container",
             "--driver-opt", "image=" + LOCK["buildkitImage"]])
    try:
        command(["docker", "buildx", "build", "--builder", builder, "--platform", LOCK["platform"],
                 "--provenance=false", "--no-cache", "--output",
                 f"type=docker,dest={image_archive},compression=uncompressed,rewrite-timestamp=true",
                 "--build-arg", f"SOURCE_DATE_EPOCH={LOCK['sourceDateEpoch']}", "--metadata-file", str(metadata),
                 "-t", LOCK["imageTag"], str(context)], timeout=600, log=OUTPUT / "image-build.log")
    finally:
        command(["docker", "buildx", "rm", builder], timeout=60)
    built = json.loads(metadata.read_text(encoding="utf-8"))
    print("Built manifest:", built["containerimage.digest"], "config:", built["containerimage.config.digest"])
    if built["containerimage.digest"] != LOCK["imageManifestDigest"]:
        # Only the public Dockerfile configuration; credentials are generated later in tests.
        with tarfile.open(image_archive) as exported:
            manifest = json.load(exported.extractfile("manifest.json"))[0]
            print("Public image config:", exported.extractfile(manifest["Config"]).read().decode("utf-8"))
        raise RuntimeError("Image manifest digest mismatch; do not run the changed image")
    command(["docker", "load", "--input", str(image_archive)], timeout=180, log=OUTPUT / "image-load.log")
    actual_id = command(["docker", "image", "inspect", LOCK["imageTag"], "--format", "{{.Id}}"])
    if actual_id not in (LOCK["imageConfigDigest"], LOCK["imageManifestDigest"]):
        raise RuntimeError("Local image identity mismatch")
    print("PASS: official source archive, Go toolchain, binary, image manifest and local identity match the lock")


if __name__ == "__main__":
    main()
