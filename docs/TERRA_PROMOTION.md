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
3. **Presets:** kept, pass in software. Round `terra-presets-1` (2026-11-15) kept the forty presets of the first ten voices (W1–W10). BAYAN's and TUN's sets were TWEAK ("All similar", "Too similar"); their three new presets each were reworked to move to the ends of two or more macros (pitch five semitones either way, the ring half or twice as long, the contact soft or hard, the fall or knock on or off). Round `terra-presets-2` (2026-11-15) kept both reworked sets with no note: Z2, BAYAN (Bayan Drag, Bayan Deep, Bayan Slap, Bayan Fall Hi), and Z4, TUN (Tabla Tun, Tun Deep, Tun Muted, Tun Pure); Z1 and Z3 were the old sets, played for reference. `TerraPresetsTest` pins the PCM24 SHA-256 of all 48 presets. A git-excluded scratch test rebuilt Z2 and Z4 (the four presets 1.5 s apart, summed and lowered 0.09 dB to a 0.94 peak) and found both byte-identical to the desk. `TerraPresets` holds four presets on each of the twelve playing voices (48). Sixteen are the kept classic pads, recipe for recipe and sample for sample. The retired COMPOUND_MEMBRANE has none. TERRA leaves `PatchTypes.ROSTERLESS`, which ends the factory exemption the master plan recorded.
4. **Picker:** built, shipping with the presets; not yet verified on a device. TERRA follows CLUTTER in the SYNTH picker and `EngineCatalog` (DRUMS), before CAIRN, with the twelve playing voices; RANDOMISE moves a voice's defaults, and SEND files the class the classifier hears (`TerraPicker`). User presets promote through `TerraPatch.fromJsonText`, so a struck pad keeps its striker. The app side is compiled only by CI's `android-build`, and `NewEngineSynthScreenTest` checks the DJEMBE preset recall on the emulator.
5. **Genre kits:** kept, pass in software. Round `terra-presets-1` kept all three kits and grooves (X1–X3, Y1–Y3), and `TerraGenreKitsTest` pins every pad and softer strike. `TerraGenreKits` adds TERRA SAHEL (West African, a G major pentatonic balafon), TERRA MAQAM (darbuka-led) and TERRA PUNJAB (dholak and tabla), each a starter under SKINS after TERRA. Every pad is a new recipe, none shared between kits or with the classic kit, and the balafon keys are measured against their names (`TerraGenreKitsTest`).
6. **Round robin and touch:** built for the classic kit, awaiting the owner's listen (round `terra-rr-1`, below). The genre kits follow if the owner keeps it. Soft and medium hits get heard clearly in the same round (J2).

## Round robin, opt-in (2026-11-16, round terra-rr-1, not yet heard)

TERRA ROUND ROBIN (`terra-round-robin`, `TerraRoundRobinKits`) sits on the shelf right after the TERRA starter, which is unchanged. It has the classic kit's sixteen pads and three touches, with four takes in every touch zone, dealt in order by the phone, the groove preview and the MPC 3 export.

- **What a take is.** A take is a fresh render of the same recipe and touch, the drum struck again. What changes is what a player's next stroke changes on that drum:
  - **Every voice: fresh noise.** The renderer's take (`Terra.render(..., take)`) re-draws every noise TERRA draws: the palm's or stick's grit, the slap's smack, the tek's snap, the tin's tick, the doum's thud, the cajón's wires, the buzz, the clack. The drum itself (modes, split pairs, partials, bends, knock) stays the same.
  - **The strike point moves**, up to ±0.04 on POS (`Restrike`'s spread), on every voice that has one: udu, low cajón, the agogôs, balafon, cajón slap, djembe slap, dumbek tek.
  - **A slightly lighter landing** where the voice's own velocity law keeps it the same stroke: up to 15 % under the touch on udu, low cajón, agogôs and balafon; up to 7.5 % on the cajón slap and the doum. The doum has no strike point and its thud is too quiet for fresh noise alone to be heard. The slap, tek and tin keep their touch: their smack, snap and tick grow as v^1.5, and in a first probe a landing up to 15 % lighter moved them up to 4.8 dB off their shape, which reads as another stroke.
  - **The fitted drums' wash keeps its levels.** Djembe bass and open, dholak, bayan and tun vary only by their residual wash. A plain re-draw moves each short band's energy by several dB: up to 4 dB off the kept hit's shape, and up to five times the energy time on the djembe bass; most takes of the tun and the djembe open failed the screen. Every take of those five (`Terra.renderFittedTake`) holds each band at the energy the kept hit realised, so only the wash's grain is new. `Terra.render`'s own later takes, which re-struck chains and other kits use, are unchanged.
- **Take 1 is the kept hit.** Every zone's first take is the kept kit's sample, reused from `TerraKits.classic()`. `TerraRoundRobinKitsTest` checks it against the PCM24 hashes `TerraKitsTest` pins, and checks that the take path renders the same bytes at take 0.
- **Which renders.** No one renderer take passed the screen on all sixteen pads, so each pad has its own three (`TerraRoundRobinKits.STRIKES`). The screen tried renderer takes 1 to 47 on every pad's hard hit and kept those that start within 1 ms of the kept hit, keep its spectral shape within 2 dB (and within 0.35 of the distance to the nearest other pad), have 90 % of their energy by 0.7 to 1.4 times the kept hit's time, and sit at least 0.2 dB from it phase-blind (band levels in 23 ms frames). Of each pad's first twelve passing takes, it dealt the three whose set with the kept hit is furthest apart. The Tabla Tun had the fewest passing takes, 9 of 47; most failures were fitted-drum washes off in shape and udu or agogô landings too close to the kept hit.
- **Measured, not heard.** Hard takes start within 0.07 ms of the kept hit and are 0.0 to 2.0 dB from its spectral shape, against 3.5 to 14.4 dB to the nearest other pad. Their 90 %-energy times are 0.86 to 1.40 times the kept hit's. Phase-blind, they sit 0.25 to 0.41 dB from the kept hit on the udu, 0.3 to 0.9 dB on the low cajón, doum, djembe slap and agogôs, about 1 to 2 dB on the cajón slap, tin, tek and balafon, and 2 to 4 dB on the five fitted drums, whose takes vary most. The softer zones vary less (the soft tin's closest pair is 0.03 dB). These numbers describe the takes; whether each pad still sounds like itself struck again is the owner's listen.
- **Saved and exported.** Grid pads carry no `PadRecipe`, because a one-hit recipe cannot regenerate four takes. Each pad's source keeps the kept recipe JSON and its renderer takes. The MPC 3 export carries every take. MPC 2 keeps each zone's first take (the recorded [format limit](ENGINE_ACCEPTANCE_GATES.md#mpc-2-and-round-robin-owner-decision-2026-11-14)), and the export screen says so (`TerraRoundRobinStarterTest`).
- **Pinned.** `TerraRoundRobinPinsTest` pins all 192 takes (16 pads, three touches, four takes) by SHA-256 of their float samples, in `terra/round-robin-v1.sha256`, as rendered for the round.
- **Open.** Not yet heard. Not played on a phone or loaded on an MPC. The phone's player (`PadEngine`) counts takes per pad rather than per touch zone (the known SC-02 issue), so changing touch between hits can skip a take. Round-robin pads carry no `PadRecipe`.

The gates are recorded in [the acceptance gates](ENGINE_ACCEPTANCE_GATES.md#terra-promotion-started-2026-11-14).
