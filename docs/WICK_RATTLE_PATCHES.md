# WICK RATTLE: accepted bodies and saved patches

Status: implementation of the owner's accepted direction, 2026-11-01.
Repository baseline: `46a046e8dd24ed50149c0fc1cf96b2a694d59af3`.

The owner liked the alternate source audition and authorized Warm wire as the
default, with Hollow reed and Rubbed glass retained as normal saved variants.
This is an integration and REVIEW round. The accepted default spectra, gate
timing, reserve, release events and output stage retain their arithmetic.

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

`Wick.Options.rattleSource` is now a nullable diagnostic override. Null keeps the
normal patch's body; an explicit source selects that body for a comparison.
The public `render` and `inspect` calls append a normal source parameter, with
`inspect` retaining its existing positional options argument. The override is
resolved once before either finite or held engine construction.

## Recipes and playable kit

The provisional library contains 27 recipes. RATTLE's six are **Warm Wire**,
**Hollow Reed**, **Rubbed Glass**, **Depleted Rattle**, **Small Rattle** and
**Held Rattle**. The first three share the accepted default controls and differ
only in their source. The latter three use Warm wire with their existing
controls. The other five voices' recipe definitions remain unchanged.

The sixteen-pad kit is dry. Pads carry their patch names so the bodies can be
identified on the MPC.

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
variants. The full base pack has 359 clips, 70 held examples and seven phrases.

The earlier six cohesion clips explicitly retain folded FM for both sides of
that historical envelope comparison. The twenty source-study clips retain
their accepted audio. Their twelve accepted single-note/held cards now provide
normal patch downloads. The previous body retains explicit comparison recipes.
All four phrase recipes record the body used by each scored note. The page has
379 cards including this retained comparison section.

Hosted source-study aliases must be rebuilt against the new base audio. A
previous-body alias must never silently start pointing at a newly rendered
Warm-wire default. Every shared path is selected by matching actual bytes or
decoded PCM, and every hosted conversion is checked against its source WAV.

Validation covers exact normal-patch replay against accepted diagnostic
renders, source JSON compatibility, edits and velocity, CLI overrides, kit
recipes and labels, pitch, bounds, causal ancestry and real held boundaries.
Independent artifact checks compare all 960 accepted five-voice WAVs and all
96 retained source-study WAVs, including stems and genuine contexts.

## Evidence ledger and limits

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
- Verified: the patch, engine, copy/velocity/CLI, kit and generator integration
  described above exists against the named baseline.
- Measured: the focused command, export manifests and independent artifact
  reports record their actual outcomes; a full JVM run requires exit code zero.
- Not established: phone rendering speed, MPC hardware loading and behavior,
  or the proposed full 36-recipe bank. The engine remains outside the Android
  picker and global factory routing pending their separate integration work.

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
