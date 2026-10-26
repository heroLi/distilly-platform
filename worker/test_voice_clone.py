import base64
import io
import unittest
import wave
from types import SimpleNamespace
from unittest.mock import patch
from voice_clone import process, DisabledVoiceCloneProvider


class VoiceCloneTest(unittest.TestCase):
    def payload(self, operation='VOICE_CLONE'):
        return {'provider': 'mock', 'synthetic': True, 'profile_id': 'a' * 36, 'operation': operation}

    def test_create_is_stable_and_never_calls_network_or_model(self):
        with patch('urllib.request.urlopen', side_effect=AssertionError('network forbidden')):
            first = process(SimpleNamespace(mode='mock'), self.payload())
            self.assertEqual(first, process(SimpleNamespace(mode='mock'), self.payload()))
            self.assertTrue(first['synthetic'])
            self.assertEqual('mock', first['provider'])

    def test_preview_is_valid_bounded_tone_and_deterministic(self):
        result = process(SimpleNamespace(mode='mock'), self.payload('VOICE_PREVIEW'))
        self.assertEqual(result, process(SimpleNamespace(mode='mock'), self.payload('VOICE_PREVIEW')))
        raw = base64.b64decode(result['audio'].split(',')[1], validate=True)
        with wave.open(io.BytesIO(raw)) as audio:
            self.assertEqual((1, 2, 8000, 3200), (audio.getnchannels(), audio.getsampwidth(), audio.getframerate(), audio.getnframes()))

    def test_gateway_mode_and_other_providers_are_closed(self):
        for mode, provider in [('gateway', 'mock'), ('mock', 'real')]:
            p = self.payload(); p['provider'] = provider
            with self.assertRaisesRegex(ValueError, 'real_voice_clone_disabled'):
                process(SimpleNamespace(mode=mode), p)

    def test_recordings_urls_paths_and_unknown_operations_are_rejected(self):
        for key in ['audio', 'url', 'path', 'reference_audio']:
            p = self.payload(); p[key] = 'synthetic-rejected'
            with self.assertRaisesRegex(ValueError, 'invalid_mock_voice_payload'):
                process(SimpleNamespace(mode='mock'), p)
        with self.assertRaises(ValueError):
            process(SimpleNamespace(mode='mock'), self.payload('TTS'))

    def test_real_adapter_is_unconditionally_disabled(self):
        for method in [DisabledVoiceCloneProvider().create, DisabledVoiceCloneProvider().preview]:
            with self.assertRaisesRegex(ValueError, 'real_voice_clone_disabled'):
                method('synthetic')
