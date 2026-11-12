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

## BACKWASH acoustic integration (updated 2026-11-10)

- Melodic/keygroup/chord-tool gate: not applicable to this drum engine.
- Articulation: finite hits remain appropriate. Authored tambourine ROLL and RISER now cycle recorded hit sources; both are saved recipe articulations and appear on each genre kit.
- Drum shelf: BACKWASH SOUL and BACKWASH PSYCH are authored 16-pad kits. They differ in tuning, damping, backbeat roles and percussion arrangement as well as effects. The owner approved the published sound direction on 2026-11-10.
- Velocity/round robin: model 5 selects recorded dynamic groups and native alternate strikes. Cross-stick groups four adjacent velocity recordings into each of four groups (not dedicated recorded round robins); cowbell has 3 × 7, ride bell 3 × 3, tambourine 2 × 2. Procedural core drums retain generated variations.
- Automated integration evidence is produced by `:shell:generateBackwashAcousticKits`: PCM replay from persisted recipes, save/load of all 32 grids, offline playback cycling, MPC 3 cycle metadata and MPC 2 read-back. SFZ and DecentSampler exports are also generated.
- **Overall gate remains incomplete:** Android/MPC hardware playback has not been exercised. MPC 2 exports only the first take of each velocity zone and therefore does not pass the round-robin export gate. The repository emulator suite passes on the integrated build; this does not establish physical phone audio behavior or MPC playback.
- Sound character: approved direction. On 2026-11-10 the owner responded to the published acoustic integration, “Sounds good, proceed.” Preserve this sound as the accepted baseline; this is not evidence of a physical MPC audition.
- Repository regression checks: **pass at `4cfe915f6bb84e1b755614277989b1001cb5521a`**, which contains merged BACKWASH PR #1009. All JVM shards, Android build and native tests passed in [CI run 42700786733](https://github.com/BertCalm/SnipSnap/actions/runs/42700786733); the full emulator suite passed in [run 42700786974](https://github.com/BertCalm/SnipSnap/actions/runs/42700786974). These are specific-commit results, not a claim about later unrelated changes.

Old model-3 and model-4 recipes retain their original renderers. Source selection, build success and a website demo must not be reported as all engine gates passing.

Prototype rounds with fewer voices or provisional listening studies remain in
progress until the applicable completion gates have evidence. There is no fixed
preset count beyond voices having presets.

## HALOBAR status at the crotales build

Reviewed against source `cadcad2c26a020607df06c27a016b4781f73c2b5`.

| Gate | Current evidence | Status |
|---|---|---|
| Six melodic voices | Vibraphone, marimba, xylophone, glockenspiel and crotales are built. Tubular bells remain planned. | Open: five of six |
| Presets | Twelve accepted recipes cover the original three voices. Glockenspiel and crotales each have four provisional studies. | Open: complete six-voice preset coverage |
| Playable keygroups | Existing deliveries are a sixteen-pad starter and separate study kits. They do not establish playable keygroups. | Open: implementation and workflow verification |
| Premade kit | The original three-voice starter is on the shelf; the two new voices have separate study kits. | Evidence exists; review final engine coverage |
| Chord-tool compatibility | Not established by the current audition, kit or build checks. | Open: verification |
| Appropriate HOLD | Finite decay is implemented and tested; the top is a long note rather than a seamless loop. | Record the final articulation decision per voice |
| Velocity and round robin | Native velocity rendering, recipe replay and exported study-sample evidence exist. No complete velocity/RR demonstration across rendering, saving, playback and export has been recorded. | Open: full workflow evidence |

Tubular bells would reach the minimum voice count. It would not, by itself,
close the remaining engine-completion gates. See [HALOBAR](HALOBAR.md) for the
sound design and the existing build evidence.


## HALOBAR six-voice build

See [implementation and evidence](HALOBAR_SIX_VOICES.md). Software validation
for this build is recorded with the delivered artifacts; this table keeps
unverified completion gates explicit.

