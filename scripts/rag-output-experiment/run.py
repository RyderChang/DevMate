"""Four-arm offline preflight / opt-in DeepSeek experiment. Never persist bodies.

Java builds the real v1 prompt and validates responses using the production gateway and
RAG validator over an in-memory pipe. Live mode is bounded and deliberately has no retry.
The cache is historical public retrieval data; no historical answers are used.
"""
import argparse
from datetime import date
from decimal import Decimal
import getpass
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid

from collect import SOURCE_FIELDS, collect

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
ARMS = ("baseline", "example", "json_mode", "combined")
MAX_RESPONSE_BYTES = 1048576


def digest(value):
    return hashlib.sha256(value).hexdigest()


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()


def reserve_for(payload):
    # One token per transmitted input byte, plus 1 KiB provider framing; peak prices.
    return (Decimal(len(canonical(payload)) + 1024) * 2 + Decimal(1024) * 8) / 1000000


def hit_metadata(hit):
    return {k: hit[k] for k in (*SOURCE_FIELDS, "score")}


def frozen_inputs(cache_path, frozen_root):
    manifest_bytes = (HERE / "frozen-inputs.json").read_bytes()
    manifest = json.loads(manifest_bytes)
    for source in manifest["sources"]:
        if digest((frozen_root / source["path"]).read_bytes()) != source["sha256"]:
            raise ValueError("Frozen source drift")
    cache = json.loads(cache_path.read_text(encoding="utf-8"))
    rows = cache["results"]
    if len(rows) != 10 or {row["number"] for row in rows} != set(range(1, 11)):
        raise ValueError("Frozen question set drift")
    result = []
    for frozen in manifest["questions"]:
        row = next(row for row in rows if row["number"] == frozen["number"])
        search = row["searchResponse"]["data"]
        hits = search["hits"]
        if (row["searchHttpStatus"] != 200 or search["incomplete"] is not False
                or row["question"] != frozen["question"] or len(hits) != 5
                or [hit_metadata(hit) for hit in hits] != frozen["candidates"]):
            raise ValueError("Frozen candidate drift")
        if any(digest(hit["text"].encode()) != hit["chunkSha256"] for hit in hits):
            raise ValueError("Frozen chunk content drift")
        # Only explicitly approved public question and hits; never use cached ragResponse.
        result.append({"number": frozen["number"], "question": row["question"], "hits": hits,
                       "targets": frozen["targets"]})
    return result, digest(manifest_bytes)


def bridge(action):
    java_home = os.environ.get("JAVA_HOME")
    if not java_home:
        raise ValueError("Select JDK 21 using JAVA_HOME")
    java = Path(java_home) / "bin" / ("java.exe" if os.name == "nt" else "java")
    classpath = (ROOT / "tmp/dev-032/test-classpath.txt").read_text().strip()
    classpath = os.pathsep.join((str(ROOT / "devmate-server/target/test-classes"),
                               str(ROOT / "devmate-server/target/classes"), classpath))
    process = subprocess.run([str(java), "-cp", classpath,
                              "com.devmate.conversation.service.RagOutputExperimentBridge"],
                             input=canonical(action), capture_output=True, timeout=30)
    # Do not echo process stdout/stderr: they may contain a transient prompt or answer.
    if process.returncode:
        raise ValueError("Offline Java bridge failed")
    result = json.loads(process.stdout)
    if "bridgeError" in result:
        raise ValueError("Offline Java bridge rejected input")
    return result


def dispatch(payload, key):
    raw = canonical(payload)
    if len(raw) > 98304:
        raise ValueError("Request byte limit exceeded")
    request = urllib.request.Request("https://api.deepseek.com/chat/completions", data=raw,
              headers={"Authorization": "Bearer " + key, "Content-Type": "application/json"}, method="POST")
    # Default urllib opener can follow redirects. Use a dedicated opener which rejects them.
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, *args, **kwargs):
            return None
    try:
        with urllib.request.build_opener(NoRedirect()).open(request, timeout=120) as response:
            content = response.read(MAX_RESPONSE_BYTES + 1)
            if len(content) > MAX_RESPONSE_BYTES:
                return {"status": "FAILED", "failureStage": "PROVIDER_RESPONSE", "failureCategory": "RESPONSE_SIZE"}
            try:
                envelope = content.decode("utf-8", errors="strict")
            except UnicodeDecodeError:
                return {"status": "FAILED", "failureStage": "PROVIDER_RESPONSE", "failureCategory": "RESPONSE_ENCODING"}
            return bridge({"action": "evaluate", "envelope": envelope, "allowed": ["C1", "C2", "C3", "C4", "C5"]})
    except urllib.error.HTTPError as error:
        # Never read, log, or store upstream error body, reason, headers or request ID.
        return {"status": "FAILED", "failureStage": "PROVIDER_HTTP", "failureCategory": "UPSTREAM_STATUS",
                "httpStatus": error.code}
    except (urllib.error.URLError, TimeoutError, OSError):
        return {"status": "UNKNOWN", "failureStage": "TRANSPORT", "failureCategory": "RESULT_UNKNOWN"}


