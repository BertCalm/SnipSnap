# TERRA: promotion to a finished engine

On 2026-11-14 the owner chose to **promote TERRA**. Until then, TERRA was a world-percussion engine with a factory exemption and no SYNTH picker entry.

The master plan's instruction was "determine original finish scope before proposing promotion". This note records that scope and the first step.

## What TERRA has (base `23cde0d1c`)

- **Voices:** 13 in `TerraVoice`. `Terra.kt` routes them to four files:
  - **The four spec voices.** RESONANT_CAVITY (udu, cajón), CONICAL_BELL (agogô), TUNED_BAR (balafon) and COMPOUND_MEMBRANE (retired, no pad left).
    - `TerraFrozenTest` holds them to `LegacyTerraBank`.
    - The owner heard them only at the strike-velocity gate, and never picked a setting.
  - **Nine newer voices, each built from the owner's listening rounds of 6–8 Nov:**
    - **BOX** (`TerraBox.kt`): the cajón slap.
    - **SLAP, DAYAN, DARBUKA and DOUM** (`TerraHands.kt`).
    - **DJEMBE, DHOLAK, BAYAN and TUN** (`TerraFitted.kt`).
    - [TERRA_BOX](TERRA_BOX.md) and [TERRA_HANDS](TERRA_HANDS.md) record each pick. The picks were heard as Python prototype clips. Their ledger says the app's own renders of the fitted voices and of TUN were not yet heard, and that soft and medium hits were never heard clearly.
- **One kit:** `TerraKits.classic()`, 16 pads. Only tests and `TerraKitGenerator` use it. It is not a starter, and nothing in the shell or app offers it.
- **No presets, no picker entry, no roster entry.** `SynthRoot` knows `TerraPatch` only as a recipe it can regenerate.
- **Round robin:** TERRA can re-strike (`Restrike.canRestrike`), with each take moving POS a little. No TERRA pad on any shelf uses it.

## The classic kit, pad by pad

