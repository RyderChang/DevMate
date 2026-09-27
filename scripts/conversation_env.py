"""Isolated DEV-015 containers. Credentials exist only in process/container environments."""
import argparse
import json
import os
from pathlib import Path
import secrets
import subprocess
import time
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
PREFIX = "devmate015"
LABEL = "devmate.acceptance=dev015"
IMAGES = ("mysql:8.4.6", "eclipse-temurin:21-jre", "node:22.19.0-alpine")
APP_KEYS = ("DB_URL", "DB_USERNAME", "DB_PASSWORD", "JWT_SECRET", "SPRING_PROFILES_ACTIVE", "SERVER_PORT",
            "AI_ENABLED", "OPENAI_BASE_URL", "OPENAI_API_KEY", "OPENAI_MODEL", "AI_READ_TIMEOUT", "AI_GENERATION_LEASE",
            "LOGGING_LEVEL_ORG_SPRINGFRAMEWORK_BOOT_AUTOCONFIGURE_SECURITY", "JWT_EXPIRATION")


def docker(*args, env=None, check=True):
    result = subprocess.run(["docker", *args], env=env, capture_output=True, text=True, timeout=180)
    if check and result.returncode:
        raise RuntimeError("Docker operation failed: " + result.stderr[:300])
    return result


def owned(name, network=False):
    result = docker(*(["network", "inspect"] if network else ["inspect"]), name, check=False)
    if result.returncode:
        return False
    item = json.loads(result.stdout)[0]
    labels = item.get("Labels") if network else item.get("Config", {}).get("Labels")
    if (labels or {}).get("devmate.acceptance") != "dev015":
        raise RuntimeError("Refusing to touch a resource without the task label")
    return True


def stop():
    for suffix in ("app", "stub", "db"):
        name = f"{PREFIX}-{suffix}"
        if owned(name):
            docker("rm", "-f", "-v", name)
    if owned(f"{PREFIX}-net", network=True):
        docker("network", "rm", f"{PREFIX}-net")
    print("Removed only labelled DEV-015 containers and network; temporary data deleted.")


def stub(scenario, delay):
    if owned(f"{PREFIX}-stub"):
        docker("rm", "-f", f"{PREFIX}-stub")
    docker("run", "-d", "--name", f"{PREFIX}-stub", "--label", LABEL,
           "--network", f"{PREFIX}-net", "-e", "STUB_ISOLATED_CONTAINER=1",
           "-v", f"{ROOT / 'scripts' / 'ai-stub.mjs'}:/stub.mjs:ro", IMAGES[2],
           "node", "/stub.mjs", scenario, "19090", str(delay))
    print(f"Stub scenario={scenario}; no published port; counters reset.")


def wait_for(check, description, attempts=90):
    for _ in range(attempts):
        try:
            if check():
                return
        except (OSError, urllib.error.URLError):
            pass
        time.sleep(1)
    raise RuntimeError(description + " timed out")


def app(env):
    jar = ROOT / "devmate-server/target/devmate-server-0.0.1-SNAPSHOT.jar"
    docker("run", "-d", "--name", f"{PREFIX}-app", "--label", LABEL,
           "--network", f"{PREFIX}-net", "-p", "127.0.0.1:18085:8080", "-v", f"{jar}:/app.jar:ro",
           *[arg for key in APP_KEYS for arg in ("-e", key)], IMAGES[1], "java", "-jar", "/app.jar", env=env)
    wait_for(lambda: urllib.request.urlopen("http://127.0.0.1:18085/health", timeout=2).status == 200,
             "Backend readiness")
    print("Backend ready at http://127.0.0.1:18085; AI " + ("disabled" if env["AI_ENABLED"] == "false" else "local Stub only"))


