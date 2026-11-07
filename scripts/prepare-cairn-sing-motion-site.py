#!/usr/bin/env python3
"""Package the held SING comparison, preserve earlier auditions, restore its source."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile

REPO = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('cairn_assets', REPO / 'scripts/prepare-cairn-sing-site.py')
assets = importlib.util.module_from_spec(spec)
spec.loader.exec_module(assets)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--site', type=Path, required=True)
    parser.add_argument('--audition', type=Path, default=REPO / 'testkit/cairn-sing-motion')
    parser.add_argument('--source-only', action='store_true')
    args = parser.parse_args()
    site = args.site.resolve(); native = args.audition.resolve(); dest = site / 'dist/sing-motion'
    base = '97a5d6309de1825dbd3940e578384b11368b6f16'
    before = {str(p.relative_to(site)): assets.sha(p.read_bytes())
              for name in ['reconstruction', 'hold', 'tumble', 'sing', 'audio', 'downloads']
              for p in (site / 'dist' / name).rglob('*') if p.is_file()}
    dest.mkdir(parents=True, exist_ok=True)
    manifest = json.loads((dest / 'manifest.json' if args.source_only else native / 'manifest.json').read_text())
    for name, digest in manifest['sourceSha256'].items():
        assert assets.sha((REPO / 'synth/src/main/kotlin/com/snipsnap/synth' / name).read_bytes()) == digest, 'Regenerate changed DSP'
    for name in ['index.html', 'styles.css', 'app.js']:
        shutil.copyfile(REPO / 'synth/src/test/resources/cairn-sing-motion-audition' / name, dest / name)
    shutil.copytree(native / 'recipes', dest / 'recipes', dirs_exist_ok=True)
    rows = []
    if not args.source_only:
        for c in manifest['clips']:
            wav = native / c['audio']; assert assets.sha(wav.read_bytes()) == c['sha256']
            target = dest / 'audio' / (c['id'] + '.flac')
            raw = assets.encode(wav, target); x = assets.samples(raw)
            assert abs(float(x.mean())) < .002 and 0 < float(max(abs(x))) < .991
            c.update(audio=str(target.relative_to(dest)), nativeWavSha256=c.pop('sha256'),
                     flacSha256=assets.sha(target.read_bytes()), previewGain=assets.preview_gain(x))
            row = {'id': c['id'], 'frames': len(x)}
            if c['held']:
                target = dest / 'continuation' / (c['id'] + '.flac')
                full = assets.encode(native / 'continuation' / (c['id'] + '.wav'), target)
                start = c['loopStartFrame']; assert raw == full[start * 3:]
                y = assets.samples(full); a = y[start - 256:start]; b = y[-256:]
                error = float((a - b) @ (a - b)); energy = float(a @ a)
                seam = error / energy if energy else 0.0 if not error else float('inf')
                assert seam < 1e-3 and c['warmReferenceRelativeRms'] < 1e-5
                row['nativePcmSeamError'] = seam
                c['continuation'] = str(target.relative_to(dest))
            rows.append(row)
    subprocess.run(['git', 'add', '-N', '.'], cwd=REPO, check=True)
    patch = assets.git('diff', '--binary', base); assert patch.strip()
    with tempfile.TemporaryDirectory(prefix='cairn-rim-restore-') as tmp:
        env = os.environ.copy(); env['GIT_INDEX_FILE'] = str(Path(tmp) / 'index')
        subprocess.run(['git', 'read-tree', base], cwd=REPO, env=env, check=True)
        subprocess.run(['git', 'apply', '--cached', '--binary', '-'], input=patch, cwd=REPO, env=env, check=True)
        tree = subprocess.check_output(['git', 'write-tree'], cwd=REPO, env=env).decode().strip()
    archive = dest / 'source/cairn-sing-motion-source.zip'; archive.parent.mkdir(exist_ok=True)
    readme = ('CAIRN SING: held rim motion\nRepository: https://github.com/BertCalm/SnipSnap\n'
              f'Base: {base}\nRestored tree: {tree}\nCheck out the base, then: git apply cairn-sing-motion.patch\n'
              'JDK 21: ./gradlew --no-daemon :synth:generateCairnSingMotionAudition\n'
              'Full verification: ./gradlew --no-daemon test\nRead docs/CAIRN_SING_MOTION.md.\n')
    files = {'README.txt': readme.encode(), 'cairn-sing-motion.patch': patch}
    for path in ['docs/CAIRN_SING_MOTION.md', 'synth/src/main/kotlin/com/snipsnap/synth/CairnSing.kt',
                 'synth/src/test/kotlin/com/snipsnap/synth/CairnSingMotionTest.kt',
                 'synth/src/test/kotlin/com/snipsnap/synth/CairnSingMotionAuditionGenerator.kt']:
        files[path] = (REPO / path).read_bytes()
    with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as zipped:
        for name, content in sorted(files.items()):
            item = zipfile.ZipInfo(name, (2026, 11, 7, 0, 0, 0)); item.compress_type = zipfile.ZIP_DEFLATED
            item.external_attr = 0o644 << 16; zipped.writestr(item, content)
    manifest.update(sourceArchiveBase=base, sourceArchiveTree=tree,
                    previewPolicy='Native 24-bit PCM preserved losslessly; gain only. Eight held cycles per preview.')
    (dest / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    assert all(assets.sha((site / name).read_bytes()) == digest for name, digest in before.items())
    proof = {'preservedFiles': len(before), 'changedPreservedFiles': 0, 'nativePcmChanged': 0,
             'sourceBase': base, 'restoredSourceTree': tree, 'sourcePatchSha256': assets.sha(patch),
             'sourceZipSha256': assets.sha(archive.read_bytes())}
    if rows:
        proof.update(clips=len(rows), heldCycles=sum(c['held'] for c in manifest['clips']),
                     maxNativePcmSeamError=max(r.get('nativePcmSeamError', 0) for r in rows),
                     allLoopPcmExactSuffix=True, results=rows)
    elif (dest / 'verification.json').exists():
        previous = json.loads((dest / 'verification.json').read_text()); previous.update(proof); proof = previous
    (dest / 'verification.json').write_text(json.dumps(proof, indent=2) + '\n')
    print(json.dumps({k: v for k, v in proof.items() if k != 'results'}))

if __name__ == '__main__':
    main()
