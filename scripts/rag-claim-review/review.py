"""Transient manual review. No provider client, answer loader or free-text receipt."""
import hashlib
import json
import os
import re
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from types import MappingProxyType

VERSION = 'claim-review-v1'
ARMS = ('baseline', 'example', 'json_mode', 'combined')
CORE = {1: ('E1', 'E2'), 2: ('E3', 'E4'), 3: ('E5', 'E6'), 4: ('E7', 'E8'),
        5: ('E9',), 6: ('E10', 'E11'), 7: ('E12', 'E13'), 8: ('E14', 'E15'),
        9: ('E16', 'E17'), 10: ()}
SUPPORT = ('SUPPORTED', 'CONTRADICTED', 'INSUFFICIENT', 'CONFLICTING', 'UNKNOWN')
TYPES = ('DIRECT', 'INFERENCE', 'NONE', 'UNKNOWN')
MATCH = ('SUPPORTS', 'CONTRADICTS', 'INSUFFICIENT', 'IRRELEVANT', 'UNKNOWN')
BINDING = ('LOCAL', 'GLOBAL_ONLY', 'ABSENT', 'AMBIGUOUS', 'UNKNOWN')
LOCATORS = ('sourceSha256', 'chunkSha256', 'ordinal', 'start', 'end', 'startLine', 'endLine')
PATHS = ('README.md', 'docs/api/document-retrieval.md', 'docs/api/rag-conversation.md',
         'docs/api/document-indexing.md', 'docs/testing/frontend-rag-citations-acceptance.md',
         'docs/requirements/devmate-baseline.md', 'docs/testing/document-indexing-review.md')
MAX_ANSWER = 32768
# Updated only after deliberately reviewing the committed public manifest.
MANIFEST_SHA = '2ff08dc4060f74fe18cc10112b9988e3fa4bf2c11439699407b65ba3b2f5a506'


class ReviewError(Exception):
    """Only fixed, non-content error codes leave validation and persistence."""


def require(condition, code='INVALID_REVIEW'):
    if not condition:
        raise ReviewError(code)


def keys(value, expected):
    require(type(value) is dict and set(value) == set(expected))


def choice(value, allowed):
    require(type(value) is str and value in allowed)
    return value


def integer(value, minimum, maximum):
    require(type(value) is int and minimum <= value <= maximum)
    return value


def array(value, maximum):
    require(type(value) is list and len(value) <= maximum)
    return value


def digest(value):
    return hashlib.sha256(value).hexdigest()


@dataclass(frozen=True, repr=False)
class Context:
    question: int
    arm: str
    scope: str
    mode: str
    prompt: str = field(repr=False)
    sources: object = field(repr=False)
    chunks: tuple = field(repr=False)
    targets: object = field(repr=False)
    returned: tuple


def _returned(values):
    ids = array(values, 5)
    require(len(ids) == len(set(choice(x, ('C1', 'C2', 'C3', 'C4', 'C5')) for x in ids)))
    return tuple(ids)


def load_context(blob, read_source, number, arm, returned):
    """Pinned manifest + SHA-verified public sources; callback receives a fixed path."""
    integer(number, 1, 10); choice(arm, ARMS)
    require(type(blob) is bytes and len(blob) <= 65536 and digest(blob) == MANIFEST_SHA, 'FROZEN_INPUT_INVALID')
    try:
        manifest = json.loads(blob)
        texts = {}
        for i, s in enumerate(manifest['sources']):
            require(s['id'] == f'S{i + 1}' and s['path'] == PATHS[i], 'FROZEN_INPUT_INVALID')
            raw = read_source(manifest['sourceCommit'], s['path'])
            require(type(raw) is bytes and len(raw) <= 262144 and digest(raw) == s['sha256'], 'FROZEN_SOURCE_INVALID')
            texts[s['id']] = (s['sha256'], raw.decode('utf8'))
        require(len(texts) == 7, 'FROZEN_INPUT_INVALID')
        for q in manifest['questions']:
            require(tuple(q['targets']) == CORE[q['number']], 'FROZEN_INPUT_INVALID')
            require(len(q['candidates']) == 5, 'FROZEN_INPUT_INVALID')
            for c in q['candidates']:
                text = next(t for h, t in texts.values() if h == c['sourceSha256'])
                require(digest(text[c['start']:c['end']].encode('utf8')) == c['chunkSha256'], 'FROZEN_SOURCE_INVALID')
        q = next(q for q in manifest['questions'] if q['number'] == number)
        return Context(number, arm, digest(blob), 'FROZEN', q['question'], MappingProxyType(texts),
                       tuple(MappingProxyType(c) for c in q['candidates']),
                       MappingProxyType({e: MappingProxyType(t) for e, t in q['targets'].items()}), _returned(returned))
    except ReviewError:
        raise
    except Exception:
        raise ReviewError('FROZEN_INPUT_INVALID') from None


