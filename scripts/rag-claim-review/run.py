"""Only public-source preflight or synthetic GUI demo; deliberately no live mode."""
import argparse
import json
import subprocess
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
    args = parser.parse_args()
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


if __name__ == '__main__':
    try: main()
    except Exception:
        print('REVIEW_STOPPED (fixed diagnostic; no retry)')
        raise SystemExit(1) from None
