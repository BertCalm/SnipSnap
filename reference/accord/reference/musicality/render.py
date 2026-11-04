"""Accord musical voicing studies, adapted from Luthier proto_template.py.

Python research harness, not Kotlin: approximate output filters and whole-file
RMS matching. All three voices read the same literal mechanical event history.
Only root hierarchy, contact shape, modal decay and geometry-gated brushing vary.
"""
import json
import sys
from pathlib import Path
from time import perf_counter
import numpy as np
from scipy import signal

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / 'mechanics'))
import snipkit as sk
import probes as pr

OUT = Path(__file__).parent
RATE = sk.INTERNAL
ROOT_MIDI = 48
HZ = 440 * 2 ** ((ROOT_MIDI - 69) / 12)
START = 4.0
EXCITE = 8.0
RELEASE = .9
PACE = 2.0
CONFIGS = {
    'wood': dict(width_ms=2.1, tongue=[(1., .85, .48), (2., .50, .29),
                  (3., .24, .17), (4.04, .10, .10)],
                 body=[(1., .22, .72), (1.51, .07, .22), (2.29, .035, .16),
                       (3.41, .018, .10)], body_gain=.50, brush=0.),
    'tine': dict(width_ms=.72, tongue=[(1., 1., .83), (2., .38, .40),
                  (3., .20, .24), (5.96, .60, .26)],
                 body=[(1., .14, .62), (1.50, .035, .28), (2.42, .025, .17),
                       (3.24, .012, .11)], body_gain=.32, brush=0.),
    'brush': dict(width_ms=4.5, tongue=[(1., 1., 1.05), (2., .13, .52),
                  (3., .025, .29), (4., .009, .17)],
                 body=[(1., .26, 1.35), (1.50, .06, .55), (2.25, .018, .31),
                       (3.25, .009, .19)], body_gain=.58, brush=.0024, pulse_gain=.55),
}


def pitch_nsdf(x, hz_ref, start_s):
    """Unrestricted 40..2000 Hz McLeod-style NSDF, with overlap energies.

    This deliberately corrects the bundled Python probe's denominator rather
    than changing the shared reference file. Root support is assessed without
    telling the detector the expected note.
    """
    segment = x[round(start_s*sk.RATE):round((start_s+.30)*sk.RATE)]
    segment = segment-segment.mean()
    n = len(segment)
    spectrum = np.fft.rfft(segment, 2*n)
    acf = np.fft.irfft(spectrum*np.conj(spectrum))[:n]
    prefix = np.r_[0., np.cumsum(segment*segment)]
    lag = np.arange(n)
    overlap = prefix[n-lag] + prefix[n]-prefix[lag]
    nsdf = 2*acf / np.maximum(overlap, 1e-24)
    lo,hi = int(np.ceil(sk.RATE/2000)),int(sk.RATE/40)
    peaks,_ = signal.find_peaks(nsdf[lo:hi])
    peaks += lo
    if not len(peaks):
        return dict(start_s=start_s,hz=0.,cents=None,confidence=0.)
    best = peaks[nsdf[peaks] >= .8*np.max(nsdf[peaks])][0]
    a,b,c = nsdf[best-1:best+2]
    denominator=a-2*b+c
    refined = best + (.5*(a-c)/denominator if abs(denominator)>1e-15 else 0.)
    hz=sk.RATE/refined
    return dict(start_s=start_s,hz=float(hz),cents=float(pr.cents(hz,hz_ref)),
                confidence=float(np.clip(nsdf[best],0,1)))


def load_inputs():
    source = json.loads((Path(__file__).resolve().parent.parent / 'mechanics' / 'gathered-events.json').read_text())
    events = np.array(source['events'])
    events[:, 0] /= PACE
    z = np.load(Path(__file__).resolve().parent.parent / 'mechanics' / 'gathered.npz')
    # Geometry is smooth and sampled at 40 points/reference cycle. Interpolate
    # its pressure readout at 2 kHz; preserve exact dense-output contact roots.
    t = np.arange(round(EXCITE * 2000)) / 2000
    pressure = np.zeros(len(t))
    mechanical_t = z['time_reference_cycles'] / PACE
    for i in range(15):
        theta = np.interp(t + START, mechanical_t, z['state'][i])
        speed = np.abs(np.interp(t + START, mechanical_t, z['state'][15 + i]))
        pressure += np.maximum(0., 1 - (theta / .17) ** 2) ** 2 * np.minimum(speed / .8, 2.) / 15
    audio_t = np.arange(round((EXCITE + RELEASE) * RATE)) / RATE
    p = np.interp(audio_t, t, pressure, right=0.)
    # Excitation stops over 60 ms; the following .9 s is an unforced modal tail.
    release_gate = np.ones_like(audio_t)
    zone = audio_t > EXCITE - .060
    release_gate[zone] = np.maximum(0., .5 + .5 * np.cos(np.pi *
                             np.minimum((audio_t[zone] - EXCITE + .060) / .060, 1.)))
    p *= release_gate
    rng = np.random.default_rng(20261022)
    roughness = np.tanh(rng.standard_normal(len(p)))
    sos = signal.butter(2, [80, 2600], btype='bandpass', fs=RATE, output='sos')
    roughness = signal.sosfilt(sos, roughness)
    return events, p, roughness, release_gate


