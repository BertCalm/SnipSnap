# CIRCUIT in SnipSnap

CIRCUIT ports the [supplied Circuit engineering specification](superpowers/specs/2026-10-03-circuit-engine-engineering-spec.md) into SnipSnap's
offline, mono synth pipeline. Three distinct breath resonators share a root;
stone rattle, paired wooden clapper, clay vessel and intermittent synthesized
grunt/uh-huh gestures surround them. The instruments and performance rules are
invented. The engine makes no claim to reproduce a real tradition.

The owner rejected the initial audio: “It all sounds the same. The brrrr sound
overpowered everything.” After the balance revision, the owner could distinguish
the parts, but described the vocal as honking and the ensemble as chaotic and
abrupt. The current tone and phrasing revision is a new listening candidate;
owner acceptance is pending. Numerical checks and patch round-trips do not
establish that its sound is useful. Vocal integration, phrasing, mono movement,
and the feeling of an ensemble listening to its surroundings still need listening.

## Listening revisions

The initial mix let the sustained central trio mask the instruments that give
each configuration its identity. The revision substantially lowers central
mix weights relative to the surrounding players. This changes their
balance before the shared output loudness target is applied.

The three tube roles now use distinct starting phases, fundamental/octave drive
balances, modal gains and decay times. The anchor remains rooted; pulse and upper
sources explicitly excite octave modes. Resonator losses also vary by voice,
with wider upper resonances intended to let throat modulation reach the output.
The pulse's continuous pressure floor is reduced, and the upper source has
cadence-related pressure gestures. BREATH changes the relative pressure of the
roles as well as source stiffness and color.

In the second, balance-focused revision, synthesized vocal gestures favored
their moving tract resonances over a plain chest tone. The chest sine
contribution changed from .20 to .06, and the three tract-band weights from
.50/.27/.11 to .80/.46/.25. Seed-derived pitch-bend amounts narrowed from
.012–.025 to .002–.006, keeping
the coordination gestures closer to the requested root without removing their
two-part envelopes, breath onset or deterministic variation.

Each voice now selects a complementary eight-slot pattern for rattle, clapper,
clay, vocals and tube accents. ANSWER additionally interpolates a cyclic breath
pressure pattern with smooth transitions and deep reductions between its
outgoing groups. These gaps follow the playing pattern; they are not dynamically
aligned to every returning cue. The separate performer-listening paths and
bounded reply scheduler still determine which returned accents produce gestures.

Default-clip calibration measured the following changes from the rejected
initial audio to the second, balance-focused revision:

- VOICED's surrounding-player/trio RMS ratio changes from −24.53 dB to
  +12.81 dB, using raw stems at one shared gain.
- Across the six defaults, measured root-band power changes from
  98.39–98.77% to 18.69–51.90%; inter-voice spectral cosine similarities
  range from .630 to .969 for the revised audio.
- Added ANSWER replies measure −5.08 dB relative to the full mix's body RMS.

These values use 0.15–2.8 seconds of the default clips. Root-band power is the
Welch spectral power within ±4 Hz of C3, divided by power from 20–10,000 Hz;
the analysis uses Hann windows up to 16,384 samples. These measurements quantify
that revision's balance and spectral variety in that window. They do not establish
perceived distinction, whole-matrix quality or owner acceptance.

The current revision retains the quieter tube balance, with a stronger stationary
anchor in PROCESSION to keep its requested root present beneath moving pitched
players. It replaces the vocal's
same-phase harmonic source with a bounded, band-limited asymmetric glottal-flow
derivative. Wider moving vowel regions, tract weights .48/.42/.20 and direct
chest gain .025,
more filtered aspiration, and longer voiced attack/release aim to soften the
previous low-partial concentration. The grunt and two-syllable uh-huh retain their
.28/.58-second buffers, deterministic identities, root-related pitch and passive
tract response after excitation stops. Stone and wooden contacts are rounded
over a wider fixed-area interval. The wooden pair has a warmer modal body and
more restrained upper modes; its contact widths are capped against each arm's
pitched mode. Clay contact width is capped by pitch so the
requested membrane mode is still excited in the upper register.

