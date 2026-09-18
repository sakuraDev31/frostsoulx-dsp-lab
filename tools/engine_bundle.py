#!/usr/bin/env python3
"""Bounded, transactional source/compiled engine ZIP import and export. Never builds."""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import stat
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parent.parent
ENGINE = ROOT / 'app/src/main/cpp/engine'
SDK = ROOT / 'engine-bundle/third_party/steamaudio_sdk'
ABIS = {'arm64-v8a': (183, 'android-armv8'), 'x86_64': (62, 'android-x64')}
LIMIT = 256 * 1024 * 1024


def require(ok, message):
    if not ok:
        raise ValueError(message)


def extract(archive, target):
    require(archive.stat().st_size <= LIMIT // 2, 'ZIP exceeds 128 MiB')
    with zipfile.ZipFile(archive) as z:
        entries = z.infolist()
        require(0 < len(entries) <= 2048, 'Invalid ZIP entry count')
        total = 0
        seen = set()
        for entry in entries:
            path = PurePosixPath(entry.filename)
            require(not path.is_absolute() and '..' not in path.parts and '\\' not in entry.filename,
                    'Unsafe ZIP path')
            require(not stat.S_ISLNK(entry.external_attr >> 16), 'ZIP symlinks are not accepted')
            require(str(path) not in seen, 'Duplicate ZIP entry')
            seen.add(str(path))
            total += entry.file_size
            require(total <= LIMIT and not entry.flag_bits & 1, 'Oversized or encrypted ZIP')
            dest = target / str(path)
            if entry.is_dir():
                dest.mkdir(parents=True, exist_ok=True)
            else:
                dest.parent.mkdir(parents=True, exist_ok=True)
                with z.open(entry) as source, dest.open('wb') as output:
                    shutil.copyfileobj(source, output, 65536)


def unique(root, name, required=True):
    found = [p for p in root.rglob(name) if p.is_file()]
    require(len(found) <= 1 and (found or not required), f'Expected one {name}; found {len(found)}')
    return found[0] if found else None


def elf(path, abi):
    with path.open('rb') as f:
        header = f.read(20)
    require(len(header) == 20 and header[:6] == b'\x7fELF\x02\x01' and
            int.from_bytes(header[16:18], 'little') == 3 and
            int.from_bytes(header[18:20], 'little') == ABIS[abi][0],
            f'Not an Android {abi} shared ELF: {path.name}')


def copy(source, destination):
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, destination)


def check_manifest(root):
    manifest = unique(root, 'engine-manifest.json', False)
    if not manifest:
        return  # legacy source bundles remain supported
    data = json.loads(manifest.read_text())
    require(data.get('format') == 1, 'Unsupported engine bundle format')
    for relative, digest in data.get('sha256', {}).items():
        path = (manifest.parent / relative).resolve()
        require(path.is_relative_to(manifest.parent.resolve()) and path.is_file(), 'Invalid manifest path')
        require(hashlib.sha256(path.read_bytes()).hexdigest() == digest, f'Checksum mismatch: {relative}')


def import_bundle(source):
    require(source.exists(), 'Engine ZIP/directory does not exist')
    with tempfile.TemporaryDirectory(prefix='.engine-stage-', dir=ROOT) as temp:
        temp = Path(temp)
        if source.is_dir():
            require(not any(p.is_symlink() for p in source.rglob('*')), 'Source symlinks are not accepted')
            incoming = source
        else:
            incoming = temp / 'incoming'
            incoming.mkdir()
            extract(source, incoming)
        check_manifest(incoming)
        header = unique(incoming, 'immersive_audio_engine.h')
        implementation = unique(incoming, 'immersive_audio_engine.cpp', False)
        staged = temp / 'engine'
        copy(ENGINE / 'CMakeLists.txt', staged / 'CMakeLists.txt')
        copy(header, staged / 'include/frostsoulx/immersive_audio_engine.h')
        if implementation:
            copy(implementation, staged / 'src/immersive_audio_engine.cpp')
            p = staged / 'src/immersive_audio_engine.cpp'
            p.write_text(p.read_text().replace('frostsoulx/ImmersiveAudioEngine.h', 'frostsoulx/immersive_audio_engine.h'))
            phonon = unique(incoming, 'phonon.h', False)
            sdk = phonon.parent.parent if phonon else SDK
            for name in ('phonon.h', 'phonon_interfaces.h', 'phonon_version.h'):
                copy(sdk / 'include' / name, staged / 'third_party/steamaudio_sdk/include' / name)
            copy(sdk / 'LICENSE.md', staged / 'third_party/steamaudio_sdk/LICENSE.md')
            for abi, (_, arch) in ABIS.items():
                lib = sdk / 'lib' / arch / 'libphonon.so'
                elf(lib, abi)
                copy(lib, staged / 'third_party/steamaudio_sdk/lib' / arch / 'libphonon.so')
            kind = 'source'
        else:
            require(unique(incoming, 'engine-manifest.json', False) is not None,
                    'Compiled bundles require engine-manifest.json and matching public header')
            for abi in ABIS:
                for name in ('libfrostsoulx_engine.so', 'libphonon.so'):
                    found = [p for p in incoming.rglob(name) if p.parent.name == abi]
                    require(len(found) == 1, f'Missing/ambiguous {abi}/{name}')
                    elf(found[0], abi)
                    copy(found[0], staged / 'lib' / abi / name)
            license_file = unique(incoming, 'LICENSE.md')
            copy(license_file, staged / 'LICENSE.md')
            kind = 'compiled'
        # Validate all inputs before swapping the live tree; roll back on rename failure.
        backup = temp / 'previous'
        ENGINE.rename(backup)
        try:
            staged.rename(ENGINE)
        except BaseException:
            backup.rename(ENGINE)
            raise
        print(f'Imported {kind} engine for arm64-v8a and x86_64. Rebuild the APK to activate.')


