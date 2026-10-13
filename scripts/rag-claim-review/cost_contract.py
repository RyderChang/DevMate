"""Read-only hosted cost evidence audit. Never authorizes or sends a request.

Default uses reviewed summaries and regenerates frozen payloads in captured pipes.
Optional source replay reads pinned public files as DATA, never executes them.
"""
import json
import re
import sys
from pathlib import Path

from execution import CONTEXT_LIMIT, PREPARATION_SHA, evidence, frozen_preparation
from plan import PLAN_SHA, canonical, frozen_plan, prepare
from review import ReviewError, digest, integer, keys, load_context, require
from run import HERE, ROOT, public_source

SOURCE_REVIEW_SHA = 'b0b36d622b22c8ef0ebea8c34566098d2dee975d6983d4ff64cc5d638f3e3a2d'
AUDIT_SHA = '37e71d794d657379c732a6d186699651c0e86a963cecb259cdbc8dd08584d43d'
RECIPE_REVISION = '8cadfede7063c896b944e7bae05daa3549ae97ea'
JSON_INSTRUCTION = '\n\n## Response Format:\n\nYou MUST strictly adhere to the following schema to reply:\n'
JSON_SCHEMA = '{"type": "json_object"}'
HOSTED_GAPS = ('TOKENIZER_REVISION_BINDING', 'CONTENT_PREPROCESSING_BOUND', 'JSON_MODE_FRAMING_BOUND')


def source_review():
    try:
        value = json.loads((HERE / 'cost-contract-sources.json').read_bytes())
        require(digest(canonical(value)) == SOURCE_REVIEW_SHA, 'COST_AUDIT_DRIFT')
        require(value['hostedContract'] == {gap: 'UNPROVEN' for gap in HOSTED_GAPS}
                and value['recipeRevision'] == RECIPE_REVISION
                and value['liveEnabled'] is False and value['authorization'] == 'NOT_GRANTED'
                and value['paidRequests'] == 0, 'COST_AUDIT_DRIFT')
        return value
    except BaseException:
        raise ReviewError('COST_AUDIT_DRIFT') from None


def reference_row(body, frozen_row):
    """Only the reviewed reference renderer's two-text-message byte bound.

    No rendering/tokenizer library is installed. This arithmetic does NOT claim
    the hosted model uses this recipe, tokenizer, preprocessing or JSON framing.
    """
    try:
        require(type(body) is bytes and digest(body) == frozen_row['payloadSha256'], 'COST_AUDIT_DRIFT')
        payload = json.loads(body)
        json_mode = frozen_row['arm'] in ('json_mode', 'combined')
        fields = ('model', 'stream', 'thinking', 'max_tokens', 'messages')
        keys(payload, fields + (('response_format',) if json_mode else ()))
        require(payload['model'] == 'deepseek-flash' and payload['stream'] is False
                and payload['thinking'] == {'type': 'disabled'}
                and type(payload['max_tokens']) is int and payload['max_tokens'] == 1024, 'COST_AUDIT_DRIFT')
        if json_mode: require(payload['response_format'] == {'type': 'json_object'}, 'COST_AUDIT_DRIFT')
        messages = payload['messages']
        require(type(messages) is list and len(messages) == 2, 'COST_AUDIT_DRIFT')
        for message, role in zip(messages, ('system', 'user')):
            keys(message, ('role', 'content'))
            require(message['role'] == role and type(message['content']) is str, 'COST_AUDIT_DRIFT')
        content_bytes = sum(len(m['content'].encode('utf8')) for m in messages)
        frame_bytes = evidence()['profile']['referenceChatFrameBytes']
        suffix_bytes = len((JSON_INSTRUCTION + JSON_SCHEMA).encode('utf8')) if json_mode else 0
        return dict(question=frozen_row['question'], arm=frozen_row['arm'],
                    payloadSha256=frozen_row['payloadSha256'], messageUtf8Bytes=content_bytes,
                    referenceFrameBytes=frame_bytes, referenceJsonSuffixBytes=suffix_bytes,
                    referenceInputTokenBound=content_bytes + frame_bytes + suffix_bytes,
                    hostedInputTokenBound=None, usableForPaidReservation=False)
    except BaseException:
        raise ReviewError('COST_AUDIT_DRIFT') from None