| Pad | Name | Voice | Source | Measured pitch |
|---|---|---|---|---|
| A01 | Udu Whoomp | RESONANT_CAVITY | **Never picked** (spec S5, 55 Hz) | A1 +6c (55 Hz), as named |
| A02 | Bayan Drag | BAYAN | Owner pick ("Both work") | F#2 +7c, falling ghe |
| A03 | Djembe Bass | DJEMBE | Owner pick (round 10's B) | A#1 −12c (58 Hz bowl) |
| A04 | Cajon Low | RESONANT_CAVITY | **Never picked** (spec S5, 60 Hz) | B1 −33c (60 Hz), as named |
| A05 | Dholak Bass | DHOLAK | Owner pick (round 9's B) | G2 −20c, falling |
| A06 | Dumbek Doum | DOUM | Owner pick (round 5's A) | A2 +6c (110 Hz air), as written |
| A07 | Cajon Slap | BOX | Owner pick ("C (deeper box)") | Noisy plate, no single pitch |
| A08 | Djembe Open | DJEMBE | Owner pick (round 9's B) | Head modes near C#5 |
| A09 | Tabla Tin | DAYAN | Owner pick (setting 3, heard in the app) | Strongest partial D5, octave above its D4 note |
| A10 | Tabla Tun | TUN | Owner pick (round 1's A) | D4 −2c, as named |
| A11 | Dumbek Tek | DARBUKA | Owner pick (round 3's B) | Rim crack near F6 |
| A12 | Djembe Slap | SLAP | Owner pick (round 2's A) | Palm on the bowl, no single pitch |
| A13 | Agogo Low | CONICAL_BELL | **Never picked** (spec S5, D5) | D5 −2c, as named |
| A14 | Agogo High | CONICAL_BELL | **Never picked** (spec S5, A5) | A5 +0c, a fifth above A13, as named |
| A15 | Agogo Clack | CONICAL_BELL | **Never picked** (spec S5, 740 Hz) | F#5 +1c (740 Hz), as written |
| A16 | Balafon Key | TUNED_BAR | **Never picked** (spec S5, E4) | E4 +1c, as named |

Pitches were measured as the strongest spectral peak, from 20 ms into each pad's hard render. Every named or written pitch matches. The Tin's octave is the dayan's rim stroke ringing its second mode over its D4 note, which is the stroke the owner picked.

**Flagged:** six pads (A01, A04, A13–A16) use the spec voices, whose settings the owner never picked.
- Nothing in the owner's picks can stand in for an agogô or a balafon, so swapping them would cost the kit its bells and its bar.
- The round therefore played them as they are, as clip E4. It also offered a labelled alternative, E3: the same groove with each of the six swapped for a picked pad.
- None of the six changes without a verdict.

## The owner's verdict (terra-kit-1, 2026-11-14)

| Clip | What it was | Verdict |
|---|---|---|
| E1 | The 16 pads in order, one second apart | KEEP |
| E2 | A four-bar groove on the full kit, 100 BPM | KEEP |
| E3 | The same groove with the six spec pads swapped for picked pads | TWEAK: "Full kit" |
| E4 | The six spec pads on their own | TWEAK: "Full kit" |

**The kit is kept in full.** The owner chose the full kit over the picks-only version, which settles the six spec pads: Udu Whoomp, Cajon Low, Agogo Low, Agogo High, Agogo Clack and Balafon Key stay, as heard.
- Nothing was re-tuned or re-levelled. The six spec pads keep their shorter lengths and their full-scale peaks.
- `TerraKitsTest` pins the PCM24 SHA-256 of all 16 pads and of their 32 softer strikes. E1 is these 16 pads, one second apart, summed and lowered 0.56 dB as one clip, to within half a 16-bit step at every sample (0.502 at worst, from fitting the gain).
- The kit is now FRESH TAPE's **TERRA** starter, shelved under SKINS after the TEMPEST kits.

## Finish path

1. **Kit verdict:** done. Round `terra-kit-1`, KEEP in full (2026-11-14).
2. **Starter:** done. The `terra` starter, with its pads pinned by hash, exact replay from saved recipes and MPC 3/MPC 2 export read-back.
3. **Presets:** round `terra-presets-1` (2026-11-15) kept the forty presets of the first ten voices (W1–W10), pinned in `TerraPresetsTest`. BAYAN's and TUN's sets were TWEAK ("All similar", "Too similar"); their three new presets each were reworked to move to the ends of two or more macros (pitch five semitones either way, the ring half or twice as long, the contact soft or hard, the fall or knock on or off) and wait on round `terra-presets-2`. `TerraPresets` holds four presets on each of the twelve playing voices (48). Sixteen are the kept classic pads, recipe for recipe and sample for sample; the other 32 are new settings and have not been heard. The retired COMPOUND_MEMBRANE has none. TERRA leaves `PatchTypes.ROSTERLESS`, which ends the factory exemption the master plan recorded.
4. **Picker:** built, shipping with the presets. TERRA follows ETCH in the SYNTH picker and `EngineCatalog` (DRUMS), with the twelve playing voices; RANDOMISE moves a voice's defaults, and SEND files the class the classifier hears (`TerraPicker`). User presets promote through `TerraPatch.fromJsonText`, so a struck pad keeps its striker. The app side is compiled only by CI's `android-build`, and `NewEngineSynthScreenTest` checks the DJEMBE preset recall on the emulator.
5. **Genre kits:** kept. Round `terra-presets-1` kept all three kits and grooves (X1–X3, Y1–Y3), and `TerraGenreKitsTest` pins every pad and softer strike. `TerraGenreKits` adds TERRA SAHEL (West African, a G major pentatonic balafon), TERRA MAQAM (darbuka-led) and TERRA PUNJAB (dholak and tabla), each a starter under SKINS after TERRA. Every pad is a new recipe, none shared between kits or with the classic kit, and the balafon keys are measured against their names (`TerraGenreKitsTest`).
6. **Round robin and touch:** the starter's pads re-striking through saving, playback and export. Soft and medium hits get heard clearly.

The gates are recorded in [the acceptance gates](ENGINE_ACCEPTANCE_GATES.md#terra-promotion-started-2026-11-14).
