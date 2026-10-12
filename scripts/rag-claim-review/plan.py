"""Reproducible public request preparation. Prints hashes/bounds only; never sends.

This is a plan, never authorization. Exact canonical UTF-8 bytes stay in memory.
"""
import json
import subprocess
from pathlib import Path

from adapter import configured_validator
from review import ARMS, LOCATORS, MANIFEST_SHA, ReviewError, digest, load_context, require, safe_context
from run import ROOT, public_source
from provider import MODEL

HERE = Path(__file__).resolve().parent
ENDPOINT = 'https://api.deepseek.com/chat/completions'
PRICE = {'currency': 'CNY', 'checkedDate': '2026-10-12',
         'inputMissMicrosPerToken': 2, 'outputMicrosPerToken': 8,
         'cacheDiscountUsed': False, 'offPeakDiscountUsed': False,
         'source': 'https://api-docs.deepseek.com/zh-cn/quick_start/pricing/'}
REQUEST_INPUT_SHA = '5144607270b3fa6b64e988f4921fa9bafbfa7499a97f0d1a89031d5ed0892b26'
PLAN_SHA = 'c5f81231b9b79aeffa7db3bac5f9fccf19a883705c2e928d6eafbf0c180bb750'


def source_digest(path):
    return digest(path.read_bytes().replace(b'\r\n', b'\n'))


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'),
                      allow_nan=False).encode('utf8')


def inputs():
    blob = (HERE / 'request-inputs.json').read_bytes()
    require(digest(blob) == REQUEST_INPUT_SHA, 'PLAN_DRIFT')
    return json.loads(blob)


def prepare(context):
    safe_context(context)
    require(context.mode == 'FROZEN' and not context.returned, 'PLAN_DRIFT')
    manifest = inputs()
    q = next(q for q in manifest['questions'] if q['number'] == context.question)
    require(q['question'] == context.prompt and
            [{k: c[k] for k in LOCATORS} for c in q['candidates']] == [dict(c) for c in context.chunks],
            'PLAN_DRIFT')
    hits = []
    for c in q['candidates']:
        text = next(t for h, t in context.sources.values() if h == c['sourceSha256'])
        hits.append({**c, 'text': text[c['start']:c['end']]})
    command = configured_validator(ROOT).command[:]
    command[-1] = 'com.devmate.conversation.service.ClaimReviewRequestBridge'
    output = action = result = None
    try:
        action = canonical(dict(arm=context.arm, question=context.prompt, hits=hits))
        result = subprocess.run(command, input=action, capture_output=True, timeout=30)
        require(result.returncode == 0 and len(result.stdout) <= 1048576, 'PLAN_BRIDGE')
        output = json.loads(result.stdout)
        payload = output['payload']
        require(payload['model'] == MODEL and payload['stream'] is False
                and payload['thinking'] == {'type': 'disabled'} and payload['max_tokens'] == 1024,
                'PLAN_DRIFT')
        body = canonical(payload)
        require(len(body) <= 98304 and len(body.decode('utf8')) <= 24000, 'PLAN_DRIFT')
        bound = len(body) + 1024
        row = dict(question=context.question, arm=context.arm, payloadSha256=digest(body),
                   payloadBytes=len(body), inputTokenBound=bound, maxOutputTokens=1024,
                   reservedMicros=bound * 2 + 1024 * 8,
                   templateVersion=output['templateVersion'], exampleVersion=output['exampleVersion'])
        return body, row
    except BaseException:
        raise ReviewError('PLAN_BRIDGE') from None
    finally:
        hits = output = action = result = None


def build_plan():
    rows = []
    blob = (HERE / 'frozen-inputs.json').read_bytes()
    for number in range(1, 11):
        offset = (number - 1) % 4
        for arm in ARMS[offset:] + ARMS[:offset]:
            context = load_context(blob, public_source, number, arm, [])
            body, row = prepare(context)
            rows.append(row)
            body = context = None
    value = dict(version='claim-review-new-round-plan-v1', authorization='NOT_GRANTED',
                 liveEnabled=False, model=MODEL, endpoint=ENDPOINT, method='POST',
                 readiness='BLOCKED_INPUT_BOUND_PROOF',
                 contentType='application/json', encoding='UTF-8 sorted keys, compact, non-ASCII literal',
                 manifestSha256=MANIFEST_SHA, sourceCommit=json.loads(blob)['sourceCommit'],
                 requestInputsSha256=REQUEST_INPUT_SHA,
                 templateSha256=source_digest(ROOT / 'devmate-server/src/main/java/com/devmate/conversation/service/ProjectRagPromptBuilder.java'),
                 requestBridgeSha256=source_digest(ROOT / 'devmate-server/src/test/java/com/devmate/conversation/service/ClaimReviewRequestBridge.java'),
                 price=PRICE, chatCalls=40, embeddingCalls=0, capMicros=2000000,
                 inputBoundRule='UTF-8 payload bytes + 1024 framing tokens; conservative planning assumption, recheck before execution',
                 outputTokenBound=1024, concurrency=1, retries=0, timeoutSeconds=120,
                 maxResponseBytes=1048576, resume=False, topUp=False,
                 egress='public question + original five candidates with frozen metadata + fixed v1/group configuration; empty history + fixed synthetic project metadata',
                 stops=['unknown', 'provider failure', 'pairing failure', 'usage missing/invalid/over bounds',
                        'persistence failure', 'interruption', 'price/model/scope drift'],
                 totalReservedMicros=sum(r['reservedMicros'] for r in rows), samples=rows)
    require(value['totalReservedMicros'] <= value['capMicros'], 'PLAN_BUDGET')
    return value


def verify_plan():
    frozen, sha = frozen_plan()
    require(frozen == build_plan(), 'PLAN_DRIFT')
    return sha


def frozen_plan():
    try:
        frozen = json.loads((HERE / 'new-round-plan.json').read_bytes())
        sha = digest(canonical(frozen))
        require(sha == PLAN_SHA and frozen['authorization'] == 'NOT_GRANTED'
                and frozen['liveEnabled'] is False, 'PLAN_DRIFT')
        return frozen, sha
    except BaseException:
        raise ReviewError('PLAN_DRIFT') from None


if __name__ == '__main__':
    try:
        print(json.dumps(dict(planSha256=verify_plan(), paidRequests=0, authorization='NOT_GRANTED')))
    except BaseException:
        print('PLAN_STOPPED')
        raise SystemExit(1) from None
