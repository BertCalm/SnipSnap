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

Newly constructed patches use synthesis model 2. Saved JSON with model 1
keeps the model 1 renderer; a missing or null model field also reads as
model 1 so older recipes keep their intended behavior. Both versions use
the same patch format, and editing a saved sound retains its model. Other
model versions are refused.

TOUCH changes attack contact and early spectrum continuously, and retains
a restrained influence on the settled spectrum in HOLD. COLOR moves
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
./gradlew --no-daemon :synth:generateStoopAudition -PstoopQuick
```

The generator writes WAVs, measurements, patch provenance and a listening
page into `testkit/stoop-audition/` (ignored by Git). It includes dry presets
and musical phrases, macro sweeps and interaction comparisons, raw and
matched velocity, shadow and recording-character examples, explicit chords,
and repeated held loops. The page plays the engine's exported audio and
offers local listening notes; browser playback does not resynthesize Stoop.

For the polish comparison, supply both published reference packs:

```sh
./gradlew --no-daemon :synth:generateStoopAudition \
  -PstoopBaselineDir=/path/to/october-15/stoop-audition \
  -PstoopOriginalBaselineDir=/path/to/october-10/stoop-audition
```

The full comparison contains 558 clips: 300 current examples, 24 bright
held LENGTH comparisons, 96 factory notes/phrases from the October 15
publication, 96 from the October 10 first publication, and 42 held examples
from October 15. The new held comparisons cover LENGTH 0, .5, .75 and 1
across all six families. Without reference packs, the full generator writes
324 current clips. The 18-clip smoke mode uses `-PstoopQuick` and cannot be
combined with reference inputs.

The factory shelf defaults to **Compare previous** for the October 15 bank;
**Compare first** selects October 10. Factory comparisons keep the MIDI
root, velocity and phrase timing aligned. Reference audio and its manifests
are copied from the published packs rather than recreated with the new
renderer. Source and model provenance stay attached to each clip. Listening
feedback can carry forward only when the exact WAV hash matches; a familiar
preset name or path does not transfer a verdict to changed audio.

To rebuild the local listening page while retaining existing WAV evidence:

```sh
./gradlew --no-daemon :synth:generateStoopAudition -PstoopRefreshPage
```

The corresponding generator arguments are `--baseline-dir=...`,
`--original-baseline-dir=...`, `--quick` and `--refresh-page`.

## As built: October 15 preset contrast

The contrast revision keeps all 48 names and family counts. Round Corner,
Dust Tines, Soft Clav, Amber Organ, Nose Talk and Velvet Stack retain their
original complete recipes as reference sounds. Other recipes use wider
active waveform/registration, contact, duration and answering-voice choices.
The starter kit keeps its bass and tine note rows, while its upper pads add
the revised wire clav, hollow organ, whistle, bright chord and bell-answer
roles. Every pad retains its complete regenerable recipe.
This revision changed recipes while retaining synthesis model 1 and the
saved-patch format. Before the recipe changes, the unchanged bank matched
all 300 first-publication WAV hashes on that base. That comparison separated
the preset contrast work from renderer changes.

## As built: October 23 Luthier polish

Model 2 develops the finite CLAV gesture from pick contact through a
selected harmonic body into the existing release. The source keeps its
original first 25 ms, then harmonics 2–5 continue from their actual level
with slower loss. That join is continuous and only removes energy. The
root, finite note gate and the six-macro contract stay in place. The intended improvement is
body character after the attack; its usefulness remains a listening question.

Held CLAV computes its fixed pick weights, harmonic slope and losses once
per gesture instead of repeating those calculations for every sample. This
keeps the settled registration and avoids unnecessary work in the audio
loop. Model 1 retains its original arithmetic and behavior for
saved-recipe compatibility.

Three narrow model 2 corrections accompany the CLAV change. CHORD filtering
uses the explicitly marked root rather than the first serialized interval.
The HOLD period search includes the configured companion even at SHADOW 0,
so raising the knob does not select a new oscillator clock; zero still
omits companion synthesis. Bright held LENGTH settings change the settled
harmonic balance even when filter or FM brightness has reached its bound.
In TINE, the safe FM limit is applied before the LENGTH-dependent loss.

Round Corner, Dust Tines, Soft Clav, Amber Organ, Nose Talk and Velvet Stack
remain explicitly on model 1 as the six published reference sounds. The
other 42 factory patches use model 2 with their October 15 source, macro,
pitch, velocity, interval and timing recipes retained. The model field
selects the polished renderer for those existing palette settings.

### Why it is built this way

- A retained CLAV harmonic body gives the pick something to develop
  into while preserving its root and finite ending.
- Versioned rendering keeps old kits and saved sounds tied to the behavior
  they recorded. The six unchanged factory references make that continuity
  available in the audition.
- A common held period for muted and audible companions prevents the
  SHADOW knob from changing the main oscillator clock as a side effect.
- LENGTH acts on held harmonic balance because a loop-only export contains
  no release tail to lengthen. Precomputing fixed held CLAV weights avoids
  repeating work that cannot change during that gesture.

### Verification and listening status

The implementation changes above are recorded against source base
`91384fa`. Eighteen pre-change model 1 recipes reproduce the exact raw and
leveled Float32 hashes and frame counts, including finite and held examples
of all six families. The compatibility tests also cover missing/null model
fields, saved edits, pad recipes and velocity regeneration.

Independent joint harmonic fits of dry Kotlin renders at 150–350 ms put
Short Wire's H2–H8 power share at 0.401% before and 3.454% after the change;
Copper Pick moves from 0.392% to 3.441%. Their first 20 ms of conditioned raw
Float32 audio remain exact. The five measured CLAV registrations keep root
error below 0.99 cents and pitch confidence above .88. These measurements
establish retained pitched body, not a perceptual approval of the presets.

A paired cloud-JVM benchmark of Grain String, with five warmups and fifteen
measured renders per variant, puts the median at 923 ms before and 368 ms
for optimized model 1, a 2.50× improvement with identical audio. Model 2
measures 362 ms in the same test. These are desktop measurements, not
Android slider-to-preview timings.

Full-suite results and the final audition pack checks remain pending. The
October 15 and October 10 reference audio remain the comparison evidence;
no new listening verdict is recorded here.

Automated checks establish reproducibility, bounds, routing, serialization
and loop continuity. They do not replace listening approval. Android UI
and MPC hardware validation remain device checks when those devices are
unavailable in the development environment.

## Export limits

HOLD returns loop-only settled material. The initial attack and the shadow's
delayed reply are absent; the shadow becomes a quieter sustained companion.
The patch API does not attach a separate attack segment or promise a release
tail in that loop. LENGTH still shapes the held character, while TOUCH and
the companion's own articulation remain audible in the settled spectrum.

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

Full-WEAR folded-energy coverage is limited. The original 671-Hz,
amplitude-.36 recording-stage probe measures about −64 dB against a 16×
reference; a separate 8372-Hz, amplitude-.03 stage probe measures −27 dB.
That isolated high, quiet input is not a demonstrated factory-patch failure,
but the cleaner bound must not be generalized to every pitch and velocity.

Live polyphony, cross-note legato, scale-aware replies, arbitrary
arpeggiation and standalone plugin hosting are outside this port.
