#!/usr/bin/env python3
"""Package a clean, tested board build without changing APKs, Git or hosting.

The Android/full-JVM launch records are external execution receipts. This script
checks their actual exit results and source hashes rather than inferring success
from log text. SDK signature/manifest checks bind the download to its build.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[2]
LIMIT = 25 * 1024 * 1024
MODULES = ['audio', 'cli', 'json', 'kit', 'loop', 'mpc3', 'shell', 'synth', 'xpm']

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def command(*args):
    return subprocess.check_output([str(a) for a in args], cwd=ROOT, text=True).strip()

def read_record(path):
    record = json.loads(path.read_text())
    assert record['status'] == 'completed' and record['exitCode'] == 0, f'failed/incomplete gate: {path}'
    assert record['portInputsUnchanged'], f'inputs changed during gate: {path}'
    for name, digest in record['portInputHashesAtEnd'].items():
        assert sha(ROOT / name) == digest, f'changed since gate: {name}'
    return record

def identity(apk, tools, package):
    verification = command(tools / 'apksigner', 'verify', '--verbose', '--print-certs', apk)
    certificate = re.search(r'Signer #1 certificate SHA-256 digest: ([0-9a-f]{64})', verification)
    assert certificate and 'Number of signers: 1' in verification
    badging = command(tools / 'aapt', 'dump', 'badging', apk)
    assert f"package: name='{package}'" in badging and "sdkVersion:'29'" in badging
    abis = set()
    with zipfile.ZipFile(apk) as archive:
        assert archive.testzip() is None
        abis = {name.split('/')[1] for name in archive.namelist() if name.startswith('lib/') and name.endswith('.so')}
    return {'package': package, 'minSdk': 29, 'certificateSha256': certificate.group(1), 'abis': sorted(abis),
            'signatureVerified': True, 'sha256': sha(apk), 'bytes': apk.stat().st_size}

def archive(path, entries):
    with zipfile.ZipFile(path, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as output:
        for name, content in entries.items():
            info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            output.writestr(info, content, compresslevel=9)
    with zipfile.ZipFile(path) as check:
        assert check.testzip() is None and set(check.namelist()) == set(entries)
        for name, content in entries.items():
            assert hashlib.sha256(check.read(name)).digest() == hashlib.sha256(content).digest(), name
    assert path.stat().st_size < LIMIT, f'archive exceeds static asset limit: {path}'

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--android-record', required=True, type=Path)
    parser.add_argument('--jvm-record', required=True, type=Path)
    parser.add_argument('--apk-receipt', required=True, type=Path)
    parser.add_argument('--profile', type=Path, default=ROOT / 'testkit/obliterate-board/host-profile.json')
    parser.add_argument('--pr-url', required=True)
    parser.add_argument('--sdk', type=Path, default=Path(os.environ.get('ANDROID_SDK_ROOT', os.environ.get('ANDROID_HOME', '/workspace/android-sdk'))))
    parser.add_argument('--output', type=Path, default=ROOT / 'build/artifacts/obliterate')
    args = parser.parse_args()
    assert re.fullmatch(r'https://github.com/BertCalm/SnipSnap/pull/[0-9]+', args.pr_url)
    assert not command('git', 'status', '--porcelain'), 'commit the reviewed source before packaging'
    head = command('git', 'rev-parse', 'HEAD'); tree = command('git', 'rev-parse', 'HEAD^{tree}')
    android = read_record(args.android_record); jvm = read_record(args.jvm_record)
    apk_receipt = json.loads(args.apk_receipt.read_text())
    assert apk_receipt['androidRecordSha256'] == sha(args.android_record), 'APK receipt names a different build gate'
    for name, evidence in apk_receipt['artifacts'].items():
        assert sha(ROOT / name) == evidence['sha256'] and (ROOT / name).stat().st_size == evidence['bytes'], name
    tests = {}
    for module in MODULES:
        suites = [ET.parse(p).getroot() for p in (ROOT / module / 'build/test-results/test').glob('TEST-*.xml')]
        assert suites, module
        counts = {key: sum(int(t.get(key, '0')) for t in suites) for key in ['tests', 'failures', 'errors', 'skipped']}
        assert counts['tests'] and not any(counts[key] for key in ['failures', 'errors', 'skipped']), (module, counts)
        tests[module] = counts
    profile = json.loads(args.profile.read_text())
    assert profile['sourceCommit'] == head and not profile['workingTreeDirty']
    assert len(profile['cases']) == 4 and all(c['sampleExactToPublicComposition'] and c['clockEditReusedShimmer'] for c in profile['cases'])
    for path, digest in profile['sourceHashes'].items(): assert sha(ROOT / path) == digest, path
    tools = args.sdk / 'build-tools/35.0.0'
    apk = ROOT / 'app/build/outputs/apk/debug/app-debug.apk'
    instrumentation = ROOT / 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
    app_id = identity(apk, tools, 'com.snipsnap.app.brassgrow')
    test_id = identity(instrumentation, tools, 'com.snipsnap.app.brassgrow.test')
    assert app_id['certificateSha256'] == test_id['certificateSha256']
    assert set(app_id['abis']) == {'arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64'}
    metadata = {
        'schema': 1, 'packagedAt': datetime.now(timezone.utc).isoformat(),
        'sourceCommit': head, 'sourceTree': tree, 'workingTreeDirty': False, 'prUrl': args.pr_url,
        'boards': ['WIDE', 'RUSH', 'OBLITERATE'], 'android': app_id, 'instrumentation': test_id,
        'androidCompile': {'exitCode': android['exitCode'], 'startedUtc': android['startedUtc'], 'endedUtc': android['endedUtc']},
        'jvmRegression': {'exitCode': jvm['exitCode'], 'modules': tests, 'tests': sum(c['tests'] for c in tests.values())},
        'hostProfile': profile,
        'limits': 'Unshrunk debug build. Android app and test APKs compiled; no phone/emulator was attached locally. Device profiling, new board listening and on-device UI execution are not inferred from compilation. See PR checks for current CI.',
    }
    out = args.output.resolve(); out.mkdir(parents=True, exist_ok=True)
    app_name = 'SnipSnap-OBLITERATE-debug.apk'
    test_name = 'SnipSnap-OBLITERATE-profile-tests.apk'
    text = f'''SnipSnap OBLITERATE + WIDE + RUSH

Android 10 or later. Development build. Extract this ZIP, then install {app_name}.
Open a pad, expand TREATMENT and choose OBLITERATE.
AMT controls destruction; SHIMMER controls the halo. CLOCK & PATTERN opens timing.
Compare HEAR ORIGINAL / HEAR OBLITERATE. Only KEEP saves the candidate.
REMOVE restores the shared original. UNDO reverses the last kept edit/removal.
Nonzero OBLITERATE accepts sources through nine seconds, in every velocity zone.
Phone performance and the new low-AMT board mapping still need listening/profile checks.

Source: {head}
Review: {args.pr_url}
APK SHA-256: {app_id['sha256']}
Signer certificate SHA-256: {app_id['certificateSha256']}
The package identity is the existing com.snipsnap.app.brassgrow development app.
'''
    metadata_bytes = (json.dumps(metadata, indent=2) + '\n').encode()
    (out / 'OBLITERATE-build.json').write_bytes(metadata_bytes)
    shutil.copyfile(apk, out / app_name)
    assert sha(out / app_name) == app_id['sha256']
    main_zip = out / 'SnipSnap-OBLITERATE-debug.zip'
    archive(main_zip, {app_name: apk.read_bytes(), 'README.txt': text.encode(), 'OBLITERATE-build.json': metadata_bytes,
                       app_name + '.sha256': (app_id['sha256'] + '  ' + app_name + '\n').encode()})
    instructions = f'''Optional device profiling bundle; the main application is a separate download.

Install the main {app_name} first, then use an Android SDK's adb:

adb install -r {test_name}
adb shell am instrument -w -e class com.snipsnap.app.ObliterateDeviceProfileTest com.snipsnap.app.brassgrow.test/androidx.test.runner.AndroidJUnitRunner
adb shell run-as com.snipsnap.app.brassgrow cat files/obliterate-profile.json > obliterate-profile.json

The report identifies the device/build, installed APK hashes, complete saved recipes,
exact four listening inputs, all timing trials, cached processor calls, real shell
preview reads and KEEP/save costs.
This targeted profile does not run the full Compose UI regression suite.
An emulator report does not establish physical-phone performance.

Source {head}
Review {args.pr_url}
Test APK SHA-256 {test_id['sha256']}
'''
    test_zip = out / 'SnipSnap-OBLITERATE-profiling.zip'
    archive(test_zip, {test_name: instrumentation.read_bytes(), 'README.txt': instructions.encode(),
                       'OBLITERATE-build.json': metadata_bytes})
    for path in [main_zip, test_zip]:
        path.with_name(path.name + '.sha256').write_text(sha(path) + '  ' + path.name + '\n')
    assert command('git', 'rev-parse', 'HEAD') == head and not command('git', 'status', '--porcelain')
    assert sha(apk) == app_id['sha256'] and sha(instrumentation) == test_id['sha256']
    print(json.dumps({'sourceCommit': head, 'apkSha256': app_id['sha256'], 'jvmTests': metadata['jvmRegression']['tests'],
                      'downloads': [{'path': str(p), 'bytes': p.stat().st_size, 'sha256': sha(p)} for p in [main_zip, test_zip]],
                      'metadata': str(out / 'OBLITERATE-build.json')}, indent=2))

if __name__ == '__main__': main()
