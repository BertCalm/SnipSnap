# Rime — contour instrument


## HOLD and application integration build

Model 5 preserves every finite model-4 recipe sample for sample when HOLD is zero.
Models 1–4 keep their original rendering laws and refuse nonzero HOLD. The nine
finite factory recipes remain pinned to model 4. A deliberate HOLD edit or MAKE
INSTRUMENT uses model 5; loading an old patch alone never upgrades it.

HOLD below .85 extends the saved excitation gate toward four seconds. At .85 and
above, the source, seeded friction, modal body and output filters become periodic.
The root fits a whole number of periods into approximately half a second. The
modal bank settles after the fine-band opening, and two independently simulated
cycles are compared with `Keys.seamError`. No wrap crossfade repairs that seam.
A third guard cycle protects the measured region from decimator end padding.
The bounded eight-entry render cache owns its samples and returns copies.
The first Kotlin test run measured cold held renders at approximately 130–1242 ms
under concurrent build/test load. These misses exceed the tens-of-ms goal; warm
cache hits avoid the simulation. Android device timings remain unmeasured.

The drum-pad HOLD path returns one resolved cycle and sets gated loop playback.
MAKE INSTRUMENT retains the actual opening, makes a phase-aligned handoff to the
settled sound, then places the loop marker after two independently simulated
steady cycles. That handoff is outside the loop. UNFOLD shapes the opening once;
it does not repeat the fine-band attack on every wrap. A .1–1.5 second RELEASE
belongs to the MPC player's note-off envelope. Player velocity controls volume;
each of these instruments has one saved excitation velocity per root.

The app's dry HOLD audition also retains the opening and uses the actual loop
marker, so UNFOLD remains audible on key-on. Processed audio cannot inherit a
marker from a different buffer; a changed processing path plays through once.
`RimeOpeningPreviewTest` compiles with the device tests and checks the PCM reader's
one-time opening and sustain wrap. Its device execution remains unverified.

FRESH TAPE includes the fixed `rime` starter: the same sixteen editable finite
pads and three native velocity layers as the downloadable bank.

The app picker includes RIME, the nine finite factory recipes, all six macros,
absolute ROOT, VELOCITY and GATE, complete-patch drafts, SEND TO PAD and MAKE
INSTRUMENT. The nine keyboard roots are spaced by minor thirds over two octaves
centred on the saved note, shifted at the supported MIDI 24–96 boundaries.
XTY, XPM, phone sidecar and WAV sampler chunks share the same roots and loop
markers. Each instrument includes its ordinary `rime-patch.json` recipe.

```sh
# Existing finite recipe, explicitly selected new rendering model for HOLD:
synth RIME GLINT --model 5 --macros HOLD=1 --out /absolute/held-cycle
synth RIME GLINT --model 5 --instrument --release .6 --out /absolute/keys
./gradlew :shell:generateRimeInstruments -PrimeInstrumentOutput=/absolute/instruments
# Append six opening/sustain comparisons to a generated finite listening pack:
./gradlew :synth:generateRimeHoldAudition -PrimeOutput=/absolute/audition
```

The audition preserves the 189 finite clips and adds six held comparisons, each
with the opening and eight sustain repetitions. Their original-level files retain
native velocity; whole-clip matching is available separately. Only the preview
gets a terminal fade. The downloaded instrument's sustain remains unfaded.

**Scope still open:** the uploaded original specification is absent from this
workspace. The other three voice identities and their roles are awaiting owner
clarification; they have not been guessed or replaced by variants of these three.
The planned six-voice, 36-preset library is therefore incomplete. Device and MPC
hardware acceptance are also open. Build and numerical checks do not establish
sonic acceptance.

### Why it is built this way

- Keeping the nine finite recipes on their saved model protects the accepted sound direction.
- Periodic forcing lets the body ring indefinitely without retriggering its opening.
- Independent consecutive cycles make the seam check capable of finding a real mismatch.
- Keeping the opening before the keygroup loop preserves UNFOLD as a playable gesture.
- The short sustain cycle and bounded cache reduce repeat-render cost and exported sample size.

## Earlier finite-build milestones

The sections below record the successive finite audition rounds. Their deferred
HOLD and picker statements describe those earlier milestones.

## Musical bank 1 — nine finite recipes and an MPC kit

The owner accepted the model-4 direction. The engine and its accepted controls
remain unchanged; this pass authors nine recipes using the existing macros,
note lengths and native velocity. It keeps the three original starters exactly.

