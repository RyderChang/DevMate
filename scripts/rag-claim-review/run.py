"""Only public-source preflight or synthetic GUI demo; deliberately no live mode."""
import argparse
import json
import subprocess
import uuid
from functools import lru_cache
from pathlib import Path

from review import ARMS, ReviewError, demo_context, load_context, review_transient

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent


class FixedParser(argparse.ArgumentParser):
    def error(self, message):
        raise ReviewError('INVALID_ARGUMENTS')


@lru_cache(maxsize=7)
def public_source(commit, path):
    try:
        return subprocess.check_output(['git', 'show', commit + ':' + path], cwd=ROOT, stderr=subprocess.DEVNULL)
    except Exception:
        raise ReviewError('FROZEN_SOURCE_UNAVAILABLE') from None


def main():
    parser = FixedParser(description='Offline transient claim review; no model calls')
    parser.add_argument('--demo', action='store_true', help='local synthetic review window')
    parser.add_argument('--stub-demo', action='store_true', help='offline Stub/production validator/window demo')
    args = parser.parse_args()
    if args.demo and args.stub_demo: raise ReviewError('INVALID_ARGUMENTS')
    blob = (HERE / 'frozen-inputs.json').read_bytes()
    for number in range(1, 11):
        for arm in ARMS:
            load_context(blob, public_source, number, arm, ['C1', 'C2', 'C3', 'C4', 'C5'])
    print(json.dumps({'preflight': 'PASS', 'questions': 10, 'arms': 4, 'paidRequests': 0}))
    if args.demo:
        context = demo_context()
        answer = '合成演示正文：事实甲。[C1] 额外事实乙。\n这个演示不属于任何真实模型样本。'
        path = review_transient(context, answer, {'status': 'SUCCEEDED', 'usage': {'inputTokens': 0, 'outputTokens': 0, 'totalTokens': 0},
                                                'reservedMicros': 0}, ROOT / 'tmp/dev-034/demo')
        answer = None; context = None
        print('Synthetic receipt saved under tmp/dev-034/demo; no body exported.')
    if args.stub_demo:
        from adapter import OfflineAdapter, StubGrant, StubTransport, configured_validator
        context = load_context(blob, public_source, 1, 'baseline', [])
        grant = StubGrant(uuid.uuid4().hex, context.scope, ((1, 'baseline'),), 1, 1000, 1000)
        raw = json.dumps({'answer': '合成适配演示：事实甲。[C1] 额外事实乙。仅测试离线流程。',
                          'citationIds': ['C1', 'C2', 'C3', 'C4', 'C5']})
        transport = StubTransport(raw, ids=('C1', 'C2', 'C3', 'C4', 'C5'))
        try:
            adapter = OfflineAdapter(grant, ROOT / 'tmp/dev-034/stub-demo', configured_validator(ROOT))
            adapter.review(context, transport)
        finally:
            raw = context = None
            transport.clear(); public_source.cache_clear()
        print('Offline Stub receipt saved under tmp/dev-034/stub-demo; paidRequests=0.')


if __name__ == '__main__':
    try: main()
    except Exception:
        print('REVIEW_STOPPED (fixed diagnostic; no retry)')
        raise SystemExit(1) from None
