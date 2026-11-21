"""BOROUGH round 3.1: the app's native glue (app/src/main/cpp/GlueBus.h, through the host tool glue_render) against
glue_recipe.py's model() of it, per kit, on the same raw demo groove. Python harness, not Kotlin.

Each kit's demo groove is built exactly as glue_recipe.py builds it (build.groove raw, the app's exported pads at their
own level, round 3.1's stacks), written as a 32-bit float WAV, rendered by glue_render with the kit's recipe glue
(recipes.json kits[].glue: trim, saturation or none, limiter drive; -10 dBFS, 4:1, 0 dB makeup, -1 dBFS) and compared
with model() on the same samples. Both carry the limiter's 3 ms lookahead, so they are compared sample for sample with
no alignment. The residual is rms(native - model) / rms(model), in dB.

The trim here is the recipe's, not the app's: this checks the bus's arithmetic on the exported pads at their own level,
which is what the recipe is set for. The app adds its +3.0 dB live calibration (a default pad at full velocity reaches
the mix at 0.708 of its sample; GlueSettings.LIVE_TRIM_CALIBRATION_DB) in front of the same bus.

    python3 glue_native.py <app export dir> <glue_render binary> <scratch dir>
"""
from __future__ import annotations
import json, math, subprocess, sys
from pathlib import Path
import numpy as np
from scipy.io import wavfile

import build as B
import listen_app as L
import glue_recipe as G


def main(app_dir, tool, scratch):
    app_dir, scratch = Path(app_dir), Path(scratch)
    scratch.mkdir(parents=True, exist_ok=True)
    recipes = {k['id']: k for k in json.load(open(L.RECIPES))['kits']}
    stacks = {s['id']: s for s in B.STACKS}
    rows = []
    for kit in B.K.KITS:
        r = recipes[kit['id']]
        glue = r['glue']
        st = stacks[r['default_stack']]
        bank = [L.app_pad(app_dir / kit['id'], s) for s in range(1, 13)]
        sbank = [L.app_pad(app_dir / kit['id'], s) for s in range(13, 17)]
        raw = B.groove(kit, bank, st, sbank, raw=True).astype(np.float32)
        src, out = scratch / f"{kit['id']}-raw.wav", scratch / f"{kit['id']}-native.wav"
        wavfile.write(src, B.SR, raw)
        sat = glue['saturation_db']
        cmd = [str(tool), str(src), str(out), repr(glue['input_trim_db']), 'none' if sat is None else repr(sat),
               repr(glue['limiter_drive_db']), repr(glue['threshold_dbfs']), repr(glue['ratio']), repr(glue['makeup_db']),
               repr(glue['ceiling_dbfs'])]
        run = subprocess.run(cmd, capture_output=True, text=True, check=True)
        rate, native = wavfile.read(out)
        assert rate == B.SR and native.dtype == np.float32, (rate, native.dtype)
        latency = int(run.stdout.split('latency ')[1].split(' ')[0])
        assert len(native) == len(raw) + latency, (len(native), len(raw), latency)
        args = (glue['input_trim_db'], sat, glue['threshold_dbfs'], glue['ratio'], glue['makeup_db'],
                glue['limiter_drive_db'], glue['ceiling_dbfs'])
        x = raw.astype(np.float64)
        as_is = G.model(x, *args)
        # model()'s sliding minimum is scipy's minimum_filter1d, whose default mode='reflect' mirrors the groove's
        # first hit (frame 0) into the time before the file, so its limiter ducks ahead of a signal that has not
        # started. The native bus starts from silence (a clear detector, an empty delay line). Give the model that
        # same silence: a one-second lead-in of zeros, dropped afterwards.
        lead = B.SR
        model = G.model(np.concatenate([np.zeros(lead), x]), *args)[lead:]
        nv = native[:len(model)].astype(np.float64)
        clamped = int(run.stdout.rsplit('clamped ', 1)[1])

        def resid(m):
            e = nv - m
            return 20 * math.log10(np.sqrt(np.mean(e ** 2)) / np.sqrt(np.mean(m ** 2))), float(np.max(np.abs(e)))
        r_is, w_is = resid(as_is)
        r, w = resid(model)
        rows.append((kit['id'], r, w, r_is, w_is, float(np.max(np.abs(nv))), float(np.max(np.abs(model))), latency, clamped))
        print(f"{kit['id']:16s} trim {glue['input_trim_db']:+.2f} sat {'none' if sat is None else sat:>4} drive "
              f"{glue['limiter_drive_db']:.2f} | native vs model: residual {r:.1f} dB (worst sample {w:.1e}); "
              f"model() as is, reflect edge: {r_is:.1f} dB (worst {w_is:.1e}) | peak {rows[-1][5]:.4f} vs {rows[-1][6]:.4f}, "
              f"latency {latency} frames, clamped {clamped}", flush=True)
    return rows


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2], sys.argv[3])
