# Tonewheel

TONEWHEEL is the additive engine, made playable as drawbars. The characteristic
gesture is the organ stab you would sample: a key click, a percussion ping that
dies while eight sine bars hold, and a rotary speaker turning under it, gated for
about half a second. Every voice runs one path:

```
8 sine bars (BAR1..BAR8, level^1.6) ──> gate (0.55 s + 0.1 s release)
  ──> scanner chorus (always on)
  + PERC ping on the 3rd harmonic + key click   (both dry, after the scanner)
  ──> DIRT drive ──> cabinet low-pass (3.8 kHz)
  ──> rotary speaker: horn and bass rotor split at 800 Hz (WARBLE)
  ──> 4x decimate ──> Snip
```

The percussion and the key click join after the scanner on purpose: a real
organ taps them ahead of its vibrato line, and running them through the scanner
put a comb null on the percussion (`Tonewheel.synthesize`'s own comment). The
cabinet sits after DIRT because overdrive with nothing above it is buzz. The
roadmap row is S3.5.

## Voices

| Voice | Registration (BAR1..BAR8) | Starts with | Bottom of TUNE |
|---|---|---|---|
| FULL | 0.9 1 0.8 0.75 0.6 0.55 0.4 0.5: every bar out | DIRT 0.3 | A2 (110 Hz) |
| SOUL | 0.7 1 0.4 0.3 0.15 0 0 0: sub, fundamental, a little fifth | DIRT 0.1 | A2 (110 Hz) |
| STAB | 0.3 0.9 0.2 0.85 0.55 0.8 0.3 0.7: top-heavy | PERC 0.6 | A2 (110 Hz) |

A voice is a registration: the eight bar defaults (`Tonewheel.registration`),
with PERC and DIRT defaults per voice. All three have the same twelve knobs.

## Knobs

- **TUNE** snaps to whole semitones over two octaves above A2.
- **BAR1..BAR8** are the drawbars, at 0.5, 1, 1.5, 2, 3, 4, 6 and 8 times the
  note (16', 8', 5 1/3', 4', 2 2/3', 2', 1 1/3' and 1'; the 1 3/5' bar is left
  out). Each level is raised to the 1.6 power, a drawbar taper.
- **PERC** is the percussion register: a sine on the third harmonic with a 0.2 s
  decay, scaled by `PERC_GAIN` (0.88, set by ear at full PERC per its KDoc).
- **WARBLE** is the rotary speaker's speed, 0.8 Hz to 6.9 Hz. Below 0.15 the
  rotor is braked, fading its depth to nothing at 0. The scanner chorus runs at
  every setting.
- **DIRT** pushes the signal into `Dsp.drive` on a cubic taper (gain 1 to 2.4),
  so the low end of the knob stays near clean.

## Kit, audition, presets

- **Presets:** twelve per voice in `TonewheelPresets.kt`, thirty-six in all, each
  a full twelve-macro registration. Authored from the DSP, not by ear, per that
  file's KDoc.
- **Starter kit:** `SynthKits.tonewheel()`, a sixteen-pad starter kit: A01-A08
  walk the first voice up the minor pentatonic, A09-A16 are factory presets.
  Render it with `./gradlew :synth:generateTonewheelKit`. TONEWHEEL also fills
  A13-A16 of `SynthKits.melodic()` (`./gradlew :synth:generateMelodicKit`), and
  the Organ keys instrument in `Keys` renders it with motion off so its sustain
  loop closes.
- **Audition page:** `./gradlew :synth:generateTonewheelRosterAudition` runs
  `TonewheelRosterAuditionGenerator`, the shared `RosterAudition` page. It writes
  `testkit/tonewheel-audition/` with each voice's default, every knob at both
  ends (each drawbar alone among them), TUNE at three notes, and every preset, at
  one loudness. It has no kit section until `SynthKits.tonewheel()` is wired into
  the spec.

## Ledger

- **Verified:** `TonewheelTest` and `TonewheelPresetsTest` cover the engine and
  the roster. `FirstGenAuditionTest` checks the audition spec has a note for every
  voice and every knob but TUNE.
- **Not heard:** no TONEWHEEL preset, knob end or audition clip has had the
  owner's listen on record. `PERC_GAIN` is the one constant whose KDoc says it
  was set by ear.
- **Open:** the per-voice loudness offsets are zero awaiting that listen. Twelve
  knobs is past the house's three to six; whether eight single drawbars earn
  their place is a question the audition page asks. The kit and its task are
  written separately and are not checked here.
