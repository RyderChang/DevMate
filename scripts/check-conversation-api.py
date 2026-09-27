"""Exercise only the labelled local DEV-015 environment with disposable synthetic users."""
import concurrent.futures
import json
import secrets
import time
import urllib.error
import urllib.request
import uuid
from conversation_env import docker, owned, stub, wait_for

BASE = "http://127.0.0.1:18085"


def request(method, path, token=None, body=None, expected=200):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(BASE + path, method=method, headers=headers,
                                 data=json.dumps(body).encode() if body is not None else None)
    try:
        response = urllib.request.urlopen(req, timeout=12)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        data = json.load(response)
        assert response.status == expected, f"{method} {path}: expected {expected}, got {response.status}"
        assert data["code"] == expected
        # Security can reject before the MVC trace filter; application responses carry it.
        if expected not in (401, 403):
            assert response.headers.get("X-Trace-Id")
        return data.get("data")


def stats():
    result = docker("exec", "devmate015-stub", "node", "-e",
                    "fetch('http://127.0.0.1:19090/stats').then(r=>r.text()).then(console.log)")
    return json.loads(result.stdout)


def sql(query):
    # Only fixed metadata queries below; no credentials or user content printed.
    return docker("exec", "devmate015-db", "sh", "-c",
                  'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u devmate devmate -N -e "$1"', "sh", query).stdout.strip()


def scenario(name, delay=1500):
    stub(name, delay)
    wait_for(lambda: docker("exec", "devmate015-stub", "node", "-e",
             "fetch('http://127.0.0.1:19090/stats').then(r=>{if(!r.ok)process.exit(1)}).catch(()=>process.exit(1))",
             check=False).returncode == 0, "Stub readiness", 15)


def main():
    assert owned("devmate015-app") and owned("devmate015-db")
    suffix = secrets.token_hex(4)
    password = secrets.token_urlsafe(24)
    tokens = []
    for role in ("owner", "outsider"):
        body = {"username": f"a{suffix}-{role}", "password": password}
        request("POST", "/auth/register", body=body)
        tokens.append(request("POST", "/auth/login", body=body)["token"])
    owner, outsider = tokens
    project = request("POST", "/projects", owner, {"name": "Synthetic acceptance", "description": "Disposable"})["id"]
    other = request("POST", "/projects", owner, {"name": "Second project"})["id"]
    base = f"/projects/{project}/conversations"
    conversation = request("POST", base, owner, {})
    assert conversation["title"] == "New conversation"
    cid = conversation["id"]
    chat = f"{base}/{cid}"
    messages = chat + "/messages"
    send = lambda content="Synthetic question": {"clientRequestId": str(uuid.uuid4()), "content": content}
    request("GET", base, expected=401)
    for target in (f"/projects/{other}/conversations/{cid}", f"{base}/{cid + 999999}"):
        request("GET", target, owner, expected=404)
        request("GET", target + "/messages", owner, expected=404)
        request("POST", target + "/messages", owner, send(), expected=404)
    for method, path, body in (("GET", base, None), ("POST", base, {}), ("GET", chat, None),
                              ("GET", messages, None), ("POST", messages, send())):
        request(method, path, outsider, body, expected=404)
    scenario("success")
    payload = send()
    first = request("POST", messages, owner, payload)
    repeat = request("POST", messages, owner, payload)
    assert first == repeat and stats()["requests"] == 1
    assert first["invocation"]["totalTokens"] == 13
    assert sql(f"SELECT COUNT(*) FROM ai_invocations WHERE conversation_id={cid}") == "1"
    assert sql(f"SELECT COUNT(*) FROM conversation_messages WHERE conversation_id={cid}") == "2"
    for _ in range(25):
        request("POST", messages, owner, send())
    page1 = request("GET", messages + "?page=1&pageSize=50", owner)
    page2 = request("GET", messages + "?page=2&pageSize=50", owner)
    assert page1["total"] == 52 and len(page1["items"]) == 50 and len(page2["items"]) == 2
    assert [m["sequenceNo"] for m in page1["items"] + page2["items"]] == list(range(1, 53))
    request("POST", messages, owner, send("😀" * 8001), expected=400)
    request("POST", messages, owner, send("  "), expected=400)
    request("POST", messages, owner, {"clientRequestId": "bad", "content": "Synthetic"}, expected=400)
    print("PASS: authentication, all outsider routes, project mismatch, UUID replay, persistence and 52-message pagination")
    for mode, delay, expected in (("rate-limit", 0, 503), ("unavailable", 0, 503),
                                  ("invalid", 0, 502), ("delay", 6500, 504)):
        scenario(mode, delay)
        payload = send()
        request("POST", messages, owner, payload, expected)
        request("POST", messages, owner, payload, expected)
        assert stats()["requests"] == 1
        assert request("GET", chat, owner)["generationState"] == "IDLE"
        assert sql(f"SELECT status FROM ai_invocations WHERE conversation_id={cid} AND client_request_id='{payload['clientRequestId']}'") == "FAILED"
    scenario("delay", 2500)
    with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
        pending = pool.submit(request, "POST", messages, owner, send())
        wait_for(lambda: stats()["active"] == 1, "In-flight request", 5)
        request("POST", messages, owner, send(), expected=409)
        pending.result()
    assert stats()["requests"] == 1
    request("DELETE", f"/projects/{project}", owner)
    for method, path, body in (("GET", base, None), ("POST", base, {}), ("GET", chat, None),
                              ("GET", messages, None), ("POST", messages, send())):
        request(method, path, owner, body, expected=404)
    scenario("success")
    print("PASS: fault persistence/replay, timeout, active lease conflict and deleted-project isolation")


if __name__ == "__main__":
    main()
