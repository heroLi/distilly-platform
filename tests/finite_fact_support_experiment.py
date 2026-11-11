"""Test-only formal inference; never imported by server/Worker or used on free text.

Domain: exactly two fictional lamps, predicate ``is_on``, explicit boolean polarity.
Fixture facts are axioms, not extracted text or evidence of real-world truth. Only
CONFIRMED facts at the current, non-withdrawn source revision apply within their
half-open [valid_from, valid_until) UTC-second interval (1970 through 2100).
Opposite applicable values yield CONFLICT; absence/expiry/revocation yields UNKNOWN,
never a closed-world negative. REFUTED requires an explicit opposite fact. A source
revision change invalidates old facts, even if a later source has identical text.

The explicit enabled=True argument exists only for this isolated test experiment;
it is not an application flag, public endpoint, model output or permission check.
Synthetic markers/namespaces are fixture constraints, not data-loss prevention or
authentication. Scope separation and source heads are formal fixture assumptions,
not a substitute for the application's SQL authority. No answer text is produced.
Run exposed engineering regressions: python3 tests/finite_fact_support_experiment_test.py
"""
import re

LAMPS = frozenset({'synthetic-lamp-a', 'synthetic-lamp-b'})
MAX_TIME = 4102444800
MAX_ROWS = 128
_ID = re.compile(r'synthetic-[a-z][a-z0-9-]{0,47}\Z')


def _shape(value, keys):
    if type(value) is not dict or set(value) != set(keys):
        raise ValueError('invalid synthetic fixture shape')


def _identifier(value):
    if type(value) is not str or not _ID.fullmatch(value):
        raise ValueError('invalid synthetic fixture identifier')


def _integer(value, low, high):
    if type(value) is not int or not low <= value <= high:
        raise ValueError('invalid synthetic fixture integer')


def _boolean(value):
    if type(value) is not bool:
        raise ValueError('invalid synthetic fixture boolean')


def _rows(value):
    if type(value) is not list or len(value) > MAX_ROWS:
        raise ValueError('invalid synthetic fixture collection')


def _result(status, facts=(), sources=()):
    return {'status': status, 'fact_ids': tuple(sorted(facts)),
            'source_versions': tuple(sorted(set(sources)))}


def evaluate(dataset, query, *, enabled=False):
    """Decide only a symbolic fixture proposition; default closure does not inspect inputs."""
    _boolean(enabled)
    if not enabled:
        return _result('DISABLED')
    _shape(dataset, ('contract', 'synthetic', 'sources', 'facts'))
    if dataset['contract'] != 'SYNTHETIC_LAMP_FACTS_V1' or dataset['synthetic'] is not True:
        raise ValueError('not an explicit synthetic lamp fixture')
    _shape(query, ('scope_id', 'lamp_id', 'predicate', 'expected_value', 'at'))
    _identifier(query['scope_id']); _identifier(query['lamp_id'])
    _boolean(query['expected_value']); _integer(query['at'], 0, MAX_TIME)
    if type(query['predicate']) is not str or len(query['predicate']) > 50:
        raise ValueError('invalid synthetic query predicate')
    _rows(dataset['sources']); _rows(dataset['facts'])
    heads = {}
    for source in dataset['sources']:
        _shape(source, ('scope_id', 'source_id', 'revision', 'withdrawn'))
        _identifier(source['scope_id']); _identifier(source['source_id'])
        _integer(source['revision'], 1, 2147483647); _boolean(source['withdrawn'])
        key = (source['scope_id'], source['source_id'])
        if key in heads:
            raise ValueError('ambiguous synthetic source head')
        heads[key] = source
    seen, selected = set(), []
    for fact in dataset['facts']:
        _shape(fact, ('fact_id', 'scope_id', 'source_id', 'source_revision', 'lamp_id',
                      'predicate', 'value', 'confirmation', 'valid_from', 'valid_until'))
        for field in ('fact_id', 'scope_id', 'source_id', 'lamp_id'):
            _identifier(fact[field])
        _integer(fact['source_revision'], 1, 2147483647); _boolean(fact['value'])
        _integer(fact['valid_from'], 0, MAX_TIME); _integer(fact['valid_until'], 0, MAX_TIME)
        if (fact['lamp_id'] not in LAMPS or fact['predicate'] != 'is_on'
                or fact['confirmation'] not in ('PENDING', 'CONFIRMED', 'REJECTED', 'REVOKED')
                or fact['valid_from'] >= fact['valid_until'] or fact['fact_id'] in seen):
            raise ValueError('invalid or ambiguous synthetic fact')
        seen.add(fact['fact_id'])
        head = heads.get((fact['scope_id'], fact['source_id']))
        if (head is not None and not head['withdrawn'] and head['revision'] == fact['source_revision']
                and fact['confirmation'] == 'CONFIRMED' and fact['scope_id'] == query['scope_id']
                and fact['lamp_id'] == query['lamp_id'] and fact['predicate'] == query['predicate']
                and fact['valid_from'] <= query['at'] < fact['valid_until']):
            selected.append(fact)
    if not selected:
        return _result('UNKNOWN')
    values = {fact['value'] for fact in selected}
    status = 'CONFLICT' if len(values) > 1 else (
        'SUPPORTED' if next(iter(values)) == query['expected_value'] else 'REFUTED')
    return _result(status, (fact['fact_id'] for fact in selected),
                   ((fact['scope_id'], fact['source_id'], fact['source_revision']) for fact in selected))