def demo_context(number=1, arm='baseline', returned=None):
    """Explicit synthetic scope, never reported as the historical/live cohort."""
    integer(number, 1, 10); choice(arm, ARMS)
    text = 'SYNTHETIC_SOURCE_SENTINEL\nSynthetic example only.\n'
    h = digest(text.encode('utf8'))
    c = MappingProxyType(dict(zip(LOCATORS, (h, h, 0, 0, len(text), 1, 2))))
    return Context(number, arm, digest(b'claim-review-demo-v1'), 'SYNTHETIC',
                   '合成演示：逐项评审事实、引用与绑定；不代表真实模型结果。',
                   MappingProxyType({'S1': (h, text)}), (c,) * 5,
                   MappingProxyType({e: MappingProxyType({'sourceSha256': h, 'startLine': 1, 'endLine': 1})
                                     for e in CORE[number]}),
                   _returned(['C1', 'C2'] if returned is None else returned))


def spans(values, answer):
    result = []
    for pair in array(values, 8):
        require(type(pair) is list and len(pair) == 2)
        start = integer(pair[0], 0, len(answer) - 1)
        end = integer(pair[1], start + 1, len(answer))
        require(not result or start >= result[-1][1])
        result.append([start, end])
    return result


def typed_verdict(verdict, kind, allowed):
    choice(verdict, allowed); choice(kind, TYPES)
    require((verdict == 'UNKNOWN') == (kind == 'UNKNOWN'))
    require(verdict not in ('SUPPORTED', 'SUPPORTS', 'CONTRADICTED', 'CONTRADICTS', 'CONFLICTING')
            or kind in ('DIRECT', 'INFERENCE'))


def safe_receipt(receipt):
    keys(receipt, ('status', 'usage', 'reservedMicros'))
    choice(receipt['status'], ('SUCCEEDED', 'FAILED', 'UNKNOWN'))
    integer(receipt['reservedMicros'], 0, 1000000000)
    usage = receipt['usage']
    if usage is not None:
        keys(usage, ('inputTokens', 'outputTokens', 'totalTokens'))
        for n in usage.values(): integer(n, 0, 10000000)
        require(usage['inputTokens'] + usage['outputTokens'] == usage['totalTokens'])
        usage = {k: usage[k] for k in ('inputTokens', 'outputTokens', 'totalTokens')}
    return {'status': receipt['status'], 'usage': usage, 'reservedMicros': receipt['reservedMicros']}


