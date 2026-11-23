"""Sets the release version in version.properties from the publish date.

A release is named after its day in China Standard Time: the first one that
day is YYYY.MM.DD, the next YYYY.MM.DD.2, and so on. The version code is
YYMMDDNN (NN = the release number that day), so it always grows, also past the
old 1.x codes. A rerun for a commit that already has a tag that day reuses it.

Run by the release workflow before the core and the APK are built; it needs
GITHUB_REPOSITORY, GITHUB_SHA and GH_TOKEN, and writes `version` to
GITHUB_OUTPUT. With --dry-run it only prints the version it would use.
"""

import datetime
import json
import os
import re
import subprocess
import sys
from pathlib import Path

PROPERTIES = Path('version.properties')
CST = datetime.timezone(datetime.timedelta(hours=8))


def tags_for(day: str, repository: str):
    """Every tag of that day, as {name: commit sha}."""
    output = subprocess.check_output(
        ['gh', 'api', f'repos/{repository}/git/matching-refs/tags/{day}'], text=True
    )
    tags = {}
    for entry in json.loads(output):
        name = entry['ref'].removeprefix('refs/tags/')
        if not re.fullmatch(re.escape(day) + r'(\.\d+)?', name):
            continue
        target = entry['object']
        while target['type'] == 'tag':
            target = json.loads(subprocess.check_output(
                ['gh', 'api', f"repos/{repository}/git/tags/{target['sha']}"], text=True
            ))['object']
        tags[name] = target['sha']
    return tags


def number_of(name: str) -> int:
    parts = name.split('.')
    return int(parts[3]) if len(parts) == 4 else 1


def main():
    now = datetime.datetime.now(CST)
    day = now.strftime('%Y.%m.%d')
    tags = tags_for(day, os.environ['GITHUB_REPOSITORY'])
    commit = os.environ['GITHUB_SHA']
    reused = [name for name, sha in tags.items() if sha == commit]
    number = number_of(reused[0]) if reused else max((number_of(n) for n in tags), default=0) + 1
    if number > 99:
        raise SystemExit(f'More than 99 releases on {day}')
    name = day if number == 1 else f'{day}.{number}'
    code = int(now.strftime('%y%m%d')) * 100 + number

    print(f'Release version {name} ({code})')
    if '--dry-run' in sys.argv:
        return
    lines = PROPERTIES.read_text().splitlines()
    values = {'VERSION_NAME': name, 'VERSION_CODE': str(code)}
    lines = [
        f'{line.split("=", 1)[0]}={values[line.split("=", 1)[0]]}' if line.split('=', 1)[0] in values else line
        for line in lines
    ]
    PROPERTIES.write_text('\n'.join(lines) + '\n')
    if 'GITHUB_OUTPUT' in os.environ:
        with open(os.environ['GITHUB_OUTPUT'], 'a') as out:
            out.write(f'version={name}\ncode={code}\n')


if __name__ == '__main__':
    main()
