# UPRIGHT, the finger-plucked bass voice of PLUCK

UPRIGHT is one finite voice inside PLUCK. Open **SYNTH → PLUCK → UPRIGHT**.
ROUND and MUTED are the two starting presets: the same string with DAMP at
0.18 and 1.0. Both start on A1 with TOUCH 0.5, WOOD 0.55 and saved playing
strength 0.65. TUNE covers E1–E3 in semitone steps.

The characteristic gesture is **finger release → string ring → wooden
response → damped tail**. This is a reduced synthesis model: an initialized
string displacement circulates through a tuned fractional delay and loses
energy each trip. Three stable filters color a one-way readout of that same
string. There is no moving finger, fingerboard collision, slap, bow, measured
acoustic body, or energy returned from the body into the string.

| Control | Default | What changes |
| --- | ---: | --- |
| TUNE | 5/24 | Exact semitone from E1 through E3; the default is A1. |
| TOUCH | 0.50 | Smoothing of the initial displacement and its small initial texture. |
| DAMP | 0.18 | Both string lifetime and high-frequency loss; 1 is the muted endpoint. |
| WOOD | 0.55 | Balance and fixed-frequency centers of the one-way body filters. |

Playing strength is saved beside the macros in an UPRIGHT patch. It changes
the excitation spectrum and amplitude. A velocity-one reference selects the
house normalization gain; softer notes reuse that gain, so normalization does
not erase the playing-strength difference. The cache owns at most eight
reference notes and returns copies. No HOLD control or sustain loop is offered.
The 3.2-second samples are finite tonal one-shots even where the generic
length-only classifier would call a long sound a loop.

Saved patches, pad recipes and the CLI retain the excitation strength. The
UPRIGHT starter repeats an E minor pentatonic walk with ROUND then MUTED:
E1, G1, A1, B1, D2, E2, G2, A2. The two MPC generations share the rendered
audio; their playback velocity scales that baked audio. It does not rerun the
string model on the hardware.

Reproduce the listening files with `./gradlew :synth:generateUprightAudition`
and the kit with `./gradlew :synth:generateUprightKit`. The generators write
native and matched audio, complete recipes, fingerprints and read-back evidence.
The audition is at https://upright-snipsnap-first-listen.bertcalm.chatgpt.site.
The original Python study remains a separate historical comparison.

The owner approved the Python listening study with “Sounds good, what's next”
on 2026-10-24. That is the source direction for this port, not a claim that the
owner has heard the resulting Kotlin recordings or played them on a phone/MPC.
Python noise, filters and output normalization differ from SnipSnap's shared
implementations; the native audition is the check of that adaptation.

The debug APK is labeled **SnipSnap Development**. Its existing
`com.snipsnap.app.brassgrow` package identity is retained so it can update the
previous development install without moving its data. This label does not
introduce another product or a separate UPRIGHT app.

## Why it is built this way

- A single string keeps the bass note clear and each control understandable.
- The body reads that string, so the wooden response follows the played note.
- Damping changes loss inside the string, giving short notes a distinct decay.
- Velocity changes the excitation before the shared reference gain is applied.
- The new voice has its own conditioning path, preserving the five older voices.