def restart_app(disabled, short_session):
    if not owned(f"{PREFIX}-app"):
        raise RuntimeError("Start the task environment first")
    config = json.loads(docker("inspect", f"{PREFIX}-app").stdout)[0]["Config"]["Env"]
    env = os.environ.copy()
    env.update(dict(value.split("=", 1) for value in config))
    env.update(AI_ENABLED=str(not disabled).lower(), JWT_EXPIRATION="PT30S" if short_session else "PT2H")
    docker("rm", "-f", f"{PREFIX}-app")
    app(env)


def start(disabled, scenario, delay):
    jar = ROOT / "devmate-server/target/devmate-server-0.0.1-SNAPSHOT.jar"
    if not jar.is_file():
        raise RuntimeError("Run Maven clean verify first")
    for suffix in ("app", "stub", "db"):
        if docker("inspect", f"{PREFIX}-{suffix}", check=False).returncode == 0:
            raise RuntimeError("Task resource already exists; inspect and stop it first")
    if docker("network", "inspect", f"{PREFIX}-net", check=False).returncode == 0:
        raise RuntimeError("Task network already exists")
    for image in IMAGES:
        if docker("image", "inspect", image, check=False).returncode:
            docker("pull", image)
    docker("network", "create", "--label", LABEL, f"{PREFIX}-net")
    try:
        env = os.environ.copy()
        env.update(MYSQL_ROOT_PASSWORD=secrets.token_hex(32), MYSQL_PASSWORD=secrets.token_hex(32),
                   MYSQL_DATABASE="devmate", MYSQL_USER="devmate")
        docker("run", "-d", "--name", f"{PREFIX}-db", "--label", LABEL,
               "--network", f"{PREFIX}-net",
               *[arg for key in ("MYSQL_ROOT_PASSWORD", "MYSQL_PASSWORD", "MYSQL_DATABASE", "MYSQL_USER")
                 for arg in ("-e", key)], IMAGES[0], "--default-time-zone=+00:00", env=env)
        wait_for(lambda: docker("exec", f"{PREFIX}-db", "sh", "-c",
                 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u devmate devmate -N -e "SELECT 1"', check=False).returncode == 0,
                 "MySQL readiness")
        stub(scenario, delay)
        env.update(DB_URL=f"jdbc:mysql://{PREFIX}-db:3306/devmate", DB_USERNAME="devmate",
                   DB_PASSWORD=env["MYSQL_PASSWORD"], JWT_SECRET=secrets.token_hex(32),
                   SPRING_PROFILES_ACTIVE="dev", SERVER_PORT="8080", AI_ENABLED=str(not disabled).lower(),
                   OPENAI_BASE_URL=f"http://{PREFIX}-stub:19090/v1", OPENAI_API_KEY=secrets.token_hex(32),
                   OPENAI_MODEL="acceptance-model", AI_READ_TIMEOUT="PT5S", AI_GENERATION_LEASE="PT10S",
                   LOGGING_LEVEL_ORG_SPRINGFRAMEWORK_BOOT_AUTOCONFIGURE_SECURITY="ERROR", JWT_EXPIRATION="PT2H")
        app(env)
    except Exception:
        stop()
        raise


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("start", "stop", "stub", "app"))
    parser.add_argument("--disabled", action="store_true")
    parser.add_argument("--short-session", action="store_true")
    parser.add_argument("--scenario", choices=("success", "delay", "rate-limit", "unavailable", "invalid"), default="success")
    parser.add_argument("--delay-ms", type=int, default=1500)
    args = parser.parse_args()
    if not 0 <= args.delay_ms <= 150000:
        parser.error("delay must be between 0 and 150000 ms")
    if args.action == "start":
        start(args.disabled, args.scenario, args.delay_ms)
    elif args.action == "stop":
        stop()
    elif args.action == "app":
        restart_app(args.disabled, args.short_session)
    else:
        if not owned(f"{PREFIX}-net", network=True):
            raise RuntimeError("Start the task environment first")
        stub(args.scenario, args.delay_ms)
