# Stoop

Stoop is an original, preset-first groove instrument. BASS, TINE, CLAV,
ORGAN, LEAD and CHORD use family-specific synthesis, a continuous alternate
articulation, an independently articulated answering voice, and bounded
recording character. The supplied [engineering specification](superpowers/specs/2026-10-10-stoop-engine-engineering-spec.md)
is preserved verbatim. The initial implementation and preset roster are
provisional until the owner's listening verdict.

## Patch and render contract

Every patch saves its six macros, explicit MIDI root (24–96), velocity
(0–1), source configuration, shadow interval/delay/articulation/reply count,
chord intervals and model version. The same recipe is used by the phone,
CLI, starter kit and offline audition generator. `Patches.edited`, saved
presets and `PadRecipe` retain these fields.

TOUCH changes attack contact and early spectrum continuously. COLOR moves
the family from dark/full to bright/nasal. SHADOW controls a quieter answer
with its own source and envelope; zero omits that synthesis. WEAR controls
bounded coloration and bypasses cleanly at zero. LENGTH changes the finite
gesture and damping rather than simply resizing the output buffer. HOLD at
0.99 or above selects a settled sustain loop.

Each render starts fresh. Audio is deterministic, mono, and 44.1 kHz through
the shared 4× path and melodic loudness processing. Source partials are
band-limited before decimation. Stoop uses generated synthesis; it carries
no external recordings or ROM content. The source settings describe an
imagined instrument, without a claim of historical hardware emulation.

BASS, LEAD and CHORD use polyBLEP oscillator mixtures; TINE uses a
pitch-limited FM strike; CLAV and ORGAN use bounded harmonic banks with
different damping and registration. The answering voice has independent
source/filter/envelope state. Higher WEAR introduces restrained quantization
at the internal rate, followed by the shared antialiasing path. There is no
unfiltered reduced-rate sample-hold stage or automatically added hiss.

Finite patches route as TONAL and held patches as LOOP. The starter kit is
dry: effects are optional and remain separate in the regenerable recipe.
CHORD recipes transpose with the requested root and are explicitly labelled.

The MIDI input range is a rendering contract, not a promise about the
generic capture classifier. Musical pitch and no-drum checks cover the
preset registers below and every factory recipe at its authored root.
Legal input boundaries are also checked for finite, bounded output. Very
high notes can read SNARE to the capture classifier's spectral rules;
Stoop's explicit tonal routing is retained in SYNTH and its starter kits.

| Family | Preset register (MIDI) |
| --- | --- |
| BASS | 28–55 |
| TINE, ORGAN, LEAD | 48–84 |
| CLAV | 40–76 |
| CHORD | 36–72 |

## Listening and validation

```sh
./gradlew --no-daemon test
./gradlew --no-daemon :synth:generateStoopAudition
./gradlew --no-daemon :synth:generateStoopAudition -Pquick
```

The generator writes WAVs, measurements, patch provenance and a listening
page into `testkit/stoop-audition/` (ignored by Git). It includes dry presets
and musical phrases, macro sweeps and interaction comparisons, raw and
matched velocity, shadow and recording-character examples, explicit chords,
and repeated held loops. The page plays the engine's exported audio and
offers local listening notes; browser playback does not resynthesize Stoop.

Automated checks establish reproducibility, bounds, routing, serialization
and loop continuity. They do not replace listening approval. Android UI
and MPC hardware validation remain device checks when those devices are
unavailable in the development environment.

## Export limits

HOLD returns loop-only settled material. The initial attack and the shadow's
delayed reply are absent; the shadow becomes a quieter sustained companion.
The patch API does not attach a separate attack segment or promise a release
tail in that loop. LENGTH still shapes the held character.

The loop planner searches a long compatible period and records its maximum
oscillator tuning adjustment. Pitch wear completes an integral cycle in
that period. Filters and recording character run continuously through
bounded preroll; the seam measurement compares actual preceding audio
with the end of the kept region, rather than manufacturing history by
duplicating the loop. The audition manifest carries this evidence.

A finite shadow is baked into the WAV. Transposing the exported sample by
changing playback rate also changes its reply delay in seconds. It cannot
remain tempo-locked under arbitrary external transposition. Existing
keygroup and velocity-layer exporters own multisample mapping.

Live polyphony, cross-note legato, scale-aware replies, arbitrary
arpeggiation and standalone plugin hosting are outside this port.
