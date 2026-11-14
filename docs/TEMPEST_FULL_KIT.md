# TEMPEST: six-voice full-kit audition

## On the default branch (2026-11-13)

This work came back from the owner's recovery backup. The six engine, patch, preset and
compatibility sources are byte-identical to the backup, so every accepted render is
unchanged; the sixteen full-kit, ten model-2 and eight model-1 PCM24 hashes still pass
(`TempestPromotionTest`, `TempestTest`). The registries, CLI, user-preset promotion,
the `tempest` and `tempest-tuned` starters and the SYNTH picker are wired in. The picker
shows all six voices and 22 presets, hides the reserved HOLD and offers no MAKE INSTRUMENT.
Recalled recipes keep their saved model through edits, RANDOMISE, navigation and SAVE.
`TempestSynthScreenTest` covers that on a device and has not yet run on one. The gates are
in [ENGINE_ACCEPTANCE_GATES.md](ENGINE_ACCEPTANCE_GATES.md).

Status: owner-accepted six-voice instrument, 2026-11-07. Repository base
`df675d00426486ce301c7a742b4a7691baf30af8` plus the TEMPEST worktree.
The owner accepted the complete audition with “Sounds great, proceed as recommended.”
The assistant makes no hearing claim. All six voices and sixteen positions retain the
published audio; the DSP/model source files are unchanged by promotion.

## Accepted factory integration, 2026-11-07

The factory roster now contains 22 exact accepted recipes: six KICK, six METAL, two SNARE,
two CLAP, three TOM and three HAT. The ten added entries keep their auditioned names,
complete controls and model 3. KICK/METAL retain all twelve model-2 presets.
No new combinations or unlistened recipes were added.

`SynthKits.tempest()` and the `tempest` shell starter now create the full sixteen-pad drum
kit, without a whole-kit melodic key. Hats explicitly share mute group 1. Other pads use
zero, independent of the capture classifier. `ArrangedPad.muteGroup` carries this choice
through normal kit assembly and sidecar replay, so starter creation and export agree.
Null retains the previous class-based behavior for every existing caller.

The original tuned arrangement remains available through `SynthKits.tempestTuned()` and
starter ID `tempest-tuned`, with its E minor pentatonic key and model-2 recipes.
Existing saved patches and kits do not change when a starter is promoted.
`TempestExpansion` and `tempestFullAudition()` remain compatibility entry points for the
accepted factory recipes. The normal CLI factory lookup serves all six voices.

```sh
./gradlew :synth:generateTempestKit
./gradlew :synth:generateTempestTunedKit
./gradlew :cli:run --args='synth TEMPEST HAT --all --out testkit/tempest-hats'
```

The full-kit export keeps the same sixteen native WAVs and hat choke metadata heard in the
audition. Sixteen published PCM24 hashes protect that acceptance, alongside ten earlier
model-2 hashes and eight model-1 hashes. Both MPC formats and the regular starter are checked.
The promotion record is among the recovery backup's evidence records, which did not come back with this port.

HOLD remains reserved. Phone timing, actual MPC loading/choke playback, the Android picker
and broader alias investigation remain separate. Earlier measurements below retain their
original date and scope; the promotion changes registration and metadata, not the DSP.

## Retained six-voice design and audition record

The characteristic gesture is **digital strike -> driven bite -> degrading repeats -> finite landing**.

## Scope and landing

This extends TEMPEST with SNARE, CLAP, TOM and HAT. KICK and METAL keep their accepted
model-2 defaults, source, filter, drive, memory and output behavior. Model 1 remains frozen.
The design record below describes the November 5 audition. Factory promotion is recorded
above; the Android picker remains deferred and the earlier tuned starter is retained.

THUMP and SKIN already supply standard drum roles, and ONSET blends their electronic/physical
sources. Their presence is a reason to add these as voices inside TEMPEST's existing driven
short-memory instrument. It does not justify another engine or an acoustic-emulation claim.
The mechanism names describe digital synthesis gestures, not literal membranes or maker circuitry.

The chosen direction splices four source gestures into the accepted drive/memory architecture.
An external-FX recipe would obscure that integrated memory control; rebuilding the whole engine
would risk the accepted two voices without helping the missing drum roles.

## Saved models

