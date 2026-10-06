# UNDERTOW

UNDERTOW ports the supplied [engineering specification](superpowers/specs/2026-10-05-undertow-engine-engineering-spec.md)
into SnipSnap as a dry pitched instrument. A powered piston draws air through
four weighted leather flaps on one shell. The same moving apertures produce
ceramic rim contacts, tuned breath and changes to their neighbors' pressure.
The reduced model uses normalized quantities and proposed material settings;
the instrument and its constants remain subject to the owner's listening verdict.

## Playing and saving

KNOCK, BREATH, FLUTTER, SEAL, HOLLOW and SURGE bias one coupled shell network.
The dominant chamber carries the requested root; the responding chambers use
ratios 2, 3 and 4 at lower levels. Twelve initial presets range from First Draw
and Ceramic Rim to Alternating Seal and Held Suction.

| Control | Behavior |
| --- | --- |
| TUNE | Requested root, snapped in semitones from C3 to C5 |
| DRAW | Powered extraction strength, shape and duration |
| FLAP | Compliance, damping, clearance and flutter response |
| WEIGHT | Ceramic inertia, contact timing and loading |
| SPIRAL | Shell modes, path impedance and acoustic reaction |
| LEAK | Bypass flow, imperfect closure and pressure relaxation |
| HOLD | Extended finite draws; at 0.99 and above, settled powered suction material |

Velocity supplies gesture energy while preserving the saved macros. Each
independently rendered pad starts from fresh pressure, flap and resonator
states. Patches use the established `Patches` JSON format, and `PadRecipe`
retains the patch with its optional `FxChain`. The dry engine establishes
contact and breath before rack processing. `SynthKits.undertow()` provides a
pitched sixteen-pad starting kit, also available from FRESH TAPE. The phone
synth picker exposes all six voices and their macros.

The existing drum-pad sample format has no attack-plus-loop-region field.
HOLD therefore exports a settled sustain buffer, omitting the initial ceramic
catch. A drum program plays the complete buffer once; repeated playback in
the audition exposes the wrap. The classifier's LOOP label follows duration
and is separate from HOLD's repeated sustain.

## Pressure, contact and pitch

Normalized suction is exterior pressure minus reservoir pressure. Extraction
increases this deficit; inward chamber flow and bypass leakage replenish air
and reduce it. Reservoir compliance and bounded piston capacity keep the
drive finite. Opening several inlets changes the common pressure trajectory.
Sealing an inlet reduces demand and allows continued extraction to alter the
other flaps. Local chamber loading is tracked separately from the shared
reservoir.

Each flap maintains displacement, velocity and a seal state. Pressure forces,
elastic return, damping and acoustic reaction affect its motion. A ceramic
contact follows travel through a rim boundary; its finite pulse excites local
rim modes and the shell. A short compliant reaction at that boundary removes
inward mechanical work; a separate reaction bounds travel near the seal seat.
Seal hysteresis prevents sample-by-sample threshold chatter. Weight changes
mechanics before audio level. A contact can leave a partly open inlet, and
closure can retain pressure until release or leakage relaxes it.

V1 represents leather motion with one gross flap coordinate. Flutter is a
pressure-fed change in that coordinate's damping, bounded by mechanical
losses and travel. A separate fast flexural leather mode remains deferred;
the tuned airflow resonator runs at the acoustic clock.

Each chamber has a tuned nonlinear airflow resonator. Flow and aperture
control its excitation and losses, while flap movement disturbs the same
resonator. Closing an inlet cuts its powered excitation and leaves passive
ringing. Ceramic, airflow and shell contributions share the requested root
and its responding ratios. SPIRAL changes internal loading and shell color
before the final sum.

The piston supplies sustained work explicitly. When extraction ends,
pressure and passive mechanics relax. A baseline relaxation path keeps a
low-LEAK one-shot from remaining locked indefinitely. LEAK noise follows
actual flow. Low-DRAW and high-LEAK settings retain a playable response.

