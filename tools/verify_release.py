"""Verify the signed ARM64 release artifact before upload or publication."""

import hashlib
import json
import os
import re
import subprocess
from pathlib import Path
from zipfile import ZipFile


# The libraries every ABI directory must carry: the core (libbox.so) and the
# Rust + Slint UI (libfkui.so), each an ELF shared object for that ABI.
REQUIRED_LIBRARIES = ('libbox.so', 'libfkui.so')
# Executables shipped as lib*.so and run from nativeLibraryDir
# (docs/kernel-isolation.md): fk-core, the official Xray and mihomo, and the
# libXray share-link converter. mihomo is the upstream static linux build, a
# non-PIE executable (ELF type EXEC); the others are PIE (type DYN).
REQUIRED_EXECUTABLES = ('libfkcore.so', 'libxray.so', 'libmihomo.so', 'libxrayshare.so')
ELF_MACHINES = {'arm64-v8a': 183, 'armeabi-v7a': 40, 'x86_64': 62, 'x86': 3}


def undefined_cxx_symbols(data):
    """Undefined, non-weak C++ standard library symbols in a 64-bit little-endian
    ELF shared object's dynamic symbol table. libfkui.so links libc++ statically
    and needs no libc++_shared.so, so any such symbol is left unresolved at load
    time: System.loadLibrary fails and the app closes at launch (Slint 1.19's
    prebuilt Skia linked with an NDK older than r30 left
    std::__ndk1::__hash_memory undefined)."""
    if data[:4] != b'\x7fELF' or data[4] != 2 or data[5] != 1:
        return []
    u16 = lambda o: int.from_bytes(data[o:o + 2], 'little')
    u32 = lambda o: int.from_bytes(data[o:o + 4], 'little')
    u64 = lambda o: int.from_bytes(data[o:o + 8], 'little')
    shoff, shentsize, shnum = u64(0x28), u16(0x3A), u16(0x3C)
    sections = [(u32(shoff + i * shentsize + 4), u64(shoff + i * shentsize + 0x18),
                 u64(shoff + i * shentsize + 0x20), u32(shoff + i * shentsize + 0x28))
                for i in range(shnum)]
    found = []
    for sh_type, offset, size, link in sections:
        if sh_type != 11:  # SHT_DYNSYM
            continue
        strtab = sections[link][1]
        for entry in range(offset, offset + size, 24):
            name_offset, info, shndx = u32(entry), data[entry + 4], u16(entry + 6)
            if shndx != 0 or name_offset == 0 or info >> 4 == 2:  # defined, unnamed or weak
                continue
            end = data.index(b'\0', strtab + name_offset)
            name = data[strtab + name_offset:end].decode('ascii', 'replace')
            if name.startswith(('_ZNSt', '_ZNKSt', '_ZSt', '_ZTVNSt', '_ZTINSt', '_ZTSNSt')):
                found.append(name)
    return found


def verify_native_libraries(apk_path, abis):
    with ZipFile(apk_path) as apk:
        names = set(apk.namelist())
        for abi in sorted(abis):
            for library in REQUIRED_LIBRARIES + REQUIRED_EXECUTABLES:
                name = f'lib/{abi}/{library}'
                if name not in names:
                    raise SystemExit(f'Missing native library {name}')
            for name in sorted(n for n in names if n.startswith(f'lib/{abi}/') and n.endswith('.so')):
                with apk.open(name) as handle:
                    header = handle.read(20)
                if len(header) < 20 or header[:4] != b'\x7fELF':
                    raise SystemExit(f'Not an ELF library: {name}')
                elf_class = header[4]
                little = header[5] == 1
                elf_type = int.from_bytes(header[16:18], 'little' if little else 'big')
                machine = int.from_bytes(header[18:20], 'little' if little else 'big')
                expected_class = 1 if abi in ('armeabi-v7a', 'x86') else 2
                expected_types = (2, 3) if name.rsplit('/', 1)[1] in REQUIRED_EXECUTABLES else (3,)
                if elf_class != expected_class or elf_type not in expected_types or machine != ELF_MACHINES.get(abi):
                    raise SystemExit(f'{name}: class {elf_class}, type {elf_type}, machine {machine} do not match {abi}')
                if name.endswith('/libfkui.so'):
                    missing = undefined_cxx_symbols(apk.read(name))
                    if missing:
                        raise SystemExit(f'{name}: unresolved C++ library symbols {missing[:5]} '
                                         '(Slint\'s prebuilt Skia needs the NDK it was built with, see FKUI_NDK_HOME)')
    print(f'Verified native libraries for {sorted(abis)}: {", ".join(REQUIRED_LIBRARIES + REQUIRED_EXECUTABLES)} present, all ELF for their ABI, libfkui.so without unresolved C++ library symbols')