def safe_context(context):
    require(type(context) is Context)
    integer(context.question, 1, 10); choice(context.arm, ARMS)
    choice(context.mode, ('FROZEN', 'OFFLINE_STUB', 'SYNTHETIC'))
    require(context.scope == (digest(b'claim-review-demo-v1') if context.mode == 'SYNTHETIC' else MANIFEST_SHA))
    require(len(context.chunks) == 5 and tuple(context.targets) == CORE[context.question])
    _returned(list(context.returned))
    require(bool(context.sources) and set(context.sources) <= {f'S{i}' for i in range(1, 8)})
    hashes = set()
    for h, text in context.sources.values():
        require(type(text) is str and len(text) <= 262144 and h == digest(text.encode('utf8')))
        hashes.add(h)
    for c in context.chunks:
        require(set(c) == set(LOCATORS) and c['sourceSha256'] in hashes)
        text = next(t for h, t in context.sources.values() if h == c['sourceSha256'])
        integer(c['ordinal'], 0, 10000)
        start = integer(c['start'], 0, len(text) - 1); end = integer(c['end'], start + 1, len(text))
        require(c['chunkSha256'] == digest(text[start:end].encode('utf8')))
        first = integer(c['startLine'], 1, len(text.splitlines()))
        integer(c['endLine'], first, len(text.splitlines()))
    for t in context.targets.values():
        require(set(t) == {'sourceSha256', 'startLine', 'endLine'} and t['sourceSha256'] in hashes)
        text = next(t0 for h, t0 in context.sources.values() if h == t['sourceSha256'])
        first = integer(t['startLine'], 1, len(text.splitlines()))
        integer(t['endLine'], first, len(text.splitlines()))
    if context.mode == 'SYNTHETIC':
        expected = demo_context(context.question, context.arm, list(context.returned))
        require(context.prompt == expected.prompt and context.sources == expected.sources
                and context.chunks == expected.chunks and context.targets == expected.targets)
    else:
        try:
            blob = Path(__file__).with_name('frozen-inputs.json').read_bytes()
            require(digest(blob) == MANIFEST_SHA, 'FROZEN_INPUT_INVALID')
            manifest = json.loads(blob)
            expected = next(q for q in manifest['questions'] if q['number'] == context.question)
            require(context.prompt == expected['question'])
            require({s: h for s, (h, _) in context.sources.items()} == {s['id']: s['sha256'] for s in manifest['sources']})
            require([dict(c) for c in context.chunks] == expected['candidates'] and dict(context.targets) == expected['targets'])
        except ReviewError:
            raise
        except Exception:
            raise ReviewError('FROZEN_INPUT_INVALID') from None