Gross pressure and flap mechanics update near 1 kHz. The update order uses
previous flow to advance the reservoir, then local compliance, flap motion
and the aperture. The shell's prior acoustic state supplies a lagged reaction.
Flow is smoothed before it controls nonlinear acoustic feedback. Acoustic
rotations and finite ceramic force pulses run on the existing 4× internal
path, followed by band-limited decimation to 44.1 kHz mono and
`MELODIC_LOUDNESS_TARGET`. Normal finite renders are bounded by six seconds.
Seed context reaches breath texture; identical voice, note, macros, event
energy and seed context reproduce the same samples and mechanical events.

HOLD selects settled powered material after bounded preroll. Its approximately
1.6-second cycle aligns the slow clock and a repeatable seeded texture buffer.
The acoustic root fits a whole number of periods into that cycle with a small
tuning adjustment. HOLD increases flap damping and uses continuous extraction
to favor useful stationary flow; it does not promise the same irregular
flutter cycle as a finite draw. Preroll compares successive complete states
before capture, with a twelve-cycle upper bound. Loop evidence includes both
`Keys.seamError < 1e-3` and compatible reservoir, local pressure, flap, seal,
flow smoothing and resonator state across cycles. A small audio seam alone
does not establish mechanical continuity. Inspect repeated contact and breath
at the wrap as part of listening acceptance.

Quiet, soft draws with high leakage receive an explicit held maintenance
extraction floor. It keeps the resonator above its singing threshold without
retriggering separate ceramic notes. This ongoing powered work is part of
HOLD; zero event energy leaves the shell silent.

## Audition and verification

```sh
./gradlew --no-daemon test
./gradlew :synth:generateUndertowAudition
./gradlew :cli:run --args='synth UNDERTOW BREATH --all --out /tmp/undertow-breath'
```

Open `testkit/undertow-audition/index.html`. The audition supplies raw and
matched-loudness material, every voice at low, middle and high notes with
three event energies, five-step timbral sweeps, the five required interaction
grids, causal diagnostics, passive tails and difficult HOLD cases. The
manifest records audio levels, render cost and loop evidence. Pressure and
mechanical traces make shared competition inspectable before leveling.

The full roster contains 314 renders, 317 listening cases and 634 raw/matched
WAV files, including quiet event energy .25 with DRAW 0, LEAK 1 and HOLD 1
on all six voices. `-PundertowQuick=true` selects 20 representative renders,
23 listening cases and 46 WAVs, including BREATH's quiet high-leak maintenance
case and the difficult sealed HOLD. The manifest labels this development
subset; regenerate without the flag for complete acceptance coverage.

`Undertow.probe` is an internal diagnostic surface. Its options can isolate
one inlet, use independent reservoirs, disable ceramic contacts, stop the
piston at a chosen time, change seed context, or retain raw output. The probe
returns ceramic, airflow and shell stems, contact events, and snapshots of
shared suction, local pressures, flows, apertures, flap displacement and
velocity, seal states, piston work and stored energy. These controls are
outside the product macro surface.

The diagnostic mechanical clock can be varied from 500 to 4000 Hz. The
convergence check compares the ordinary 1 kHz render with a 2 kHz render for
reservoir pressure, first-catch timing and requested pitch.

The tests target deterministic samples and mechanics, shared pressure
competition, staggered velocity-dependent contacts, weight-dependent timing,
leakage causality, passive decay, audible macro changes after RMS matching,
ordinary requested pitch within ten cents at every supported semitone from
C3 through C5, bounded extremes, pitched routing and held state/audio
continuity. Those checks establish numerical and causal
behavior. They do not establish whether the sound is one convincing hollow
object. The owner still needs to judge the ceramic catch, hollow breath,
competing openings, settling tail and repeated HOLD material. Voice settings,
preset names and sonic acceptance remain provisional until that listen.

## Deferred scope

V1 has no full fluid solver, deformable leather mesh, literal spiral geometry,
stereo propagation, external samples or shared airflow memory across exported
pads. Independently playable chamber notes and a persistent phrase harness
remain deferred. The source specification describes an imagined musical
instrument, rather than measured construction parameters.