| Voice | Presets | Musical roles |
| --- | --- | --- |
| LOOP | Round Seed, Firm Step, Edged Root | round, punchy and edged bass |
| GLINT | Soft Snow, Hollow Frost, Fine Frost | soft keys, hollow pluck and bright strike |
| VESSEL | Pebble Knock, Ringing Stone, Open Vessel | short knock, resonant tone and unfolding texture |

The bank is pinned to model 4. `RimePresets.starter(voice)` retains the listening
anchors, while `forVoice` and `all` now return the three-per-voice musical bank.
The original 161-clip bench still uses the explicit starters. CLI `--all` renders
the three named presets for the selected RIME voice. The phone factory picker
remains deferred until an Android integration pass.

Each preset has a single note, an original C-minor phrase at 104 BPM, and a touch
study with native .3/.6/.8 strikes. The touch study leaves complete tails between
strikes and matches the whole clip once, retaining relative dynamics. Phrases
retain the preset's complete gate and body tail and persist every note, onset,
velocity and seed in ordinary `RIME_SCORE` recipes. The full audition now has
189 clips. The native one-shot download contains nine kit-main WAVs and nine
ordinary, replayable patches; it does not apply audition gain matching.

`RimeKits` supplies a complete sixteen-pad bank A. A01–A08 walk Round Seed through
C2, Eb2, F2, G2, Bb2, C3, Eb3 and F3. A09–A16 contain the remaining eight named
presets. Every pad is a finite, dry one-shot with native .3/.6/.8 strikes: soft
layers are peak-matched to the .8 reference, and hardware velocity owns level.
The written zones are 1–41, 42–84 and 85–127; velocity zero remains note-off.

The downloadable kit includes an MPC 3 XTD and an MPC 2 XPM twin sharing the same
48 PCM24 mono 44.1 kHz samples. The XTD carries an original four-bar pattern. Set the MPC project tempo to
104 BPM to match the audition; a standalone track does not set project tempo. `kit.json` preserves editable pad recipes; `pad-map.json` adds every
zone's complete patch, matching rule and WAV checksum. The web kit study uses
the same main-zone recipes and the house preview mix, not a hardware recording.

```sh
./gradlew :synth:generateRimeAudition -PrimeOutput=/absolute/audition
./gradlew :synth:generateRimeKit -PrimeKitOutput=/absolute/output
```

`RimeBankTest` checks all nine patch round trips, spectral separation at a fixed
note/gate/velocity, native notes over three registers, and the actual dual-format
kit. Its 27 measured roots track within .20 cents; normalized spectral distances
between presets of the same voice range from .190 to .912. Both written programs
pass the repository's read-back, and all 48 layer WAVs reproduce exactly from
their saved recipe and native-velocity matching rule. Read-back proves agreement
with the repository's reader; physical MPC loading and owner listening remain open.

Why it is built this way:

- Existing controls and note lengths provide nine roles without changing the accepted instrument.
- Fixed-note spectral comparisons prevent a bank distinguished only by pitch, level or length.
- Native soft strikes retain physical contact changes; peak matching leaves hardware volume in charge.
- The same recipes drive the audition, downloadable notes and MPC kit so edits remain reproducible.

These are proposed musical recipes for owner listening. HOLD, additional voices,
the full preset bank and phone picker integration are subsequent work.

## Revision 4 — refine the return of ANGLE

The owner preferred model 3's direction and contact spacing. This revision keeps
its entire first half-turn, its rounded/open anchors, and all three starter
settings sample-exact. It addresses the weaker stretch of the return around
270°, especially in LOOP. The characteristic gesture remains rounded contour →
intermediate edge colours → open contour, with the played root retained.

Above 180°, model 4 maps the normalized return position `t` to a contact span of
`.10 * t^1.75` root periods. The first half retains the model-3 spacing law.
This gives the middle of the return more contact separation without immediately
opening the full edge after 180°. The root bypass, body, unfolding, defaults and
output stage remain as before. The exponent is a macro-travel design choice,
not a change to the Koch geometry or a new physical simulation.

At 270°, the Kotlin probe's fine-energy share in the .43–.63-second window rises
from 1.27% to 3.21% in LOOP, 36.59% to 58.57% in GLINT, and 2.38% to 6.01% in
VESSEL. A 17-position JVM sweep finds lower adjacent-step variation in LOOP and
GLINT. VESSEL's variation increases, with larger colour steps early in the return;
uniform perceptual spacing is not claimed. The 24 focused DSP, replay, seam,
determinism and CLI checks pass. Owner listening remains the acceptance test.

