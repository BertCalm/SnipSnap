#!/usr/bin/env python3
"""Read emitted MPC 2 XML/windows and PCM, plus listening-pack asset closure.

MPC 3 ACVS and playback proof runs in :shell:generateTimberKits. This check
proves only MPC 2's three first-take velocity windows, not hardware playback.
"""
import argparse
import hashlib
import json
from pathlib import Path
import wave
import xml.etree.ElementTree as ET
from html.parser import HTMLParser


def verify(root):
    results = []
    for folder in sorted(root.glob('timber-*')):
        if not folder.is_dir():
            continue
        kit = json.loads((folder / 'native/kit.json').read_text())
        program = next((folder / 'mpc2').glob('*/*.xpm'))
        instruments = ET.parse(program).findall('./Program/Instruments/Instrument')
        for pad in kit['pads']:
            inst = instruments[pad['slot'] - 1]
            assert int(inst.findtext('MuteGroup')) == pad['muteGroup']
            layers = [l for l in inst.findall('./Layers/Layer') if l.findtext('Active') == 'True' and l.findtext('SampleName')]
            assert len(layers) == 3
            expected = (folder / 'native' / pad['sample']).read_bytes()
            for layer, zone in zip(layers, pad['chain']['zones']):
                assert int(layer.findtext('VelStart')) == zone['velStart']
                assert int(layer.findtext('VelEnd')) == zone['velEnd']
                sample = program.parent / (layer.findtext('SampleName') + '.wav')
                assert sample.read_bytes() == expected, 'MPC 2 changed the chain PCM'
                start, end = int(layer.findtext('SliceStart')), int(layer.findtext('SliceEnd'))
                base = zone['baseSlice']
                assert start == pad['chain']['boundaries'][base]
                assert end == pad['chain']['boundaries'][base + 1]
                with wave.open(str(sample), 'rb') as wav:
                    assert (wav.getnchannels(), wav.getframerate(), wav.getsampwidth()) == (1, 44100, 3)
                    assert 0 <= start < end <= wav.getnframes()
                    wav.setpos(start)
                    pcm = wav.readframes(end - start)
                    assert pcm
                results.append(dict(genre=folder.name, slot=pad['slot'],
                    velocity=[zone['velStart'], zone['velEnd']], first_take=[start, end],
                    pcm_sha256=hashlib.sha256(pcm).hexdigest()))
    assert len(results) == 144

    class Assets(HTMLParser):
        def __init__(self):
            super().__init__()
            self.refs = []
        def handle_starttag(self, tag, attrs):
            if tag == 'audio':
                self.refs.extend(v for k, v in attrs if k == 'src')
    assets = Assets()
    assets.feed((root / 'index.html').read_text())
    assert len(assets.refs) == 147  # 48 pads x hard/native-touch/matched-touch plus three grooves.
    for ref in assets.refs:
        with wave.open(str(root / ref), 'rb') as wav:
            assert wav.getnframes() > 0 and wav.getframerate() == 44100
    report = dict(mpc2_velocity_windows='pass', mpc2_round_robin='owner format exception',
        hardware='not verified', audio_assets=len(assets.refs), windows=results)
    (root / 'mpc2-readback.json').write_text(json.dumps(report, indent=2) + '\n')
    print(f'PASS: {len(results)} MPC 2 velocity windows, 48 chain WAVs, {len(assets.refs)} audition assets; no hardware claim')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('root', type=Path, nargs='?', default=Path('testkit/timber-kits'))
    verify(parser.parse_args().root)
