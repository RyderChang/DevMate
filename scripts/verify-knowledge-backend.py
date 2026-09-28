"""Run the backend in the pinned Linux Java runtime, failing on missing/skipped tests.

Windows Oracle JDK's loopback failure cannot replace a real MySQL/MinIO test run.
Logs and Maven reports remain local; only aggregate test counts are printed.
"""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import uuid
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
OUTPUT = ROOT / "tmp/dev-016"


def execute(args, name, *, env=None, timeout=1200):
    log = OUTPUT / (name + ".log")
    with log.open("w", encoding="utf-8") as stream:
        result = subprocess.run(args, cwd=ROOT, env=env, stdout=stream, stderr=subprocess.STDOUT, timeout=timeout)
    text = log.read_text(encoding="utf-8", errors="replace")
    if result.returncode or "BUILD SUCCESS" not in text or "BUILD FAILURE" in text:
        raise RuntimeError(f"{name} failed; inspect {log.relative_to(ROOT)} locally; do not upload raw logs")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--tests", help="Optional Surefire class-name filter for development; not full acceptance")
    arguments = parser.parse_args()
    if arguments.tests and not re.fullmatch(r"[A-Za-z0-9_,*]+", arguments.tests):
        raise ValueError("Invalid test filter")
    OUTPUT.mkdir(parents=True, exist_ok=True)
    properties = dict(line.split("=", 1) for line in (ROOT / "scripts/storage-preflight/environment.properties").read_text().splitlines()
                      if line and not line.startswith("#"))
    env = os.environ.copy()
    java = Path(env["JAVA_HOME"]) / "bin" / ("java.exe" if os.name == "nt" else "java")
    version = subprocess.run([str(java), "-version"], capture_output=True, text=True, check=True, timeout=20)
    if not re.search(r'version "21\.', version.stdout + version.stderr):
        raise RuntimeError("JAVA_HOME must select JDK 21")
    image = subprocess.run(["docker", "image", "inspect", properties["minio.image"], "--format", "{{.Id}}"],
                           capture_output=True, text=True, check=True, timeout=30)
    if image.stdout.strip() not in (properties["minio.manifestDigest"], properties["minio.configDigest"]):
        raise RuntimeError("Build and verify the locked MinIO test image first")
    wrapper = ROOT / "devmate-server" / ("mvnw.cmd" if os.name == "nt" else "mvnw")
    prefix = ["cmd", "/d", "/c", str(wrapper)] if os.name == "nt" else ["sh", str(wrapper)]
    execute([*prefix, "-B", "-ntp", "-f", str(ROOT / "devmate-server/pom.xml"), "clean", "package", "-DskipTests", "surefire:help", "dependency:get",
             "-Dartifact=org.apache.maven.surefire:surefire-junit-platform:3.5.4"],
            "knowledge-resolve", env=env)
    maven_user = Path(env.get("MAVEN_USER_HOME", str(Path.home() / ".m2")))
    maven = maven_user / "wrapper/dists/apache-maven-3.9.10/apache-maven-3.9.10"
    repository = Path.home() / ".m2/repository"
    runner = "devmate016-backend-" + uuid.uuid4().hex[:12]
    network = (["-e", "TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal"] if os.name == "nt" else
               ["--network", "host", "-e", "TESTCONTAINERS_HOST_OVERRIDE=127.0.0.1"])
    args = ["docker", "run", "--rm", "--name", runner, "--label", "devmate.knowledge-test=dev016", "--platform", "linux/amd64",
            *network, "-v", f"{ROOT}:/workspace", "-v", f"{repository}:/root/.m2/repository:ro", "-v", f"{maven}:/opt/maven:ro",
            "-v", "/var/run/docker.sock:/var/run/docker.sock", "-w", "/workspace/devmate-server", properties["java.image"],
            "sh", "/opt/maven/bin/mvn", "-B", "-ntp", "-o", "surefire:test"]
    if arguments.tests:
        args.append("-Dtest=" + arguments.tests)
    try:
        execute(args, "knowledge-linux-tests", timeout=1800)
    finally:
        state = subprocess.run(["docker", "inspect", runner, "--format", '{{index .Config.Labels "devmate.knowledge-test"}}'],
                               capture_output=True, text=True, timeout=30)
        if state.returncode == 0 and state.stdout.strip() == "dev016":
            subprocess.run(["docker", "rm", "-f", "-v", runner], capture_output=True, check=True, timeout=30)
    totals = {key: 0 for key in ("tests", "failures", "errors", "skipped")}
    reports = list((ROOT / "devmate-server/target/surefire-reports").glob("TEST-*.xml"))
    for report in reports:
        suite = ET.parse(report).getroot()
        for key in totals:
            totals[key] += int(suite.attrib[key])
    if not reports or not totals["tests"] or any(totals[key] for key in ("failures", "errors", "skipped")):
        raise RuntimeError("Every selected test must run and pass without skips")
    if not arguments.tests:
        subprocess.run([sys.executable, "-B", str(ROOT / "scripts/check-knowledge-test-reports.py")], check=True, cwd=ROOT, timeout=30)
    print("PASS:", json.dumps(totals), "(filtered development run)" if arguments.tests else "(complete backend)")


if __name__ == "__main__":
    main()