def render(name, cfg, events, pressure, roughness, release_gate, hz=HZ):
    n = len(pressure)
    source = np.zeros(n)
    kept = events[(events[:, 0] >= START) & (events[:, 0] < START + EXCITE)]
    kernels = {}
    for direction in [-1, 1]:
        width = max(4, round(cfg['width_ms'] * min(1.,HZ/hz) * (1 if direction > 0 else 1.12) * .001 * RATE))
        # A fixed force-kernel calibration per voice/direction, never per hit.
        kernel = np.hanning(width)
        kernels[direction] = kernel / kernel.sum()
    for tc, mechanism, direction, speed in kept:
        position = (tc - START) * RATE
        index = int(position)
        fraction = position - index
        amplitude = np.sqrt(min(speed / .8, 2.)) / 15
        pulse = kernels[int(direction)] * amplitude
        for shift, weight in [(0, 1-fraction), (1, fraction)]:
            end = min(n, index + shift + len(pulse))
            source[index + shift:end] += pulse[:end-index-shift] * weight
    source *= release_gate * cfg.get('pulse_gain',1.)
    source = sk.band_limit(source, RATE, cutoff_hz=10000)
    tongue = sk.modal_ring(source, hz, cfg['tongue'], RATE, compensate=True)
    body = sk.modal_ring(source, hz, cfg['body'], RATE, compensate=True)
    raw_direct = tongue + cfg['body_gain'] * body
    brush_source = sk.band_limit(cfg['brush'] * pressure * roughness,RATE,cutoff_hz=10000)
    raw_brush = np.zeros_like(raw_direct)
    if cfg['brush']:
        raw_brush = sk.modal_ring(brush_source,hz,cfg['tongue'],RATE,compensate=True)
        raw_brush += cfg['body_gain'] * sk.modal_ring(brush_source,hz,cfg['body'],RATE,compensate=True)
    raw = raw_direct + raw_brush
    # All banks are feedforward, with poles strictly inside the unit circle.
    conditioned = sk.dc_block(sk.decimate4(sk.band_limit(raw, RATE)))
    # One constant whole-file gain makes equal-RMS voicing comparisons; it
    # preserves the gathering envelope and does not level individual impacts.
    gain = .105 / sk.rms(conditioned)
    peak = np.max(np.abs(conditioned * gain))
    if peak > .95:
        gain *= .95 / peak
    out = sk.fade_tail(conditioned * gain)
    # Start the note from zero acoustic state, not a pre-ringing sample cut.
    head = round(.003 * sk.RATE)
    out[:head] *= np.linspace(0, 1, head)
    pitches = []
    for start in [.5, 2., 4., 6.5]:
        pitches.append(pitch_nsdf(out,hz,start))
    f, spectrum = signal.welch(out[:round(EXCITE*sk.RATE)], sk.RATE, nperseg=32768)
    root_band = (f >= hz*.94) & (f <= hz*1.06)
    report = dict(config=cfg, root_hz=hz, events=len(kept), raw_peak=float(np.max(np.abs(raw))),
                  gain=float(gain), finite=bool(np.isfinite(out).all()),
                  peak=float(np.max(np.abs(out))), rms=sk.rms(out), dc=float(out.mean()),
                  active_spectral_centroid_hz=float(np.sum(f*spectrum)/np.sum(spectrum)),
                  active_root_band_energy_share=float(spectrum[root_band].sum()/spectrum.sum()),
                  brushing_to_direct_rms_db=(float(20*np.log10(sk.rms(raw_brush[:round(EXCITE*RATE)])/sk.rms(raw_direct[:round(EXCITE*RATE)]))) if cfg['brush'] else None),
                  pitch_method='Corrected Python NSDF overlap-energy denominator, unrestricted 40..2000Hz search, .3s windows',
                  pitch_windows=pitches,
                  last_100ms_rms=sk.rms(out[-4410:]),
                  max_pole_radius=float(max(np.exp(-6.9078/(r[2]*RATE)) for r in cfg['tongue']+cfg['body'])))
    sk.write_wav(str(OUT/(name+'.wav')), out)
    return report


def main():
    begin = perf_counter()
    events, pressure, roughness, gate = load_inputs()
    report = dict(status='Python voicing studies, not Kotlin or reviewed production sounds',
                  root_midi=ROOT_MIDI, root_hz=HZ, output_rate=sk.RATE, internal_rate=RATE,
                  mechanics_preroll_seconds=START, acoustic_preroll_seconds=0.,
                  excitation_seconds=EXCITE, release_seconds=RELEASE,
                  reference_cycles_per_second=PACE,
                  comparison='Same events and note; one constant whole-file RMS-proxy gain per voice',
                  limitations=['Approximate Python output filters, not Kotlin house code',
                               'RMS proxy is not perceived loudness matching',
                               'Mode ratios, gains, contact widths and decay constants are unreviewed design choices',
                               'Pressure descriptors interpolate a coarse smooth mechanical readout; events retain exact timing',
                               'No HOLD, note-range sweep, velocity sweep or listening judgment'],
                  renders={})
    for name, cfg in CONFIGS.items():
        report['renders'][name] = render(name, cfg, events, pressure, roughness, gate)
    for midi in [36,60]:
        hz=440*2**((midi-69)/12)
        name='brush_C'+str(midi//12-1)
        report['renders'][name]=render(name,CONFIGS['brush'],events,pressure,roughness,gate,hz)
    report['wall_seconds_python_all_three_with_metrics'] = perf_counter()-begin
    (OUT/'report.json').write_text(json.dumps(report, indent=2))
    print(json.dumps(report, indent=2))

if __name__ == '__main__':
    main()
