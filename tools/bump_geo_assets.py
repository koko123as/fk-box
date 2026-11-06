#!/usr/bin/env python3
"""Pin the bundled geo data (app/geo-assets.properties) to a meta-rules-dat commit.

Usage: python3 tools/bump_geo_assets.py [<commit>] [--dry-run]

Without a commit the current head of the release branch is used. Each file is
downloaded once to compute its SHA-256; nothing is written into the
repository except the properties file. The app build downloads and checks the
same files (prepare*GeoAssets in app/build.gradle.kts).
"""

import hashlib
import json
import re
import sys
import urllib.request
from pathlib import Path

PINS = Path(__file__).resolve().parent.parent / 'app' / 'geo-assets.properties'


def fetch(url, accept=None):
    request = urllib.request.Request(url, headers={'User-Agent': 'fk-box-bump-geo-assets'})
    if accept:
        request.add_header('Accept', accept)
    with urllib.request.urlopen(request, timeout=120) as response:
        return response.read()


def main():
    args = [arg for arg in sys.argv[1:] if not arg.startswith('--')]
    dry_run = '--dry-run' in sys.argv
    text = PINS.read_text()
    repository = re.search(r'^GEO_REPOSITORY=(.+)$', text, re.M).group(1).strip()
    ref = args[0] if args else 'release'
    commit = json.loads(fetch(f'https://api.github.com/repos/{repository}/commits/{ref}',
                              'application/vnd.github+json'))
    sha = commit['sha']
    date = commit['commit']['committer']['date']
    lines = []
    for line in text.splitlines():
        match = re.match(r'^(geoip|geosite|mmdb|asn)=([^:]+):([^:]+):[0-9a-f]{64}$', line)
        if match:
            name, source, file = match.groups()
            data = fetch(f'https://raw.githubusercontent.com/{repository}/{sha}/{source}')
            digest = hashlib.sha256(data).hexdigest()
            print(f'{name}: {source} {len(data)} bytes {digest}')
            line = f'{name}={source}:{file}:{digest}'
        elif line.startswith('GEO_COMMIT='):
            line = f'GEO_COMMIT={sha}'
        elif line.startswith('GEO_TIME='):
            line = f'GEO_TIME={date}'
        lines.append(line)
    print(f'{repository}@{sha} ({date})')
    if not dry_run:
        PINS.write_text('\n'.join(lines) + '\n')


if __name__ == '__main__':
    main()