Models 3 and 4 use the same friction seed convention. The table-cache key now
includes the aperture law, so older recipes cannot reuse the revised readout.
Published-WAV fixtures cover the model-3 starters, rounded anchors and 270°
positions. A dense angle probe also exposed a floating-point wrap just below
phase zero; periodic table reads now treat a fraction rounded to one as sample
zero. Ordinary table reads and the previous published PCM remain unchanged.

The full audition has 161 clips: nine current ANGLE positions per voice, the
earlier comparisons, and nine model-3 return positions. “Play return” follows
180°, 225°, 270°, 315° and 360°; “Previous return” uses the original middle
positions and the shared exact anchors. The default comparison voice is GLINT.

Why it is built this way:

- The preferred anchors keep the tonal contrast the owner liked.
- Earlier contact separation gives the weaker middle of the return more range.
- A shaped return avoids exposing all the edge in the first small movement.
- Versioned tables and a fixed noise stream preserve meaningful comparisons.

Owner preference establishes model 3 as the basis. Listening acceptance of this
refinement is pending; HOLD, phone integration and the remaining voices stay deferred.

## Revision 3 — ANGLE has a finite pickup aperture

The owner still could not hear a useful ANGLE change. Rotating a point projection
of the symmetric Koch contour largely changes phase. The weak fixed pickup made
some FFT magnitudes change, but those measurements did not establish an effective
musical control.

Model 3 combines pickup direction with the arc spacing of two opposed contacts.
For fine contour bands, its readout is `gain * (z(u) - z(u - span))`, with
`span = .10 * .5 * (1 + cos(angle)) * (1 + .65 * sin(angle))` root periods.
Contact gain is .65 in broad lower registers, easing to .5 as safe bandwidth
falls from 36 to 18 harmonics so a few strong modes do not mask the upper root.
The same readout applies to position and derivative pressure at both modal ports.
The fundamental bypasses the contact difference. At 180°, the fine contacts
coincide and cancel; at 0°, their spacing admits more contour edge. This gives
ANGLE an actual change in overtone power while retaining the played root.
The contact-spacing law is a design adaptation, not a literal rotating microphone.
The underlying Koch reference and geometric study remain unchanged.

The finite aperture is baked into the existing band tables. Its cache key includes
ANGLE; unmodified and aperture banks share one twelve-entry LRU, still about 14 MiB
at the maximum band count. No extra reads or filters run per audio sample. GLINT's
new default ANGLE is .15 to keep its starter in an open region of this new mapping;
model 2 retains .65. Other starting controls retain their previous values.

Saved model-1 and model-2 recipes keep their original render paths and noise seeds.
Published-WAV SHA fixtures cover three model-1 starters and six model-2 starter and
ANGLE cases. The new audition contains 140 clips, including the four original
model-1 comparisons and six model-2 ANGLE clips. Its first comparison alternates
180° and 0°; the previous pair is available beside it. Both playback gain modes,
ordinary recipes, diagnostic recipes and phrases retain explicit provenance.

Kotlin regression measurements distinguish the open/rounded positions by averaged,
level-independent square-root power-spectrum distance: .288 for LOOP, .924 for
GLINT and 1.017 for VESSEL. In the .43–.63-second window, open-position energy
above 1.5 times the root is 8.1%, 67.6% and 9.2% respectively; the rounded
positions remain below .001%. The root/pitch, BODY, LOOP UNFOLD, finite extremes,
legacy PCM, 4x/8x reference, determinism and CLI checks pass. These measurements
establish a larger spectral contrast; they do not establish owner acceptance.
At the closed aperture, EDGE and UNFOLD have no fine contour to act upon, just as
at DETAIL zero. Normal starter positions keep that aperture open.

Why it is built this way:

- Two spaced contacts change overtone weights on the symmetric source.
- The root bypass keeps a cancellation position from erasing the note's pitch.
- Cached readouts keep the stronger pickup from adding work inside the sample loop.
- Versioned replay preserves earlier listening evidence while the control changes.

The owner preferred this new direction. Earlier revision measurements below
refer to model 2 unless explicitly labeled model 1.

## Revision 2 — BODY and LOOP development

The owner reported that BODY's endpoints were hard to distinguish and LOOP's
UNFOLD did little. ANGLE 0 and 100 are intentionally the same geometric pickup
orientation, but the page previously presented them as ordinary percentage steps.