Foreground wood, clay and vocal gestures now take complementary turns. Rattle
supports some outgoing accents at lower energy. A vocal reserves its complete
buffer plus .08 seconds of breathing space, including across the held seam;
primary contact attacks avoid its occupied turn while quiet modal tails may
overlap. A sparse short phrase uses a later quiet grunt instead of adding a
two-syllable vocal to the opening group. Replies still follow a real reflected
performer arrival, but wait for a free quarter-slot, carry less energy, and are
deferred or omitted if no suitable space exists within the bounded response
window. Reply count and energy budgets, refractory intervals and depth-one cues
remain enforced. This coordination changes playing; ORBIT still controls movement
independently.

Finite tube pressure now starts and releases through smooth .12/.44-second
transitions. Tube accents have overlapping smooth .42-second pressure gestures;
a new accent no longer discards its predecessor's remaining pressure. The
bounded active-accent scan also includes the periodic predecessor at a held
seam. These are source and performer changes, before propagation and shared
output leveling; no rack effect or playback limiter supplies the refinement.

Quick-pack calibration against the second revision, at the same settings and
matched level, measures:

- VOICED vocal power in the first three root harmonics, within ±10 Hz, changes
  from 92.44% to 54.30% of its 20–10,000 Hz power.
- All six default matched peaks fall by 1.56–3.21 dB.
- VOICED vocal/percussion activity overlap changes from 31.06% to zero.

These measurements use the same .15–2.8-second body window above. Activity
uses 10 ms RMS envelopes above 10% of each source's own peak. They describe the
25-variant development pack, not a listening verdict or the full matrix.