class Session:
    """Answer and public source text stay here/in the window, never in record()."""
    def __init__(self, context, answer, receipt):
        safe_context(context)
        safe = safe_receipt(receipt)
        require(answer is None or type(answer) is str and 0 < len(answer) <= MAX_ANSWER)
        require(receipt['status'] == 'SUCCEEDED' or answer is None)
        require(receipt['status'] == 'SUCCEEDED' or not context.returned)
        if answer is not None:
            ids = re.findall(r'\[C([^\]\r\n]*)\]', answer)
            require(all(x in ('1', '2', '3', '4', '5') and 'C' + x in context.returned for x in ids), 'INVALID_CITATION')
        self.context = context
        self.answer = answer
        self.receipt = safe
        self.sample = uuid.uuid4().hex
        self._claims = []
        self._finish = None
        self.status = 'NOT_REVIEWED'

    def _claim(self, value):
        require(self.answer is not None and self.receipt['status'] == 'SUCCEEDED')
        keys(value, ('spans', 'kind', 'coreIds', 'support', 'supportType', 'checks', 'links', 'unlinkedBinding'))
        ranges = spans(value['spans'], self.answer)
        require(bool(ranges))
        kind = choice(value['kind'], ('CORE', 'EXTRA', 'ABSTENTION'))
        core = array(value['coreIds'], 2)
        require(len(core) == len(set(choice(e, CORE[self.context.question]) for e in core)))
        require(bool(core) == (kind == 'CORE'))
        require(kind != 'ABSTENTION' or self.context.question == 10)
        typed_verdict(value['support'], value['supportType'], SUPPORT)
        checks = []
        for c in array(value['checks'], 8):
            keys(c, ('sourceId', 'startLine', 'endLine'))
            sid = choice(c['sourceId'], tuple(self.context.sources))
            h, text = self.context.sources[sid]
            first = integer(c['startLine'], 1, len(text.splitlines()))
            last = integer(c['endLine'], first, len(text.splitlines()))
            checks.append({'sourceId': sid, 'sourceSha256': h, 'startLine': first, 'endLine': last})
        require(value['support'] not in ('SUPPORTED', 'CONTRADICTED', 'CONFLICTING') or bool(checks))
        links, seen = [], set()
        for link in array(value['links'], 5):
            keys(link, ('citationId', 'correspondence', 'supportType', 'binding', 'markerSpan'))
            cid = choice(link['citationId'], self.context.returned)
            require(cid not in seen); seen.add(cid)
            typed_verdict(link['correspondence'], link['supportType'], MATCH)
            binding = choice(link['binding'], BINDING)
            marker = link['markerSpan']
            if marker is not None:
                marker = spans([marker], self.answer)[0]
                require(self.answer[marker[0]:marker[1]] in (cid, '[' + cid + ']'))
            require(binding != 'LOCAL' or marker is not None)
            require(binding not in ('ABSENT', 'GLOBAL_ONLY') or marker is None)
            locator = {k: self.context.chunks[int(cid[1]) - 1][k] for k in LOCATORS}
            links.append({'citationId': cid, 'correspondence': link['correspondence'],
                          'supportType': link['supportType'], 'binding': binding,
                          'markerSpan': marker, 'source': locator})
        unlinked = value['unlinkedBinding']
        require(unlinked is None if links else unlinked in ('GLOBAL_ONLY', 'ABSENT', 'AMBIGUOUS', 'UNKNOWN'))
        require(unlinked != 'GLOBAL_ONLY' or bool(self.context.returned))
        return {'spans': ranges, 'kind': kind, 'coreIds': list(core), 'support': value['support'],
                'supportType': value['supportType'], 'checks': checks, 'links': links, 'unlinkedBinding': unlinked}

    def add(self, value):
        require(self.status == 'NOT_REVIEWED' and len(self._claims) < 64)
        claim = self._claim(value)
        require(not self._claims or claim['spans'][0][0] >= self._claims[-1]['spans'][0][0])
        # Keep normalized input without caller-owned mutable objects or added locator keys.
        self._claims.append(json.loads(json.dumps(value)))
        return len(self._claims)

    def undo(self):
        require(self.status == 'NOT_REVIEWED' and bool(self._claims))
        self._claims.pop()

    def _decision(self, decision):
        require(self.answer is not None and self.receipt['status'] == 'SUCCEEDED')
        keys(decision, ('inventoryComplete', 'secondPassComplete', 'coreDisposition', 'citationRelevance', 'abstentionVerdict'))
        require(decision['inventoryComplete'] is True and decision['secondPassComplete'] is True, 'INCOMPLETE_REVIEW')
        keys(decision['coreDisposition'], CORE[self.context.question])
        keys(decision['citationRelevance'], self.context.returned)
        claims = [self._claim(x) for x in self._claims]
        for e, v in decision['coreDisposition'].items():
            choice(v, ('PRESENT', 'OMITTED'))
            require((v == 'PRESENT') == any(e in c['coreIds'] for c in claims))
        for v in decision['citationRelevance'].values(): choice(v, ('RELEVANT', 'IRRELEVANT'))
        abstention = decision['abstentionVerdict']
        if self.context.question == 10:
            choice(abstention, SUPPORT[:-1])
            require(any(c['kind'] == 'ABSTENTION' for c in claims))
        else:
            require(abstention == 'NOT_APPLICABLE')
        require(all(c['support'] != 'UNKNOWN' and c['unlinkedBinding'] != 'UNKNOWN'
                    and all(l['correspondence'] != 'UNKNOWN' and l['binding'] != 'UNKNOWN' for l in c['links'])
                    for c in claims), 'INCOMPLETE_REVIEW')
        used = {l['citationId'] for c in claims for l in c['links'] if l['correspondence'] != 'IRRELEVANT'}
        require(all(v != 'RELEVANT' or cid in used for cid, v in decision['citationRelevance'].items()))
        return json.loads(json.dumps(decision))

    def complete(self, decision):
        require(self.status == 'NOT_REVIEWED')
        self._finish = self._decision(decision)
        self.status = 'COMPLETED'

    def interrupt(self):
        self.status = 'INTERRUPTED'
        self._claims.clear(); self._finish = None

    def record(self):
        safe_context(self.context)
        require(type(self.sample) is str and re.fullmatch('[a-f0-9]{32}', self.sample) is not None)
        require(self.status in ('NOT_REVIEWED', 'INTERRUPTED', 'COMPLETED'))
        receipt = safe_receipt(self.receipt)
        finish = self._decision(self._finish) if self.status == 'COMPLETED' else None
        claims = [] if self.status != 'COMPLETED' else [dict(claimId=f'A{i + 1}', **self._claim(x)) for i, x in enumerate(self._claims)]
        coverage = {}
        for e, t in self.context.targets.items():
            ids = [cid for cid in self.context.returned
                   if (c := self.context.chunks[int(cid[1]) - 1])['sourceSha256'] == t['sourceSha256']
                   and c['startLine'] <= t['startLine'] and c['endLine'] >= t['endLine']]
            coverage[e] = {'status': ('COVERED' if ids else 'NOT_COVERED') if receipt['status'] == 'SUCCEEDED' else 'UNKNOWN',
                           'citationIds': ids if receipt['status'] == 'SUCCEEDED' else None}
        return {'version': VERSION, 'sampleId': self.sample, 'mode': self.context.mode,
                'question': self.context.question, 'arm': self.context.arm, 'scopeSha256': self.context.scope,
                'receipt': receipt, 'reviewStatus': self.status,
                'reviewer': ('OWNER_MANUAL' if self.context.mode == 'FROZEN' else 'SYNTHETIC_CHOICES')
                            if self.status == 'COMPLETED' else None,
                'claimCount': len(claims) if self.status == 'COMPLETED' else None, 'claims': claims,
                'completion': finish,
                'directCoverage': coverage,
                'returnedCitations': ([{'citationId': cid, 'source': {k: self.context.chunks[int(cid[1]) - 1][k] for k in LOCATORS}}
                                       for cid in self.context.returned] if receipt['status'] == 'SUCCEEDED' else None)}

    def clear(self):
        self.answer = None
        self._claims.clear()
        self.context = None