Model 2 routes bounded, contour-derived pressure more strongly into upper body
modes, gives those modes larger readout weights, and uses small absolute mode
detunings so they remain excitable across registers. The first model predominantly
reinforced the direct fundamental; finishing often removed that level difference.
BODY now has an explicit dry endpoint and retains a direct root at its wet endpoint.

LOOP starts at DETAIL .46 and EDGE .22. More actual contour structure is therefore
available for UNFOLD to reveal during its .8-second excitation. The reveal still
changes band admission rather than pitch or an unrelated modulation. ANGLE retains
one full turn; the page labels 0°, 90°, 180°, 270°, and 360° = 0° and provides direct
quarter-turn comparisons. Cached audio URLs include the source revision.

Original model-1 patches dispatch to the frozen `RimeV1` implementation. SHA-256
fixtures prove that the original three published matched PCM WAVs remain identical.
The page includes the original BODY-100 and LOOP-UNFOLD-100 recipes beside model 2.
Its full collection contains 134 clips, including four retained model-1 comparisons.

The reference contour is cached once. Up to twelve immutable band banks are cached
by frequency, DETAIL and EDGE, with a worst-case allocation of about 14 MiB. Changes
to BODY, ANGLE or UNFOLD reuse those banks. Voice/filter/noise state remains per render.
Phone latency has not been measured; the audition generator records desktop costs.

The 4x/8x probe now uses identical final house conditioning: the higher-rate DSP is
first decimated to 4x, then both paths use the same 4x IIR band limit and output
decimator. This removes rate-dependent cutoff-filter phase from the alias comparison.
Observed residuals at C2/C5/C7 were below -78 dB with friction disabled.

Level-independent, averaged Hann-window spectra now distinguish BODY's 0/50/100
steps on all three normal voices. Endpoint square-root power distances are .838
(LOOP), .978 (GLINT), and .786 (VESSEL). LOOP's UNFOLD endpoint distance is .145;
at UNFOLD 100, energy above 1.5 times the root rises from less than .001% early in
the note to 10.1% later. These are regression measurements, not listening acceptance.

Why it is built this way:

- Pressure excites upper resonances so BODY can change the leveled spectrum.
- LOOP gains real fine structure so UNFOLD has something to reveal and conceal.
- A periodic ANGLE keeps the geometric interpretation; degree labels expose the wrap.
- Model dispatch protects downloaded recipes while the new voicing is evaluated.

Owner listening of the revision remains pending. The following sections retain the
original finite prototype's architecture and recipe contracts.

Rime turns a finite Koch contour into a pitched source, reveals its finer frequency
bands within a note, and drives a two-port modal body. Model 1 is the first listening
prototype developed from the supplied engineering specification. Three provisional
recipes cover LOOP (Round Seed), GLINT (Fine Frost) and VESSEL (Open Vessel).
Owner listening acceptance is pending. The phone picker, HOLD, remaining voices,
36-preset bank and held instrument export are later stages. Nonzero HOLD is refused.

## Render and replay

```sh
./gradlew --no-daemon :synth:generateRimeAudition
./gradlew --no-daemon :synth:generateRimeAudition -PrimeQuick
./gradlew --no-daemon :synth:generateRimeAudition -PrimeReplay=/absolute/recipe.json -PrimeOutput=/absolute/output
```

The page, WAVs, manifest, geometric reference, recipes and downloads are written
under `testkit/rime-audition/`. `-PrimeOutput` changes that destination. The main
bench has 130 clips, plus four retained model-1 comparisons: three starters, three original six-note phrases, five-step
sweeps of each timbral control on each voice, three path removals and a full
reference per voice, three registers at two velocities, four paired-control
matrices, and an all-high C7 case per voice. Quick mode produces 45 comparisons.

Ordinary patches also use the CLI:

```text
snipsnap synth RIME GLINT --all --out /tmp/rime
snipsnap synth RIME VESSEL --patch /path/vessel-patch.json --out /tmp/replay
snipsnap synth RIME LOOP --midi 48 --velocity .6 --seconds .8 --seed 2718 --macros DETAIL=.7,EDGE=.5 --out /tmp/note
```

`--seconds` is the excitation gate (0.15–4 seconds); the body tail follows it.
MIDI is 24–96, velocity 0–1, and the saved seed is a signed 32-bit integer.
The patch persists model, voice, macros, MIDI, velocity, gate and seed.
`Patches.edited`, `PadRecipe` and native velocity regeneration preserve that state.
Provisional recipes stay outside `Presets` and the phone's factory selector.

