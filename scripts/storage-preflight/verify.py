"""Resolve, verify and run DEV-016 dependency probes; fail on missing/skipped tests."""
import json
import copy
import os
from pathlib import Path
import re
import subprocess
import sys
import uuid
import xml.etree.ElementTree as ET

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
OUTPUT = ROOT / "tmp/dev-016"


def execute(args, name, *, env=None, timeout=600, maven=False):
    log = OUTPUT / (name + ".log")
    with log.open("w", encoding="utf-8") as stream:
        result = subprocess.run(args, cwd=ROOT, env=env, stdout=stream, stderr=subprocess.STDOUT, timeout=timeout)
    text = log.read_text(encoding="utf-8", errors="replace")
    # The repository's Windows wrapper currently loses Maven's failure exit code.
    if result.returncode or (maven and ("BUILD SUCCESS" not in text or "BUILD FAILURE" in text)):
        raise RuntimeError(f"{name} failed; inspect {log.relative_to(ROOT)} locally (do not upload raw logs)")


def compare_backend(prefix, env):
    namespace = "{http://maven.apache.org/POM/4.0.0}"
    ET.register_namespace("", namespace[1:-1])
    backend = ROOT / "devmate-server/pom.xml"
    candidate = ET.parse(backend)
    probe = ET.parse(HERE / "pom.xml").getroot()
    candidate.getroot().find(namespace + "properties").append(
        copy.deepcopy(probe.find(namespace + "properties/" + namespace + "aws-sdk.version")))
    candidate.getroot().append(copy.deepcopy(probe.find(namespace + "dependencyManagement")))
    for dependency in probe.find(namespace + "dependencies"):
        if dependency.findtext(namespace + "groupId") == "software.amazon.awssdk":
            candidate.getroot().find(namespace + "dependencies").append(copy.deepcopy(dependency))
    candidate_path = OUTPUT / "server-candidate-pom.xml"
    candidate.write(candidate_path, encoding="utf-8", xml_declaration=True)
    graphs = []
    for name, pom in (("backend-base", backend), ("backend-candidate", candidate_path)):
        output = OUTPUT / (name + ".json")
        execute([*prefix, "-B", "-ntp", "-f", str(pom), "dependency:tree", "-DoutputType=json",
                 "-DoutputFile=" + str(output)], name, env=env, maven=True)
        def flatten(node):
            result = {}
            for child in node.get("children", []):
                key = ":".join(child.get(part, "") for part in ("groupId", "artifactId", "type", "classifier"))
                result[key] = (child["version"], child.get("scope", ""))
                result.update(flatten(child))
            return result
        graphs.append(flatten(json.loads(output.read_text(encoding="utf-8"))))
    before, after = graphs
    if any(after.get(key) != value for key, value in before.items()):
        raise RuntimeError("The SDK candidate changes existing backend dependencies; review before proceeding")
    print(f"PASS: {len(before)} existing backend artifacts retain their versions and scopes; "
          f"{len(after.keys() - before.keys())} SDK artifacts added only in the temporary candidate")


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    env = os.environ.copy()
    java_home = Path(env["JAVA_HOME"])
    java = java_home / "bin" / ("java.exe" if os.name == "nt" else "java")
    version = subprocess.run([str(java), "-version"], capture_output=True, text=True, check=True, timeout=20)
    if not re.search(r'version "21\.', version.stdout + version.stderr):
        raise RuntimeError("JAVA_HOME must select JDK 21; the default PATH Java is not sufficient")
    if not (java_home / "bin" / ("javac.exe" if os.name == "nt" else "javac")).is_file():
        raise RuntimeError("Compilation requires a JDK, not a JRE")
    properties = dict(line.split("=", 1) for line in (HERE / "environment.properties").read_text().splitlines()
                      if line and not line.startswith("#"))
    build_lock = json.loads((HERE / "build-lock.json").read_text())
    if (properties["minio.image"] != build_lock["imageTag"]
            or properties["minio.configDigest"] != build_lock["imageConfigDigest"]
            or properties["minio.manifestDigest"] != build_lock["imageManifestDigest"]):
        raise RuntimeError("Build and runtime locks differ")
    inspected = subprocess.run(["docker", "image", "inspect", properties["minio.image"], "--format", "{{.Id}}"],
                               capture_output=True, text=True, check=True, timeout=30)
    if inspected.stdout.strip() not in (build_lock["imageConfigDigest"], build_lock["imageManifestDigest"]):
        raise RuntimeError("Build the locked MinIO image first")
    wrapper = ROOT / "devmate-server" / ("mvnw.cmd" if os.name == "nt" else "mvnw")
    prefix = ["cmd", "/d", "/c", str(wrapper)] if os.name == "nt" else ["sh", str(wrapper)]
    execute([*prefix, "-B", "-ntp", "-f", str(HERE / "pom.xml"), "clean", "test-compile", "surefire:help",
             "dependency:get", "-Dartifact=org.apache.maven.surefire:surefire-junit-platform:3.5.4",
             "dependency:tree", "-DoutputFile=target/dependency-tree.txt", "dependency:build-classpath",
             "-Dmdep.outputFile=target/classpath.txt"], "resolve-and-compile", env=env, maven=True)
    subprocess.run([sys.executable, "-B", str(HERE / "check-dependencies.py")], check=True, cwd=ROOT, timeout=30)
    compare_backend(prefix, env)
    maven_user = Path(env.get("MAVEN_USER_HOME", str(Path.home() / ".m2")))
    maven_home = maven_user / "wrapper/dists/apache-maven-3.9.10/apache-maven-3.9.10"
    repository = Path.home() / ".m2/repository"
    if not (maven_home / "bin/mvn").is_file() or not repository.is_dir():
        raise RuntimeError("This probe uses the repository wrapper and default ~/.m2/repository cache")
    runner = "devmate016-runner-" + uuid.uuid4().hex[:12]
    # The Linux runner shares host networking to reach loopback-only mapped ports.
    # Docker Desktop instead exposes the host through host.docker.internal.
    network = (["-e", "TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal"] if os.name == "nt" else
               ["--network", "host", "-e", "TESTCONTAINERS_HOST_OVERRIDE=127.0.0.1"])
    args = ["docker", "run", "--rm", "--name", runner, "--label", "devmate.preflight=dev016",
            "--platform", "linux/amd64", *network, "-v", f"{ROOT}:/workspace",
            "-v", f"{repository}:/root/.m2/repository:ro", "-v", f"{maven_home}:/opt/maven:ro",
            "-v", "/var/run/docker.sock:/var/run/docker.sock", "-w", "/workspace/scripts/storage-preflight",
            properties["java.image"], "sh", "/opt/maven/bin/mvn", "-B", "-ntp", "-o", "surefire:test"]
    try:
        execute(args, "linux-tests", timeout=600, maven=True)
    finally:
        state = subprocess.run(["docker", "inspect", runner, "--format", '{{index .Config.Labels "devmate.preflight"}}'],
                               capture_output=True, text=True, timeout=30)
        if state.returncode == 0 and state.stdout.strip() == "dev016":
            subprocess.run(["docker", "rm", "-f", "-v", runner], capture_output=True, check=True, timeout=30)
    reports = list((HERE / "target/surefire-reports").glob("TEST-*.xml"))
    total = {key: 0 for key in ("tests", "failures", "errors", "skipped")}
    for path in reports:
        suite = ET.parse(path).getroot()
        for key in total:
            total[key] += int(suite.attrib[key])
    if len(reports) != 2 or total != {"tests": 4, "failures": 0, "errors": 0, "skipped": 0}:
        raise RuntimeError("Expected all four fresh SDK/MinIO/MySQL tests to pass without skips")
    print("PASS: 4 SDK/MinIO/MySQL tests; 0 failures, 0 errors, 0 skipped")


if __name__ == "__main__":
    main()
