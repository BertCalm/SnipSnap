# KEEL: a bass that settles (rounds R1 to R3, and BELLY)

KEEL is a bass engine for the A01 pad and, later, a bass keygroup. The gesture is
attack with character, the upper spectrum distils away, the body fills in under the
note, release. One settle clock, `s(t) = 2^(-t / h)` with `h` set by SETTLE (0.05 to
1.2 s), drives every voice. The settle is computed, not simulated: an exponential
and a half-life, nothing more.

Round R1 builds two voices, REDUCE and UPRIGHT, as finite one-shots under 1.5 s
that file TONAL. Round R2 adds the held state: from HOLD .85 a pad is the settled
note as one seamless 3 s loop, filed LOOP, and `KeelPadMaker` makes a held keygroup
of nine zones, C1 to C3, each the note's attack and settle followed by that loop.
Round R3 adds factory presets, a sixteen-pad kit and a KEEL starter on the
phone's new-kit menu; the phone picker is R4. BELLY, the third voice, was put to one
listening question first, heard as its own sound (2026-10-31), and built. The design, the Python prototype and the
measurements are in
[the spec](superpowers/specs/2026-10-23-keel-bass-engine-design.md); its "As built"
section lists every place the Kotlin differs from it and why. WEIGHT was rebuilt on
2026-10-26 after the sub-octave it started with was heard as doing nothing; the Python
version of the new WEIGHT was heard ("Sounds good"). The Kotlin keygroup was first heard
on 2026-10-27: UPRIGHT's sustain level about right, WEIGHT 1 still KEEL, and UPRIGHT's
held keys pumping. The bow is now locked to the string; on 2026-10-28 that bow, with the
most hair offered, was heard at C2 and A2 as no pump, and the level as about right again.
The presets, the kit and the Kotlin BELLY have not been heard; the Kotlin BELLY measures
the same as the Python clip that was.

## Voices

| Voice | Source |
| --- | --- |
| REDUCE | Two-operator FM at ratio 1 whose index decays on the settle clock, so it distils to its carrier. |
| UPRIGHT | A plucked string (`Strings.Loop`, retuned to the slide) through a closing low-pass and three fixed body modes that ring shorter than the note. |
| BELLY | A warm sine belly plus a saw bite 9 cents sharp through a closing low-pass; the bite fades on the settle clock and the belly stays. |

## Playing and saving

`KeelPatch` saves the voice, all seven controls, the requested note (MIDI 24 to 55),
the velocity and the model version. TUNE moves the saved note by plus or minus 12
semitones inside that range. Velocity scales GRIT's drive, REDUCE's index and
UPRIGHT's pick low-pass and BELLY's bite before it scales the level (`v^0.35`). The presets are in the
shared factory roster (`Presets`); the phone picker does not show KEEL yet.

| Control | Action |
| --- | --- |
| TUNE | The note, plus or minus 12 semitones from the saved root. |
| SETTLE | The half-life of the settle clock: how fast the harmonics leave and the body fills in. |
| WEIGHT | The body. The note's own fundamental and overtones 2 to 4 are read on its phase track and raised together by up to 9 dB, and an overtone the note lacks is given a floor 3, 6 and 9 dB under the raised fundamental. The effect starts at half strength and grows to full as the settle takes the top away. The pitch does not move; 0 changes nothing. |
| GRIT | A tanh drive on the source only. WEIGHT is added after it, so the drive cannot square what it adds. |
| SLIDE | The start offset, an octave below at 0 to an octave above at 1, gliding onto the note in 30 to 280 ms. |
| LENGTH | The time to fade 60 dB, 0.2 to 1.25 s. |
| HOLD | Seconds the envelope holds at full before it decays, up to just under .85. From .85 the pad is the held loop: the note settled, at its pitch fitted to whole periods of a 3 s loop, held up by the envelope (and for UPRIGHT by a bow). SLIDE and LENGTH play no part in it. |

## Listening

`./gradlew :synth:generateKeelAudition` writes `testkit/keel-audition/` (not
committed): `index.html`, `manifest.json` and loudness-matched WAVs. The page opens
with one question and nine clips: does WEIGHT make the note heavier? Three notes
(REDUCE at F#1 and F#2, UPRIGHT at C2) at WEIGHT 0, .5 and 1. Everything else (the
voices at three notes, each macro swept, SLIDE at five settings, the corners) is under
a closed fold.

## Presets and the kit

`KeelPresets` holds six presets per voice, every note in C minor pentatonic, each naming
all seven controls. The loops (HOLD 1) sit at C2, where the bow was heard, and no preset
slides from above.

