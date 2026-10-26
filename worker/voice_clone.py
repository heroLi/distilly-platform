"""Voice cloning contract preparation. Mock emits a tone, never an imitation of a person."""
from abc import ABC, abstractmethod
import base64
import io
import math
import re
import struct
import wave


class VoiceCloneProvider(ABC):
    @abstractmethod
    def create(self, profile_id):
        """Return a stable provider reference for one authorized, idempotent operation."""

    @abstractmethod
    def preview(self, profile_id):
        """Return a bounded preview after the platform authorizes this profile."""


class MockVoiceCloneProvider(VoiceCloneProvider):
    def create(self, profile_id):
        return {'provider': 'mock', 'synthetic': True, 'profile_ref': 'mock-' + profile_id}

    def preview(self, profile_id):
        # 400 ms sine tone at 8 kHz, 16-bit mono. No speech, recording or biometric data.
        stream = io.BytesIO()
        with wave.open(stream, 'wb') as wav:
            wav.setnchannels(1)
            wav.setsampwidth(2)
            wav.setframerate(8000)
            wav.writeframes(b''.join(struct.pack('<h', int(3000 * math.sin(2 * math.pi * 440 * i / 8000))) for i in range(3200)))
        return {'provider': 'mock', 'synthetic': True,
                'audio': 'data:audio/wav;base64,' + base64.b64encode(stream.getvalue()).decode('ascii')}


class DisabledVoiceCloneProvider(VoiceCloneProvider):
    def create(self, profile_id):
        raise ValueError('real_voice_clone_disabled')

    def preview(self, profile_id):
        raise ValueError('real_voice_clone_disabled')


def process(model, payload):
    # Reject before reading any configuration, recording, path or external URL.
    if model.mode != 'mock' or payload.get('provider') != 'mock' or payload.get('synthetic') is not True:
        raise ValueError('real_voice_clone_disabled')
    if set(payload) != {'provider', 'synthetic', 'profile_id', 'operation'}:
        raise ValueError('invalid_mock_voice_payload')
    profile_id = payload['profile_id']
    if not isinstance(profile_id, str) or not re.fullmatch(r'[0-9a-f-]{36}', profile_id):
        raise ValueError('invalid_mock_voice_profile')
    provider = MockVoiceCloneProvider()
    if payload['operation'] == 'VOICE_CLONE':
        return provider.create(profile_id)
    if payload['operation'] == 'VOICE_PREVIEW':
        return provider.preview(profile_id)
    raise ValueError('invalid_mock_voice_operation')
