"""Render the R1 audition, run independent numerical probes, write HTML/JSON.

Run: python make_proof.py [--out OUTPUT_DIRECTORY]
Dependencies: numpy, scipy, matplotlib. Python harness, not Kotlin.
"""
from __future__ import annotations
import argparse
import csv
import hashlib
import html
import json
import platform
from pathlib import Path
import sys
import time

import numpy as np
from scipy import signal
from scipy.io import wavfile
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import halobar as h

VOICES = ("vibraphone", "marimba", "xylophone")
NOTES = ((53, "F3"), (65, "F4"), (77, "F5"))


def rms(x):
    return float(np.sqrt(np.mean(x*x)))


def db(x):
    return float(20*np.log10(max(float(x), 1e-15)))


def pcm(x):
    # Refuse clipping instead of silently hiding it in file conversion.
    assert np.isfinite(x).all() and np.max(np.abs(x)) < 1., "PCM would clip"
    return np.round(x*32767.).astype("<i2")


def write_wav(path, x):
    path.parent.mkdir(parents=True, exist_ok=True)
    wavfile.write(path, h.RATE, pcm(x))


def pitch(x):
    # Blind strongest spectral peak over a broad musical range, not a search
    # restricted to the expected note. Cent interpolation uses a long zero pad.
    y = x[int(.035*h.RATE):int(.36*h.RATE)]
    nfft = 131072
    power = np.abs(np.fft.rfft(y*np.hanning(len(y)), nfft))**2
    freqs = np.fft.rfftfreq(nfft, 1/h.RATE)
    ids = np.flatnonzero((freqs >= 55.) & (freqs <= 2000.))
    k = int(ids[np.argmax(power[ids])])
    z = np.log(np.maximum(power[k-1:k+2], 1e-30))
    d = .5*(z[0]-z[2])/(z[0]-2*z[1]+z[2])
    hz = (k+d)*h.RATE/nfft
    lag = int(round(h.RATE/hz))
    corr = float(np.dot(y[lag:], y[:-lag]) / np.sqrt(np.dot(y[lag:], y[lag:])*np.dot(y[:-lag], y[:-lag])))
    return float(hz), corr


def centroid(x, start=.0, seconds=.08):
    y = x[int(start*h.RATE):int((start+seconds)*h.RATE)]
    s = np.abs(np.fft.rfft(y*np.hanning(len(y))))**2
    f = np.fft.rfftfreq(len(y), 1/h.RATE)
    return float(np.sum(f*s)/max(np.sum(s), 1e-30))


def t20(x):
    # Schroeder energy integration; include no knowledge of designed decays.
    e = np.cumsum((x*x)[::-1])[::-1]
    edb = 10*np.log10(np.maximum(e/max(e[0], 1e-30), 1e-30))
    mask = (edb <= -5.) & (edb >= -25.)
    t = np.arange(len(x))/h.RATE
    if np.count_nonzero(mask) < 10:
        return None
    slope, intercept = np.polyfit(t[mask], edb[mask], 1)
    return float(-60./slope)