def semantic_review(number, answer, targets, frozen_root):
    # The transient answer stays in a review window, never terminal history or result files.
    import tkinter as tk
    from tkinter import ttk
    window = tk.Tk()
    window.title(f"Q{number} transient semantic review — do not copy or save")
    text = tk.Text(window, wrap="word", width=100, height=25)
    manifest = json.loads((HERE / "frozen-inputs.json").read_text(encoding="utf-8"))
    question = next(q["question"] for q in manifest["questions"] if q["number"] == number)
    references = []
    for slot, target in targets.items():
        source = next(s for s in manifest["sources"] if s["sha256"] == target["sourceSha256"])
        lines = (frozen_root / source["path"]).read_text(encoding="utf-8").splitlines()
        references.append(f"{slot} — {source['path']}:{target['startLine']}-{target['endLine']}\n"
                          + "\n".join(lines[target["startLine"] - 1:target["endLine"]]))
    text.insert("1.0", question + "\n\n" + answer + "\n\nFrozen core evidence:\n" + "\n\n".join(references))
    text.configure(state="disabled")
    text.pack(fill="both", expand=True)
    values = {}
    labels = {slot: f"{slot}: core conclusion supported by frozen evidence?" for slot in targets}
    if number == 9:
        labels["extra"] = "Q9: all extra factual claims supported?"
    if number == 10:
        labels["abstention"] = "Q10: explicitly declines to invent production point count?"
    for slot, label in labels.items():
        frame = ttk.Frame(window)
        frame.pack(fill="x")
        ttk.Label(frame, text=label).pack(side="left")
        variable = tk.StringVar(value="UNKNOWN")
        ttk.Combobox(frame, state="readonly", textvariable=variable,
                     values=("UNKNOWN", "SUPPORTED", "CONTRADICTED", "INSUFFICIENT")).pack(side="right")
        values[slot] = variable
    submitted = False
    result = {}
    def submit():
        nonlocal submitted
        result.update({slot: variable.get() for slot, variable in values.items()})
        submitted = True
        window.destroy()
    ttk.Button(window, text="Save fixed verdicts only", command=submit).pack()
    window.mainloop()
    if not submitted:
        raise ValueError("Semantic review interrupted; no retry")
    return ({slot: result[slot] for slot in targets}, result.get("extra", "UNKNOWN"),
            result.get("abstention", "NOT_APPLICABLE"))


