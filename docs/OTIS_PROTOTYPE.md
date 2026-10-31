# Otis: soul-family audition

This is a finite-note research prototype inspired by the
[XOtis musical brief](https://github.com/BertCalm/XO_OX-XOmnibus/blob/main/Docs/engines/otis-engine-architecture.md).
The first question is whether four recognizable source identities deserve
development in SnipSnap: Organ, Steam, Blues harmonica and Squeeze. The
saved harmonica family remains `HARP`; the listening page uses its full
name. Calibrations are provisional; listening acceptance is pending.

## Revision 6: keep the draw in Squeeze chords

**Heard:** Josh reports that Squeeze's chord loses its vibrato/draw quality; the
other sounds are better. Steam, Harp and Organ therefore keep their model-5 paths.
The reference-strength Squeeze single note and independent-pressure renders also
keep model 5 exactly. Historical model-1–5 requests retain their original paths.

**Fix now [A], verified in model 5:** the four-note .68-velocity Cm7 draws Shared
pressure to its .40 floor. The fifth and higher reeds then use an opening of about
.0052, versus unity unloaded. In addition, default bellows amplitude movement is
only .06 deep. This leaves the chord's moving upper-reed colour almost closed.
These coefficient values explain a plausible mechanism for Josh's report; they
are not a claim that the assistant heard it.

The characteristic gesture is **uneven reed bite → a chord draws air → one shared
vibrato and swell carries the nasal body → the upper reeds choke before the root**.
Model 6 retains the unequal ranks, case weights, attack lag, choke and deterministic
seed identities. It changes only Squeeze while keeping the accepted Shared budget:

- Upper opening becomes `.30 + .70*p*p`, reaching .412 at the pressure floor.
  Shared still darkens and softens the source, but cannot shut its moving bank away.
- Excess pressure demand brings chord notes onto one player clock, derived from
  reference effort rather than each note's velocity. Different playing strengths
  can share a draw instead of drifting into unrelated pulse rates.
- Load raises bellows amplitude depth from .12 to .32 before the PULSE control,
  with corresponding upper-reed colour movement. Default maximum depth is .16.
- PULSE also introduces bounded shared pitch vibrato under load, up to
  `PULSE*(8+14*MUSETTE)` cents. Default full-load depth is 8.2 cents; the corner is
  22 cents. This pitch movement is intentional, unlike pressure-induced detuning.

The same instantaneous rank frequency controls both phase advancement and the
harmonic taper; the 180 Hz modulation guard remains. There is no audio feedback,
solver, rack effect or extra noise. An all-unity trace explicitly uses the frozen
model-5 source, protecting both the single reference note and the independent
bypass. The small control trace remains at roughly 1 kHz. Loaded sample work adds
one common clock and bounded FM/taper arithmetic; no geometry cache is required.

```sh
./gradlew --no-daemon :synth:test --tests 'com.snipsnap.synth.Otis*' :synth:generateOtisSqueezeDrawAudition
```

The focused study has four comparisons and nine complete recipe variants, each
rendered twice and checked sample-for-sample. It includes the exact previous Cm7,
a longer 3.6-second draw, a joining key, and an unchanged single-note reference.
Before/Restored use the same notes, controls, seed and .85 Shared pressure. An older
independent model-4 reference is clearly labelled on the first chord. Matching
uses the same house rule; source WAVs share one disclosed headroom gain.

**Measured:** the emitted-audio tests check a mixed-velocity chord's common motion,
FM/AM sidebands, loaded upper-to-root balance, centered pitch with PULSE off, finite
boundaries and selected low-rate folds. Sixteen published model-5 PCM fixtures
extend the earlier replay checks. **Proposed:** the restored draw, vibrato depth
and reed-opening curve are musical shapes awaiting Josh's listening verdict.
**Disclosed, not fixed:** this is still a finite research source, not HOLD or a
production engine; no phone latency, physical bellows realism or general alias
certificate is claimed. BELLOWS and REGISTERS remain the nearest production
families; this correction does not decide a new engine landing.

The focused Kotlin run passed all 96 Otis tests. Under a constant .40 pressure
probe with motion disabled, upper-to-root power was .000001856 before and .01166
after, versus .06869 unloaded. Root amplitude still followed the .40 pressure
factor. In an emitted mixed-velocity, zero-detune chord, common-clock sideband
power increased at each root; the asymmetric sidebands distinguish combined
pitch/amplitude motion from a gain pulse alone. The original single reference
and other three families pass exact sample comparisons. All 16 published model-5
variants replay their original PCM24 hashes.

The warmed four-note, 1.5-second chord took about 355 ms in model 5 and 431 ms in
model 6 (serial JVM, two warmups and three repeats). This loaded path is more
expensive; the unloaded bypass retains model 5. Phone latency and a production
render-time target remain unverified.

Why it is built this way:

- Keeping more upper-reed body gives the swell something to move through; raising
  final volume would leave the loaded chord's colour absent.
- One player clock keeps movement together across the chord, including uneven
  velocities. Independent clocks can average away when notes are combined.
- Small source pitch motion restores a vibrato gesture as well as amplitude swell.
  Its depth stays bounded and PULSE can turn it off.
- Versioned paths and the exact unloaded bypass protect the improved sounds and
  make this listening correction reversible.

## Revision 5: character after listening

Josh heard models 3 and 4 as the same, asked for more Steam power, did not yet
recognize Harp as harmonica, wanted chord examples, and asked for a more distinct
Squeeze. He preferred **Shared** in the separate phrase-pressure study. Model 4
therefore remains the technical baseline; model 5 changes the three air sources
and carries the preferred player budget into their finite scores. Organ renders
exactly through model 4. All historical output paths remain available.

The characteristic gestures are:

- Steam: valve opening → full harmonic pipe body → shared pressure draw → release.
- Harp: tongue release → reed opens into mouth focus → delayed vibrato → release.
- Squeeze: uneven bite → dry nasal reeds → pressure closes upper reeds → refill → choke.

These are additive pipe/reed sketches with pressure-shaped source spectra. They
are musical analogies, not reconstructed steam flow, reed mechanics, a player's
mouth, or a measured enclosure. No instrument recordings or rack effects are used.

The strongest direction is to change the sources and couple them to the accepted
player budget. It has a clear key/chord gesture, a bounded feedforward signal path,
and keeps the scope to one listening round. Simply increasing detuning and adding
an effect chain would be cheaper but would overlap MUSETTE's existing role. A
literal reed/air solver would bring more physical interaction at considerably
higher implementation and tuning risk. Neither alternate is built here.

### Source changes and controls

| Voice/control | What changes in model 5 | Listening proof |
| --- | --- | --- |
| Steam PRESSURE | Coherent partials extend through the eighth; higher effort strengthens the held body, with no extra output gain or increased noise | C3/C4 and pushed phrase at Even level |
| Harp PRESSURE / BREATH | A 24-partial asymmetric reed feeds broad moving mouth/hand spectral weights; pressure changes upper opening, breath adds restrained source articulation | Sustained C-major and G7 chords, G4 alone, stabs and blues phrase |
| Harp BEND / VIBRATO | Finite scooped entrance and delayed player-clock vibrato remain tied to a sustained note | Single G4 and blues phrase |
| Squeeze MUSETTE / PULSE | Unequal ranks gain controlled even harmonics and broad fixed-Hz case weights; the upper bank speaks late and chokes before the root | Dry zero-detune note, moving note and Cm7 |
| Shared pressure, default .85 | Overlapping reference key envelopes draw one bounded budget; each source reads it for amplitude and upper-harmonic opening before summation | New Shared/New Independent on Steam's open chord, Harp's G7 and Squeeze's joining key |
| RELEASE | The root follows the finite exponential release; Squeeze's upper reeds close sooner | Short stabs and release tails |

All existing air controls remain in 0..1. The controller runs near 1 kHz, with
pressure in .40..1, draw time .045 s, recovery .20 s and reference velocity .8.
Zero strength is an exact pressure bypass. A single reference-strength key also
leaves pressure at unity; Shared becomes audible through excess overlapping demand.
These constants are shape choices. The trace changes source spectra as well as
level, and it leaves oscillator frequencies under each voice's own pitch law.
Model-5 recipes require `sharedPressure`; model-1–4 schemas do not accept it.

The nearest families remain TONEWHEEL, BELLOWS FOLK and REGISTERS MUSETTE. The latter
two cover reed body, detuning and motion; the proposed Squeeze distinction is its
uneven attack, dry even-harmonic nasal body and pressure-sensitive choke, including
at zero detune. That is an audition hypothesis, not a claim that another production
engine has earned its integration cost. These variants stay in test-side research.

### Reproduce the listening round

```sh
./gradlew --no-daemon :synth:test --tests 'com.snipsnap.synth.Otis*' :synth:generateOtisCharacterAudition
```

The 13 comparisons contain 29 complete recipes and 58 PCM24 mono 44.1 kHz WAVs,
with raw/source and matched playback kept separate. All variants replay twice
with exact sample equality. The earlier side is model 4 at the same score, controls
and seed; New Shared is model 5 at .85; three examples also expose New Independent.
C-major 60/64/67 and G7 67/71/74/77 are blow/draw-style musical gestures, not a claim
that this source models the hole layout or breath direction of a specific harp.

Costs are bounded by 8 Steam, 24 Harp, or two 12-harmonic Squeeze banks per active
note and sample. Each source allocates once per note/render; pressure uses a small
control trace and no solver. Harp's spectral targets update near 2.75 kHz and smooth
over .8 ms; Squeeze case weights precompute per note. No geometry cache is needed.
Render timing on the JVM does not establish phone latency; Air still needs a
performance pass before an interactive production landing. All variants retain
model 4's output stage and its disclosed low-frequency high-pass attenuation.

**Heard:** Josh's directions above; Shared preferred. **Verified:** actual source APIs
on base `310bee383bcd17a7c8f7a5bda06500e4b30e8922` plus the staged prototype; finite
source structure and complete versioned recipes. **Measured:** emitted-audio tests,
model-4 PCM replay fixtures and the generated manifest provide evidence for pitch,
pressure, spectral change, bounds and determinism. **Proposed:** new harmonic/formant
shapes and instrument likeness. The assistant has not heard these new sounds.

The focused Kotlin run passed all 89 Otis tests, including 29 published model-4
PCM fixtures and the earlier 40 model-3 sources. Noise-free, RMS-matched Steam
upper-to-root power rose from .2492 to .9197 (3.69×), while the measured pitch drift
retained the same four-window contour. Dry Squeeze even-to-root power rose from
near zero to .05084. Harp's worst settled pitch error in the selected C3/C4/C5 and
bent-C4 tests was .071 cents, with minimum confidence .969; both chord voicings
retained their constituent roots. These are numerical properties, not listening
acceptance or a general alias certificate.

Warmed serial JVM medians for 1.5-second model-5 gates were approximately 123 ms
Steam, 107 ms Harp and 118 ms Squeeze for a single C4; three-note chords took
280/248/234 ms respectively. Two warmups and three timed renders were used. Harp's
chord is slower than model 4's roughly 202 ms in this run. None of these measurements
establishes Android latency or the tens-of-ms production target.

Why it is built this way:

- Extra coherent pipe harmonics put Steam's change in the held sound, so equal-level
  comparison can test power without a volume boost supplying the answer.
- Mouth-shaped reed harmonics and sustained chords give Harp a stronger test of
  instrument identity than adding more entrance bend to a single note.
- Squeeze's late upper reeds and early choke shape the gesture; its dry spectrum can
  be judged independently of musette detuning and chorus-like width.
- Shared pressure acts before the voices are mixed, so a joining key changes how
  an already-held note speaks. A post-mix gain would miss that interaction.
- Separate model paths protect every previous audition and make the listening choice
  reversible. New character, physical authenticity and phone performance remain open.

## Revision 4: hardening without changing saved auditions

Model 4 adds a separate renderer and output stage. Models 1–3 retain the
historical source dispatch, decimation, whole-file mean subtraction and guards
in `OtisPrototypeLegacy`. Forty published model-3 source WAVs are replayed from
their complete recipes and checked against their original PCM24 hashes; the
model-1 and model-2 default fixtures remain in place. The character, range and
preset generators explicitly stay on model 3.

The Organ attack now stops rising at key-up, then releases from that level.
Fixed-frequency oscillator state avoids repeated sine evaluations inside the
oversampled note loop. Air voices derive their harmonics from one sine/cosine
pair per rank and sample; their saved phase, noise and pressure clocks remain
the same. The shared amp, percussion rearming and rotary cabinet
still process the complete score. Simultaneous percussion continues to follow
the first listed note; choosing a different chord policy is separate work.

New output processing is source → eight-pole house band limit at 4× → house
decimation → causal 20 Hz high-pass → onset and final guards. A 120 ms settling
interval lets the DC filter return to rest. Whole-file mean subtraction is
deliberately omitted here: it moved otherwise silent regions away from zero.
The audition renderer keeps source dynamics; `AuditionLevel` supplies matched
playback separately, and raw exports share one disclosed headroom gain.

```sh
./gradlew :synth:generateOtisHardeningAudition -PotisTimings
```

The study compares models 3 and 4 using the same notes and controls: C3/C4/C5,
the twelve existing preset candidates, chords, rotary motion, a delayed note
and a short tap with a long attack. Diagnostics include silence windows,
release envelopes, file hashes and warmed JVM timing. Timing on a workstation
is not an Android latency measurement.

The production landing remains a proposal. Organ is closest to a versioned
TONEWHEEL extension. Squeeze is compared with actual BELLOWS FOLK and REGISTERS
MUSETTE sources at reference commit `565551e`, which is newer than this checkout.
Those sources are built separately for research; they are not merged into this
branch. The native single-note APIs do not preserve every Otis score behavior.

A separate Squeeze experiment gives overlapping notes one bounded player
budget. A second key draws available pressure down, softens upper reed
harmonics, and lets the first note recover when it leaves. This is a pressure
analogy evaluated at roughly 1 kHz, not an acoustic energy simulation. It never
changes model-4 defaults. Zero coupling and one reference-strength key preserve
the accepted raw Squeeze source exactly.

```sh
./gradlew :synth:generateOtisPhrasePressureAudition
```

The 20 Hz high-pass intentionally reduces the lowest MIDI-36 Organ half-frequency
stop (32.7 Hz) by about 1.4 dB; the new output stage is not an exact spectral null.

Why it is built this way:

- Separate source and output versions preserve the sounds already auditioned.
- Causal DC removal keeps silence before a note silent; settling protects the end.
- Oscillator state reduces repeated work while retaining the registration and pitch.
- A sibling comparison tests whether presets or a voice can carry the sound before
  another production engine is introduced.

## Revision 3: more steam power

The owner asked for more power in Steam. Model 3 strengthens its sustained
pipe body: pressure feeds more coherent harmonics through the fifth.
Fundamental level, final gain, pressure drift, opening chuff and note/release
behavior are retained. The change comes from the sustained source spectrum,
heard through the same loudness matching. Organ, blues harmonica and Squeeze
retain their model-2 sound.

Previous/Revised on the listening page selects the previous published
take or the revised render. All eighteen finite prototype cases have
unchanged previous WAVs alongside the current take. Only the Steam pairs
change sound when compared with model 2. The three existing-engine
references remain unchanged. Raw/Matched is a separate choice; use Matched
to compare character at equal loudest-moment level.

## Listen and reproduce

The preset audition authors three variations per family using only existing
model-3 controls: warm/biting/rotary Organ, rounded/forceful/unstable Steam,
gentle/raspy/deeply bent Blues harmonica, and dry/musette/pulsing Squeeze.
The twelve presets leave every source renderer and current default intact.

```sh
./gradlew :synth:generateOtisPresetAudition
```

Open `testkit/otis-preset-audition/index.html`. It starts with the same phrase
in all twelve presets. Default/Preset compares each setting with its current
family default, using identical notes, timing, velocity, seed and model. Note
and Phrase gestures cover all presets; Cm7 Chord covers Organ and Squeeze.
The thirty preset requests share ten default references: 80 PCM24 mono WAVs
and 40 full recipes. `presets.json` collects the twelve single-note prototype
recipes as a named bank for review. These are listening candidates, not a
production preset registration. Raw/Matched remains a separate choice.

The range audition keeps all model-3 source settings fixed and explores their
musical range. It contains 42 requests: soft/hard notes and C–E♭–G–F phrases
at C3, C4 and C5 for all four voices, plus simultaneous Cm7 chords at each
register for Organ and Squeeze. Middle register opens first; register,
gesture and family filters let each comparison stay small. Use Matched to
compare character and Raw to hear changes in playing energy.

```sh
./gradlew :synth:generateOtisRangeAudition
```

Open `testkit/otis-range-audition/index.html`. Its recipes still pin model 3.
The character audition and accepted Steam source are unchanged. This pass
does not add presets, change a voice calibration, or register a production
engine; the listening decision across the musical range comes first.

The earlier character comparison is reproduced separately:

```sh
./gradlew :synth:generateOtisAudition
```

Open `testkit/otis-audition/index.html`. The page uses local WAVs and an
embedded manifest, so no server or network service is required. The directory
also contains full prototype recipes, raw and matched 24-bit mono WAVs, source
provenance and numerical measurements. An alternate output directory can be
selected with `-PotisOutput=/path/to/audition`.
Add `-PotisEmbedAudio` to also generate `standalone.html`, with all audio
embedded for sharing as one file.
When available, `testkit/otis-audition-model2` supplies the previous take
automatically, with `testkit/otis-audition-model1` as a fallback. Set
`-PotisBaseline=/path/to/extracted/previous-audition` to select another
earlier model explicitly. The manifest records its actual model, recipes,
source provenance and WAV hashes. Previous audio filenames include that
model number so a published update does not reuse cached comparison audio.
Without a baseline it generates the revised takes alone. Both earlier
published packs remain available separately as `Otis-first-listen-model1.zip`
and `Otis-first-listen-model2.zip`.

Start with the four sources at C4, then compare soft and hard excitation.
Matched playback uses the existing `AuditionLevel` loudest-200-ms target of
0.03; raw playback preserves source level, with any PCM export attenuation
recorded in the manifest. Changes in attack, spectrum and pitch approach
should remain audible when playback levels match. The identical short phrase
across the four sources makes their articulation easier to compare.

The current TONEWHEEL, AEROSTAT and EMBOUCHURE comparisons retain those
engines' own mechanisms and authored gestures. They are overlap checks,
not claims that all four engines implement the same player.

## Source identities

| Family | Mechanism to listen for |
| --- | --- |
| ORGAN | Nine independent drawbar oscillators, low seeded leakage, contact click, second/third harmonic percussion and a driven cabinet |
| STEAM | Sustained pipe body with pressure-fed harmonics, shared slow pressure drift and independent pipe wobble |
| Blues harmonica (`HARP`) | A breath-driven single-reed sketch with an attack scoop into the requested note, upper partials, rasp and delayed vibrato |
| SQUEEZE | Twin odd-rich reeds, musette beating, bellows contact and pressure pulses |

These are reduced musical models, not reconstructions of a particular
Hammond, steam calliope, harmonica or diatonic accordion. Blues harmonica
does not simulate coupled physical reeds or a complete cross-harp fingering
system. It is not a harpsichord or a plucked-string voice;
SQUEEZE does not implement push/pull button mapping. The source spectra and
articulations are the first listening hypotheses.

The organ phrase sums notes into one optional rotary cabinet and tracks
held keys for single-trigger percussion. Its slow-to-fast example bakes
the rotary acceleration into the phrase. That gesture is not live CC1 or
aftertouch support; changing an exported sample's playback rate changes
its motion timing too.

## Render and saving boundary

All prototype sources live in the `:synth` test source set. No factory
engine, picker, preset roster or existing recipe decoder is changed.
TONEWHEEL's original eight-bar order and renderer are preserved.

Each render starts fresh from explicit MIDI notes, velocity, start/gate
times, source settings and seed. Source synthesis runs at 4×, followed
once by the shared decimation path, DC removal and short boundary guards.
The audition has no effects rack; the organ's cabinet and optional rotary
are explicit source settings. Standalone prototype JSON pins its renderer
model and does not masquerade as a shipping `Patch` or `PadRecipe`. New
requests use model 3. Model-1 and model-2 recipes retain their frozen source
renderers and conditioning, including the earlier source calibrations.
PCM fixtures check the four original defaults against the first published
WAV hashes; model-2 compatibility and unchanged current non-Steam voices
are checked separately.

There is no HOLD loop claim in this pass. These WAVs contain finite notes
and phrases, with their attacks and releases. A future sustained instrument
must separately establish attack-plus-loop mapping and periodic motion.
Live shared cabinet state also requires a playback DSP path; independent
pad WAVs cannot carry that state between performances.

## Acceptance

Focused tests cover deterministic audio, source behavior, range and
finite-output guards, global organ percussion, and complete recipe replay.
Numerical checks support the listening comparison; they cannot decide
whether a voice is musically convincing or distinct from an existing engine.
Phone and MPC playback, sustained loops, final controls and a preset library
follow only after the first sonic verdict.