| Gate | Implemented behavior | Remaining acceptance |
|---|---|---|
| Six melodic voices | Original five plus tubular bells, with different spectral/decay/contact shapes. | Owner listening for the new bell voices. |
| Presets | Four starting recipes per voice are available in MAKE and CLI. Original twelve accepted presets retain their factory identity. | New bell banks remain provisional. |
| Playable keygroups | Six finite instruments, sampled roots, two touch levels and three strikes per touch. Saved phone sidecars retain all captures. | On-device playability and MPC loading. |
| Premade kit | Separate HALOBAR SIX STUDY shelf kit, six dry/dub pairs and four tubular answers. | Owner listening. |
| Chord tools | Existing diatonic, modal and borrowed voicings can be mapped into every voice's range. Polyphonic keygroups and exported-sample chord auditions. | Owner listening and device playback. |
| Articulation | Finite HOLD, retained onset, key-up fade. No picked/thumbed strings. | Owner articulation review; STRUM does not apply. |
| Percussion | Authored finite accelerating rolls and pentatonic risers, with event scores. Individual voices remain finite. | Owner review of the authored examples. |
| Velocity/RR rendering | Native velocity changes contact; repeat identities vary contact, modal balance, force and noise without detuning. | Device render-time measurements. |
| Velocity/RR saving/playback | Saved PCM captures and version-4 sidecars retain two touch windows and three cycling takes; phone and reference player share selection. | Physical device execution. |
| Velocity/RR export | All captures travel with the phone instrument. MPC keygroups export velocity levels using each level's first take. Pad grid exports keep the existing corpus-derived descriptors. | **Combined MPC keygroup velocity × round robin is open**, as is the existing Slice Motion WAV-map/hardware bench gate. |
| Genre drum kits | HALOBAR is melodic percussion, not a drum engine. | Not applicable. |

The engine remains in progress. Voice count, green tests and export file
presence do not close the outstanding gates above.

## BREAKER (rounds 1 to 3, 2026-11-09 to 11-11)

- **Melodic, keygroup and chord-tool gates:** not applicable. BREAKER is a drum engine.
- **Articulation:** finite hits, with no HOLD.
  - **The snare riser (round 3): built in rendering, chosen by ear.** The owner picked the tamed roll in the Python harness on 2026-11-11. It is twenty fresh snare strikes accelerating over 2.4 s into the spring, with a fader push after the rig. Its Kotlin render has not been heard, played from a pad, saved in a kit or exported. Until then, it does not satisfy the percussion gate's roll or riser.
  - **The surf tom fill:** not yet built.
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
- **Round 3 (2026-11-11, model 3):** the ride re-voiced, and the crash and snare riser added.
  - **The cymbals:** a modal synth of 400 and 418 resonances, fitted to CC0 recordings that do not ship. Dry by default, with every control a true bypass at 0.
  - **Velocity: verified in rendering, measured.** A cymbal's soft hit follows the difference measured between a soft and a hard recording. At matched loudness, a 40% ride gains 8.9 dB at 250 Hz and a 40% crash 6.4 dB at 500 Hz. Neither is simply darker; the change in spectral shape is what is asserted. The riser's strikes take SKIN's contact law.
  - **Round robin: verified in rendering.** A cymbal take re-strikes every mode: a fresh phase, up to 0.3 Hz of detune, a 1.6 dB rms level scatter, and a fresh stick. A riser take re-strikes all twenty snares.
  - **Saving: partly verified.** Patches round-trip through JSON with model 3, and model 2's ride still replays. A saved kit has not been tested.
  - **Playback and export:** not yet verified.
  - **Sound character:** the owner picked the tight cymbals (heard dry) and the tamed riser in the Python harness. The Kotlin renders wait for a confirming listen.
- **Overall: incomplete.** This is a prototype with fourteen voices and no kit yet. The kit, the two genre kits and velocity and round robin through saving, playback and export are still open.

## GATECRASH kits (sound approved 2026-11-11, hardware open)

A kit family of three 1980s gated drum kits: Stone Room, Machine Pop and Electro Funk. See [GATECRASH](GATECRASH.md).