The [private Circuit audition](https://circuit-snipsnap-audition.bertcalm.chatgpt.site/#comparison)
includes six previous/revised pairs, one for each voice, alongside the full matrix.
Each pair preserves the same macros and performer energy and uses the same
matched listening target. The previous examples preserve the balance revision
the owner just heard; the rejected original examples remain linked separately.
The revised examples are candidates for a new owner verdict.

## Host contract

`Circuit.render(voice, macros, velocity, normalize, probe)` returns a 44.1 kHz
mono `Snip`. Defaults fill any omitted macros. Unknown, non-finite and out-of-range
macro values are rejected. `CircuitPatch` stores its name, voice and macros in
the existing versioned JSON format; its ordinary `render()` uses velocity 1.
Velocity is a render argument and routes through `Velocity.atVelocity`, rather
than becoming an eighth macro or an independently persisted patch field.

TUNE snaps across MIDI 36–60, C2–C4, with C3 at .5. BREATH, DIAMETER, ORBIT,
PACE, CANYON and HOLD complete the seven controls. Finite phrases sustain
performer input for about 3.2–5 seconds, then allow a canyon-dependent tail;
their total duration is about 5–7.7 seconds. HOLD values below .99 extend that
finite phrase; HOLD at or above .99 selects a settled loop of approximately
sixteen seconds.

The host carries one buffer without an attack-plus-loop marker. A held Circuit
pad therefore starts in the settled procession and omits its initiating gesture.
All Circuit buffers route as `DrumClass.LOOP`: the finite outputs are phrases
past the classifier's 1.5-second boundary, and HOLD outputs recur. The central
pitched sources and surrounding instruments remain in the full mix; classifier
guards do not remove percussion to obtain this routing.

## Voices and controls

| Voice | Character | BREATH | DIAMETER | ORBIT | PACE | CANYON |
|---|---|---:|---:|---:|---:|---:|
| ROOT | Central trio, sparse accents | .55 | .30 | .15 | .20 | .35 |
| PROCESSION | Interlocking playing and movement | .50 | .45 | .55 | .55 | .45 |
| ANSWER | Gaps and returned replies | .45 | .55 | .25 | .30 | .75 |
| VOICED | Stronger tube color and coordination voice | .75 | .40 | .30 | .40 | .50 |
| EXPANSE | Wide, slow formation and longer returns | .50 | .85 | .20 | .25 | .85 |
| CONFLUENCE | Rapid playing and faster movement | .70 | .55 | .75 | .80 | .65 |

All voices default to TUNE .5 and HOLD 0. Neutral companion values for
controlled auditions are BREATH .45, DIAMETER .40, ORBIT 0, PACE .35,
CANYON .40, HOLD 0 and TUNE .5. Neutral is distinct from each voice's defaults.

- BREATH changes the bounded periodic lip drive's stiffness and coloration,
  tube pressure and articulation, and synthesized voice breathiness.
- DIAMETER changes the surrounding players' radius from approximately
  1.2 to 4 metres, with compatible canyon boundaries.
- ORBIT 0 is stationary. Nonzero ORBIT requests approximately .025–.125
  revolutions per second. Movement changes direct and reflected delay,
  distance loss, radiation facing and spectral loss in mono.
- PACE requests approximately .5–6 playing slots per second. Complementary
  patterns assign subsets of those slots to each surrounding instrument and
  to tube accents. PACE does not change the requested root or motion clock.
- CANYON changes the two unequal wall paths, reflection loss, later returns,
  returning tube influence and the opportunities for behavioral answers.
- HOLD lengthens a finite phrase until .99, then delivers its settled loop.

The first three sources form the central trio; the other four move with fixed
angular offsets. The observer is off-center. Direct and reflected observer
paths use fractional delays, smoothed motion and path-dependent low-pass loss.
Performer-listening arrivals are calculated separately from observer arrivals.
Tagged tube or clapper accents may cue one extra gesture after a return reaches
another player. Replies have refractory intervals, a canyon-dependent event
budget of at most six (also bounded by the global limit of 12), a cumulative
energy budget and depth 1; replies never become new cues.
Disabling replies in a diagnostic retains canyon audio.

The tube model is an abstract periodic pressure/lip source feeding stable
lossy resonators, with role-specific phases, fundamental/octave drive, partials
and articulation, plus voice-specific losses.
The upper tube has a subordinate band-limited throat modulation source that
changes excitation pressure; it does not radiate an independent lead voice.
It is a reduced model rather than a detailed lip-reed waveguide or a claim of
real instrument fidelity. Local neighbor radiation changes its nonlinear
excitation. Finite collision/contact gestures excite the surrounding modal
instruments; vocals use a bounded harmonic source, filtered air and moving
tract resonances. No sampled chants, continuous rattle noise bed or footsteps
are included. Nonlinear sources and contacts run at 4× output rate and use
the shared band-limited decimator. Normal output uses
`Dsp.MELODIC_LOUDNESS_TARGET` after synthesis.

## Held clock approximation

HOLD rounds the two clocks independently onto cycles that fit an approximately
sixteen-second loop. PACE completes whole eight-slot patterns, preserving their
vocal sequence, upper pressure gestures and ANSWER's smooth breath gaps. An even
number of root cycles also closes the subordinate half-root throat source.
ORBIT 0 stays stationary; every requested
nonzero orbit rounds to at least one whole turn per loop. The current
.025–.125 Hz request range rounds to one or two turns, approximately .0625 or
.125 Hz. Slow requested motion therefore speeds up to the nearest compatible
nonzero rate. Requested ORBIT also changes source-facing/spectral modulation
depth, so nearby values retain a tonal distinction after clock rounding. This
delivery approximation needs listening, particularly for EXPANSE and the
slow-orbit HOLD diagnostic. One-shot clocks retain their requested independent
rates.

The engine settles tube states and acoustic buffers through bounded preroll,
measures two adjacent real cycles and refuses a held render if its seam or
state convergence error exceeds 1e-3. Periodic gesture seeds and bounded reply
scheduling repeat with the held cycle. The sidecar records the actual and
requested rates, loop duration, preroll cycles, convergence and seam evidence.
Phrase timing still needs listening: a numerical seam cannot certify that a
clapper pair or vocal gesture feels continuous.

## Presets, kit and audition

`CircuitPresets` supplies twelve dry starting points across the six voices.
`SynthKits.circuit()` builds sixteen editable pads: a C-minor-pentatonic ROOT
row from C2 on A01–A08, followed by formation, wood, clay, vocal, wide and rapid
presets, and a held procession. Internal canyon paths belong to the engine;
the rack is handled by the existing `PadRecipe`/`FxChain` pipeline.

Generate the full owner listening matrix from the repository root:

```bash
./gradlew --no-daemon :synth:generateCircuitAudition
```

For a smaller development pack:

```bash
./gradlew --no-daemon :synth:generateCircuitAudition -Pquick
```

The generator also accepts `--quick` directly. Its default destination is
`testkit/circuit-audition/`, which is gitignored. Open that folder's `index.html`
directly: the manifest is embedded so local file URLs work. Generated artifacts
are not automatically published or committed.

Every variant has a 24-bit raw WAV and a WAV at the shared `AuditionLevel`
target, plus a diagnostic JSON sidecar. Raw retains source gain; matched
clips support fair comparison of tone and timing. A raw peak above the PCM
range or a non-finite sample stops the generator instead of being hidden by
file conversion. Standard full mixes reject KICK, SNARE, CLAP, either HAT and
TOM classifications. Source isolates and passive-decay diagnostics omit that
guard so their individual roles can be heard.

Separately matched source solos help identify instrument character, but they
cannot establish ensemble balance: matching each branch independently makes a
buried source sound foregrounded. The source-balance regression therefore sums
raw trio stems and raw surrounding-player stems at one shared gain and compares
their active-phrase energy. The VOICED regression requires combined player RMS
above trio RMS to support the current calmer balance. It also checks upper-tube
octave content. These
checks catch the previous sustained-root masking; they complement listening to
the full mix and do not certify every voice's musical balance.

The complete pack includes:

- Every voice at defaults, plus C2/C3/C4 at velocities .25/.6/1.
- Every voice's TUNE, BREATH, DIAMETER, ORBIT, PACE, CANYON and HOLD at
  0/.25/.5/.75/1, with neutral companions.
- Five 3 × 3 interactions: DIAMETER × ORBIT, DIAMETER × CANYON,
  PACE × CANYON, ORBIT × PACE and BREATH × CANYON.
- Seven source isolates, isolated dry grunt and uh-huh gestures; replies
  enabled/disabled; local coupling enabled/disabled; performer input off at 1 s.
- Stationary fast playing, fast movement with sparse playing, close/wide
  formations, every voice at all-high extremes, and difficult held clocks.
- All twelve presets and the sixteen-pad kit recipes.

The quick pack keeps the six voice defaults, ROOT's low/high register at
quiet/strong velocity, ORBIT/PACE endpoints, seven source isolates, the reply
comparison, one all-high phrase and a slow-orbit held loop. It does not replace
the full acceptance matrix.

Sidecars retain event kind, source, time, energy, reply depth and trigger,
cue emission and separate performer/observer arrival times, cue source/wall,
sampled source positions and
direct/reflected distances, actual clocks, recovery count, raw peak and loop
evidence. Render time and JVM used heap at the render boundaries are recorded;
those heap readings are not peak-memory measurements. The page permits repeating
clips, recording choices and downloading an owner verdict JSON.

`measurements.json` summarizes the completed pack using those same renders:
variant/WAV counts, classifier-guarded count and class distribution, total
recoveries, all-variant/full-mix duration and render-time ranges, total render time, loop count,
seam/convergence ranges and the clips at each extreme. For raw, engine-normalized
and audition-matched audio it records output-rate peak, absolute DC and
whole-buffer RMS ranges. These audio values describe the floating-point buffers
before PCM quantization; RMS is not the loudest-window loudness target. Heap
boundary ranges remain process-wide observations, not peak render allocation.
No extra renders are made to produce this summary. A summary is written only
after the requested pack finishes successfully.

Run the repository's JVM checks with `./gradlew --no-daemon test`; host and
phone checks remain subject to the repository's build setup. Preserve the
owner's listening verdict alongside any measured results. The initial revision
was rejected; a verdict on the revised candidate remains pending.

## Build and runtime evidence

The initial-revision Circuit Android debug APK built with JDK 17, Gradle 8.14.3,
AGP 8.7.3, Kotlin 2.0.21, API 35, NDK 27.2.12479018 and CMake 3.22.1.
All four configured native ABIs are packaged. `apksigner verify --verbose`
passes; the manifest targets API 35 with minimum API 29. This verifies assembly
and signing; no emulator or physical phone run has been performed.

Fresh desktop OpenJDK 17 JVMs also rendered initial-revision default CONFLUENCE
one-shots and HOLD loops successfully with both 128 MiB and 192 MiB maximum heaps. At 128 MiB,
the 5.585-second phrase took 2.066 seconds and the 16.008-second held buffer took
10.938 seconds under concurrent test load. Linux peak resident sizes were
108.79 MiB and 181.03 MiB respectively; resident memory includes JVM native
memory as well as heap. Every returned sample was finite. These probes cover
the renderer alone; they do not establish Android ART or whole-app memory use,
and 128 MiB is the lowest capacity tested, rather than a measured minimum.
These assembly and runtime benchmarks describe the initial implementation;
they are not measurements of the revised listening candidate.

Anti-aliasing uses the shared 4× renderer and band-limited decimator within the
C2–C4 register. A Circuit-specific residual-alias threshold or high-register
spectral comparison has not been calibrated. Acoustic timing evidence uses
the path and performer-cue diagnostics rather than a rendered moving-impulse
arrival probe. Those limits, ensemble balance, vocal integration, mono motion
and held phrase continuity remain part of listening acceptance.
