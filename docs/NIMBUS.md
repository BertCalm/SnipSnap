# NIMBUS

NIMBUS imagines six electromagnetically suspended cymbals inside a funnel:
Body, Bell, Paper, Dark, Flex and Wire. Each render contains the whole stack;
all six principal modes share the requested note. Their upper modal ratios,
excitation, loss and bounded flexibility give them different characters.
The [engineering specification](superpowers/specs/2026-10-05-nimbus-engine-engineering-spec.md)
defines the intent. This is an invented musical model, not measured cymbal
acoustics or a validated levitation controller.

## Listen

The [published audition](https://snipsnap-nimbus-audition.bertcalm.chatgpt.site)
uses the same listening transport as MURK. Audio starts off. Enable audio,
then choose a clip. Playing a new clip stops the previous one. The main
HOLD Pause and Stop all controls ramp to silence. Native audio controls
provide a fallback.

```sh
./gradlew --no-daemon :synth:generateNimbusAudition
```

The task writes `testkit/nimbus-audition/index.html`, a manifest, PCM16 WAVs
and mechanical records. Normal generation contains 323 clips, including
voices at C3/C4/C5 and three physical velocities, all nine factory presets,
every timbral control at 0/.25/.5/.75/1 for all six voices, five interaction grids, raw/matched
pairs, six isolated identities, full-stack source taps, coupling/funnel/
contact switches, geometry corners, finite decay and difficult held loops.
Generated files are ignored by Git; source, tests and templates are tracked.

The first C4 default audition concentrated nearly all early/body energy in
the shared root. This revision changes modal excitation and loss so the
upper metal modes have a stronger, longer role. The original pitch and
stability checks did not establish useful audible voice character.

When previous/revised pairs are present, start there at C4. Each pair uses
the same controls and event velocity; the previous audio is preserved from
its recorded source SHA. Compare attack, upper wash, motion and tail while
checking that the common note remains recognizable. The listening targets
are:

| Voice | Listen for |
| --- | --- |
| RING | A rounded Body attack with clear metal rings |
| SHIMMER | Thin Paper shimmer with a richer upper wash |
| GATHER | Flexural wavering and a yielding, gathering tail |
| THROAT | Dark lower and middle metal concentrated in the chamber |
| CONTACT | Wiry upper modes and fine motion-driven rim contact |
| SUSPEND | A soft broad attack and a longer supported metallic body |

Then compare the six isolated identities for a common root and different
upper rings. Compare the velocity cards for a changing attack and
gathering gesture. Compare HEIGHT at fixed SPACING, then SPACING
at fixed HEIGHT. Finally compare contact on/off and repeat each HOLD clip
for several cycles. Descriptions provide listening questions; they do not
establish that a clip has passed a subjective listening gate.

Matched audition files target loudest-200-ms RMS 0.12 with a 0.90 peak
ceiling. Raw partners retain their original level. Source taps use the
full reference's gain so their balance remains meaningful. This audition
gain is separate from the engine's shared melodic loudness target.

HOLD cards repeat by default with the main WebAudio player over HTTP(S).
One decoded buffer repeats without browser seek gaps. Playback ramps
affect only the start and stop; the exported periodic WAV keeps its boundary.
Disable Repeat to hear one cycle with a smooth ending.

```sh
# Small development pack
./gradlew --no-daemon :synth:generateNimbusAudition -PnimbusQuick=true

# Refresh the page from an existing manifest without rerendering audio
./gradlew --no-daemon :synth:generateNimbusAudition -PnimbusRefreshPage=true

# Render factory sounds through the shared CLI
./gradlew --no-daemon :cli:run --args="synth NIMBUS RING --all --out /tmp/nimbus"
```

Optional before/after evidence uses a separately preserved audition from a
clean source revision:

```sh
./gradlew --no-daemon :synth:generateNimbusAudition \
  -PnimbusBaselineDir=/absolute/path/to/preserved-nimbus-audition \
  -PnimbusBaselineRevision=9b77e2eaaff8c050d683399b197c5319b8bea8de \
  -PnimbusCompareHold=true
```

The example uses the preserved first-audition source. For another baseline,
supply its full 40-character Git SHA. Generate the baseline from a separate
checkout of that revision and preserve its output before regenerating the
revision. The directory must contain its manifest, selected original WAVs and mechanical
records. The generator validates source provenance and matching recipes,
copies the previous WAVs unchanged, and records source hashes.

Supplying the baseline directory and revision adds six C4 default pairs,
for 335 clips. `nimbusCompareHold=true` adds SHIMMER and SUSPEND at C4 plus
the difficult settled CONTACT rim case at C3, for 341 clips. Comparison
partners use the same note, controls, velocity and listening target.
Normal 323-clip regeneration needs no preserved baseline. Page refresh
uses the existing manifest and retains its comparison section.

## Host contract

- Deterministic mono output at 44.1 kHz through the shared 4× internal path
  and decimator. Every independent note starts a new complete stack.
- RING, SHIMMER, GATHER, THROAT, CONTACT and SUSPEND bias excitation, modal
  balance and loss; they retain all six cymbal identities.
- TUNE follows the current C3–C5 grid (MIDI 48–72), in semitone steps.
  C4 is the midpoint.
- EXCITE, SPACING, HEIGHT, FIELD and FUNNEL are independent normalized
  controls. HOLD selects finite or explicitly powered sustained behavior.
- `NimbusPatch` uses shared versioned JSON. `PadRecipe` regenerates dry
  source before `FxChain`. Physical velocity changes impulse energy and
  suspension disturbance while EXCITE retains the contact character.
- Nine factory presets land dry. `SynthKits.nimbus()` provides sixteen
  recipe pads, including a pentatonic RING walk, all voices and HELD METAL.
- Finite notes route as TONAL for IN KEY; the settled HOLD region routes as
  LOOP. The SYNTH picker and FRESH TAPE catalog expose the engine.
- `Snip` has no attack-plus-loop marker. Held output is the settled sustain,
  excluding the initial gathering attack.

## Controls

| Control | Low → high |
| --- | --- |
| EXCITE | Soft distributed magnetic flex → harder concentrated contact |
| SPACING | Close sympathetic loading → separated individual rings |
| HEIGHT | Enclosed throat position → exposed mouth position |
| FIELD | Yielding suspension → firmer restoration and coupling |
| FUNNEL | Shallow open loading → deeper tapered chamber |
| HOLD | Finite event → powered metallic sustain; top step loops |

## Numerical model

Six banks of sixteen modes use exact damped rotations in energy-normalized
coordinates: one common principal and fifteen inharmonic upper modes per
cymbal. Unsupported upper modes are suppressed at the note-range edges.
Separate excitation participation and listener radiation give upper modes
independent energy and pickup balance. Each voice sets the
root/upper excitation balance, spectral tilt, upper losses and finite
release pattern. A magnetic pull is followed by short projected flex
releases into the same modes; these are finite note-on gestures. Upper
losses are gentler than in the first audition, and their unloaded decay
times are bounded at 4.5 seconds.

Ten lossy traveling-wave stores form five reciprocal neighbor
links. Orthogonal scattering exchanges modal velocity with those waves;
output radiation gains are readouts and never feed back into the network.
Static loading changes losses rather than transposing the note. Flex has a
small bounded movement-dependent stiffness deviation at the root and a
larger bounded deviation in its upper modes.

Six separate slow height coordinates follow positive restoring springs and
damping. Positions integrate smoothly on every internal sample; slow forces
and acoustic coefficients update at the control rate. Modal energy funds a bounded temporary disturbance. Resting gaps
and stack center map into valid geometry at every HEIGHT/SPACING corner;
bounded excursions preserve ordering. The ordinary suspension cannot supply
unlimited acoustic resonance. Close rims use relative acoustic displacement
and velocity plus slow motion to produce compliant contact. Wide spacing
disables contact. No independent buzz oscillator supplies its identity.

Four chamber modes and twelve source-specific early paths return pressure
to the same cymbals. Height affects modal loss, radiation and return weighting.
Early path lengths are fixed per render to avoid delay-motion energy and
unwanted Doppler; movement changes loading rather than moving delay taps.
This is a deliberate simplification of the design specification.

Intermediate HOLD adds a smooth finite drive that releases before the tail;
HOLD at .99 and above returns a settled repeating region. Both use weak
modal forcing, distinct from stabilization. Voice and selected-material
weights also shape the sustained upper modes. The
seamless region follows bounded preroll of the complete acoustic and
mechanical state. The probe retains an independently rendered preceding
cycle for the current `Keys.seamError` metric; a copied boundary cannot serve
as the continuity evidence. Upper drive frequencies align to the repeat
period for a stable loop, while its principal remains at the requested note
within sample-period quantization.

## Checks and acceptance

```sh
./gradlew --no-daemon :synth:test --tests 'com.snipsnap.synth.Nimbus*'
./gradlew --no-daemon test
# With an Android SDK:
./gradlew --no-daemon :app:compileDebugKotlin
```

Raw probes expose six cymbal taps, chamber/contact/return taps, acoustic and
mechanical energy, heights, resting positions, velocity, controller work,
penetration, contact count, powered work and the genuine preceding cycle.
Tests check deterministic variation, silence at rest, isolated and full-root
calibration, isolated spectral identity, audible-band upper energy through
attack and body, contrasting voice spectra and temporal profiles, causal
sympathetic and chamber response, passive decay, field restoration,
valid geometry, motion-derived contact,
control activity and interactions, control-rate convergence, DC, loudness,
drum guards, held continuity and saved-patch/recipe/kit/velocity integration.

These numerical checks guard against a return to the shared-root-dominated
mix and establish the measured behavior in their tested cases. They do not
establish the intended voice character by listening. The owner's listening
verdict, phone playback and MPC export feel remain part of acceptance.
Release naming checks remain open, as in the supplied specification.
