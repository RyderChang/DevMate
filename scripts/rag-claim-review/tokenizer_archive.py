"""Pinned public tokenizer ZIP inspected as data, never imported or executed.

Default checks the reviewed summary only. Optional replay reads a local archive;
there is no downloader, tokenizer runtime, model client or authorization path.
"""
import ast
import io
import json
import sys
import zipfile

from plan import canonical
from review import ReviewError, digest, require
from run import HERE, ROOT

ARCHIVE_SHA = 'e7310d1dafe0a86d8a5629fe78a7c763760f651db9b8682718a1781dcd6fe495'
SUMMARY_SHA = '74b9f3a369c63a370457919ebfe204a0d0aa52a367f4cb8adfe0537f577e84d5'
MEMBERS = {
    'deepseek_v4_tokenizer/deepseek_tokenizer.py':
        (277, '4ab78a66b76bb70fb462c6b240c8721abd4e02219a5fffb2d746bf1d3568c24e'),
    'deepseek_v4_tokenizer/tokenizer_config.json':
        (3128, '841f8cf146e3f0ad1082594a31f68ecf7608c20467ef355081333d85bbaeb1cb'),
    'deepseek_v4_tokenizer/tokenizer.json':
        (6367096, '89085f12ef79460ac5f66d1119325ddfc694b4ab209d80bbd81d35f081dc9614'),
}


def inspect_profile(config, tokenizer, source):
    """Restricted JSON/AST findings; source is parsed only as syntax data."""
    require(type(config) is dict and type(tokenizer) is dict, 'ARCHIVE_INVALID')
    require(type(config.get('model_max_length')) is int and config['model_max_length'] == 16384
            and config.get('tokenizer_class') == 'LlamaTokenizerFast'
            and config.get('add_bos_token') is False and config.get('add_eos_token') is False,
            'ARCHIVE_INVALID')
    template = config.get('chat_template')
    require(type(template) is str and '<｜System｜>' not in template
            and 'thinking' not in template and 'response_format' not in template, 'ARCHIVE_INVALID')
    require(tokenizer.get('model', {}).get('type') == 'BPE'
            and tokenizer.get('normalizer') == {'type': 'Sequence', 'normalizers': []}, 'ARCHIVE_INVALID')
    tokens = tokenizer.get('added_tokens')
    require(type(tokens) is list and all(type(t) is dict and type(t.get('content')) is str for t in tokens)
            and not any(t['content'] == '<｜System｜>' for t in tokens), 'ARCHIVE_INVALID')
    tree = ast.parse(source)
    calls = [n for n in ast.walk(tree) if isinstance(n, ast.Call)]
    loaders = [n for n in calls if isinstance(n.func, ast.Attribute) and n.func.attr == 'from_pretrained']
    encoders = [n for n in calls if isinstance(n.func, ast.Attribute) and n.func.attr == 'encode']
    require(len(loaders) == 1 and any(k.arg == 'trust_remote_code' and isinstance(k.value, ast.Constant)
                                    and k.value.value is True for k in loaders[0].keywords)
            and len(encoders) == 1 and len(encoders[0].args) == 1
            and isinstance(encoders[0].args[0], ast.Constant) and encoders[0].args[0].value == 'Hello!',
            'ARCHIVE_INVALID')
    # Parsed example instructions are observations, not operations to perform.
    return dict(configModelMaxLength=16384, configValueIsHostedInputLimit=False,
                tokenizerClass='LlamaTokenizerFast', tokenizerModelType='BPE',
                referenceSystemMarkerPresent=False, chatTemplateThinkingTogglePresent=False,
                chatTemplateJsonModePresent=False, demoRequiresTrustRemoteCode=True,
                demoOperation='TEXT_ENCODE_ONLY', sourceExecuted=False,
                hostedTokenizerBinding='UNPROVEN', hostedJsonFramingBound='UNPROVEN',
                usableForPaidReservation=False)


def inspect_archive(raw):
    try:
        require(type(raw) is bytes and len(raw) == 1911504 and digest(raw) == ARCHIVE_SHA, 'ARCHIVE_DRIFT')
        data = {}
        with zipfile.ZipFile(io.BytesIO(raw)) as archive:
            names = archive.namelist()
            require(len(names) == 4 and len(set(names)) == 4
                    and set(names) == set(MEMBERS) | {'deepseek_v4_tokenizer/'}, 'ARCHIVE_INVALID')
            for name, (size, sha) in MEMBERS.items():
                require(archive.getinfo(name).file_size == size, 'ARCHIVE_INVALID')
                member = archive.read(name)
                require(len(member) == size and digest(member) == sha, 'ARCHIVE_DRIFT')
                data[name] = member.decode('utf8', errors='strict')
        profile = inspect_profile(json.loads(data['deepseek_v4_tokenizer/tokenizer_config.json']),
                                  json.loads(data['deepseek_v4_tokenizer/tokenizer.json']),
                                  data['deepseek_v4_tokenizer/deepseek_tokenizer.py'])
        return dict(version='claim-tokenizer-archive-review-v1', checkedDate='2026-10-13',
                    sourceUrl='https://cdn.deepseek.com/api-docs/deepseek_v4_tokenizer.zip',
                    archiveSha256=ARCHIVE_SHA, archiveBytes=len(raw),
                    members=[dict(path=name, bytes=size, sha256=sha) for name, (size, sha) in MEMBERS.items()],
                    authorization='NOT_GRANTED', liveEnabled=False, paidRequests=0,
                    decision='BLOCKED_HOSTED_BOUND', profile=profile,
                    archiveReplayedInCi=False, frozenPlanChanged=False, capMicros=2000000)
    except BaseException:
        raise ReviewError('ARCHIVE_INVALID') from None


def reviewed_archive():
    try:
        value = json.loads((HERE / 'tokenizer-archive-review.json').read_bytes())
        require(digest(canonical(value)) == SUMMARY_SHA and value['archiveSha256'] == ARCHIVE_SHA
                and value['authorization'] == 'NOT_GRANTED' and value['liveEnabled'] is False
                and value['decision'] == 'BLOCKED_HOSTED_BOUND'
                and value['profile']['configValueIsHostedInputLimit'] is False
                and value['profile']['hostedTokenizerBinding'] == 'UNPROVEN'
                and value['profile']['hostedJsonFramingBound'] == 'UNPROVEN'
                and value['profile']['sourceExecuted'] is False
                and value['profile']['usableForPaidReservation'] is False, 'ARCHIVE_DRIFT')
        return value
    except BaseException:
        raise ReviewError('ARCHIVE_DRIFT') from None


if __name__ == '__main__':
    try:
        require(sys.argv[1:] in ([], ['--replay-local']), 'ARCHIVE_INVALID')
        value = reviewed_archive()
        if sys.argv[1:]:
            raw = (ROOT / 'tmp/dev-034/hosted-cost-sources/deepseek_v4_tokenizer.zip').read_bytes()
            require(inspect_archive(raw) == value, 'ARCHIVE_DRIFT')
        print(json.dumps(dict(archiveSha256=ARCHIVE_SHA, summarySha256=SUMMARY_SHA,
                              decision=value['decision'], sourceExecuted=False, paidRequests=0,
                              hostedInputLimit='UNPROVEN', replayedLocal=bool(sys.argv[1:]))))
    except BaseException:
        print('ARCHIVE_REVIEW_STOPPED')
        raise SystemExit(1) from None