| Voice | Presets (note) |
| --- | --- |
| REDUCE | ROUND FLOOR (C2), QUICK SINK (G1), LOW HULL (C1), GROWL LINE (F1), SCOOP UP (D#2), SETTLED HUM (C2, loop) |
| UPRIGHT | WARM GUT (C2), MUTED THUMB (A#1), LONG NECK (D#1), BRIGHT PICK (G2), HOT WOOD (C2), BOWED NOTE (C2, loop) |
| BELLY | SOFT PILLOW (C2), DEEP CUSHION (C1), TOOTH MARK (G1), SLOW MELT (D#2), CLIMB IN (A#1), WARM HUSH (C2, loop) |

`SynthKits.keel()` is the kit. A01 to A08 walk C minor pentatonic up from C2 on REDUCE
at its defaults. A09 and A10 are UPRIGHT's WARM GUT and BRIGHT PICK, A11 and A12
BELLY's SOFT PILLOW and TOOTH MARK, A13 and A14 REDUCE's QUICK SINK and LOW HULL, and
A15 and A16 the loops, BOWED NOTE (UPRIGHT) and WARM HUSH (BELLY). On the phone it is the KEEL starter on the new-kit menu.
`./gradlew :synth:generateKeelKit` writes it for the MPC to `testkit/keel-kit/` (not
committed).

## The keygroup

`./gradlew :shell:generateKeelKeys` writes `testkit/keel-keys/` (not committed): four
instruments in `mpc/` for the MPC (each voice at its defaults and at WEIGHT 1), previews
played through SnipSnap's own sample player in `previews/`, and a README with three
questions. A zone is levelled on its first 0.6 s, so short presses match. UPRIGHT's
bowed sustain is lifted to sit 7.5 dB under its pluck where it would sit lower (19 dB at
C1 as rendered), by a gain that only rises, so it touches neither the attack nor the
seam. The 7.5 dB is the mean level of the sustain the owner first heard as about right,
measured on the loop's loudest window; the hair leaves the loop's mean 8 to 9 dB under,
which the owner heard as about right again.

UPRIGHT's held bow is locked to the string: a drive at the note's own harmonics, in the
pluck's phase, with a trace of noise (the hair) over it. The first bow was noise alone,
and the owner heard it pump: tiled one loop long, its lines beat inside the string's
resonance, 2.6 dB at C1 and 12.5 dB at A2, every 3 s. The owner chose the most hair
offered (.15, over none and .06): the loop moves 1.0 to 3.5 dB, the most at A2, and the
press settles into it within 2.2 dB, heard at C2 and A2 as no pump. An instrument is 51 s of audio for REDUCE and 75 s for
UPRIGHT at the defaults, and 137 s at SETTLE 1, where the warm-up is ten half-lives
of 1.2 s.

## Limits worth knowing

- A render that reaches the 1.44 s cap at high HOLD and LENGTH ends mid-decay (30 dB
  under the peak for REDUCE) and closes with the 4 ms fade. LOW HULL (A14 in the kit) is
  one: its last 50 ms sit 36 dB under its peak.
- On the phone, KEEL pads play from the KEEL starter, but SYNTH cannot reopen them for
  editing until KEEL is on its engine picker (R4).
- UPRIGHT's softer touch is brighter, not darker, and its SLIDE 1 at MIDI 42 reads an
  octave high in the late window. Both are in the spec's "As built" findings.
- REDUCE at GRIT 1 on the highest notes is over the spec's alias bar before the band limit.
- LOW HULL is a long held C1 because shorter C1 notes read as a tom to SnipSnap's own
  classifier: its envelope window (23 ms) is shorter than a C1 cycle (31 ms). Kits file
  KEEL pads as TONAL or LOOP whatever the classifier says. Short BELLY notes under G1
  read as a kick for the same reason; DEEP CUSHION is long like LOW HULL.
- BELLY's held loop needs a whole number of bite beats in 3 s, so its detune is fitted
  per note: 8.8 cents at C2, up to 17.6 at C1, where low held keys beat about twice as
  fast as the one-shot. Measured, not heard.
- UPRIGHT's bow was heard at C2 and A2 only; the other seven zones, and every zone at
  WEIGHT 1, are measured, not heard.
- At WEIGHT 1 the A2 zone's sustain sits 2.2 dB under its attack and C3's 6.1, against 8 to 9 elsewhere:
  WEIGHT's body fills in where A2's harmonics meet the 190 Hz body mode, above the lift's
  line.