- `Tempest.MODEL_VERSION` is 3. `defaultModelFor` still selects 2 for KICK/METAL; it selects 3 for new voices.
- Explicit models 1/2/3 remain valid for KICK/METAL; 3 uses the same accepted audio as 2.
- New voices require model 3. Missing model metadata continues to mean 1 and therefore refuses a new voice.
- Model-2 noise retains its original seed literal 2 even though the highest supported model is now 3.
- JSON, recipe editing, CLI replay, user-preset promotion and velocity dispatch retain the selected model.
- The CLI accepts the provisional new recipes separately from `Presets`; they are not factory presets.

## Signal flow

```
SNARE: two-sine body -> LP ---------+
        clocked noise -> wire BP --+-> bounded fold -> DC -> lossy memory -> house finish
CLAP:  three noise bursts + release -> BP -> bounded fold -> DC -> lossy memory -> house finish
TOM:   analytically falling partials -> LP -> bounded fold -> DC -> lossy memory -> house finish
HAT:   six partials + clocked noise -> BP -> bounded fold -> DC/HP -> lossy memory -> house finish
```

The new sources run at 4x 44.1 kHz, mono, with 52,920 delivered frames (1.20 seconds).
`Tempest.finish` retains the house lowpass/decimation, mean subtraction, 20 Hz highpass,
loudness target and 4 ms fade. Noise and oscillator states restart deterministically per render.
No cross-note state or external sample is required.

## Voice mechanisms

SNARE combines a bent body at twice the common tuning reference with a 1.73-ratio secondary
partial. A separate positively damped bandpass carries its wire noise, so the body lowpass
cannot erase SOURCE's added noise. SOURCE reduces body pickup and increases wire pickup;
the fixed wire gain compensates for broadband energy spread rather than normalizing events.

CLAP has three smoothly attacked short bursts spaced `8 + 22*SOURCE` ms apart. A fourth
longer release begins at three times that spacing. All gains are fixed before rendering.
SOURCE changes both spacing and the noise/tone color. The clap's smaller individual bursts
start from wet-branch drive voltage 8.0 so middle DRIVE positions change shape after leveling.
A smooth bandwidth guard lowers this to 5.33 at fully open FILTER.

TOM uses a smaller pitch fall than KICK, analytically integrated with a 24 ms time constant.
SOURCE 0 is a tonal body; higher SOURCE adds two inharmonic partials, PM and a little noise.
The LP range crosses the first four partials instead of opening far above the useful spectrum.

HAT sums six inharmonic oscillators and interpolated noise. Its constant-frequency oscillators
use Double unit-circle recurrences with coefficients calculated once per render. SOURCE changes
partial spacing and noise fraction continuously. A highpass after the drive suppresses low
products of the asymmetric fold. DECAY produces separate short and open articulations.

The new noise generator advances at a fixed 44.1 kHz clock and linearly interpolates at the
internal rate. Higher-rate comparisons therefore receive the same underlying realization.
This is interpolated noise, not a claim of ideal band limitation or zero aliasing.

## Controls

| Voice | TUNE | SOURCE | FILTER | DRIVE | SPACE | DECAY |
| --- | --- | --- | --- | --- | --- | --- |
| SNARE | .55 | .55 | .68 | .28 | .10 | .40 |
| CLAP | .60 | .45 | .65 | .22 | .18 | .45 |
| TOM | .45 | .25 | .65 | .25 | .12 | .48 |
| HAT | .50 | .45 | .70 | .18 | .04 | .18 |

These defaults are listening hypotheses. HOLD stays reserved at zero in all candidates.
KICK/METAL keep their original five sound controls; DECAY belongs to the four new voices.

| Control | Model quantity | Required interaction |
| --- | --- | --- |
| TUNE | common C1-C4 reference for body drums; noise-band color for CLAP; 1.6-6.4 kHz cluster base for HAT | source partials and filter color |
| SOURCE | body/wire balance, clap spacing, tom partials/bend, hat partial spacing/noise fraction | DRIVE |
| FILTER | positively damped LP or normalized BP; SNARE body/wire filters move together | DRIVE |
| DRIVE | accepted bounded fold with square-root wet travel and fixed per-voice input voltage | SOURCE and FILTER |
| SPACE | accepted fixed-per-render lossy memory, first-repeat level and time | DECAY |
| DECAY | source decay time independent of SPACE | SPACE |

