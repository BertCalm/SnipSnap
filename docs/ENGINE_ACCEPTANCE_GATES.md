# Engine acceptance gates

Owner-specified gates, recorded 2026-11-09. Apply these when designing, reviewing and declaring an engine complete. These requirements supplement the Luthier conventions; this owner's instruction takes precedence where older guidance differs.

| Engine / instrument type | Required gate |
| --- | --- |
| Melodic | At least six distinct musical voices with presets, playable keygroups, a premade kit and chord-tool compatibility. |
| Articulation | Appropriate HOLD. Picked/thumbed string voices use STRUM, with the owner's intended-character exception for distinctive repeats. |
| Percussion | Authored roll/riser where appropriate; individual voices may remain finite. |
| Drums | Multiple distinct genre kits on the shelf. |
| All instruments | Working velocity levels and round robin through rendering, saving, playback and export. |

## Evidence required

Record the applicable gates and their status for each engine. Use pass, fail, not yet verified, or not applicable with a concrete reason. Do not treat an exception as permission to omit unrelated requirements.

Velocity evidence must distinguish changes in recorded or synthesized strike character from volume-only changes. Round-robin evidence must demonstrate distinct strikes, selection/repeat behavior, preservation of the variant pool and settings across save/load, correct playback selection, and exported variants and ordering/selection behavior. State platform or target-format limitations directly. Repeated copies of one strike are not round robin.

Genre kits must be authored musical choices with distinct roles, balance and character; different effect strengths on the same kit are not sufficient evidence of distinct genre kits.

## BACKWASH acoustic integration (2026-11-09)

- Melodic/keygroup/chord-tool gate: not applicable to this drum engine.
- Articulation: finite hits remain appropriate. Authored tambourine ROLL and RISER now cycle recorded hit sources; both are saved recipe articulations and appear on each genre kit.
- Drum shelf: BACKWASH SOUL and BACKWASH PSYCH are authored 16-pad kits. They differ in tuning, damping, backbeat roles and percussion arrangement as well as effects. Owner listening assessment remains pending.
- Velocity/round robin: model 5 selects recorded dynamic groups and native alternate strikes. Cross-stick groups four adjacent velocity recordings into each of four groups (not dedicated recorded round robins); cowbell has 3 × 7, ride bell 3 × 3, tambourine 2 × 2. Procedural core drums retain generated variations.
- Automated integration evidence is produced by `:shell:generateBackwashAcousticKits`: PCM replay from persisted recipes, save/load of all 32 grids, offline playback cycling, MPC 3 cycle metadata and MPC 2 read-back. SFZ and DecentSampler exports are also generated.
- **Overall gate remains incomplete:** Android/MPC hardware playback has not been exercised. MPC 2 exports only the first take of each velocity zone and therefore does not pass the round-robin export gate. Full CI results must be reported separately from focused checks.
- Sound character: the acoustic integration and processed comparisons require the owner's audition; no automated test establishes musical approval.

Old model-3 and model-4 recipes retain their original renderers. Source selection, build success and a website demo must not be reported as all engine gates passing.

## BREAKER (round 1, 2026-11-09)

- **Melodic, keygroup and chord-tool gates:** not applicable. BREAKER is a drum engine.
- **Articulation:** finite hits, with no HOLD. The authored snare roll and riser, and the surf tom fill, are not yet built.
- **Drum shelf:** not yet built. The owner picked Dub Tank and Trip hop on 2026-11-09 to join the BREAKER kit. Each will be an authored 16-pad kit with its own tuning, damping, backbeat roles and pad arrangement. A copy of the BREAKER kit at other control settings would not pass this gate.
- **Velocity and round robin:**
  - **Rendering: verified.** Velocity re-strikes the SKIN source, which changes its character as well as its level. `Restrike` takes are fresh, deterministic strikes (`BreakerTest`, `DeterminismTest`).
  - **Saving: partly verified.** A patch round-trips through JSON with its model. A saved kit has not been tested yet.
  - **Playback and export: not yet verified.**
- **Sound character:** the owner chose the round-0 settings by ear.
  - **Round 1:** they heard the app's snare, its takes and a half-strength hit as theirs, and the kick as "close, but not it".
  - **Round 1b (model 2):** the kick gets back the tank and slap time its pick was heard with. Measured, it is within 1.1 dB of the pick in every band, except up to 2.2 dB at 450 Hz. On 2026-11-10 the owner heard it as the same kick, the snare as still theirs, and the re-fit kick's takes and half-strength hit as one drum played softer.
- **Round 2 (2026-11-10):** ten more drums: hats, cross stick, shaker, tambourine, three toms and ride.
  - **Velocity: verified in rendering.** Every drum's 40% hit differs from its full hit in character, not only in level, because every render is levelled. The hats, ride, shaker and stick measurably darken.
  - **Round robin: verified in rendering.** Each take is a fresh strike (re-seeded SKIN noise, or the tambourine's two recorded takes).
  - **Saving, playback and export:** not yet verified.
  - **Sound character, heard 2026-11-10:** the hats, stick (short slap), tambourine, shaker and toms accepted, and the repeat and 40% hits too. The ride was heard as a crash and is re-voiced at round 3.
- **Overall: incomplete.** This is a prototype with twelve voices and no kit yet.
