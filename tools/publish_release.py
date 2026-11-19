"""Publish verified release assets without moving an existing version tag.

Invoked only by the release workflow when publish_release is enabled.
"""

import json
import os
import re
import subprocess
from pathlib import Path
from urllib.parse import quote


def main():
    repository = os.environ['GITHUB_REPOSITORY']
    commit = os.environ['GITHUB_SHA']
    version = os.environ['VERSION_NAME']
    properties = dict(
        line.strip().split('=', 1)
        for line in Path('version.properties').read_text().splitlines()
        if line.strip() and not line.lstrip().startswith('#') and '=' in line
    )
    if version != properties['VERSION_NAME']:
        raise SystemExit('Release tag must exactly match VERSION_NAME in version.properties')
    ref = f'refs/tags/{version}'
    encoded_version = quote(version, safe='')

    def api(path, *arguments):
        return json.loads(subprocess.check_output(
            ['gh', 'api', f'repos/{repository}/{path}', *arguments], text=True
        ))

    def find_tag():
        refs = api(f'git/matching-refs/tags/{encoded_version}')
        return next((entry for entry in refs if entry['ref'] == ref), None)

    tag = find_tag()
    if tag is None:
        try:
            api('git/refs', '--method', 'POST', '-f', f'ref={ref}', '-f', f'sha={commit}')
        except subprocess.CalledProcessError:
            # Another run may have created this tag in the meantime.
            if find_tag() is None:
                raise
        tag = find_tag()
    if tag is None:
        raise SystemExit(f'Cannot read release tag after creation: {ref}')
    target = tag['object']
    while target['type'] == 'tag':
        target = api(f"git/tags/{target['sha']}")['object']
    if target['type'] != 'commit' or target['sha'] != commit:
        raise SystemExit(
            f'Refusing to publish: {ref} points to {target["sha"]}, expected {commit}. '
            'Existing release tags are never moved.'
        )

    apk = Path('app/build/outputs/apk/other/release') / f'fk-box-{version}-arm64-v8a.apk'
    metadata = apk.parent / 'fk-box-version-metadata.json'
    metadata_json = json.dumps({
        'version_code': int(properties['VERSION_CODE']),
        'version_name': version,
        'application_id': 'io.fkbox.app',
    }, indent=2) + '\n'
    metadata.write_text(metadata_json)
    # Installs from before the lowercase rename look only for this name; a copy
    # lets them see the new release (their APK match needs FK-box-*, so they
    # are sent to the release page instead of a direct download).
    legacy_metadata = apk.parent / 'legacy' / 'FK-box-version-metadata.json'
    legacy_metadata.parent.mkdir(exist_ok=True)
    legacy_metadata.write_text(metadata_json)
    prerelease = bool(re.search(r'(alpha|beta|rc)', version, re.IGNORECASE))
    release_options = [
        '--repo', repository,
        '--title', f'fk-box {version}',
        f'--prerelease={str(prerelease).lower()}',
        f'--latest={str(not prerelease).lower()}',
    ]
    existing = subprocess.run(
        ['gh', 'release', 'view', version, '--repo', repository, '--json', 'tagName'],
        capture_output=True, text=True
    )
    if existing.returncode == 0:
        subprocess.run(
            ['gh', 'release', 'upload', version, str(apk), str(metadata),
             '--repo', repository, '--clobber'],
            check=True
        )
        subprocess.run(
            ['gh', 'release', 'edit', version, '--draft=false', *release_options], check=True
        )
    else:
        subprocess.run(
            ['gh', 'release', 'create', version, str(apk), str(metadata),
             '--verify-tag', '--target', commit,
             '--notes', f'Signed ARM64 APK built from {commit}.', *release_options],
            check=True
        )
    # Separate and non-fatal: GitHub may refuse an asset name that differs from
    # fk-box-version-metadata.json only in case; the release itself stands.
    legacy = subprocess.run(
        ['gh', 'release', 'upload', version, str(legacy_metadata), '--repo', repository, '--clobber']
    )
    if legacy.returncode != 0:
        print(f'::warning::Legacy {legacy_metadata.name} was not uploaded; '
              'installs from before the fk-box rename will not see this release')
    print(f'Published {version} from {commit}: {apk.name}')


if __name__ == "__main__":
    main()