def export_bundle(output, native_dir):
    output = output.resolve()
    require(output.is_relative_to(ROOT) and output.suffix == '.zip', 'Output must be a .zip inside the workspace')
    require(output.parent.is_dir(), 'Output parent directory must exist')
    files = {'include/frostsoulx/immersive_audio_engine.h': ENGINE / 'include/frostsoulx/immersive_audio_engine.h',
             'CMakeLists.txt': ENGINE / 'CMakeLists.txt'}
    sdk = ENGINE / 'third_party/steamaudio_sdk'
    if not sdk.is_dir():
        sdk = SDK
    native_dir = native_dir or (ENGINE / 'lib' if (ENGINE / 'lib').is_dir() else None)
    kind = 'compiled' if native_dir else 'source'
    if native_dir:
        for abi, (_, arch) in ABIS.items():
            for name in ('libfrostsoulx_engine.so', 'libphonon.so'):
                path = native_dir / abi / name
                if name == 'libphonon.so' and not path.exists():
                    path = sdk / 'lib' / arch / name
                elf(path, abi)
                files[f'lib/{abi}/{name}'] = path
        files['LICENSE.md'] = ENGINE / 'LICENSE.md' if (ENGINE / 'LICENSE.md').exists() else sdk / 'LICENSE.md'
    else:
        files['src/immersive_audio_engine.cpp'] = ENGINE / 'src/immersive_audio_engine.cpp'
        for path in sdk.rglob('*'):
            if path.is_file():
                files['third_party/steamaudio_sdk/' + path.relative_to(sdk).as_posix()] = path
    manifest = {'format': 1, 'kind': kind, 'abis': list(ABIS), 'activation': 'rebuild-required',
                'sha256': {name: hashlib.sha256(path.read_bytes()).hexdigest() for name, path in files.items()}}
    with tempfile.NamedTemporaryFile(prefix='.engine-export-', dir=ROOT, delete=False) as f:
        temporary = Path(f.name)
    try:
        with zipfile.ZipFile(temporary, 'w', zipfile.ZIP_DEFLATED) as z:
            z.writestr('engine-manifest.json', json.dumps(manifest, indent=2))
            for name, path in files.items():
                z.write(path, name)
        require(temporary.stat().st_size <= LIMIT // 2, 'Export exceeds ZIP limit')
        os.replace(temporary, output)
    finally:
        temporary.unlink(missing_ok=True)
    print(f'Exported {kind} SDK: {output}')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    imp = sub.add_parser('import')
    imp.add_argument('source', type=Path)
    exp = sub.add_parser('export')
    exp.add_argument('output', type=Path)
    exp.add_argument('--native-dir', type=Path, help='Existing compiled libs: DIR/<ABI>/libfrostsoulx_engine.so (no build is run)')
    args = parser.parse_args()
    try:
        if args.command == 'import':
            import_bundle(args.source.resolve())
        else:
            export_bundle(args.output, args.native_dir)
    except (ValueError, OSError, zipfile.BadZipFile, KeyError) as error:
        parser.exit(1, f'Engine bundle rejected: {error}\n')
