"""v4 (approved at the low note) against v5 (fitted across the register), mapping B: key = loudest partial."""
import sys, os, runpy
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import refs  # noqa: E402
import numpy as np
import cupola_proto as cp, tubes_v4, tubes_v5
from scipy.io import wavfile
sk, R = cp.sk, cp.RATE
OUT = str(refs.OUT / 'tubes_v4_vs_v5')
os.makedirs(OUT, exist_ok=True)
MODELS = {"v4": tubes_v4, "v5": tubes_v5}

def save(name, x):
    x = sk.fade_tail(sk.level_to(sk.dc_block(x), .12), R, 80.0)
    p = np.max(np.abs(x)); sk.write_wav(f"{OUT}/{name}.wav", x * (.9 / p if p > .9 else 1))
def note(raw):
    return sk.fade_tail(sk.decimate4(sk.band_limit(raw, cp.INTERNAL)), R, 150.0)
def place(events, total_s):
    mix = np.zeros(int(total_s * R))
    for t, y, g in events:
        o = int(t * R); mix[o:o + len(y)] += g * y[: len(mix) - o]
    return mix
def tube(model, key, vel=.75, take=0):
    MODELS[model].install()
    return note(cp.render('TUBES', midi=key - 12, vel=vel, take=take, seconds=2.0)[0])     # mapping B

# 0. the owner's loop, 8 s
r, x = wavfile.read(refs.one('RARE_tubes_melodic_percs_C_F_120_loop5.wav'))
x = x.astype(float); x = x.mean(1) if x.ndim == 2 else x
ex = x[: 8 * R].copy(); ex[: int(.01 * R)] *= np.linspace(0, 1, int(.01 * R))
save("0_your_C_F_tube_loop_8s", ex)

# 1. single notes, v4 then v5, low to high, each ringing out
ev, t = [], 0.0
for key in (48, 60, 67, 72, 79):
    for m in MODELS:
        ev.append((t, tube(m, key), 1.0)); t += 1.4
    t += .6
save("1_single_notes_v4_then_v5_on_C3_C4_G4_C5_G5", place(ev, t + 1.0))

# 2-3. a melody climbing into the high register, 100 BPM
melody = [60, 62, 64, 67, 69, 72, 74, 76, 79, 76, 74, 72, 69, 67, 64, 60]
q = 60 / 100
for i_m, m in enumerate(MODELS):
    ev = [(i * q, tube(m, k, vel=.6 + .3 * ((i * 5) % 4) / 3, take=i % 4), 1.0) for i, k in enumerate(melody)]
    save(f"{2 + i_m}_melody_C4_up_to_G5_{m}", place(ev, len(melody) * q + 1.5))

# 4. the clean one-shot against both, at its own pitch (its loudest partial, 131.9 Hz)
H = vars(__import__('hits'))
ref = H['load'](refs.one('RARE_percussionTUBE_oneshot8.wav'))
ref = ref[int(np.argmax(np.abs(ref) > .02)):]; ref = sk.fade_tail(ref, R, 40.0)
KEY = 69 + 12 * np.log2(131.9 / 440)
ev, t = [], 0.0
for _ in range(2):
    for y in (ref / np.sqrt(np.mean(ref[: int(.3 * R)] ** 2)) * .1, tube("v4", KEY), tube("v5", KEY)):
        ev.append((t, y / np.sqrt(np.mean(y[: int(.3 * R)] ** 2)) * .1, 1.0)); t += 1.2
    t += .8
save("4_your_oneshot_then_v4_then_v5_x2", place(ev, t + .5))
open(f"{OUT}/README.txt", "w").write("""v4 (the tube you approved, fitted to your one-shot) against v5 (fitted to 101 hits across the
register from your loops). Mapping B: the key is the tube's loudest note. Python renders; levels matched.

0  8 s of your C/F tube loop
1  single notes ringing out, v4 then v5, on C3, C4, G4, C5, G5
2  a melody from C4 up to G5 and back, v4
3  the same melody, v5
4  your one-shot, then v4, then v5, twice (the low end, where v4 was fitted)
""")
print(sorted(os.listdir(OUT)))