- **Melodic, keygroup and chord-tool gates:** not applicable. These are drum kits.
- **Articulation:** finite hits, with no HOLD. Each kit has an authored fill on A15 and an accelerating build on A16. The build is the roll and riser for this family.
- **Drum shelf: pass in software, not heard.** Three authored 16-pad kits are on the shelf under SKINS. They differ in source drums, tunings, pad roles (six, four and three toms), tempo and the mechanism that makes the space, not only in effect strength. `GatecrashTest` checks each of those differences. The owner chose the three genres on 2026-11-10.
- **Velocity and round robin:**
  - **Rendering: verified.** Three zones of four takes per pad. The closest pair of takes is the stone kick's at the soft zone (41 dB apart), which is weak. All 42 hits measure duller at the soft zone, and the toms at least 1.5 dB drier for their level, so a zone is not a volume change. Every take differs from the others in its zone. SKIN's kick, snare and toms are fresh strikes. THUMP, ONSET and SKIN's hats and shaker cannot be re-struck from outside, so their takes are small control moves on the same noise. That is weaker than a re-strike and is disclosed.
  - **Saving: verified.** A saved kit reloads equal, and all 576 saved slices match their renders (`:shell:generateGatecrashKits`, `GatecrashKitTest`).
  - **Playback: verified in software.** `PadHit.resolve`, the resolver the app's pad engine calls, picks the zone by velocity and deals the four takes in order, then repeats. No phone has played these kits.
  - **Export: verified for MPC 3 by read-back.** The exported track's velocity ranges, base slices, cycle lengths and frame windows match, and the exported slices match their renders. **MPC 2 does not pass:** its projection keeps only the first take of each zone. SFZ and DecentSampler exports were not generated. No MPC has loaded these kits.
- **Sound character: approved at round 4.** On 2026-11-11 the owner heard round 4 on the published listening page and said, "Everything sounds good". Preserve round 4 (`GatecrashKits.VERSION` 4) as the accepted baseline. This is not evidence of a phone or MPC audition. The path there: round 1 rejected ("harsh", "more like loud static"; its rooms carried 14 to 20 dB more energy than the drums), round 2 corrected ("80s drums round off at the end rather than cut abruptly"), round 3 corrected ("we are missing some brightness").
- **Overall: incomplete.** The software evidence is in place and the sound is approved. Phone playback and MPC hardware playback remain open, and MPC 2 does not pass the round-robin export gate.

## MIRRORBALL kits (round 1b, 2026-11-12, not yet heard)

A kit family of three 1970s disco kits: Philly Strings, Loft Party and Munich Machine. See [MIRRORBALL](MIRRORBALL.md).

- **Melodic, keygroup and chord-tool gates:** not applicable. These are drum kits.
- **Articulation:** finite hits, with no HOLD. Each kit has an authored fill on A15 and an accelerating build on
  A16. The build is the roll and riser for this family.
- **Drum shelf: pass in software, not heard.** Three authored 16-pad kits are on the shelf under SKINS. They
  differ in source drums (kick and snare measured apart, dry), tunings, pad roles (2, 0 and 4 toms), tempo, tape
  and amp, not only in effect strength. `MirrorballTest` checks each of those differences.
- **Velocity and round robin:**
  - **Rendering: verified.** Three zones of four takes per pad. Every soft hit is a different strike and duller.
    A softer hit drives the tape, compressor and amp less, so it is squeezed less (measured on each main snare).
    Kicks, snares and toms are fresh strikes, each kick with its own seeded beater. The tambourine is synthesized,
    and every take is a new pattern of jingle collisions. Hats, shakers and the THUMP and ONSET voices take small
    control moves on fresh noise, which is weaker than a re-strike and is disclosed.
  - **Saving: verified.** Each saved kit reloads equal, and all 576 saved slices match their renders
    (`:shell:generateMirrorballKits`, `MirrorballKitTest`).
  - **Playback: verified in software.** `PadHit.resolve` picks the zone by velocity and deals the four takes in
    order, then repeats. No phone has played these kits.
  - **Export: verified for MPC 3 by read-back.** Velocity ranges, base slices, cycle lengths and frame windows
    match, and the exported slices match their renders. **MPC 2 does not pass:** its projection keeps only the
    first take of each zone. SFZ and DecentSampler exports were not generated. No MPC has loaded these kits.
- **Sound character: not yet heard at round 1b.** Round 0 was heard as "kind of basic and not very punchy or
  character rich". From round 0b the owner picked Punch, "dialed back like 10-15%", and asked for a synthesized
  tambourine. Round 1b is both: [audition page](https://claude.ai/artifact/2BN7Peh1FUwrjvHjZZ5YbB).
- **Overall: incomplete.** The software evidence is in place. The owner's listen, phone and MPC hardware playback
  remain open, and MPC 2 does not pass the round-robin export gate.
