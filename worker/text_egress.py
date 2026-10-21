"""Closed real-provider text egress until a verifiable budget authority is available.

Literal loopback HTTP fixtures are development tests, not a provider budget or a
production allowlist. No environment variable opens real-provider egress.
"""
from urllib.parse import urlsplit


class TextEgressBlocked(ValueError):
    failure_code = 'MODEL_BUDGET_NOT_VERIFIED'


def fixture_text_target(mode, url, *, synthetic_fixture=False):
    """Validate before serialization, DNS, proxy lookup, credentials, or a socket."""
    try:
        target = urlsplit(url)
        allowed = (synthetic_fixture is True and mode == 'gateway' and target.scheme in ('http', 'https')
                   and target.hostname in ('127.0.0.1', '::1')
                   and target.port is not None and 1 <= target.port <= 65535
                   and target.username is None and target.password is None
                   and not target.query and not target.fragment
                   and not any(ch.isspace() or ord(ch) < 32 for ch in url)
                   and '\\' not in url)
    except (TypeError, ValueError):
        allowed = False
    if not allowed:
        raise TextEgressBlocked('Real text provider egress is closed: cost authorization cannot be verified')
    return url.rstrip('/') + '/chat/completions'