class AtomicSink:
    """Save only a freshly validated receipt, replacing metadata atomically."""
    def __init__(self, directory, session):
        session.record()
        self.directory = Path(directory)
        self.path = self.directory / ('review-' + session.sample + '.json')

    def save(self, session):
        payload = json.dumps(session.record(), ensure_ascii=True, sort_keys=True).encode('utf8')
        temporary = self.directory / ('receipt-' + uuid.uuid4().hex + '.tmp')
        try:
            self.directory.mkdir(parents=True, exist_ok=True)
            with temporary.open('xb') as file:
                file.write(payload); file.flush(); os.fsync(file.fileno())
            os.replace(temporary, self.path)
        except Exception:
            try: temporary.unlink(missing_ok=True)
            except OSError: pass
            raise ReviewError('SAVE_FAILED') from None


def review_transient(context, answer, receipt, directory):
    """Future authorized caller supplies RAM-only validated answer and actual C IDs.

    Caller owns its other response/source references and must clear those too.
    No model is invoked, and no unvalidated upstream envelope is accepted here.
    """
    session = Session(context, answer, receipt)
    sink = AtomicSink(directory, session)
    try:
        sink.save(session)  # Persist unknown review and usage BEFORE opening a window.
    except BaseException:
        session.clear()
        raise ReviewError('SAVE_FAILED') from None
    try:
        if answer is not None and receipt['status'] == 'SUCCEEDED':
            from window import ReviewWindow
            ReviewWindow(session, sink).run()
        return sink.path
    except BaseException:
        session.interrupt()
        sink.save(session)
        raise ReviewError('REVIEW_INTERRUPTED') from None
    finally:
        session.clear()