def main():
    properties = dict(
        line.strip().split('=', 1)
        for line in Path('version.properties').read_text().splitlines()
        if line.strip() and not line.lstrip().startswith('#') and '=' in line
    )
    apks = list(Path('clients/android/app/build/outputs/apk/other/release').glob('*.apk'))
    expected_name = f"fk-box-{properties['VERSION_NAME']}-arm64-v8a.apk"
    if len(apks) != 1 or apks[0].name != expected_name:
        raise SystemExit(f'Expected exactly one {expected_name}, found: {apks}')
    with ZipFile(apks[0]) as apk:
        names = apk.namelist()
        abis = {name.split('/')[1] for name in names
                if name.startswith('lib/') and name.endswith('.so')}
        if any(name.endswith('/libbbox_capture.so') for name in names):
            raise SystemExit('Retired capture collector was packaged')
        for source in Path('clients/android/app/src/main/assets/mitm-ca').rglob('*'):
            if not source.is_file():
                continue
            packaged = 'assets/' + source.relative_to('clients/android/app/src/main/assets').as_posix()
            if packaged not in names or apk.read(packaged) != source.read_bytes():
                raise SystemExit(f'Missing or changed MITM module asset: {packaged}')
        pins = dict(
            line.strip().split('=', 1)
            for line in Path('clients/android/app/geo-assets.properties').read_text().splitlines()
            if line.strip() and not line.lstrip().startswith('#') and '=' in line
        )
        manifest = json.loads(apk.read('assets/geo/manifest.json')) if 'assets/geo/manifest.json' in names else {}
        if manifest.get('commit') != pins['GEO_COMMIT']:
            raise SystemExit('Missing or stale bundled geo manifest')
        bundled = {name.strip() for name in pins.get('GEO_BUNDLED', '').split(',') if name.strip()}
        if not bundled:
            raise SystemExit('GEO_BUNDLED is empty')
        for resource in ('geoip', 'geosite', 'mmdb', 'asn'):
            source, file, digest = pins[resource].split(':')
            packaged = f'assets/geo/{file}'
            if resource in bundled:
                if packaged not in names or hashlib.sha256(apk.read(packaged)).hexdigest() != digest:
                    raise SystemExit(f'Missing or changed bundled geo file: {packaged}')
                if manifest.get('files', {}).get(resource, {}).get('sha256') != digest:
                    raise SystemExit(f'Geo manifest does not list bundled {resource}')
                continue
            # Downloaded on first use: not in the APK, pinned in the manifest.
            if packaged in names:
                raise SystemExit(f'Geo file outside GEO_BUNDLED was packaged: {packaged}')
            remote = manifest.get('remote', {}).get(resource, {})
            url = f"https://raw.githubusercontent.com/{pins['GEO_REPOSITORY']}/{pins['GEO_COMMIT']}/{source}"
            if remote.get('sha256') != digest or remote.get('url') != url or remote.get('file') != file:
                raise SystemExit(f'Geo manifest does not pin on-demand {resource}')
    if abis != {'arm64-v8a'}:
        raise SystemExit(f'Unexpected native ABIs: {abis}')
    verify_native_libraries(apks[0], abis)

    aapt2 = Path(os.environ['ANDROID_HOME']) / 'build-tools/37.0.0/aapt2'
    badging = subprocess.check_output(
        [str(aapt2), 'dump', 'badging', str(apks[0])], text=True
    )
    sdk_versions = re.findall(r"^(?:minSdkVersion|sdkVersion):'([^']*)'$", badging, re.MULTILINE)
    if sdk_versions != ['24']:
        raise SystemExit(f'Expected minimum Android SDK 24 (Android 7.0), found: {sdk_versions}')
    package_line = next(
        (line for line in badging.splitlines() if line.startswith('package: ')), ''
    )
    package = dict(re.findall(r"(\w+)='([^']*)'", package_line))
    expected_package = {
        'name': 'io.fkbox.app',
        'versionName': properties['VERSION_NAME'],
        'versionCode': properties['VERSION_CODE'],
    }
    for key, expected in expected_package.items():
        if package.get(key) != expected:
            raise SystemExit(f'Unexpected APK {key}: {package.get(key)!r}, expected {expected!r}')
    launcher_line = next(
        (line for line in badging.splitlines() if line.startswith('launchable-activity: ')), ''
    )
    launcher = dict(re.findall(r"(\w+)='([^']*)'", launcher_line))
    if launcher.get('name') != 'io.nekohasekai.sfa.compose.MainActivity':
        raise SystemExit(f'Unexpected launcher activity: {launcher.get("name")!r}')
    labels = dict(re.findall(r"^(application-label(?:-[^:]+)?):'(.*)'$", badging, re.MULTILINE))
    if 'application-label' not in labels or any(label != 'fk-box' for label in labels.values()):
        raise SystemExit(f'Unexpected application labels: {labels}')
    print(f'Verified ARM64 APK identity, minimum SDK 24 and {len(labels)} application labels: {apks[0]}')
    subprocess.run([str(aapt2.with_name('apksigner')), 'verify', '--verbose', str(apks[0])], check=True)


if __name__ == "__main__":
    main()
