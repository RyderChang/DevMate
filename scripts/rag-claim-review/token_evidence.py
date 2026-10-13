"""Inspect pinned public JSON/reference source as DATA, never import or execute it.

Optional local evidence reproduction; CI uses the reviewed summary, not a download.
No tokenizer runtime, model weights, remote code or model calls are needed.
"""
import ast
import json
from pathlib import Path

from review import ReviewError, digest, require

REVISION = '2cba9e42aa026125f3ed06c6d98c1db82f7ca027'
BASE = 'https://huggingface.co/deepseek-ai/DeepSeek-V4.1-Flash/resolve/' + REVISION + '/'
FILES = {
    'config.json': '8be45ce0476004a3f529fd896115a4a2e800a129ad2d3ec05b16050f52e21879',
    'tokenizer.json': 'c90dfa01249db1be4245780a052ede752e1361c612ac6d08e2bdada7d599476b',
    'tokenizer_config.json': '6ac8c8dc065ed118161d02dd532749ae3f52c243deac27872134fae2f50d8547',
    'encoding.py': '502bdaec8a3fd88ebc24c4721a7038fbe42f2063c664638127056107920035c1',
}
FRAME = '<｜begin▁of▁sentence｜><｜System｜><｜User｜><｜Assistant｜></think>'


def inspect_profile(tokenizer, config, model, source):
    require(tokenizer['normalizer'] == {'type': 'Sequence', 'normalizers': []}, 'TOKEN_EVIDENCE_INVALID')
    splitters = tokenizer['pre_tokenizer']['pretokenizers']
    require(tokenizer['pre_tokenizer']['type'] == 'Sequence' and len(splitters) == 4,
            'TOKEN_EVIDENCE_INVALID')
    require(all(x['type'] == 'Split' and x['behavior'] == 'Isolated' and x['invert'] is False
                for x in splitters[:3]), 'TOKEN_EVIDENCE_INVALID')
    require(splitters[-1] == dict(type='ByteLevel', add_prefix_space=False, trim_offsets=True,
                                 use_regex=False), 'TOKEN_EVIDENCE_INVALID')
    require(tokenizer['post_processor'] == dict(type='ByteLevel', add_prefix_space=True,
                                               trim_offsets=False, use_regex=True), 'TOKEN_EVIDENCE_INVALID')
    bpe = tokenizer['model']
    require(bpe['type'] == 'BPE' and bpe['continuing_subword_prefix'] is None
            and bpe['end_of_word_suffix'] is None and bpe['dropout'] is None
            and bpe['unk_token'] is None, 'TOKEN_EVIDENCE_INVALID')
    # GPT byte alphabet: one initial BPE symbol for every UTF-8 byte. Merges reduce count.
    byte_values = list(range(33, 127)) + list(range(161, 173)) + list(range(174, 256))
    characters = byte_values[:]
    for value in range(256):
        if value not in byte_values:
            characters.append(256 + len(characters) - len(byte_values))
    require(len(characters) == 256 and all(chr(c) in bpe['vocab'] for c in characters),
            'TOKEN_EVIDENCE_INVALID')
    require(all(type(t['content']) is str and t['content'] for t in tokenizer['added_tokens']),
            'TOKEN_EVIDENCE_INVALID')
    require(config['add_bos_token'] is False and config['add_eos_token'] is False
            and config['model_max_length'] == model['text_config']['max_position_embeddings'] == 1048576,
            'TOKEN_EVIDENCE_INVALID')
    # Parse string constants only. In particular do NOT exec/import encoding.py.
    literals = {}
    for node in ast.parse(source).body:
        if isinstance(node, ast.AnnAssign) and isinstance(node.target, ast.Name) and isinstance(node.value, ast.Constant):
            literals[node.target.id] = node.value.value
        if isinstance(node, ast.Assign) and len(node.targets) == 1 and isinstance(node.targets[0], ast.Name) and isinstance(node.value, ast.Constant):
            literals[node.targets[0].id] = node.value.value
    require(literals['system_msg_template'] == literals['user_msg_template'] == '{content}'
            and literals['bos_token'] + literals['SYSTEM_SP_TOKEN'] + literals['USER_SP_TOKEN']
            + literals['ASSISTANT_SP_TOKEN'] + literals['thinking_end_token'] == FRAME,
            'TOKEN_EVIDENCE_INVALID')
    return dict(byteAlphabetCoverage=256, normalizerExpands=False, prefixSpaceAdded=False,
                textTokenUpperBound='UTF-8 content bytes', referenceChatFrameBytes=len(FRAME.encode('utf8')),
                contextTokens=1048576, sourceExecuted=False,
                hostedApiEquivalence='UNPROVEN', hostedJsonModeFraming='UNPROVEN')


def inspect_directory(directory):
    data = {}
    try:
        for name, sha in FILES.items():
            raw = (Path(directory) / name).read_bytes()
            require(len(raw) <= 8388608 and digest(raw) == sha, 'TOKEN_EVIDENCE_DRIFT')
            data[name] = raw.decode('utf8', errors='strict')
        profile = inspect_profile(json.loads(data['tokenizer.json']), json.loads(data['tokenizer_config.json']),
                                  json.loads(data['config.json']), data['encoding.py'])
        return dict(version='claim-token-evidence-v1', checkedDate='2026-10-12', revision=REVISION,
                    sources=[dict(path=n, sha256=h, url=BASE+n) for n, h in FILES.items()], profile=profile)
    except BaseException:
        raise ReviewError('TOKEN_EVIDENCE_INVALID') from None
    finally:
        data = None


if __name__ == '__main__':
    try:
        from run import ROOT
        expected = json.loads(Path(__file__).with_name('token-evidence.json').read_bytes())
        require(inspect_directory(ROOT / 'tmp/dev-034/token-evidence') == expected, 'TOKEN_EVIDENCE_DRIFT')
        print('TOKEN_REFERENCE_PASS; hostedApiEquivalence=UNPROVEN; paidRequests=0')
    except BaseException:
        print('TOKEN_EVIDENCE_STOPPED')
        raise SystemExit(1) from None
