"""Offline archive metadata and untrusted-source negatives; no tokenizer execution."""
import contextlib
import io
import unittest
from copy import deepcopy
from unittest.mock import patch

from execution import require_live_ready, frozen_preparation
from review import ReviewError
from tokenizer_archive import (ARCHIVE_SHA, MEMBERS, inspect_archive, inspect_profile, reviewed_archive)


class TokenizerArchiveTest(unittest.TestCase):
    def config(self):
        return dict(model_max_length=16384,tokenizer_class='LlamaTokenizerFast',add_bos_token=False,
                    add_eos_token=False,chat_template='SYNTHETIC_USER_CONTENT_ONLY')

    def tokenizer(self):
        return dict(model={'type':'BPE'},normalizer={'type':'Sequence','normalizers':[]},
                    added_tokens=[{'content':'</think>'}])

    def source(self):
        return 'x = transformers.AutoTokenizer.from_pretrained("./", trust_remote_code=True)\ny=x.encode("Hello!")'

    def test_reviewed_zip_differs_from_pinned_v41_tokenizer_and_is_not_a_hosted_limit(self):
        value=reviewed_archive()
        self.assertEqual(value['archiveSha256'],ARCHIVE_SHA)
        self.assertEqual(value['profile']['configModelMaxLength'],16384)
        self.assertFalse(value['profile']['configValueIsHostedInputLimit'])
        self.assertNotEqual(MEMBERS['deepseek_v4_tokenizer/tokenizer.json'][1],
                            'c90dfa01249db1be4245780a052ede752e1361c612ac6d08e2bdada7d599476b')
        self.assertEqual(value['profile']['hostedTokenizerBinding'],'UNPROVEN')
        self.assertEqual(value['profile']['hostedJsonFramingBound'],'UNPROVEN')
        self.assertFalse(value['archiveReplayedInCi']); self.assertFalse(value['frozenPlanChanged'])

    def test_demo_trust_remote_code_is_parsed_not_executed(self):
        source=self.source()+'\nraise RuntimeError("UNTRUSTED_EXECUTION_SENTINEL")'
        with patch('subprocess.run') as execute,patch('socket.socket') as sock:
            result=inspect_profile(self.config(),self.tokenizer(),source)
        execute.assert_not_called(); sock.assert_not_called()
        self.assertTrue(result['demoRequiresTrustRemoteCode']); self.assertFalse(result['sourceExecuted'])
        self.assertFalse(result['usableForPaidReservation'])

    def test_missing_loader_flag_wrong_literal_or_invalid_json_profile_reject(self):
        for source in (self.source().replace('True','False'),self.source().replace('Hello!','OTHER_SENTINEL'),
                       self.source().replace('encode','apply_chat_template')):
            with self.assertRaises(ReviewError): inspect_profile(self.config(),self.tokenizer(),source)
        for changes in (dict(model_max_length=True),dict(model_max_length=1048576),
                        dict(chat_template='<｜System｜>'),dict(chat_template='response_format')):
            with self.assertRaises(ReviewError): inspect_profile({**self.config(),**changes},self.tokenizer(),self.source())
        tok=deepcopy(self.tokenizer()); tok['added_tokens'].append({'content':'<｜System｜>'})
        with self.assertRaises(ReviewError): inspect_profile(self.config(),tok,self.source())

    def test_wrong_or_missing_archive_has_fixed_error_without_source_output(self):
        out,err=io.StringIO(),io.StringIO()
        with contextlib.redirect_stdout(out),contextlib.redirect_stderr(err),self.assertRaisesRegex(ReviewError,'ARCHIVE_INVALID'):
            inspect_archive(b'UNTRUSTED_ARCHIVE_SENTINEL')
        self.assertEqual(out.getvalue()+err.getvalue(),'')
        with patch('tokenizer_archive.SUMMARY_SHA','0'*64),self.assertRaises(ReviewError): reviewed_archive()

    def test_config_length_cannot_replace_context_budget_or_enable_live(self):
        self.assertEqual(frozen_preparation()['totalReservedMicros'],84213760)
        self.assertEqual(frozen_preparation()['capMicros'],2000000)
        with patch('socket.socket') as sock,patch('getpass.getpass') as key:
            with self.assertRaisesRegex(ReviewError,'LIVE_NOT_AUTHORIZED'): require_live_ready()
        sock.assert_not_called(); key.assert_not_called()
        self.assertFalse(reviewed_archive()['liveEnabled'])


if __name__=='__main__': unittest.main()