New decay time constants are `.020*16^d` seconds for SNARE wires, `.025*9^d` for CLAP's
release, `.022*18^d` for TOM and `.007*45^d` for HAT. A smooth terminal release from .35
through .60 seconds ends the excitation; the memory then lands inside the 1.20-second sample.
Long DECAY remains a finite drum decay, not sustained HOLD.

## Numerical contract and cost

The body partial weights and hat cluster are bounded before filtering. Clock interpolation is
convex; its highpassed noise is scaled by .5. SNARE uses a stated fixed wire pickup gain up
to 3.12. Filter damping remains positive, and cutoff stays below 14 kHz internally.
There is no nonlinear gain inside the feedback loop. The existing memory's feedback stays
below .45, with the same input-bound-to-output-bound argument as the accepted model.

A stable filter domain is not a proof of a particular transient peak. The probes render all
256 binary corners of the six new controls, inspect raw bounds, DC, loudness and pre-fade
landing, and keep the old feedback-growth mutation test. Every adjacent quarter step must
exceed 3% normalized difference energy after finishing. This is an activity guard, not a
claim that the owner hears every step as useful.

All source/filter plans are per-render constants, with no solver or geometry cache. Hat
oscillator rotations avoid six sine evaluations per sample. Body/clap excitation still uses
transcendentals and the output stage, so no phone timing promise is made. The target remains
20 ms median / 40 ms p95 on a named phone; desktop timings are recorded separately for all
four new voices after ten warmups and one hundred production renders each.

## Measured acceptance scope

The focused root selector passed **78 methods**, zero failures/errors/skipped selected methods,
with exit 0. It covers the new voices and affected registries/CLI/shell paths; it is not a
rerun of the historical R1 7,041-method full suite. The temporary selector is in the source
bundle, with no exclusion installed in the repository.

The first isolated nonlinear-stage test exposed fold aliasing in the noisy high-drive snare.
Model 3 now fixes voltage once per render using `2.6/(1+2.25*SOURCE*FILTER^4)` for SNARE
and `8/(1+.5*FILTER^4)` for CLAP. This preserves early folding at normal bandwidth while
reducing index at wide noisy corners. HAT remains 2.2 and TOM 2.6. The accepted voices do
not use these guards and retain their original audio.

Twenty-four selected isolated-fold references use the **same captured 4x filter input**,
interpolated independently to 16x before applying the exact production fold. The script reads
voltage metadata from Kotlin, excluding source/filter differences. All pass the proposed
-50 dB investigation threshold; worst -53.32 dB.
The eight composite 4x/16x cases reach -34.09 dB and include
filter/noise discretization. Neither measurement certifies every macro combination or closes
the earlier KICK investigation.

The 180 rendered cases have maximum raw peak 1.440399, maximum absolute DC
2.06e-07, and worst pre-fade tail RMS relative to raw peak
-67.41 dB. Ten published model-2 and eight model-1 file hashes pass.
The capture classifier reads default SNARE as SNARE, CLAP as CLAP, TOM as PERC and HAT as
SNARE. The generic hat rule requires centroid above 12 kHz; the darker intended hat is not
retuned just to satisfy that classifier. The owner judges the role.

Desktop median / p95, in ms: SNARE 40.904/46.282; CLAP 37.021/42.615; TOM 37.549/42.620; HAT 26.784/31.201.
These use ten warmups and 100 production renders per voice. The 20 ms median target is not
met here, and no phone timing is claimed.

## Full-kit arrangement and exports

| Pads | Intended role | Status |
| --- | --- | --- |
| A01-A02 | GROUND / BREAK kicks | accepted model 2 |
| A03-A04 | SNARE START / SNARE CRACK | candidate model 3 |
| A05-A06 | CLAP START / CLAP SPREAD | candidate model 3 |
| A07-A09 | TOM LOW / MID / HIGH | candidate model 3 |
| A10-A12 | HAT CLOSED / OPEN / NOISE | candidate model 3 |
| A13-A16 | SHARD / GRAIN / BURR / TRAIL metal colors | accepted model 2 |

