#!/usr/bin/env python3
"""Offline A/B freeze and summary integrity checks; never executes an evaluator."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import math
from pathlib import Path, PurePosixPath
import re
import subprocess

SHA = re.compile(r'[0-9a-f]{64}')
COMMIT = re.compile(r'[0-9a-f]{40}')
IDENTIFIER = re.compile(r'[A-Za-z0-9_-]{1,96}')
SPEC_KEYS = {'schema_version', 'scope', 'dataset_kind', 'implementations',
             'configuration', 'dataset', 'scorer', 'case_inventory'}
MAX_BYTES = 16 * 1024 * 1024


class IntegrityError(Exception):
    pass


def require(condition, code):
    if not condition:
        raise IntegrityError(code)


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'),
                      ensure_ascii=True, allow_nan=False).encode()


def keys(value, expected):
    require(isinstance(value, dict) and set(value) == set(expected), 'schema_fields')


def relative(value):
    require(isinstance(value, str) and value and '\\' not in value and ':' not in value
            and all(ord(char) >= 32 for char in value),
            'unsafe_path')
    path = PurePosixPath(value)
    require(not path.is_absolute() and str(path) == value
            and all(part not in {'.', '..'} and not part.startswith('.') for part in path.parts),
            'unsafe_path')
    return path


def location(root, value):
    path = relative(value)
    current = root
    for part in path.parts:
        current = current / part
        require(not current.is_symlink(), 'symlink_path')
    require(current.resolve().is_relative_to(root.resolve()), 'unsafe_path')
    return current


def read(root, value):
    path = location(root, value)
    require(path.is_file() and path.stat().st_size <= MAX_BYTES, 'input_missing_or_size')
    return path.read_bytes()


def decode(raw):
    def pairs(items):
        result = {}
        for key, value in items:
            require(key not in result, 'duplicate_json_key')
            result[key] = value
        return result

    def number(value):
        result = float(value)
        require(math.isfinite(result), 'nonfinite_number')
        return result

    def invalid(_):
        raise IntegrityError('nonfinite_number')

    return json.loads(raw, object_pairs_hook=pairs, parse_float=number, parse_constant=invalid)


def git(root, *args):
    result = subprocess.run(['git', '-C', str(root), *args], capture_output=True, timeout=15)
    require(result.returncode == 0, 'git_object_unavailable')
    return result.stdout


def committed_sources(root, implementation):
    keys(implementation, {'commit', 'sources'})
    sha = implementation['commit']
    require(isinstance(sha, str) and COMMIT.fullmatch(sha), 'full_commit_required')
    require(git(root, 'cat-file', '-t', sha).strip() == b'commit', 'commit_object_required')
    paths = implementation['sources']
    require(isinstance(paths, list) and paths and len(set(paths)) == len(paths), 'source_list')
    hashes = {}
    for path in paths:
        relative(path)
        entry = git(root, 'ls-tree', '-z', sha, '--', path).split(b'\0')
        require(len(entry) == 2 and entry[1] == b'' and b'\t' in entry[0], 'source_blob_required')
        header, name = entry[0].split(b'\t', 1)
        mode, kind, oid = header.split()
        require(mode in {b'100644', b'100755'} and kind == b'blob'
                and name.decode() == path, 'source_blob_required')
        hashes[path] = digest(git(root, 'cat-file', 'blob', oid.decode()))
    return {'commit': sha, 'sources': hashes}


def artifact_hashes(root, paths):
    require(isinstance(paths, list) and paths and all(isinstance(p, str) for p in paths)
            and len(set(paths)) == len(paths), 'artifact_list')
    return {path: digest(read(root, path)) for path in paths}


def case_ids(root, path, kind):
    data = decode(read(root, path))
    require(isinstance(data, dict) and data.get('split') == kind, 'dataset_split')
    cases = data.get('cases')
    require(isinstance(cases, list) and cases, 'case_inventory')
    ids = [case.get('id') if isinstance(case, dict) else None for case in cases]
    require(all(isinstance(value, str) and IDENTIFIER.fullmatch(value) for value in ids)
            and len(set(ids)) == len(ids), 'case_inventory')
    return sorted(ids)


def freeze(root, spec):
    keys(spec, SPEC_KEYS)
    require(type(spec['schema_version']) is int and spec['schema_version'] == 1
            and spec['scope'] == 'OFFLINE_SHADOW', 'offline_schema_required')
    require(spec['dataset_kind'] in {'development', 'holdout'}, 'dataset_kind')
    keys(spec['implementations'], {'A', 'B'})
    require(isinstance(spec['dataset'], list) and spec['case_inventory'] in spec['dataset'],
            'inventory_not_bound')
    result = {key: spec[key] for key in ('schema_version', 'scope', 'dataset_kind', 'case_inventory')}
    result['implementations'] = {side: committed_sources(root, spec['implementations'][side])
                                 for side in ('A', 'B')}
    for role in ('configuration', 'dataset', 'scorer'):
        result[role] = artifact_hashes(root, spec[role])
    ids = case_ids(root, spec['case_inventory'], spec['dataset_kind'])
    result.update(case_count=len(ids), case_ids_sha256=digest(canonical(ids)),
                  validator_sha256=digest(Path(__file__).read_bytes()))
    return result


def verify(root, manifest_path, expected):
    require(isinstance(expected, str) and SHA.fullmatch(expected), 'pinned_manifest_required')
    raw = read(root, manifest_path)
    require(digest(raw) == expected, 'manifest_digest_mismatch')
    manifest = decode(raw)
    keys(manifest, SPEC_KEYS | {'case_count', 'case_ids_sha256', 'validator_sha256'})
    spec = {key: manifest[key] for key in SPEC_KEYS}
    keys(manifest['implementations'], {'A', 'B'})
    spec['implementations'] = {}
    for side in ('A', 'B'):
        value = manifest['implementations'][side]
        keys(value, {'commit', 'sources'})
        require(isinstance(value['sources'], dict), 'source_list')
        spec['implementations'][side] = {'commit': value['commit'], 'sources': list(value['sources'])}
    for role in ('configuration', 'dataset', 'scorer'):
        require(isinstance(manifest[role], dict), 'artifact_list')
        spec[role] = list(manifest[role])
    require(freeze(root, spec) == manifest, 'bound_inputs_changed')
    return manifest


def audit(root, manifest_path, expected, run_path):
    receipt = {'schema_version': 1, 'status': 'REJECTED', 'quality_status': 'NOT_ASSESSED',
               'blinding_status': 'NOT_ESTABLISHED_BY_INTEGRITY_CHECK',
               'checked_at_utc': datetime.now(timezone.utc).isoformat()}
    try:
        manifest = verify(root, manifest_path, expected)
        receipt['manifest_sha256'] = expected
        raw = read(root, run_path)
        receipt['run_sha256'] = digest(raw)
        run = decode(raw)
        keys(run, {'schema_version', 'manifest_sha256', 'run_id', 'status', 'sides'})
        require(type(run['schema_version']) is int and run['schema_version'] == 1
                and run['manifest_sha256'] == expected, 'run_manifest_mismatch')
        require(isinstance(run['run_id'], str) and IDENTIFIER.fullmatch(run['run_id'])
                and run['status'] == 'COMPLETED', 'run_incomplete')
        keys(run['sides'], {'A', 'B'})
        ids = case_ids(root, manifest['case_inventory'], manifest['dataset_kind'])
        summaries = {}
        for side in ('A', 'B'):
            value = run['sides'][side]
            keys(value, {'commit', 'implementation_digest', 'configuration_digest', 'dataset_digest',
                         'scorer_digest', 'result', 'status', 'exit_code'})
            require(value['status'] == 'COMPLETED' and type(value['exit_code']) is int
                    and value['exit_code'] == 0, 'side_incomplete_or_failed')
            require(value['commit'] == manifest['implementations'][side]['commit'], 'run_commit_mismatch')
            require(value['implementation_digest'] == digest(canonical(manifest['implementations'][side])),
                    'run_binding_mismatch')
            for role in ('configuration', 'dataset', 'scorer'):
                require(value[role + '_digest'] == digest(canonical(manifest[role])), 'run_binding_mismatch')
            keys(value['result'], {'path', 'sha256'})
            result_raw = read(root, value['result']['path'])
            require(digest(result_raw) == value['result']['sha256'], 'result_digest_mismatch')
            result = decode(result_raw)
            keys(result, {'schema_version', 'side', 'manifest_sha256', 'case_results', 'metrics'})
            require(type(result['schema_version']) is int and result['schema_version'] == 1
                    and isinstance(result['case_results'], list), 'result_schema')
            require(result['side'] == side and result['manifest_sha256'] == expected, 'result_binding_mismatch')
            actual = []
            for row in result['case_results']:
                keys(row, {'id', 'status'})
                require(isinstance(row['id'], str) and row['status'] == 'COMPLETED', 'case_incomplete')
                actual.append(row['id'])
            require(sorted(actual) == ids, 'case_population_mismatch')
            metrics = result['metrics']
            require(isinstance(metrics, dict) and metrics and all(
                isinstance(key, str) and IDENTIFIER.fullmatch(key)
                and (v is None or type(v) is int or (type(v) is float and math.isfinite(v)))
                for key, v in metrics.items()), 'metrics_schema')
            summaries[side] = {'commit': value['commit'], 'completed_cases': len(actual),
                               'result_sha256': digest(result_raw), 'metrics_sha256': digest(canonical(metrics))}
        # Recheck all bound files and outputs after validation; no evaluator or network is run.
        verify(root, manifest_path, expected)
        require(digest(read(root, run_path)) == receipt['run_sha256'], 'inputs_changed_during_audit')
        for side in ('A', 'B'):
            require(digest(read(root, run['sides'][side]['result']['path']))
                    == summaries[side]['result_sha256'], 'inputs_changed_during_audit')
        receipt.update(status='INTEGRITY_VERIFIED', run_id=run['run_id'], sides=summaries)
    except (IntegrityError, OSError, ValueError, TypeError, KeyError, subprocess.SubprocessError) as error:
        receipt['error_code'] = str(error) if isinstance(error, IntegrityError) else 'invalid_or_unavailable_input'
    return receipt


def write_new(root, path, value):
    target = location(root, path)
    # Exclusive creation preserves previous manifests/receipts and cannot overwrite inputs.
    with target.open('x', encoding='utf-8') as stream:
        stream.write(json.dumps(value, indent=2, sort_keys=True, allow_nan=False) + '\n')
    return digest(target.read_bytes())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo', type=Path, default=Path.cwd())
    sub = parser.add_subparsers(dest='operation', required=True)
    creation = sub.add_parser('freeze')
    creation.add_argument('--spec', required=True)
    creation.add_argument('--out', required=True)
    for name in ('verify', 'audit'):
        command = sub.add_parser(name)
        command.add_argument('--manifest', required=True)
        command.add_argument('--expected-manifest-sha256', required=True)
        if name == 'audit':
            command.add_argument('--run', required=True)
            command.add_argument('--out', required=True)
    args = parser.parse_args()
    try:
        if args.operation == 'freeze':
            sha = write_new(args.repo, args.out, freeze(args.repo, decode(read(args.repo, args.spec))))
            print(json.dumps({'status': 'FROZEN', 'manifest_sha256': sha}))
        elif args.operation == 'verify':
            verify(args.repo, args.manifest, args.expected_manifest_sha256)
            print(json.dumps({'status': 'INTEGRITY_VERIFIED', 'quality_status': 'NOT_ASSESSED'}))
        else:
            receipt = audit(args.repo, args.manifest, args.expected_manifest_sha256, args.run)
            write_new(args.repo, args.out, receipt)
            print(json.dumps({'status': receipt['status'], 'error_code': receipt.get('error_code')}))
            return 0 if receipt['status'] == 'INTEGRITY_VERIFIED' else 2
    except (IntegrityError, OSError, ValueError, TypeError, KeyError, subprocess.SubprocessError) as error:
        print(json.dumps({'status': 'REJECTED', 'error_code': str(error) if isinstance(error, IntegrityError)
                          else 'invalid_or_unavailable_input'}))
        return 2
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