def analyze(rows):
    review = source_review()
    plan, _ = frozen_plan()
    preparation = frozen_preparation()
    require(len(rows) == 40 and [(r['question'], r['arm'], r['payloadSha256']) for r in rows]
            == [(r['question'], r['arm'], r['payloadSha256']) for r in plan['samples']], 'COST_AUDIT_DRIFT')
    require(all(r['hostedInputTokenBound'] is None and r['usableForPaidReservation'] is False
                for r in rows), 'COST_AUDIT_DRIFT')
    for row in rows:
        keys(row, ('question', 'arm', 'payloadSha256', 'messageUtf8Bytes', 'referenceFrameBytes',
                   'referenceJsonSuffixBytes', 'referenceInputTokenBound',
                   'hostedInputTokenBound', 'usableForPaidReservation'))
        integer(row['messageUtf8Bytes'], 0, 98304)
        integer(row['referenceInputTokenBound'], 0, 100000)
        require(type(row['referenceFrameBytes']) is int and row['referenceFrameBytes'] == 80
                and type(row['referenceJsonSuffixBytes']) is int
                and row['referenceJsonSuffixBytes'] == (review['referenceJsonSuffixBytes']
                    if row['arm'] in ('json_mode', 'combined') else 0)
                and row['referenceInputTokenBound'] == row['messageUtf8Bytes'] + 80
                    + row['referenceJsonSuffixBytes'], 'COST_AUDIT_DRIFT')
    price = review['observedPrice']
    require(price['inputMissMicrosPerToken'] == plan['price']['inputMissMicrosPerToken'] == 2
            and price['outputMicrosPerToken'] == plan['price']['outputMicrosPerToken'] == 8
            and price['model'] == plan['model'] == 'deepseek-flash', 'COST_AUDIT_DRIFT')
    output_tokens = sum(r['maxOutputTokens'] for r in plan['samples'])
    output_reserve = output_tokens * 8
    allowance = (plan['capMicros'] - output_reserve) // 2
    assumed_input = sum(r['inputTokenBound'] for r in plan['samples'])
    # For I+O<=C, I*2+O*8 is maximal at O=1024, I=C-1024.
    # This is a separate documented-context arithmetic candidate, never a new
    # frozen authorization or a replacement for the existing audited fallback.
    shared_context = sum((CONTEXT_LIMIT - r['maxOutputTokens']) * 2 + r['maxOutputTokens'] * 8
                         for r in plan['samples'])
    reference_input = sum(r['referenceInputTokenBound'] for r in rows)
    return dict(version='claim-hosted-cost-audit-v1', checkedDate='2026-10-13',
                parentPlanSha256=PLAN_SHA, preparationSha256=PREPARATION_SHA,
                sourceReviewSha256=SOURCE_REVIEW_SHA,
                authorization='NOT_GRANTED', liveEnabled=False, paidRequests=0,
                decision='BLOCKED_HOSTED_BOUND', model=plan['model'],
                chatCalls=40, embeddingCalls=0, capMicros=plan['capMicros'],
                observedPrice=price, sourceVerifiedInCi=False,
                hostedContract=review['hostedContract'],
                totalOutputTokens=output_tokens, outputReservedMicros=output_reserve,
                requiredTotalInputTokenBoundAtCap=allowance,
                equalAllocationInputThreshold=allowance // 40,
                originalAssumedInputTokens=assumed_input,
                originalAssumedReservedMicros=plan['totalReservedMicros'],
                originalAssumptionHeadroomTokens=allowance - assumed_input,
                referenceInputTokenBound=reference_input,
                referenceReservedMicros=reference_input * 2 + output_reserve,
                referenceScope='PINNED_RECIPE_ONLY_NOT_HOSTED_PROOF',
                hostedInputTokenBound=None, hostedReservedMicros=None,
                sharedContextCandidateReservedMicros=shared_context,
                sharedContextCandidateShortfallMicros=max(0, shared_context - plan['capMicros']),
                frozenFallbackReservedMicros=preparation['totalReservedMicros'],
                frozenFallbackChanged=False, ownerEnvironment='UNCONFIRMED', samples=rows)


def build_audit():
    rows = []
    plan, _ = frozen_plan()
    blob = (HERE / 'frozen-inputs.json').read_bytes()
    for row in plan['samples']:
        context = load_context(blob, public_source, row['question'], row['arm'], [])
        body, regenerated = prepare(context)
        require(row == regenerated, 'COST_AUDIT_DRIFT')
        rows.append(reference_row(body, row))
        body = context = None
    return analyze(rows)


def verify_audit():
    try:
        expected = json.loads((HERE / 'cost-contract-audit.json').read_bytes())
        require(digest(canonical(expected)) == AUDIT_SHA and expected == build_audit(), 'COST_AUDIT_DRIFT')
        return expected
    except BaseException:
        raise ReviewError('COST_AUDIT_DRIFT') from None


def verify_local_sources(directory):
    """Byte/SHA replay only; source findings remain a documented manual audit."""
    try:
        review = source_review()
        for row in review['sources']:
            raw = (Path(directory) / row['localFile']).read_bytes()
            require(len(raw) == row['bytes'] and digest(raw) == row['sha256'], 'COST_SOURCE_DRIFT')
        # Read source strings as data, checking the relevant reviewed constants.
        source = (Path(directory) / 'deepseek-recipe-encoding__src__v4__mod.rs').read_text(encoding='utf8')
        literals = [json.loads('"' + s + '"') for s in re.findall(r'content\.push_str\("((?:[^"\\]|\\.)*)"\);', source)]
        require(JSON_INSTRUCTION in literals and 'ResponseFormat::JsonObject' in source, 'COST_SOURCE_DRIFT')
        return len(review['sources'])
    except BaseException:
        raise ReviewError('COST_SOURCE_DRIFT') from None


if __name__ == '__main__':
    try:
        require(sys.argv[1:] in ([], ['--verify-sources']), 'COST_AUDIT_DRIFT')
        if sys.argv[1:]:
            print(json.dumps(dict(sourceFiles=verify_local_sources(ROOT / 'tmp/dev-034/hosted-cost-sources'),
                                  sourceExecuted=False, paidRequests=0, hostedBound='UNPROVEN')))
        else:
            value = verify_audit()
            print(json.dumps({k: value[k] for k in ('decision', 'authorization', 'liveEnabled', 'paidRequests',
                  'requiredTotalInputTokenBoundAtCap', 'referenceReservedMicros',
                  'sharedContextCandidateReservedMicros', 'frozenFallbackReservedMicros', 'ownerEnvironment')}))
    except BaseException:
        print('COST_AUDIT_STOPPED')
        raise SystemExit(1) from None
