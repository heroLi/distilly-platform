import os
import unittest
from unittest.mock import patch, Mock
import worker
from text_egress import fixture_text_target, TextEgressBlocked


class TextEgressTests(unittest.TestCase):
    def test_real_targets_and_dns_aliases_never_build_a_request(self):
        urls = ['https://provider.invalid/v1', 'http://localhost:18000/v1',
                'http://127.0.0.1.example.invalid:18000/v1',
                'http://2130706433:18000/v1', 'http://0x7f000001:18000/v1',
                'http://127.0.0.1:18000/v1?secret=synthetic',
                'http://127.0.0.1:18000/v1#fragment',
                'http://synthetic@127.0.0.1:18000/v1',
                'http://127.0.0.1:0/v1', 'http://127.0.0.1:99999/v1',
                'http://127.0.0.1/v1', 'http://127.0.0.1:18000/\ninvalid']
        with patch.dict(os.environ, {'MODEL_SYNTHETIC_TEXT_FIXTURE':'true','MODEL_ALLOW_HTTP': 'true'}, clear=True):
            for url in urls:
                with self.subTest(url=url):
                    model=worker.Model(mode='gateway',url=url,model='synthetic',key='synthetic-test-only')
                    with patch('urllib.request.Request') as request, patch('urllib.request.build_opener') as opener:
                        with self.assertRaises(TextEgressBlocked) as error:model.call('synthetic',{})
                        self.assertEqual(worker.failure_code(error.exception),'MODEL_BUDGET_NOT_VERIFIED')
                        self.assertNotIn(url,str(error.exception))
                        request.assert_not_called();opener.assert_not_called()

    def test_environment_flags_cannot_self_authorize_paid_egress(self):
        with patch.dict(os.environ, {'MODEL_ALLOW_REAL':'true','MODEL_BUDGET_VERIFIED':'true','MODEL_BUDGET_CNY':'10'},clear=True):
            with self.assertRaises(TextEgressBlocked):fixture_text_target('gateway','https://provider.invalid/v1')

    def test_loopback_requires_explicit_synthetic_fixture_opt_in(self):
        with self.assertRaises(TextEgressBlocked):fixture_text_target('gateway','http://127.0.0.1:18000/v1')

    def test_mock_does_not_use_network_even_when_call_is_invoked_directly(self):
        with self.assertRaises(TextEgressBlocked):fixture_text_target('mock','http://127.0.0.1:18000/v1')
        model=worker.Model(mode='mock')
        with patch('urllib.request.build_opener') as opener:
            self.assertIn('claims',model.distill_chunk({'text':'合成材料必须检查版本。','material_id':'m','chunk_id':'m:0'},'合成规范'))
            opener.assert_not_called()

    def test_loopback_target_and_proxy_bypass_are_explicit(self):
        self.assertEqual(fixture_text_target('gateway','http://127.0.0.1:18000/v1/',synthetic_fixture=True),'http://127.0.0.1:18000/v1/chat/completions')
        self.assertEqual(fixture_text_target('gateway','http://[::1]:18000/v1',synthetic_fixture=True),'http://[::1]:18000/v1/chat/completions')
        with patch.dict(os.environ,{'MODEL_SYNTHETIC_TEXT_FIXTURE':'true','MODEL_ALLOW_HTTP':'true'},clear=True):
            model=worker.Model(mode='gateway',url='http://127.0.0.1:18000/v1',model='synthetic',key='synthetic-test-only')
        opener=Mock();opener.open.side_effect=OSError('synthetic connection failure')
        with patch.dict(os.environ,{'MODEL_SYNTHETIC_TEXT_FIXTURE':'true'}), patch('urllib.request.build_opener',return_value=opener) as build:
            with self.assertRaises(worker.ModelError):model.call('synthetic',{})
        self.assertEqual(build.call_args.args[0].proxies,{})
        self.assertIsNone(build.call_args.args[1].redirect_request(None,None,302,'synthetic',{},'https://provider.invalid'))


if __name__=='__main__':unittest.main()