def metrics(x, expected):
    hz, confidence = pitch(x)
    n = len(x)
    cumulative = np.cumsum(x*x)
    t95 = float(np.searchsorted(cumulative, .95*cumulative[-1])/h.RATE)
    return dict(frames=n, seconds=n/h.RATE, peak=float(np.max(np.abs(x))),
                mean=float(np.mean(x)), active_rms=h.active_rms(x),
                root_hz=hz, root_error_cents=float(1200*np.log2(hz/expected)),
                period_correlation=confidence, attack_centroid_hz=centroid(x),
                t60_from_t20_seconds=t20(x), energy_t95_seconds=t95,
                late_to_attack_energy_db=float(10*np.log10(max(np.sum(x[2*h.RATE:]**2),1e-30)/max(np.sum(x[:int(.4*h.RATE)]**2),1e-30))),
                last_100ms_rms_dbfs=db(rms(x[-4410:])), finite=bool(np.isfinite(x).all()))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", type=Path, default=Path(__file__).parent/"audition")
    args = parser.parse_args()
    out = args.out
    out.mkdir(parents=True, exist_ok=True)
    checks, cases, measurements = [], [], []
    def check(name, passed, **details):
        record = dict(name=name, passed=bool(passed), **details)
        checks.append(record)
        print(json.dumps(record), flush=True)
    def save_case(slug, title, r, section, voice, midi, macros=None):
        item = dict(id=slug, title=title, section=section, voice=voice, midi=midi,
                    macros=h.settled(macros), info=r.info)
        for kind, data in (("dry",r.matched_dry),("dub",r.matched_dub)):
            file = f"matched/{slug}-{kind}.wav"
            write_wav(out/file, data)
            item[kind] = file
            measurement = metrics(data, r.info["hz"])
            measurement.update(id=slug, kind=kind)
            measurements.append(measurement)
        cases.append(item)

    # Three registers per voice, with raw and matched dry/dub files.
    primaries = {}
    timings = []
    for voice in VOICES:
        for midi, name in NOTES:
            start = time.perf_counter()
            r = h.render(voice, midi)
            elapsed = 1000*(time.perf_counter()-start)
            timings.append(dict(voice=voice, note=name, ms=elapsed))
            slug = f"{voice}-{name}"
            primaries[slug] = r
            save_case(slug, f"{voice.title()} / {name}", r, "registers", voice, midi)
            write_wav(out/f"raw/{slug}-dry.wav", r.raw_dry)
            write_wav(out/f"raw/{slug}-dub.wav", r.raw_dub)
            repeat = h.render(voice, midi)
            check(f"determinism/{slug}", np.array_equal(pcm(r.matched_dry),pcm(repeat.matched_dry)) and np.array_equal(pcm(r.matched_dub),pcm(repeat.matched_dub)))
            mm = [x for x in measurements if x["id"] == slug]
            check(f"output/{slug}", all(x["finite"] and .01<x["peak"]<=.950001 and abs(x["mean"])<.005 and x["frames"]==7*h.RATE for x in mm), measurements=mm)
            check(f"pitch/{slug}", all(abs(x["root_error_cents"])<5 and x["period_correlation"]>.3 for x in mm), worst_cents=max(abs(x["root_error_cents"]) for x in mm))

    # Voice identity: measurable differences are evidence, not a listening verdict.
    identity = []
    for midi, name in NOTES:
        a = primaries[f"vibraphone-{name}"]
        b = primaries[f"marimba-{name}"]
        c = primaries[f"xylophone-{name}"]
        item = {"note": name}
        for kind in ("dry","dub"):
            va = metrics(getattr(a,f"matched_{kind}"), a.info["hz"])
            vb = metrics(getattr(b,f"matched_{kind}"), b.info["hz"])
            vc = metrics(getattr(c,f"matched_{kind}"), c.info["hz"])
            item[kind] = dict(vibraphone_t20_t60=va["t60_from_t20_seconds"], marimba_t20_t60=vb["t60_from_t20_seconds"],
                              xylophone_t20_t60=vc["t60_from_t20_seconds"],
                              vibraphone_energy_t95=va["energy_t95_seconds"], marimba_energy_t95=vb["energy_t95_seconds"],
                              xylophone_energy_t95=vc["energy_t95_seconds"])
        identity.append(item)
        check(f"decay-separation/{name}", item["dry"]["vibraphone_t20_t60"] > 1.5*item["dry"]["marimba_t20_t60"] and item["dub"]["vibraphone_energy_t95"] > 1.15*item["dub"]["marimba_energy_t95"], **item)
        mc=metrics(c.matched_dry,c.info["hz"])
        mb=metrics(b.matched_dry,b.info["hz"])
        tail=slice(round(.65*h.RATE),round(2*h.RATE))
        echo_boost=db(rms(c.raw_dub[tail])/max(rms(c.raw_dry[tail]),1e-15))
        check(f"xylophone-articulation/{name}",
              mc["t60_from_t20_seconds"] < .6*mb["t60_from_t20_seconds"]
              and mc["attack_centroid_hz"] > 1.05*mb["attack_centroid_hz"]
              and c.info["contact_ms"] < .7*b.info["contact_ms"],
              dry_t60_seconds=mc["t60_from_t20_seconds"],
              attack_centroid_hz=mc["attack_centroid_hz"],
              marimba_centroid_hz=mb["attack_centroid_hz"],
              contact_ms=c.info["contact_ms"])
        check(f"xylophone-echo-sustain/{name}",echo_boost>20.,
              processed_to_dry_tail_db=echo_boost,window_seconds=[.65,2.])

    # Independent impulse observation of the audible delay-head clock.
    rate = h.RATE*h.OVERSAMPLE
    impulse = np.zeros(rate*8)
    impulse[0] = .001
    timing_probes = []
    for value in (0.,.2,.45,.6,.8,1.):
        m = h.settled({"RATE":value})
        echo,q,info = h.echo_network(impulse,m,rate)
        onset = np.flatnonzero(np.abs(echo)>1e-12)[0]/rate
        beat_div, label = h.division(m)
        expected = (60/h.BPM)*beat_div
        err = abs(onset-expected)
        timing_probes.append(dict(rate=value, division=label, onset_seconds=onset, expected_seconds=expected, error_frames=err*rate))
    check("echo-clock/impulse", all(x["error_frames"]<=.51 for x in timing_probes), observations=timing_probes)

    # Decay guard with a negative control. Start with a quiet sinusoidal burst
    # near the flat part of the LP; saturation cannot mask an unstable loop.
    m = h.settled({"RETURN":1.,"RATE":.45})
    spacing = round((60/h.BPM)*.75*rate)
    excitation = np.zeros(rate*10)
    k = np.arange(spacing)
    excitation[:spacing] = 1e-5*np.sin(2*np.pi*700*k/rate)*np.hanning(spacing)
    energy_traces = {}
    for label, override in (("bounded",None),("negative_control",1.08)):
        _,q,info = h.echo_network(excitation,m,rate,feedback_override=override)
        energies = [float(np.sum(q[i:i+spacing]**2)) for i in range(0,len(q)-spacing+1,spacing)]
        energy_traces[label] = energies
    drops = np.diff(energy_traces["bounded"])
    rises = np.diff(energy_traces["negative_control"])
    check("feedback-decay/guard-and-negative-control", np.all(drops<0) and np.any(rises>0),
          bounded_last_to_first_db=float(10*np.log10(energy_traces["bounded"][-1]/energy_traces["bounded"][0])),
          negative_control_last_to_first_db=float(10*np.log10(energy_traces["negative_control"][-1]/energy_traces["negative_control"][0])))

    # Macro sweeps for every voice at F4. Compare unlevelled outputs, and
    # publish matched audio for listening. No macro can pass by changing its seed.
    sweep_records = []
    for voice in VOICES:
        for macro in h.DEFAULTS:
            renders = []
            for val in (0.,.5,1.):
                macros = {macro:val}
                r = h.render(voice,65,macros)
                save_case(f"{voice}-{macro.lower()}-{val:g}",f"{voice.title()} / {macro} {val:g}",r,"sweeps",voice,65,macros)
                renders.append(r)
            # Independent waveform difference, relative to default signal energy.
            changes = [rms(renders[i+1].raw_dub-renders[i].raw_dub)/max(rms(renders[i].raw_dub),1e-12) for i in range(2)]
            sweep_records.append(dict(voice=voice,macro=macro,relative_differences=changes))
            check(f"macro-moves/{voice}/{macro}", min(changes)>.005, relative_differences=changes)

    # Pair corners at F4, and a mixed-difference probe that vanishes for purely
    # additive independent controls. These pairs have a designed interaction.
    pair_records = []
    for a,b in (("TOUCH","SPACE"),("RETURN","SPACE"),("RATE","RETURN"),("MOTION","RETURN"),("HOLD","RETURN")):
        corner = {}
        for av,bv in ((0.,0.),(0.,1.),(1.,0.),(1.,1.)):
            macros = {a:av,b:bv}
            r = h.render("vibraphone",65,macros)
            slug = f"pair-{a.lower()}{int(av)}-{b.lower()}{int(bv)}"
            save_case(slug,f"{a} {av:g} × {b} {bv:g}",r,"pairs","vibraphone",65,macros)
            corner[(av,bv)] = r.raw_dub
        interaction = corner[(1.,1.)]-corner[(1.,0.)]-corner[(0.,1.)]+corner[(0.,0.)]
        ratio = rms(interaction)/max(rms(corner[(.0,.0)]),1e-12)
        pair_records.append(dict(pair=[a,b],mixed_difference_ratio=ratio))
        check(f"pair-interaction/{a}/{b}",ratio>.001,mixed_difference_ratio=ratio)

    # Nonfinite and unknown control input contract.
    base = primaries["marimba-F4"]
    bad = h.render("marimba",65,{"TOUCH":float("nan"),"RETURN":float("inf"),"UNKNOWN":1.})
    check("macro-validation/nonfinite-and-unknown",np.array_equal(pcm(base.matched_dub),pcm(bad.matched_dub)))

    # A higher-rate reference is a residual probe, not a mathematical alias proof.
    reference_records = []
    for voice in VOICES:
        macros={"TOUCH":1.,"RETURN":1.,"SPACE":1.,"MOTION":1.}
        four=h.render(voice,77,macros,seconds=3.,oversample=4).raw_dub
        eight=h.render(voice,77,macros,seconds=3.,oversample=8).raw_dub
        # Align causal-filter group delay using integer cross-correlation and
        # report the residual without fitting gain to make it artificially small.
        correlation=signal.correlate(four[:22050],eight[:22050],mode="full",method="fft")
        lag=int(np.argmax(correlation)-(min(22050,len(eight))-1))
        if lag>=0:
            x,y=four[lag:],eight[:len(four)-lag]
        else:
            x,y=four[:len(four)+lag],eight[-lag:]
        residual=x-y
        error_db=db(rms(residual)/max(rms(y),1e-15))
        # Spectral energy above 12 kHz in the difference, relative to reference.
        taper=np.hanning(len(y))
        f=np.fft.rfftfreq(len(y),1/h.RATE)
        ep=np.abs(np.fft.rfft(residual*taper))**2
        yp=np.abs(np.fft.rfft(y*taper))**2
        high_error=10*np.log10(max(np.sum(ep[f>=12000]),1e-30)/max(np.sum(yp),1e-30))
        reference_records.append(dict(voice=voice,alignment_lag_frames=lag,residual_db=error_db,high_band_residual_db=float(high_error)))
        check(f"higher-rate-reference/{voice}",error_db<-25. and high_error<-45.,**reference_records[-1])

    # Performance: cached coefficient design only, no audio-result cache. Whole
    # render includes raw+matched dry and dub. No claim about Kotlin or a phone.
    warm=[]
    for _ in range(5):
        start=time.perf_counter(); h.render("vibraphone",65)
        warm.append(1000*(time.perf_counter()-start))
    performance=dict(primary_renders=timings,warm_render_ms=warm,
                     warm_median_ms=float(np.median(warm)),target_ms=50.,
                     budget_met=bool(np.median(warm)<=50.),
                     scope="Python/SciPy in this Linux container; not Kotlin, not phone performance")

    # Musical context: one identical sparse two-bar phrase at 72 BPM for each
    # voice, first dry then dub. These are demo settings, not authored presets.
    beat=60/h.BPM
    seconds=12.
    phrase_macros={"SPACE":.68,"RETURN":.62,"MOTION":.45}
    phrase_segments=[]
    phrase_sections=[]
    for voice in VOICES:
        n=round(seconds*rate)
        source=np.zeros(n); send=np.zeros(n)
        for when,midi,velocity in ((0.,60,.80),(.75,67,.66),(1.5,63,.72),(2.75,70,.58),(3.5,67,.73),(5.25,60,.70)):
            dry,s,_=h.bar(voice,midi,phrase_macros,seconds=seconds,velocity=velocity)
            offset=round(when*beat*rate)
            source[offset:]+=dry[:n-offset]
            send[offset:]+=s[:n-offset]
        processed,_,_=h.dub_stage(source,send,phrase_macros,rate)
        dry,_=h.finish(source); wet,_=h.finish(processed)
        # One common gain for dry/dub preserves effect contribution in this demo.
        common_gain=min(.88/max(np.max(np.abs(dry)),np.max(np.abs(wet))),.1834/max(h.active_rms(dry),h.active_rms(wet)))
        for kind,data in (("dry",dry),("dub",wet)):
            data=data*common_gain
            filename=f"phrases/{voice}-{kind}.wav"
            write_wav(out/filename,data)
            start=sum(len(x) for x in phrase_segments)/h.RATE
            phrase_sections.append(dict(voice=voice,kind=kind,start_seconds=start,seconds=seconds,file=filename))
            phrase_segments.extend((data,np.zeros(round(.5*h.RATE))))
    demo=np.concatenate(phrase_segments)
    write_wav(out/"START-HERE.wav",demo)
    # Focused entry point for the new voice: dry, 0.5-second gap, then dub.
    xylophone_demo=np.concatenate(phrase_segments[-4:-1])
    write_wav(out/"XYLOPHONE-FIRST.wav",xylophone_demo)

    # Plot an envelope and spectrogram for sharing, with no subjective labels.
    fig,axes=plt.subplots(len(VOICES),2,figsize=(12,3.5*len(VOICES)),layout="constrained")
    for row,voice in enumerate(VOICES):
        r=primaries[f"{voice}-F4"]
        for label,x in (("dry",r.raw_dry),("dub",r.raw_dub)):
            step=441
            envelope=np.sqrt(np.mean(x[:len(x)//step*step].reshape(-1,step)**2,axis=1))
            axes[row,0].plot(np.arange(len(envelope))*.01,20*np.log10(np.maximum(envelope,1e-8)),label=label)
        axes[row,0].set(title=f"{voice.title()} F4 / raw level",xlabel="Seconds",ylabel="10 ms RMS / dBFS",ylim=(-85,0))
        axes[row,0].legend()
        f,t,s=signal.spectrogram(r.raw_dub,h.RATE,nperseg=2048,noverlap=1536)
        axes[row,1].pcolormesh(t,f,10*np.log10(np.maximum(s,1e-15)),vmin=-110,vmax=-30,shading="auto",cmap="magma")
        axes[row,1].set(title=f"{voice.title()} F4 / dub spectrum",xlabel="Seconds",ylabel="Hz",ylim=(0,5000))
    fig.savefig(out/"measurements.png",dpi=160)
    plt.close(fig)

    hashes={}
    for path in sorted(out.rglob("*.wav")):
        hashes[str(path.relative_to(out))]=hashlib.sha256(path.read_bytes()).hexdigest()
    report=dict(model_version=h.MODEL_VERSION,status="R1 Python proof; not Kotlin; R1 awaiting owner audition",
                owner_feedback=[dict(revision="R0",quote="That sounds great",scope="General positive response; no voice-specific listening findings recorded")],
                environment=dict(python=sys.version,platform=platform.platform(),numpy=np.__version__),
                source_files_sha256={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in (Path(__file__),Path(h.__file__))},
                sources=[dict(url="https://documentacion.sea-acustica.es/storage/publicaciones/AMS-0_010.pdf",supports="Typical first three bar-mode ratios 1:4:10; not our decay, gain or contact constants"),
                         dict(url="https://www.yamaha.com/en/musical_instrument_guide/marimba/trivia/trivia004.html",supports="Rotating fans modulate resonator opening; vibraphone damper and longer ringing"),
                         dict(url="https://www.sciencedirect.com/science/article/abs/pii/S0003682X2400001X",supports="Selected 1:3:6 xylophone tuning family; publisher-indexed search excerpt, full page returned HTTP 403"),
                         dict(url="https://www.yamaha.com/en/musical_instrument_guide/marimba/structure/structure003.html",supports="Quint versus octave tuning distinction; xylophone tuning differs among manufacturers")],
                defaults=h.DEFAULTS,reference_bpm=h.BPM,checks=checks,voice_separation=identity,
                macro_sweeps=sweep_records,pairs=pair_records,higher_rate_reference=reference_records,
                performance=performance,feedback_energy_traces=energy_traces,phrase_sections=phrase_sections,
                cases=cases,measurements=measurements,wav_sha256=hashes,
                limitations=["No SnipSnap checkout or API verification; no Kotlin changes or integration.",
                             "Bundled skill Python template/helpers could not be read; this standalone harness substitutes for them.",
                             "Hann contact, gains, modal decay laws and processing constants are proposed shapes, not calibrated measurements of real instruments.",
                             "No physical tube coupling, mallet collision, pedal mechanism, tape transport or stereo field is simulated.",
                             "Output kernels, RMS level matching and deterministic PCG64 noise differ from the house implementation.",
                             "HOLD top is reserved; these are finite seven-second probes, not seamless loops. Extreme HOLD/RETURN settings may have audible truncation.",
                             "Time-varying phase mixing and halo sit outside the feedback loop. Higher-rate residual is not an exhaustive aliasing proof.",
                             "Numerical distinction is not perceptual validation. Only the owner can judge the audition."])
    (out/"report.json").write_text(json.dumps(report,indent=2)+"\n")
    with (out/"metrics.csv").open("w",newline="") as f:
        writer=csv.DictWriter(f,fieldnames=list(measurements[0]))
        writer.writeheader(); writer.writerows(measurements)
    (out/"manifest.json").write_text(json.dumps(dict(cases=cases,phrase_sections=phrase_sections,entry_points=["XYLOPHONE-FIRST.wav","START-HERE.wav"]),indent=2)+"\n")
    make_html(out,report)
    failed=[x["name"] for x in checks if not x["passed"]]
    print(json.dumps(dict(summary=dict(checks=len(checks),failed=failed,wav_files=len(hashes),warm_median_ms=performance["warm_median_ms"])),indent=2),flush=True)
    return 1 if failed else 0


def make_html(out,report):
    def esc(s): return html.escape(str(s))
    passed=sum(x["passed"] for x in report["checks"])
    checks="".join(f'<tr><td>{esc(x["name"])}</td><td>{"PASS" if x["passed"] else "FAIL"}</td></tr>' for x in report["checks"])
    cards=[]
    for section,title in (("registers","1. Voices across registers"),("sweeps","2. Macro sweeps"),("pairs","3. Macro pairs")):
        items=[]
        for c in report["cases"]:
            if c["section"]!=section: continue
            items.append(f'''<article><h3>{esc(c["title"])}</h3><div class="pair"><label>Dry<audio controls preload="none" src="{esc(c["dry"])}"></audio></label><label>Dub<audio controls preload="none" src="{esc(c["dub"])}"></audio></label></div></article>''')
        cards.append(f'<details {"open" if section=="registers" else ""}><summary>{title}</summary>{"".join(items)}</details>')
    limitations="".join(f'<li>{esc(x)}</li>' for x in report["limitations"])
    phrase="".join(f'<tr><td>{esc(x["voice"].title())}</td><td>{esc(x["kind"])}</td><td>{x["start_seconds"]:g}s</td><td><a href="{esc(x["file"])}">WAV</a></td></tr>' for x in report["phrase_sections"])
    p=report["performance"]
    references="".join(f'<li><a href="{esc(x["url"])}">{esc(x["supports"])}</a></li>' for x in report["sources"])
    identity="".join(f'<tr><td>{esc(x["note"])}</td><td>{x["dry"]["vibraphone_t20_t60"]:.2f}s</td><td>{x["dry"]["marimba_t20_t60"]:.2f}s</td><td>{x["dry"]["xylophone_t20_t60"]:.2f}s</td><td>{x["dub"]["vibraphone_energy_t95"]:.2f}s</td><td>{x["dub"]["marimba_energy_t95"]:.2f}s</td><td>{x["dub"]["xylophone_energy_t95"]:.2f}s</td></tr>' for x in report["voice_separation"])
    markup=f'''<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>HALOBAR / xylophone revision</title>
<style>:root{{color-scheme:dark;font-family:system-ui,sans-serif;background:#10151b;color:#e8e6df}}body{{max-width:1050px;margin:auto;padding:36px 22px 80px}}h1{{font-size:3rem;letter-spacing:.12em;margin-bottom:0}}h2{{margin-top:2.2em}}p,li{{line-height:1.6;max-width:82ch}}.muted{{color:#abb7be}}a{{color:#a7daca}}article{{border-top:1px solid #37414c;padding:12px 0}}.pair{{display:flex;gap:20px;flex-wrap:wrap}}label{{display:grid;gap:7px;flex:1;min-width:280px}}audio{{width:100%;max-width:480px}}details{{margin:24px 0;padding:15px;border:1px solid #37414c;border-radius:10px}}summary{{font-size:1.2rem;cursor:pointer}}table{{border-collapse:collapse;width:100%;font-size:.9rem}}td,th{{text-align:left;padding:9px;border-bottom:1px solid #37414c}}img{{max-width:100%;height:auto}}code{{color:#c0d8d3}}.status{{display:inline-block;border:1px solid #8ca7a0;padding:7px 11px;border-radius:5px}}button{{font:inherit;padding:8px 12px;background:#283a3a;color:inherit;border:1px solid #729388;border-radius:6px;cursor:pointer}}.choice{{margin:12px 0}}</style>
<h1>HALOBAR</h1><p class="muted">Mallet strike → rounded note → rhythmic echoes → moving halo</p><p class="status">R1 Python proof · 72 BPM · mono 44.1 kHz · 4× internal · new voice awaits audition</p>
<p>R1 adds xylophone: a shorter, harder contact and a 1:3:6 bar-mode family. Vibraphone and marimba retain their 1:4:10 modes and previously rendered sound. All three use the same dub stage. Decays, gains and contact laws remain proposed shapes. F3 and the phrase bass notes deliberately extend the xylophone below its usual acoustic register.</p>
<h2>Start with xylophone</h2><p>The new voice plays dry, then through the shared dub stage. The second version starts at 12.5 seconds.</p><audio controls preload="metadata" src="XYLOPHONE-FIRST.wav"></audio><p><a href="XYLOPHONE-FIRST.wav" download>Download the xylophone comparison</a></p><h2>The full family</h2><p>The same sparse phrase plays six times: vibraphone dry, vibraphone dub, marimba dry, marimba dub, xylophone dry, xylophone dub. A common gain within each voice preserves the added return. There are no drums or backing layers.</p><audio controls preload="metadata" src="START-HERE.wav"></audio><p><a href="START-HERE.wav" download>Download the phrase comparison</a></p><table><tr><th>Voice</th><th>Version</th><th>Starts at</th><th>Separate file</th></tr>{phrase}</table>
<p>Listen for whether the woody and metallic identities survive the echoes, whether the dry onset remains playable, and whether the halo fills gaps without masking the rhythmic answers.</p>
<p>The register, sweep and pair files below are independently matched to 0.1834 RMS over the first 0.8 seconds, capped at 0.95 peak. This is an audition stand-in, not the house loudness algorithm. The <code>raw/</code> folder retains the eighteen main comparisons before level matching.</p>
<div class="choice"><button id="stop">Stop all audio</button></div>{"".join(cards)}
<h2>What the harness measured</h2><p>{passed}/{len(report["checks"])} checks passed. Five warm complete renders have a median of <strong>{p["warm_median_ms"]:.1f} ms</strong> in this container. The proposed 50 ms budget {"passed" if p["budget_met"] else "was missed"}. Kotlin and phone cost remain unmeasured.</p>
<table><tr><th>Note</th><th>Vibraphone dry T60*</th><th>Marimba dry T60*</th><th>Xylophone dry T60*</th><th>Vibraphone dub 95% energy time</th><th>Marimba dub 95% energy time</th><th>Xylophone dub 95% energy time</th></tr>{identity}</table><p class="muted">*T60 extrapolated from the Schroeder -5 to -25 dB slope. Different numbers establish measurable separation, not listening approval.</p><img src="measurements.png" alt="Raw F4 envelopes and dub spectrograms for vibraphone, marimba and xylophone">
<details><summary>Numerical checks</summary><table>{checks}</table></details><p><a href="report.json">Full JSON report</a> · <a href="metrics.csv">Metrics CSV</a> · <a href="manifest.json">Audition manifest</a></p>
<h2>Why it is built this way</h2><ul><li>The contact pulse and independent mode decays keep the material voices distinct without an expensive collision solver.</li><li>The mallet-noise onset stays dry; the bar body opens the echo send over 10 ms. This avoids repeating every contact click.</li><li>Each feedback pass is low-pass filtered and softly saturated. Gain stays below unity by construction.</li><li>Phase movement and the reflection cloud sit outside feedback so they can change the space without undermining the loop's gain bound.</li><li>The risky decision is how much diffuse halo the instrument tolerates before the voices converge into one wash. That needs your listen.</li></ul>
<h2>Limitations and next gate</h2><ul>{limitations}</ul><p>Next gate: owner listens to the phrase and the F3/F4/F5 pairs. Judge whether the new shorter attack and echo-led sustain add a useful contrast, then adjust contact and send balance before expanding further. Source-aware echo routing remains separate from source synthesis; placement in the live SnipSnap repository needs a repository check.</p>
<h2>Source ledger</h2><ul>{references}</ul><p>Verified: the cited sources and this script's execution. Measured: report.json. Proposed: all shape constants, macro laws and production landing. Owner response to R0: “That sounds great.” No detailed listening findings were recorded. R1 awaits owner audition. No live SnipSnap commit or API was read.</p>
<script>const players=[...document.querySelectorAll('audio')];players.forEach(p=>p.addEventListener('play',()=>players.forEach(q=>{{if(q!==p)q.pause()}})));document.querySelector('#stop').addEventListener('click',()=>players.forEach(p=>{{p.pause();p.currentTime=0}}));</script></html>'''
    (out/"index.html").write_text(markup)


if __name__=="__main__":
    raise SystemExit(main())
