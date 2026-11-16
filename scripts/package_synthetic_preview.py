#!/usr/bin/env python3
"""Package an allowlisted synthetic static preview; never collect repository data."""
import argparse
import hashlib
import json
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1]
FILES = ('dist/index.html', 'dist/preview.css', 'dist/demo-state.js', 'dist/preview.js', 'README.md')


def package(output):
    base = ROOT / 'preview/synthetic-text'
    contents = {}
    for name in FILES:
        path = base / name
        if path.is_symlink() or not path.is_file() or not path.resolve().is_relative_to(base.resolve()):
            raise ValueError('Preview asset must be a regular allowlisted file')
        contents[name] = path.read_bytes()
    manifest = {
        'format': 'DISTILLY_SYNTHETIC_STATIC_PREVIEW_V1',
        'entry': 'dist/index.html', 'static_root': 'dist',
        'synthetic_only': True, 'external_model_calls': 0,
        'backend_included': False, 'deployed': False,
        'sha256': {name: hashlib.sha256(value).hexdigest() for name, value in contents.items()},
    }
    contents['MANIFEST.json'] = (json.dumps(manifest, ensure_ascii=False, indent=2) + '\n').encode()
    output = Path(output)
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open('xb') as file:
        with zipfile.ZipFile(file, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
            for name, value in contents.items():
                info = zipfile.ZipInfo(name, date_time=(2026, 11, 16, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                info.external_attr = 0o100644 << 16
                archive.writestr(info, value)
    return manifest


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    manifest = package(args.output)
    print(json.dumps({'archive': str(args.output.resolve()), 'files': len(manifest['sha256']) + 1,
                      'sha256': hashlib.sha256(args.output.read_bytes()).hexdigest()}))
