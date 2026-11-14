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
- The round therefore plays them as they are, as clip T4. It also offers a labelled alternative, T3: the same groove with each of the six swapped for a picked pad.
- None of the six changes without a verdict.

## Finish path

1. **Kit verdict:** Listening Desk round `terra-kit-1`. This is the first time the owner hears the classic kit as a whole, and in the app's own renders.
2. **Starter:** on a KEEP, the kit becomes TERRA's first starter on the shelf, with its pads pinned by hash, exact replay from saved recipes and MPC 3/MPC 2 export read-back.
3. **Presets:** a factory preset set from the kept pads and the owner's picks.
4. **Picker:** a SYNTH picker entry for TERRA's voices and presets.
5. **Genre kits:** more sixteen-pad kits from the kept voices, as TEMPEST did.
6. **Round robin and touch:** the starter's pads re-striking through saving, playback and export. Soft and medium hits get heard clearly.

The gates are recorded in [the acceptance gates](ENGINE_ACCEPTANCE_GATES.md#terra-promotion-started-2026-11-14).