Diagnostic recipes include an ordinary patch and explicit contact/reference/body
switches. Phrase recipes retain each note's complete patch, onset and mix gain.
The generator replays either kind. Every clip has 24-bit mono 44.1 kHz WAVs:
raw uses one fixed 0.25 gain on conditioned prelevel samples; matched uses
`Dsp.levelTo` at a loudest-200-ms RMS target of 0.10 and a .90 peak ceiling.
All causal comparisons retain duration, source convention and noise substream.

## Source convention

The fixture starts at the top vertex of a counterclockwise equilateral triangle.
Each edge receives the four-segment right-side/outward Koch construction. Iteration
5 has 3,072 edges. The finite polygon has 16,384 equal arc-length samples without
a duplicated endpoint, is centered by its perimeter-sample centroid, and normalized
to unit maximum sampled radius. The original radius is retained. Every prototype
voice uses this exact reference; there is no hidden asymmetric variant.

Extraction uses `Fft.forward`, its unscaled negative-exponent convention, Float
buffers and Double twiddle recurrence, then divides by sample count. Bins wrap
explicitly to signed indices -512 through +512; DC is removed. Model 1 fixes
precision, ordering, traversal and voicing constants.

The source uses disjoint dyadic bands, each with phase-aligned 8,192-point x/y and
analytic-derivative tables. One advancing root phase survives admission and
unfolding. A unit circular reference blends into the actual first coefficient
below DETAIL .12. Relative contour coefficient amplitudes are preserved.

DETAIL compresses the proposed logarithmic 1/8/32/128/512 progression onto
`min(512, floor(18000 / f0))`. A 2 kHz transition reaches zero by 18 kHz. C4 has
at most 68 harmonics and C6 has 17. This note-aware mapping intentionally adapts
the proposal; geometric diagnostic cutoffs remain absolute. Exact symmetry and
high-register bandwidth still constrain which terms exist.

## Controls and body

EDGE tapers admitted upper coefficients and raises bounded derivative pressure.
One derivative-energy normalization has a floor; partials are not normalized
individually. Smooth pressure saturation and subordinate seeded friction run at 4x.
The position-derived fundamental persists. Friction substream identity is independent
of the macro map and active band count.

ANGLE rotates the first projection over a full turn. A second projection at pi/6
feeds a different modal port at level .36. Its versioned path delay is
`.035 + .075 * BODY` root periods. Differing modal participation and path responses
make coherent pickup combination consequential. ANGLE 0 and 1 are bit-identical.

UNFOLD staggers smooth scale envelopes independently of audio phase, with up to
1.5 seconds of reveal. Fine bands recede before the root. At DETAIL zero there
are no fine bands and UNFOLD produces identical audio at both endpoints.

BODY changes upper modal spacing, T60, path length and direct/body balance.
Eight `Modes.Bank` modes include a root anchor; unsafe upper modes have zero
participation. Two force vectors excite one feedforward bank. Trapezoidal forcing
reduces the rate-dependent phase discrepancy with the direct path. Every render
owns all modal/filter/noise state, with no body-to-source feedback.

Velocity changes source/contact drive, hardness and the final loudness target.
Output uses the standard 4x band limit and decimator, DC removal, a measured tail
and a terminal fade. Shared leveling caps compensation at 12x and peak at .94.
Internal bounds are checked before conditioning.

## Verification and listening limits

`RimeTest` checks convergence, nested reconstruction, signed projection, table
accuracy, complete recipe recall/ownership, input refusal, zero velocity, periodic
ANGLE, unfold timing, unforced modal decay, pitch/root energy, control activity,
finite extremes/DC/tails, and a 4x/8x reference with friction disabled. CLI tests
check exact authored-patch WAV replay and refusal before output creation. Shared
patch/preset tests register RIME as a provisional engine.

The model-1 GLINT ANGLE 0 versus .5 comparison changed leveled FFT magnitudes by
about 4.9% in normalized L2 distance. Tested bright notes tracked within .1 cent.
Its original 4x/8x full-wave residual was approximately -36 to -31 dB across C2, C5 and C7.
That includes modal integration and conditioning differences; listening is still
needed to assess alias audibility and the usefulness of every control.

The page distinguishes target contour, geometric reconstruction and rendered audio.
Repeated playback of finite clips is not seamless HOLD. Publication is a first-listen
handoff; sonic acceptance remains pending.
