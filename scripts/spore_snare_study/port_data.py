"""Freeze numeric design coefficients and canonical seeds, never audio, for the Kotlin port."""
import hashlib
import json
from pathlib import Path
from scipy import signal
from model import ROOT, INTERNAL, sk
m = json.loads(Path(__file__).with_name('model.json').read_text())
def numbers(a): return ', '.join(repr(float(v)) for v in a)
seeds = [sk.seed_for('spore-snare-study', 1, i) for i in range(32)]
sos = signal.butter(8, 20000 / (INTERNAL / 2), output='sos')
taps = signal.firwin(63, .45)
s = '''package com.snipsnap.synth

/** Numeric coefficient snapshot of the owner-approved Python study; no recorded PCM.
 * Regenerate with scripts/spore_snare_study/port_data.py. The canonical take seeds
 * preserve the approved Python LCG sequence; later takes use Dsp.seedFor.
 */
internal object SporeSnareData {
'''
s += '    const val MODEL_SHA256 = "' + hashlib.sha256(Path(__file__).with_name('model.json').read_bytes()).hexdigest() + '"\n'
s += '    const val OUTPUT_GAIN = ' + repr(m['outputGain']) + '\n'
s += '    val GAINS = doubleArrayOf(' + numbers(m['gains']) + ')\n'
s += '    val SEEDS = longArrayOf(' + ', '.join(str(n)+'L' for n in seeds) + ')\n'
s += '    val LOWPASS = arrayOf(\n' + ',\n'.join('        doubleArrayOf('+numbers(row)+')' for row in sos) + '\n    )\n'
s += '    val FIR = doubleArrayOf(' + numbers(taps) + ')\n}\n'
(ROOT/'synth/src/main/kotlin/com/snipsnap/synth/SporeSnareData.kt').write_text(s)
print('Generated coefficient/seed snapshot',m['outputGain'])
