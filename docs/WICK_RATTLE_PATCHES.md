# WICK RATTLE: accepted bodies and saved patches

Status: accepted bodies integrated into the completed factory bank and Android
workflow, 2026-11-06. The body-acceptance evidence below dates from 2026-11-01.
Body-integration baseline: `46a046e8dd24ed50149c0fc1cf96b2a694d59af3`.
Factory/UI completion baseline: `e634076db9fdf5a0ca9a8aacbbbcb5193c7bd3d4`.

The owner liked the alternate source audition and authorized Warm wire as the
default, with Hollow reed and Rubbed glass retained as normal saved variants.
The accepted default spectra, gate timing, reserve, release events and output
stage retain their arithmetic. The later performance change only hoists fixed
gate coefficients; its exactness and timing evidence are recorded below.

The characteristic gesture remains **one pitched body opens → recovering gates
move inside it → answering openings soften → the body decays**.

## Saved body selection

New `WickPatch` instances and ordinary RATTLE renders start with
`Wick.RattleSource.WARM_WIRE`. `WickPatch.rattleSource` is a field beside the
requested note and velocity; it is not another macro or a new voice. RATTLE
JSON writes it explicitly. The other five voices retain their JSON shape and
audio path. There are still six voices and the same six controls.

| Saved field | RATTLE rendering |
|---|---|
| `WARM_WIRE` | Accepted rounded harmonic body; new default |
| `HOLLOW_REED` | Accepted odd-harmonic body |
| `RUBBED_GLASS` | Accepted root with quiet half-order color |
| `CURRENT` | Earlier folded-FM body, retained as a compatibility token |
| Missing or null | Earlier folded-FM body, preserving previously saved patches |

Old saved recipes therefore regenerate their previous source. Newly created
recipes write their chosen body, so later changes to defaults cannot alter
them. Unknown selections and invalid types are refused. Copying, macro edits,
native velocity rendering, pad recipes and CLI note/velocity overrides preserve
the body. The existing CLI `--preset` route selects each named variant.

The Android synth picker now includes WICK and its shared factory presets.
RATTLE's BODY buttons select Warm wire, Hollow reed or Rubbed glass; a loaded
compatibility patch also identifies its Previous body. The shared ROOT control
and native VELOCITY control edit the saved note and strike strength. Complete
typed drafts preserve those fields through macro edits, navigation, save and
send. Sent pads retain authored roots, and SPREAD reads the saved MIDI note.
`UserPresets` roster export uses `WickPatch.fromJsonText(...)` with the full
saved JSON rather than reconstructing only voice and macros.

`Wick.Options.rattleSource` is now a nullable diagnostic override. Null keeps the
normal patch's body; an explicit source selects that body for a comparison.
The public `render` and `inspect` calls append a normal source parameter, with
`inspect` retaining its existing positional options argument. The override is
resolved once before either finite or held engine construction.

## Recipes and playable kit

The shared factory bank contains **36 recipes, six per voice**, routed through
`Presets.forVoice`. RATTLE's six are **Warm Wire**,
**Hollow Reed**, **Rubbed Glass**, **Depleted Rattle**, **Small Rattle** and
**Held Rattle**. The first three share the accepted default controls and differ
only in their source. The latter three use Warm wire with their existing
controls. All 27 established recipes retain their definitions. Nine newly
authored recipes complete PLUCK, WOOD, BUBBLE, BREATH and FLICKER's six-per-voice
sets; these settings await owner listening. No new RATTLE recipe or body is
implied by completion of the bank.

The sixteen-pad kit is dry and is available from Android's FRESH TAPE menu.
Pads carry their patch names so the bodies can be identified on the MPC.

| Pads | Recipes |
|---|---|
| A01–A10 | The existing PLUCK, WOOD, BUBBLE, BREATH and FLICKER C3/C4 pairs |
| A11 | Warm Wire at C4 |
| A12 | Hollow Reed at C4 |
| A13 | Rubbed Glass at C4 |
| A14 | Held Bubbles |
| A15 | Held Breath |
| A16 | Held Rattle with Warm wire |

Held Flicker remains available in the library and audition. This pad selection
fits all three accepted RATTLE bodies into sixteen slots. Eleven pads are exact
library recipes; five are transposed defaults. The MPC 3 track, MPC 2 program
twin and included Source Kit retain the rendered WAVs and editable pad recipes.
Kit reload checks compare the actual saved patches, body choices and pad names.

## Audition and verification

`generateWickAudition` renders normal default RATTLE as Warm wire, and renders
the two alternate preset cases using their actual saved source. Its preset
filter includes body selection so equal macro settings cannot hide the new
variants. The earlier 27-recipe base export contained 359 clips, 70 held
examples and seven phrases. The completion generator adds the nine new recipes
as a separately identified listening group and exports all 36 editable patches;
the generated manifest records the current export's actual counts and status.

The earlier six cohesion clips explicitly retain folded FM for both sides of
that historical envelope comparison. The twenty source-study clips retain
their accepted audio. Their twelve accepted single-note/held cards now provide
normal patch downloads. The previous body retains explicit comparison recipes.
All four phrase recipes record the body used by each scored note. The earlier
page had 379 cards including this retained comparison section.

Hosted source-study aliases must be rebuilt against the new base audio. A
previous-body alias must never silently start pointing at a newly rendered
Warm-wire default. Every shared path is selected by matching actual bytes or
decoded PCM, and every hosted conversion is checked against its source WAV.

