"""Validate the portable mapping against the accepted, captured study inputs.

No synth is reimplemented here. Rendering identity is checked separately by
ReplayReferences.java against the twelve recorded public PCM24 exports.
"""
import hashlib
import json
from pathlib import Path

from model import DESIGNS, original, patch, serial_designs

PACKAGE = Path(__file__).resolve().parent


def sha(data):
    return hashlib.sha256(data).hexdigest()


def wave_data(path):
    data = path.read_bytes()
    assert data[:4] == b'RIFF' and data[8:12] == b'WAVE', path
    offset = 12
    fmt = pcm = None
    while offset + 8 <= len(data):
        tag = data[offset:offset + 4]
        size = int.from_bytes(data[offset + 4:offset + 8], 'little')
        chunk = data[offset + 8:offset + 8 + size]
        assert len(chunk) == size, path
        if tag == b'fmt ':
            fmt = {
                'encoding': int.from_bytes(chunk[:2], 'little'),
                'channels': int.from_bytes(chunk[2:4], 'little'),
                'sampleRate': int.from_bytes(chunk[4:8], 'little'),
                'blockAlign': int.from_bytes(chunk[12:14], 'little'),
                'bits': int.from_bytes(chunk[14:16], 'little'),
            }
        elif tag == b'data':
            pcm = chunk
        offset += size + 8 + (size & 1)
    assert fmt is not None and pcm is not None, path
    return data, fmt, pcm


def main():
    fixture = json.loads((PACKAGE / 'fixtures.json').read_text())
    clips = fixture['clips']
    assert len(clips) == 36 and len({clip['id'] for clip in clips}) == 36
    assert fixture['liveMappingInstalled'] is False
    assert serial_designs() == json.loads((PACKAGE / 'final-designs.json').read_text())
    assert set(DESIGNS) == {'rounded_plug', 'long_prism', 'velvet_rise'}

    for clip in clips:
        path = PACKAGE / clip['patchPath']
        captured_bytes = path.read_bytes()
        assert sha(captured_bytes) == clip['patchSha256'], path
        captured = json.loads(captured_bytes)
        generated = patch(
            clip['roleId'], clip['material'], clip['mode'] == 'held',
            midi=captured['midi'], velocity=captured['velocity'],
            reference=clip['reference'],
        )
        assert generated == captured, clip['id']
        assert (json.dumps(generated, indent=2) + '\n').encode() == captured_bytes, clip['id']
        baseline = original(clip['roleId'])
        fixed = ['tine', 'breath', 'envelope', 'held', 'fuseRadians']
        for key in fixed:
            assert captured['config'][key] == baseline['config'][key], (clip['id'], key)
        assert [x['ratio'] for x in captured['config']['operators']] == [
            x['ratio'] for x in baseline['config']['operators']
        ], clip['id']
        assert captured['config']['wood']['ratios'] == baseline['config']['wood']['ratios'], clip['id']
        if clip['reference'] or clip['material'] >= .5:
            assert captured['config'] == baseline['config'], clip['id']
        assert captured['macros']['HOLD'] == int(clip['mode'] == 'held'), clip['id']
        assert captured['macros']['MATERIAL'] == clip['material'], clip['id']

    for role in DESIGNS:
        for mode in ['finite', 'held']:
            group = [clip for clip in clips if clip['roleId'] == role and clip['mode'] == mode]
            assert len(group) == 6
            assert sum(clip['reference'] for clip in group) == 1
            assert {clip['material'] for clip in group if not clip['reference']} == {0, .25, .5, .75, 1}

    goldens = json.loads((PACKAGE / 'reference/index.json').read_text())['goldens']
    assert len(goldens) == 12
    for golden in goldens:
        path = PACKAGE / golden['wavPath']
        data, fmt, pcm = wave_data(path)
        assert sha(data) == golden['wavSha256'], path
        assert sha(pcm) == golden['pcmSha256'], path
        assert fmt['encoding'] == 1 and fmt['bits'] == 24 and fmt['channels'] == 1, path
        assert fmt['sampleRate'] == 44100 and fmt['blockAlign'] == 3, path
        assert len(pcm) // 3 == golden['frames'], path
        clip = next(clip for clip in clips if clip['id'] == golden['id'])
        assert not clip['reference'] and clip['material'] in [0, .5]
        assert golden['patchPath'] == clip['patchPath']
        assert golden['pcmSha256'] == clip['native']['pcmSha256']

    print(json.dumps({
        'snapshotsExact': len(clips),
        'nativeGoldenFilesVerified': len(goldens),
        'engineSourceCommit': fixture['engineSourceCommit'],
        'liveMappingInstalled': False,
        'actualExit': 0,
    }, indent=2))


if __name__ == '__main__':
    main()
