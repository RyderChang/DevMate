"""Non-streaming DeepSeek envelope boundary, exercised only with memory Stub input.

No HTTP implementation, credentials, live grant or network entry point exists here.
Pairing is supplied by the one-shot caller, not inferred from a provider-generated id.
"""
import json
from dataclasses import dataclass, field

from review import ReviewError, integer, require, safe_receipt

MODEL = 'deepseek-flash'
MAX_ENVELOPE_BYTES = 1048576


@dataclass(repr=False)
class Decoded:
    usage: object = field(repr=False)
    raw: object = field(repr=False)
    code: str


def unique_object(pairs):
    value = {}
    for key, item in pairs:
        require(key not in value, 'PROVIDER_ENVELOPE')
        value[key] = item
    return value


def decode(envelope):
    """Usage is extracted from this envelope before checking choice/content.

    Invalid/ambiguous envelopes or usage cannot yield attributable usage. Valid usage
    remains available on model, finish, content and hidden-reasoning failures.
    The production validator alone parses the assistant's RAG JSON.
    """
    root = usage = None
    try:
        require(type(envelope) is bytes and len(envelope) <= MAX_ENVELOPE_BYTES, 'PROVIDER_ENVELOPE')
        root = json.loads(envelope.decode('utf8', errors='strict'), object_pairs_hook=unique_object,
                          parse_constant=lambda _: (_ for _ in ()).throw(ReviewError('PROVIDER_ENVELOPE')))
        require(type(root) is dict and root.get('object') == 'chat.completion', 'PROVIDER_ENVELOPE')
        u = root.get('usage')
        require(type(u) is dict, 'PROVIDER_USAGE')
        usage = safe_receipt(dict(status='UNKNOWN', reservedMicros=0, usage=dict(
            inputTokens=u.get('prompt_tokens'), outputTokens=u.get('completion_tokens'),
            totalTokens=u.get('total_tokens'))))['usage']
        # Cache counts are diagnostics only; charge all input at the miss rate.
        if 'prompt_cache_hit_tokens' in u or 'prompt_cache_miss_tokens' in u:
            hit = integer(u.get('prompt_cache_hit_tokens'), 0, usage['inputTokens'])
            miss = integer(u.get('prompt_cache_miss_tokens'), 0, usage['inputTokens'])
            require(hit + miss == usage['inputTokens'], 'PROVIDER_USAGE')
        require(root.get('model') == MODEL, 'PROVIDER_MODEL')
        require(type(root.get('id')) is str and 0 < len(root['id']) <= 255
                and root['id'].strip(), 'PROVIDER_ENVELOPE')
        choices = root.get('choices')
        require(type(choices) is list and len(choices) == 1 and type(choices[0]) is dict,
                'PROVIDER_ENVELOPE')
        c = choices[0]
        require(type(c.get('index')) is int and c['index'] == 0, 'PROVIDER_ENVELOPE')
        require(c.get('finish_reason') == 'stop', 'PROVIDER_FINISH')
        m = c.get('message')
        require(type(m) is dict and m.get('role') == 'assistant'
                and type(m.get('content')) is str and m['content'].strip(), 'PROVIDER_CONTENT')
        require(m.get('tool_calls') in (None, []) and m.get('function_call') is None
                and m.get('reasoning_content') in (None, ''), 'PROVIDER_CONTENT')
        return Decoded(usage, m['content'], 'NONE')
    except BaseException as error:
        code = str(error) if type(error) is ReviewError else 'PROVIDER_ENVELOPE'
        if code not in ('PROVIDER_ENVELOPE', 'PROVIDER_USAGE', 'PROVIDER_MODEL',
                        'PROVIDER_FINISH', 'PROVIDER_CONTENT'):
            code = 'PROVIDER_USAGE' if root is not None and usage is None else 'PROVIDER_ENVELOPE'
        return Decoded(usage, None, code)
    finally:
        root = envelope = None