Validation covers exact normal-patch replay against accepted diagnostic
renders, source JSON compatibility, edits and velocity, CLI overrides, kit
recipes and labels, pitch, bounds, causal ancestry and real held boundaries.
Independent artifact checks compare all 960 accepted five-voice WAVs and all
96 retained source-study WAVs, including stems and genuine contexts.

## Body-acceptance evidence and limits

The final playability probe found an octave ambiguity at Glass TIMBRE one:
the autocorrelation detector selected an octave below the requested note at
C3, while the requested spectral root remained present. Above TIMBRE .45,
only Glass's 2.5× and 3.5× partials now use the softly bounded effective setting
`.45 + .03 * (1 - exp(-(TIMBRE - .45) / .03))`. The integer fifth keeps its
original growth. TIMBRE at or below .45 and both other bodies retain the
original arithmetic. This bounds the half-order period cue while allowing
the upper control range to keep changing the spectrum.

An independent actual-Kotlin probe of the bounded source covers C1 through C6,
TIMBRE .45/.70/1, and finite/held states: 36 cases. The 30 cases within the
detector's supported range retain their requested pitch within 1.263 cents,
with confidence at least .802. C1 is below that detector's 40 Hz floor;
the separate local Hann-FFT peak measurement covers all 36 requested roots
within 4.392 cents. The C4 finite .45-to-one normalized twelve-band difference
is .008645, so the upper range still changes spectral balance. These are
measurements at the companion default controls, not a listening verdict at
every macro combination.

The original 36 focused tests and all three export tasks completed with exit
code zero. After the upper-range guard, nine affected body/source checks,
including the new pitch regression, and a fresh source-study export completed
with exit code zero. There are 37 distinct focused tests across these runs;
full JVM validation is tracked separately.

The post-guard independent artifact check passes: all 960 accepted five-voice
WAVs, all 96 regenerated source-study WAVs, and all 18 historical cohesion WAVs
match their baseline hashes. Normal Warm-wire registers, held default and
phrase, plus the two alternate presets, match the accepted study in matched,
native and raw forms. All 74 genuine held PCM boundaries pass. The sixteen-pad
kit has the correct body fields and labels in Source Kit, linked matching WAVs
in both native MPC formats, and exact audio for its twelve retained accepted
finite/held pads.

- Heard: the owner liked the three alternate bodies and approved this landing.
- Verified in the earlier body round: the patch, engine, copy/velocity/CLI,
  kit and generator integration against the body-integration baseline.
- Measured: the focused command, export manifests and independent artifact
  reports record their actual outcomes; a full JVM run requires exit code zero.
- Implemented in the completion round: the 36-recipe shared factory route,
  Android picker/body/root/native velocity editing, full saved drafts and
  `UserPresets` JSON, authored-root SPREAD handling and FRESH TAPE starter.
  Completion build/export checks are tracked separately in their generated
  reports; the earlier checks above do not certify later changes.
- Awaiting listening: the nine newly authored factory recipes. The accepted
  voices and three RATTLE bodies retain their earlier heard status.
- Not established: physical-phone rendering speed or MPC hardware loading and
  playback behavior.

## Bit-exact performance change

The fixed low/high cutoff law, tone/VCA exponents and stage weight now compute
once per engine instead of inside each sample's three-gate loop. The exact
original expressions and sample arithmetic order remain. Against the completion
baseline, 50 actual-Kotlin cases retained identical raw and levelled float
hashes, genuine held-context hashes and diagnostics. Cases cover all six voice
defaults finite/held, every RATTLE body, low/high registers, macro edges and
legacy envelope options.

Separate Temurin 21.0.13 cloud JVMs on x86_64 with two active processors and JFR
profiling ran the same 50-case sweep before seven timed renders per case. Across
18 cases, median time reductions ranged from 3.25% to 14.40% (median 8.79%). Warm
wire's finite median changed from 251.4 to 228.0 ms and held from 963.3 to
867.3 ms. The tens-of-ms interaction target remains unmet in these cloud
measurements. This is not a physical-phone benchmark.

Run the portable current-build profile from the checkout root:

```sh
./gradlew --no-daemon :synth:benchmarkWick \
  -PwickWarmups=1 -PwickRepetitions=7 \
  -PwickSourceRevision="$(git rev-parse HEAD)"
```

It writes `testkit/wick-audition/render-profile.json`; an absolute
`-PwickProfileOutput=/path/to/render-profile.json` overrides the destination.
It records initial and warmed finite/held renders for all six voices and all
three accepted RATTLE bodies, with runtime, frame, hash and held-strategy
metadata. This single-JVM task uses its own warm-up protocol, so its values are
fresh timing evidence rather than an exact rerun of the isolated before/after
study. The [engine overview](WICK.md#render-cost) gives the full method and
default-voice timing table.

No new solver, source model or cross-render cache is introduced. The accepted
4096-point tables and cubic reads remain. Cloud render times are not device
benchmarks; higher-rate agreement does not isolate alias power. RECOVERY and
the labeled held replay approximation retain their existing limits.

## Why it is built this way

- Saving the body beside the note makes a patch and its exported kit reproducible.
- Keeping old JSON on its former source prevents existing saved pads changing tone.
- Keeping all three bodies under one voice preserves the shared movement and controls.
- Retaining the accepted default arithmetic preserves the sounds the owner chose;
  the upper Glass guard addresses the measured pitch-period ambiguity.
- Retained audio comparisons distinguish a source decision from later changes
  in articulation, playback level or hosting transport.