The three hats share MPC mute group 1. Other pads have no mute group. Every pad is a dry
finite one-shot without velocity layers or loop points, with its complete native `PadRecipe`.
Intent labels and measured capture classes are recorded separately; classification is not forced.
The kit has no whole-kit melodic key because the six roles do not share a pitched scale.
MPC 3 `.xtd` and MPC 2 `.xpm` share sixteen native PCM24 WAVs and `kit.json`.
The sidecar, both export readers, exact sample regeneration and ZIP checksums are verified.
Actual loading and choke behavior on MPC hardware remain unverified.

```sh
./gradlew :synth:generateTempestExpansionAudition
./gradlew :synth:generateTempestFullKit
./gradlew :cli:run --args='synth TEMPEST HAT --preset 2 --macros DECAY=.7 --out testkit/tempest-open-hat'
```

The audition includes four defaults, note positions, quarter-step controls, pair corners,
all sixteen pads and a two-bar 100 BPM sketch using those same pad sounds and hat choke rules.
The sketch is a proposed arrangement and makes no sequencer or factory-pattern claim.

## Genre kits (candidates)

TEMPEST CIRCUIT, TEMPEST FOUNDRY and TEMPEST STEP are three more sixteen-pad kits
(`TempestGenreKits`, starters `tempest-circuit`, `tempest-foundry` and `tempest-step`,
shelved under SKINS after TEMPEST). They are new recipes on the existing voices: KICK and
METAL save model 2, the other four voices model 3. No engine file changes, so every accepted
render and pinned hash stays byte-identical (`TempestPromotionTest` and `TempestTest` run
unchanged).

| Kit | Character | Kicks | Snares | Claps | Toms | Hats | Metal | Accepted presets reused |
|---|---|---|---|---|---|---|---|---|
| TEMPEST (accepted) | Full six-voice kit | 2 | 2 | 2 | 3 | 3 | 4 | all 16 |
| CIRCUIT | Electro: dry, tight, clap-led, tuned bell and clave blips | 2 | 2 | 3 | 2 | 3 | 4 | CLAP START, HAT CLOSED, HAT OPEN, WIRE |
| FOUNDRY | Industrial: heavy drive, metallic sources, long darkening repeats | 3 | 2 | 1 | 3 | 2 | 5 | HAT NOISE, BURR, TRAIL |
| STEP | UK garage: short, hat-forward, rim and ghost snares, no toms | 2 | 3 | 2 | 0 | 5 | 4 | CLAP SPREAD, HAT OPEN |

- **Choke:** every hat in every kit shares mute group 1, as on the accepted kit.
- **Kick limit:** a TEMPEST kick ends by about 0.6 s, so no kit has a long 808-style boom.
  The kits are designed around that rather than changing the engine.
- **Status:** proposed shape values, not heard. `TempestGenreKitsTest` checks role counts, the
  choke group, distinctness (no shared kick or snare; at least 12 different pads between any
  two kits, today's included), exact replay from saved recipes, and MPC 3 / MPC 2 export
  read-back. Once the owner keeps them, PCM24 hashes for every pad get pinned.

## Verified / measured / proposed / heard

Verified: source/patch dispatch, supported-model policy and real APIs at base `df675d0` plus
this worktree. Measured: the focused Kotlin checks, actual-render metrics, clocked 4x/16x
composite rate references and desktop timings, recorded in the recovery backup's evidence records, which did not come back with this port.
Proposed: the new defaults, source voicing constants, ten candidate positions and complete-kit
arrangement. Heard: the owner accepted the earlier KICK/METAL edition and requested this
six-voice direction. No listening verdict for the four new voices is inferred, and the
assistant makes no hearing claim.

HOLD, target-phone timing, Android picker and hardware load remain separate. Rate comparison
residuals include filter discretization and transient differences; they are not isolated alias
power. The earlier KICK maximum-drive investigation also remains open. Full factory promotion
of the additional voices follows the listening verdict.

## Why it is built this way

- Separate source gestures make the snare, clap, tom and hat earn different kit roles.
- The snare wire band preserves the noise that SOURCE is meant to expose.
- A dedicated DECAY lets a hat open without requiring extra repeats from SPACE.
- Shared drive and lossy memory give the six voices one instrument's control language.
- Explicit models protect the accepted KICK/METAL sound while the new voices are auditioned.