def summary(rows):
    arms = {}
    for arm in ARMS:
        samples = [row for row in rows if row["arm"] == arm]
        frequencies = {}
        for row in samples:
            category = row.get("failureCategory")
            if category:
                frequencies[category] = frequencies.get(category, 0) + 1
        slots = [slot for row in samples for slot in row.get("collection", {}).get("evidenceSlots", {}).values()]
        arms[arm] = {"attempted": len(samples), "succeeded": sum(row["status"] == "SUCCEEDED" for row in samples),
                     "expectedEvidenceSlots": 17, "collectedEvidenceSlots": len(slots),
                     "failureCategories": frequencies, "semanticKnown": sum(s["semanticVerdict"] != "UNKNOWN" for s in slots),
                     "semanticSupported": sum(s["semanticVerdict"] == "SUPPORTED" for s in slots),
                     "sourceCovered": sum(s["sourceCoverage"] == "COVERED" for s in slots),
                     "knownUsageCount": sum("usage" in row for row in samples),
                     "tokens": {field: sum(row.get("usage", {}).get(field, 0) for row in samples)
                                for field in ("inputTokens", "outputTokens", "totalTokens")},
                     "q9": [row.get("collection") for row in samples if row["question"] == 9],
                     "q10Abstention": [row.get("collection", {}).get("abstentionVerdict", "UNKNOWN")
                                       for row in samples if row["question"] == 10],
                     "conservativeChargeCny": str(sum((Decimal(row["conservativeChargeCny"]) for row in samples), Decimal(0)))}
    return arms


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--cache", type=Path, default=ROOT / "tmp/dev-027/results.json")
    parser.add_argument("--frozen-root", type=Path, default=ROOT / "tmp/dev-029/frozen")
    parser.add_argument("--live", action="store_true")
    parser.add_argument("--approved-requests", type=int)
    parser.add_argument("--approved-cny", type=Decimal)
    parser.add_argument("--approved-scope-sha256")
    parser.add_argument("--price-checked", help="Date of manual official price verification, YYYY-MM-DD")
    args = parser.parse_args()
    inputs, scope = frozen_inputs(args.cache, args.frozen_root)
    if args.live and (args.approved_requests != 40 or args.approved_cny is None
                     or args.approved_cny <= 0 or args.approved_cny > Decimal("2")
                     or args.approved_scope_sha256 != scope or args.price_checked != date.today().isoformat()
                     or not sys.stdin.isatty() or not sys.stdout.isatty()):
        raise ValueError("Live run requires approved 40-request scope, sufficient cap <=2 CNY, today's price check and private interactive terminal")
    prepared = []
    # Complete all drift/bounds checks before reading a key or dispatching any request.
    for question in inputs:
        offset = (question["number"] - 1) % len(ARMS)
        for arm in ARMS[offset:] + ARMS[:offset]:
            request = bridge({"action": "request", "arm": arm, "question": question["question"], "hits": question["hits"]})
            prepared.append((question, arm, request))
    total_reserve = sum((reserve_for(request["payload"]) for _, _, request in prepared), Decimal(0))
    if args.live and total_reserve > args.approved_cny:
        raise ValueError("Frozen requests exceed approved budget")
    if not args.live:
        print(json.dumps({"preflight": "PASS", "questions": len(inputs), "arms": len(ARMS),
                          "boundedRequestsPrepared": len(prepared), "paidRequests": 0, "scopeSha256": scope,
                          "conservativeReserveCny": str(total_reserve)}))
        return
    import tkinter as tk
    check_window = tk.Tk()
    check_window.withdraw()
    check_window.destroy()  # Confirm local review works before any paid call.
    key = getpass.getpass("DeepSeek API key (hidden, memory only): ")
    if not key or key != key.strip():
        raise ValueError("Invalid key")
    output_dir = ROOT / "tmp/dev-032"
    output_dir.mkdir(parents=True, exist_ok=True)
    run_id = uuid.uuid4().hex
    # One live run per frozen scope. Do not silently resume, refill or create a second quota.
    with (output_dir / ("live-started-" + scope + ".json")).open("x", encoding="utf-8") as lock:
        json.dump({"runId": run_id, "scopeSha256": scope, "approvedRequests": 40}, lock)
    path = output_dir / ("results-" + run_id + ".json")
    rows = []
    output = {"experimentVersion": "rag-output-four-arms-v1", "scopeSha256": scope,
              "approvedRequests": 40, "approvedCapCny": str(args.approved_cny), "actualChargeCny": None,
              "actualChargeStatus": "ACCOUNT_BILLING_REQUIRES_REVIEW", "results": rows}
    def save():
        output["summary"] = summary(rows)
        path.write_text(json.dumps(output, ensure_ascii=False, indent=2), encoding="utf-8")
    try:
        for question, arm, request in prepared:
            reserve = reserve_for(request["payload"])
            if (sum((Decimal(row["reservedChargeCny"]) for row in rows), Decimal(0)) + reserve > args.approved_cny
                    or len(rows) >= args.approved_requests):
                raise ValueError("Frozen budget exceeded")
            row = {"requestId": str(uuid.uuid4()), "question": question["number"], "arm": arm,
                   "templateVersion": request["templateVersion"], "status": "UNKNOWN",
                   "reservedChargeCny": str(reserve), "conservativeChargeCny": str(reserve),
                   "requestSha256": digest(canonical(request["payload"]))}
            rows.append(row)
            save()  # Persist reservation before sending; interrupted/unknown attempts consume a slot.
            started = time.monotonic()
            evaluated = dispatch(request["payload"], key)
            row["durationMs"] = int((time.monotonic() - started) * 1000)
            for field in ("status", "failureStage", "failureCategory", "httpStatus", "usage"):
                if field in evaluated:
                    row[field] = evaluated[field]
            if "usage" in evaluated:
                usage = evaluated["usage"]
                row["conservativeChargeCny"] = str((Decimal(usage["inputTokens"]) * 2
                                                    + Decimal(usage["outputTokens"]) * 8) / 1000000)
            save()  # Preserve category/usage even if semantic review is interrupted.
            if evaluated["status"] == "SUCCEEDED":
                sources = {"C" + str(n): hit for n, hit in enumerate(question["hits"], 1)}
                citations = [{"citationId": cid, "source": sources[cid]} for cid in evaluated["citationIds"]]
                row["collection"] = collect(evaluated["answer"], citations, question["targets"])
                save()  # Source mapping survives interruption before manual semantic review.
                verdicts, extra, abstention = semantic_review(question["number"], evaluated["answer"], question["targets"], args.frozen_root)
                row["collection"] = collect(evaluated["answer"], citations, question["targets"], verdicts)
                row["collection"]["extraClaimsVerdict"] = extra
                row["collection"]["abstentionVerdict"] = abstention
                row["collection"]["semanticReviewer"] = "OWNER_MANUAL"
                save()
            del evaluated
            print(f"Q{question['number']} {arm}: {row['status']} (no retry)", flush=True)
            # Stop on ambiguous transport/HTTP results; do not refill an interrupted quota.
            if row["status"] == "UNKNOWN" or row.get("failureStage") in ("PROVIDER_HTTP", "TRANSPORT"):
                break
            if Decimal(row["conservativeChargeCny"]) > reserve:
                raise ValueError("Provider usage exceeds bounded reservation; stop for review")
    finally:
        key = None
        save()
        print(f"Sanitized results: {path}")


if __name__ == "__main__":
    try:
        main()
    except Exception:
        # Upstream/parser/subprocess exceptions can contain sensitive body text.
        print("Experiment stopped; inspect preflight inputs locally. No automatic retry.", file=sys.stderr)
        sys.exit(1)
