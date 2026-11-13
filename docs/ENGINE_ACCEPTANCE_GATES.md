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

On 2026-11-12 the owner confirmed hearing and approving the glockenspiel,
crotales and tubular-bell banks. Together with the previously accepted original
three voices, this closes bank listening approval for all six voices. Separate
performance-example reviews and physical playback/export checks retain their
own status.

| Gate | Implemented behavior | Remaining acceptance |
|---|---|---|
| Six melodic voices | Original five plus tubular bells, with different spectral/decay/contact shapes. | Bank listening passed: owner approval, 2026-11-12. |
| Presets | Four starting recipes per voice are available in MAKE and CLI. Original twelve accepted presets retain their factory identity. | Three bell banks approved by ear, 2026-11-12; the factory registry is unchanged. |
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

## BREAKER (rounds 1 to 3, the kit and the genre kits, 2026-11-09 to 11-13)

- **Melodic, keygroup and chord-tool gates:** not applicable. BREAKER is a drum engine.
- **Articulation:** finite hits, with no HOLD.
  - **The snare riser (round 3): on the kit, verified in software.** It is twenty fresh snare strikes accelerating over 2.4 s into the spring, with a fader push after the rig. The owner picked the tamed roll on 2026-11-11 and heard the app's render as their pick. It is A15 on the BREAKER kit: saved with its grid, played through the pad resolver and exported to MPC 3 (`BreakerKitTest`). No phone or MPC has played it.
  - **The surf tom fill:** not yet built.
- **Drum shelf: three kits, heard.** The BREAKER kit is on the shelf (2026-11-12), and Dub Tank and Trip hop joined it (2026-11-13), each built to a layout the owner approved first and heard by the owner. They are authored 16-pad kits with their own tempo, tuning, damping, backbeat roles and pad arrangement. `BreakerKitTest` checks them pad by pad against BREAKER: 7 and 5 pads are another drum, 4 and 6 are retuned, 7 and 11 are damped or opened, and only 3 in each differ in the rig alone.
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
- **The kit (2026-11-12): velocity and round robin through rendering, saving, playback and MPC 3 export, verified in software** (`BreakerKitTest`, `:shell:generateBreakerKit`).
  - **Rendering:** the shared robin deals every pad from its recipe. Each synth drum gets 3 zones of 3 takes: softer zones are re-rendered at velocity .3 and .6, and each later take is re-struck. The tambourine gets its recordings, 2 dynamic layers × 2 takes.
    - No two takes are copies. A copy measures more than 40 dB apart; the closest pair here, the kick's, is 34 dB apart.
    - No soft zone is the hard one turned down. The least change in spectral shape is 0.6 dB, on the kick (model 4).
    - **The kick's takes are close.** They are distinct strikes, but the crush evens out the force difference. On 2026-11-12 the owner heard them as "one kick played again: fine".
    - The tambourine at 3 × 3 measured as copies, and as one zone that was another turned down. It is fixed by giving it exactly its recordings.
  - **Saving: verified.** The kit reloads equal and regenerates from its saved recipes byte for byte.
  - **Playback: verified in software.** `PadHit.resolve` picks the zone by velocity and deals each zone's takes in order, then repeats them. The preview mixer cycles them too. No phone has played the kit.
  - **Export: MPC 3 verified by read-back.** The velocity ranges, slice anchors, cycle lengths and slice windows match, and the exported samples are the saved ones. **MPC 2 does not pass:** it plays each zone's first take. No MPC has loaded the kit.
  - **Sound character:** the drums, cymbals and riser are each accepted by ear. On 2026-11-12 the owner heard the kit as mid heavy. They picked model 4's open rig by ear, with DUST's filter opened and no amp speaker, and heard their snare, hat and kick through it as "better". On the open tone the owner accepted the kit (2026-11-12): the balance "sounds right now", all three pushed pads kept, the kick's takes "one kick played again: fine", and every pad softer in character with a fresh hit each time.
- **The genre kits (2026-11-13): velocity and round robin through rendering, saving, playback and MPC 3 export, verified in software** (`BreakerKitTest`, the same checks as the BREAKER kit, run on all three kits).
  - **Rendering:** every pad is dealt by the shared robin from its recipe, 3 zones × 3 re-struck takes (2 × 2 on the tambourine). No two takes are copies: the closest pair is the Steppers Kick's, 37.7 dB apart. No soft zone is the hard one turned down: the least change is the Steppers Kick's, 0.5 dB.
  - **Model 6:** a later kick or tom take lands with another beater. Without it, the round robin's start trim could cut a kick's click and leave two takes as copies (the Steppers Kick, 42.4 dB). `BreakerTest` checks every genre-kit kick and tom with the click cut. The BREAKER kit stays on model 5.
  - **Saving: verified.** Each kit reloads equal.
  - **Playback: verified in software.** No phone has played them.
  - **Export: MPC 3 verified by read-back.** MPC 2 does not pass, as for the BREAKER kit. No MPC has loaded them.
  - **Sound character, heard 2026-11-13:** the kicks hit again ("one kick played again: fine"), the High Timbale at about three semitones up (high enough, where the layout asked five), and every pad soft to hard were accepted. Both grooves were heard as too wet, and the owner picked how much echo and spring each keeps from four versions. The kits are built from the picks; only Trip hop's Second Kick keeps its touch of spring, since at a quarter its takes measured as near-copies.
  - **Open, for the owner:** the shared robin's start trim cuts a synth strike's click on any re-struck kit (docs/BREAKER.md, "Model 6").
- **The final audition (2026-11-13):** `:shell:generateBreakerAudition` renders every voice, soft to hard, every control swept, corners, edges, presets and the three kits' grooves (68 clips). It is published for the owner's closing listen ([BREAKER audition](https://claude.ai/artifact/TyimBbsBAwNeVRzxsSx3En)); the answers are not in yet. Every edge render is finite and within full scale.
- **Overall: incomplete.** The three kits are on the shelf and heard, and the final audition waits for the owner's answers. MPC 2 round robin and hardware playback are open, and the surf tom fill is not built.

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

## ETCH (round 1, 2026-11-11)

A step-line bass with a drive stage and a tempo delay into a reverb. See [ETCH](ETCH.md).
Rounds 1 to 3 were a Python audition, accepted 2026-11-12. Kotlin round K1 (2026-11-13) ports it as a prototype (`PatchTypes.PROTOTYPES`) with provisional `EtchPresets`.

- **Melodic:** open. The owner chose the six voices on 2026-11-12: SAW, SQUARE, RUBBER, WHISTLE, GRIT and DUB. All six are built in Python (round 2) and in Kotlin (K1), each with its own mechanism; every pair is at least 3.7 dB apart by third-octave spectrum, and each new voice at least 5.4 dB. RUBBER, WHISTLE, GRIT and DUB are not heard. Provisional presets only (each voice's note and line); no factory roster, keygroups or kit yet. Chord-tool compatibility is the owner's call, because the line voice is monophonic by design.
- **Articulation:** HOLD built in part: under .85 it sets the note's gate (TONAL), from .85 it plays the line as a seamless LOOP (`Keys.requireSeam` on every render). The sustained keygroup loop is not built. STRUM not applicable: not a picked or thumbed string.
- **Percussion and drum kits:** not applicable. A bass engine.
- **Velocity and round robin:**
  - **Rendering: Python harness only.** Velocity changes the tone on all six voices, not only the level: level-matched attack centroid rises from soft to hard hits on every voice (GRIT 876 → 1,102 Hz, the smallest; WHISTLE 622 → 1,734 Hz, the largest). Round robin **failed by ear** on 2026-11-12: the owner heard the four takes as the same strike (they measured 0.5 to 0.8 dB apart by take distance). Rebuilt with five audible dimensions dealt out so no two takes share a setting: after the round-3 tone the closest two takes are 1.8 to 3.1 dB apart at velocity 0.6, and no pair exceeds a 0.6 to 0.8 velocity step. Accepted by ear with the round-3 audition on 2026-11-12, in Python rendering only.
  - **Rendering in Kotlin (K1): verified.** Velocity changes the tone on all six voices (`EtchTest`; Kotlin within 2 Hz of the accepted Python on every voice). Four takes are distinct strikes reachable through `Restrike.take`, deterministic in (patch, velocity, seed, take); take 0 is the plain render.
  - **Saving: partly verified.** The patch round-trips through JSON with its note, velocity and model; a saved kit is not tested (no kit yet).
  - **Playback and export:** not yet verified (no kit; `Robin`'s round-robin path accepts ETCH through `Restrike.canRestrike`, but no ETCH chain has been played or exported).
- **Sound character: direction accepted at round 1b.** On 2026-11-12 the owner heard the corrected square ("That's better") and then the whole round-1b audition: "It all seems fine from the audition." Preserve round 1b as the baseline. This is a listen to Python renders, not evidence of Kotlin, phone or MPC playback. After round 2 the owner heard the engine as "a bit mid heavy, like dollar store headphones"; round 3 restores the top end (line B 8 kHz from -30.4 to -18.6 dB against the 500 Hz band; reference B -16.0). The owner then accepted the round-3 audition: "I'm good with evening in audition" (read as "everything"). Preserve round 3 as the baseline; this is a listen to Python renders, not evidence of Kotlin, phone or MPC playback.
- **Overall: incomplete.** The sound direction is accepted; the six voices, presets, keygroups, kit, HOLD and every saving, playback and export stage remain open, and no Kotlin exists.

## CYPHER kits and THUMP SUB (round 2, 2026-11-12)

Hip-hop drum kits in the GATECRASH shape, six built: TRAP, DRILL, BACKROOM, LOWRIDER, DUST, FLINT. See [CYPHER](CYPHER.md). The owner heard round 1 ("The triple snare thing is messy"), then round 1b ("Ok, sounds good. Make sure we clear the approval gates"), which approved TRAP and DRILL, then round 2 ("All four pass"), which approved BACKROOM, LOWRIDER, DUST and FLINT. Evidence at kit version 3, `CypherTest`, `CypherKitTest`, `ThumpSubTest`, `ThumpSubRobinTest` and `:shell:generateCypherKits`.

| Gate | Status | Evidence |
|---|---|---|
| Melodic voices, keygroups, premade kit, chord tools | Not applicable | Drum kits; SUB is a drum voice. |
| Articulation: HOLD, STRUM | Not applicable | Finite hits, no strings. |
| Percussion: authored roll or riser | Pass | Twelve authored phrases: a hat roll to 1/64 and a sub line (TRAP); the drill snare bar and a sub call (DRILL); swung hats and a break drop (BACKROOM); a laid-back groove and a big fill (LOWRIDER); two unquantized breaks (DUST); a click scatter and a slide stack (FLINT). |
| Drums: multiple distinct genre kits on the shelf | Pass; all six heard | Six kits under SKINS. `CypherTest` holds every pair to at least six different pads and a different kick and snare dry, and the prints differ (dry, a dark room, the Backroom rack, tape, a long room). Not "different effect strengths on the same kit". TRAP and DRILL approved at round 1b, the other four at round 2. The owner also kept SCUFF beside CYPHER FLINT, two Michigan kits by choice. |
| Velocity: rendering | Pass | Every hit is a different strike at the soft zone, duller (a sub: cleaner), not a quieter copy. THUMP's KICK and SUB, SKIN's struck voices, ONSET's kick, TERRA and RIVET strike at the velocity; the rest are touched. |
| Round robin: rendering | Pass | Four different takes in every zone of every pad; SUB deals five dimensions out and keeps its root within 10 cents; SKIN drums on the new kits re-strike with a force spread and a small decay and contact move. |
| Velocity and round robin: saving | Pass | Each kit reloads equal and all 1152 saved slices match their renders. A lone SUB pad's chain and recipe survive a save and regenerate the drum (`ThumpSubRobinTest`). |
| Velocity and round robin: playback | Pass in software | `PadHit.resolve` picks the zone by velocity and deals the takes in order, then repeats; chokes survive. No phone has played these kits. |
| Velocity and round robin: export | Pass for MPC 3, SFZ and DecentSampler; **fail for MPC 2** | MPC 3 read back: velocity ranges, base slices, cycle lengths, frame windows and slices match. SFZ and DecentSampler written by the generator, both cycling takes. MPC 2 keeps only each zone's first take: a format limit, stated. |
| Hardware playback (phone, MPC) | Not verified | Only the owner's devices can close this. |
| Sound character | Approved, all six kits | Round 1b approved TRAP and DRILL and round 2 approved the rest, both 2026-11-12, both from the listening page. Preserve kit version 3 as the accepted baseline. BACKROOM opens with the six accepted Backroom pads byte for byte through their rack (`CypherTest`). |
| SUB presets | Pass | Eight factory presets, the sub recipes of the approved TRAP and DRILL (`ThumpPresets`); none authored blind. |

- **THUMP SUB as a voice:** six macros, velocity and takes through `Thump.render`, `Velocity` and `Restrike`. The classifier reads a long sub as TONAL; the default reads KICK; no setting makes a LOOP (`ThumpSubTest`). Round 2 made its length its DECAY alone (velocity moves drive, click and settle), so the robin's windows never change with the touch.
- **Overall:** every software gate passes and all six kits are approved by ear; phone and MPC playback wait for the owner's devices; MPC 2 cannot carry the round robin.

## SCUFF (rounds 0 and 0b, 2026-11-12; round 1 Kotlin, round 2 and 3 kits, 2026-11-13)

A dry Michigan drum kit and its chain (GRIT, LOCKER, BARK, SLAM). See [SCUFF](SCUFF.md).
Rounds 0 and 0b are Python prototype auditions; no Kotlin exists. Round 0b answered the owner's note "more character in general" (written before listening): every voice gained a mechanism, BARK went from under 1 dB of effect to 3 to 5 dB, and every pad ends at a tail gate.

- **Melodic, keygroup and chord-tool gates:** not applicable. A drum engine.
- **Articulation:** finite hits, no HOLD. Not a picked or thumbed string.
- **Percussion: pass in software (rounds 2 and 3).** DETROIT's A16 is "Clap Riser" and FLINT's is "Rim Riser", each tightening from eighths to thirty-seconds over one bar through the bus; each kit's A15 is its own two bars through one bus. Not heard.
- **Drum shelf: pass in software (round 3), not heard.** SCUFF DETROIT (118 BPM) and SCUFF FLINT (132 BPM) are on the shelf under SKINS, sixteen pads each, three zones of four takes, two bus phrases each. They differ in pads, tunings, lengths, roles (FLINT has three rims and no low bass hit), tempo, chain setting and phrases; `ScuffKitsTest` holds every role apart by shape and FLINT's kick and hat brighter. Not a copy at other settings.
- **Velocity and round robin:**
  - **Rendering: verified in Kotlin by test (round 1).** `ScuffTest` holds that a soft and a hard hit differ in spectral shape at matched level on every core voice, that the kick clicks and drops harder and the clap loses a tap, and that four takes per voice are distinct strikes that each replay byte for byte. Python, re-measured with the corrected distance: soft against hard 2.8 to 9.7 dB; closest takes 1.9 (tick) to 4.6 dB (clap).
  - **Saving: verified (round 2).** A SCUFF patch saves velocity, take and model version and replays byte for byte through JSON (`DeterminismTest`). The kit reloads equal and all 192 saved slices match their renders through the pad resolver (`ScuffKitTest`, `:shell:generateScuffKits`).
  - **Playback: verified in software.** `PadHit.resolve` picks the zone by velocity and deals the four takes in order, then repeats. No phone has played the kit.
  - **Export: MPC 3 verified by read-back.** Velocity ranges, base slices, cycle lengths and frame windows match, and the exported PCM matches the renders. **MPC 2 does not pass:** it keeps one take per zone. No MPC has loaded the kit.
- **Sound character:** direction accepted. On 2026-11-12 the owner responded to the published round-0b audition, "Sounds good, please merge." Preserve round 0b as the accepted baseline; this is a listen to Python renders. The round-1 Kotlin port measures as the same waveform on the five deterministic voices (0.05 to 0.17 dB, correlation 0.997 to 1.000) and as another take on the four noise voices; it has not been heard.
- **Classifier:** the Kotlin kick reads PERC (its click and knock lift the 93 ms centroid past the sub-kick rule) and the crushed hat reads SNARE; `drumClassFor` files them KICK and HAT_CLOSED. Disclosed in [SCUFF](SCUFF.md), held by `ScuffTest`.
- **Overall: incomplete, every software gate in place.** Round 3 has the engine, the patch, two distinct kits on the shelf with their risers, and velocity and round robin proved through rendering, saving, playback and MPC 3 export in software. MPC 2 round robin (one take per zone), hardware playback, presets and the roster, the app picker, and the owner's listen to the Kotlin engine and both kits remain open.

## MIRRORBALL kits (sound approved 2026-11-12, hardware open)

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
    and every take is a new pattern of jingle collisions and rattle. The fitted open hats and ride re-strike all
    400 modes. The fitted shakers and clap draw fresh noise on a slightly stretched envelope. The closed hats and
    the THUMP and ONSET voices take small control moves on fresh noise, which is weaker than a re-strike and is
    disclosed.
  - **Saving: verified.** Each saved kit reloads equal, and all 576 saved slices match their renders
    (`:shell:generateMirrorballKits`, `MirrorballKitTest`).
  - **Playback: verified in software.** `PadHit.resolve` picks the zone by velocity and deals the four takes in
    order, then repeats. No phone has played these kits.
  - **Export: verified for MPC 3 by read-back.** Velocity ranges, base slices, cycle lengths and frame windows
    match, and the exported slices match their renders. **MPC 2 does not pass:** its projection keeps only the
    first take of each zone. **SFZ: waived by the owner** (2026-11-12: "I'm good with skipping sfz").
    **DecentSampler: waived by the owner** (2026-11-12: "Ok, Skip"). No MPC has loaded these kits.
- **Sound character: approved at round 2.** On 2026-11-12 the owner heard round 2 as "Much better" and chose to lock it in. Preserve round 2 (`MirrorballKits.VERSION` 3) as the accepted baseline. This is not evidence of a phone or MPC audition. The rounds so far:
  - Round 0: heard as "kind of basic and not very punchy or character rich".
  - Round 0b: the owner picked Punch, "dialed back like 10-15%".
  - Round 1b: heard as "mid heavy, like listening through dollar store headphones".
  - Round 1c: the owner picked the Open + parallel speaker and flagged seven pads.
  - Round 2: those pads are fitted to CC0 recordings
    ([audition](https://claude.ai/artifact/2BN7Peh1FUwrjvHjZZ5YbB),
    [comparison](https://claude.ai/artifact/AJSFi9tEwm26kTzCCFUY5M)).
- **Overall: incomplete.** The software evidence is in place and the sound is approved. Phone and MPC hardware
  playback remain open, and MPC 2 does not pass the round-robin export gate. The owner waived SFZ and
  DecentSampler.

## SOOT (five kits on the shelf, sound approved; hardware open)

A drum engine for grimy street boom bap from physical models. See [SOOT](SOOT.md).

- **Melodic, keygroup and chord-tool gates:** not applicable. SOOT is a drum engine.
- **Articulation:** finite hits, with no HOLD.
- **Percussion: pass for SOOT, heard.** A15, the coil roll: sixteen fresh snare strikes into one coil,
  with three zones and four takes. The owner heard all three zones on 2026-11-13: "Yes". Each sibling
  kit has its own roll on A15 (rod, rim, brush, snare), and the owner kept all four on 2026-11-13.
- **Drum shelf: five kits, pass in software.** SOOT and four siblings the owner chose on 2026-11-13
  are on the shelf (SKINS):
  - SOOT DRUMLESS, 80 BPM;
  - SOOT BASEMENT DUB, 72 BPM;
  - SOOT BRUSHED NOIR, 76 BPM;
  - SOOT WAREHOUSE INDUSTRIAL, 96 BPM.

  Each sibling has its own tempo, tuning, pad roles, groove and processing (`SootGenreKits`).
  `SootGenreKitsTest` checks the tempos and roles, and that pads within each kit stay apart. On
  2026-11-13 the owner kept 63 of the 64 sibling pads, and picked a steppers groove for Basement
  Dub's four bars (`SootGenreKits.VERSION` 2).
- **Velocity and round robin:**
  - **Rendering: verified.** Every drum takes velocity through its own contact law, so a soft zone
    is a duller strike. A take is a fresh strike.
  - **Saving: verified.** Each saved kit reloads equal. All 960 saved slices of the five kits match
    their renders (`SootKitTest`, `SootGenreKitTest`, `:shell:generateSootKit`,
    `:shell:generateSootGenreKits`).
  - **Playback: verified in software.** `PadHit.resolve` picks the zone by velocity and deals the four
    takes in order, then repeats. No phone has played the kit.
  - **Export: verified for MPC 3 by read-back.** The exported slices match their renders. **MPC 2 does
    not pass:** it keeps only the first take of each zone. SFZ and DecentSampler were not generated.
    No MPC has loaded the kit.
- **Sound character: approved at round 2.** On 2026-11-13 the owner heard the kit read back from its
  MPC 3 export and said, "All sounds fine". How they got there:
  - **Round 0:** they picked the hits.
  - **Round 1:** they accepted the kick and snare in Kotlin.
  - **Round 1b:** they kept the alternates and approved the kit.
  - **Round 2:**
    - the three re-voiced snares heard as different drums;
    - the roll and the loop point accepted;
    - the four bars moved to the heavier dictaphone (`SootKits.VERSION` 3).

  Preserve version 3 as the baseline. This is not evidence of a phone or MPC audition.
- **Overall: incomplete.** The sound of all five kits is approved, and the software evidence is in
  place for all of them. Still open: phone and MPC hardware playback.

  MPC 2 does not pass the round-robin export gate.

## MURMUR round robin (V2 instrument, 2026-11-13)

MURMUR's selected, kept and round-robin work came back from the owner's recovery snapshot
(source 53a409bc) onto this branch, MURMUR files only. See
[MURMUR_RR_CONTRAST_V2.md](MURMUR_RR_CONTRAST_V2.md). This entry covers round robin for the
accepted C2 pluck. The other gates keep the scope earlier MURMUR work recorded.

- **Velocity:** unchanged. Three native touches (.35, .65, 1) render separately. The owner's
  C2 touch verdict was KEEP.
- **Round robin:**
  - **Rendering: verified.** V2 takes are distinct native strikes: two FM modulator start
    phases and the transient index and contact length vary, and pitch and level do not.
    Take 0 is the accepted sound exactly.
  - **Saving: verified.** A V2 bank saves, admits to the shelf and reloads with its policy at
    every level. V1 banks keep their exact version-1 format. Neither reads as the other.
  - **Playback: verified in software.** The saved-sample player deals takes 0 to 3 in order
    and wraps back to 0. Through it, V2's closest pair of takes is .039 apart by spectrum,
    against .0020 for V1's furthest pair. The app's native round-robin player and KEYS
    screen are ported (native host tests pass); no phone has played a bank.
  - **Export: partial.** Each take exports as a fixed-take MPC program; an MPC does not
    rotate them.
- **Sound character: KEEP for V2 (2026-11-13).** On the Listening Desk (round `murmur-rr-1`)
  the owner kept V2's repeated C2s and its four takes heard one at a time. V1's REVISIT
  stays on record for V1. This is a listen to saved-player renders, not to a phone or an MPC.
- **Overall: incomplete.** Phone playback and MPC rotation remain open.

## ALLOY (MATERIAL body choices ported from the recovery backup, 2026-11-13)

An attack-driven four-operator phase-modulation instrument with six voices. See [ALLOY](ALLOY.md)
and [the MATERIAL response](alloy-material/IMPLEMENTATION.md).

This port brings back the optional MATERIAL body response: Rounded Body (BASS), Prism Body (KEY)
and Velvet Body (SOFT) join the picker and CLI after each voice's four factory presets. Below
MATERIAL .5 each blends toward an owner-approved body; at .5 and above it is the factory preset
exactly. The 24 factory presets and every old save are unchanged.

| Gate | Status | Evidence |
|---|---|---|
| Six melodic voices | Pass | BASS, KEY, PLUCK, BRASS, STRING and SOFT. |
| Presets | Pass | 24 factory presets, four per voice, plus the three opt-in body choices. |
| Playable keygroups | Not yet verified | No ALLOY keygroup has been played on a phone or MPC. |
| Premade kit | Pass in software | ALLOY starter kit; its A15/A16 pads are settled STRING/SOFT loops. The body choices are not on it. |
| Chord-tool compatibility | Not yet verified | No chord-tool evidence is recorded for ALLOY. |
| Articulation: HOLD | Partial | Settled HOLD loops with seam checks; indefinite HOLD on the MPC needs whole-sample looping turned on by hand. STRUM: not applicable, not a picked or thumbed string. |
| Percussion, drum kits | Not applicable | A melodic engine. |
| Velocity: rendering | Pass | Velocity is native: it re-renders the strike. A body choice keeps its response at every velocity (`AlloyMaterialResponseTest`). |
| Body response: rendering and saving | Pass in software | All 36 approved snapshots replay with their PCM24 fingerprints. The response survives edits, user-preset save and promotion, and pad-recipe reopen (`AlloyMaterialResponseTest`, `AlloyMaterialPickerTest`, `AlloyMaterialIntegrationTest`). |
| Round robin | Not yet verified | ALLOY has no re-strike (`Restrike.canRestrike` is false), so no round robin exists through rendering, saving, playback or export. |
| Hardware playback | Not verified | No phone or MPC has played the body choices. |

- **Sound character:** the owner heard the role comparison, reported that it was much better and
  authorized integration of the 24-preset bank ([ALLOY](ALLOY.md)). The body study for Rounded Plug,
  Long Prism and Velvet Rise was approved on 2026-11-07 as "New body is good", as recorded in the
  backup. That approval covers those three roles' endpoints and their exact original midpoint only.
- **Overall: incomplete.** The other fifteen roles have no body response, and keygroups, chord
  tools, round robin and hardware playback remain open.

## TREFOIL (held instrument sheet ported from the recovery backup, 2026-11-13)

A three-body resonator: five voices (WEAVE, HOLLOW, WIRE, TANGLE, TRACE) with finite model-two
studies and an ordered held playback. See [TREFOIL](TREFOIL.md) and [held playback](TREFOIL_HELD.md).

This port brings one change back from the recovery backup: MAKE INSTRUMENT's sheet for a held
TREFOIL patch now offers the same press-and-release INSTRUMENT AUDITION as the held source, in
place of a one-shot PREVIEW (`TrefoilHeldInstrumentSheetTest`). The renderer, presets and kit are
unchanged.

| Gate | Status | Evidence |
|---|---|---|
| Six melodic voices | **Fail: five of six** | WEAVE, HOLLOW, WIRE, TANGLE and TRACE. No sixth voice is designed. |
| Presets | Pass | Twelve model-two presets across all five voices (`TrefoilPresets`). |
| Playable keygroups | Partial | The held trial exports three native roots with loop metadata; finite notes play from the pads. No phone or MPC check of the held keygroup is recorded. |
| Premade kit | Pass in software | TREFOIL starter: the twelve presets on C4/Eb4/F4/G4 and four Low Weave pads, each at three touch layers (`TrefoilKits`). |
| Chord-tool compatibility | Not yet verified | No chord-tool evidence is recorded for TREFOIL. |
| Articulation: HOLD | Partial | Ordered held playback is built (`TrefoilHeld`); finite HOLD stays zero by design. Held MPC listening is still open. STRUM: not applicable, not a picked or thumbed string. |
| Percussion, drum kits | Not applicable | A melodic resonator. |
| Velocity: rendering | Pass | Velocity re-renders the contact (`Velocity`), and the kit's soft zones are native renders at .3 and .6, not the hard sample turned down. The held trial has one authored sample per root; key velocity only scales it. |
| Round robin | Not yet verified | TREFOIL has no re-strike (`Restrike.canRestrike` is false), so no round robin exists through rendering, saving, playback or export. |
| Hardware playback | Partial | Finite bank: owner hardware acceptance recorded (below). The held trial and the INSTRUMENT AUDITION sheet have not been played on a phone or MPC; the instrumentation test has not been run on a device here. |

- **Sound character, as recorded in [TREFOIL](TREFOIL.md):** Josh described the revised model-two
  audition as "Sounds pretty good" and authorized the playable-bank round; owner feedback on the
  playable bank was positive on 2026-10-26. On 2026-11-01 Josh reported, "Hardware is fine proceed
  as recommended." That records finite-bank hardware acceptance; device models and firmware were
  not supplied. The owner accepted the revised Python HOLD proof and authorized its Kotlin port on
  2026-11-07.
- **Overall: incomplete.** The voice count, chord tools, round robin and held hardware listening
  remain open.

## APSIS (GRAIN Lower texture ported from the recovery backup, 2026-11-13)

An orbital modal instrument with three voices: PASSAGE, GRAIN and ANCHOR. See [APSIS](APSIS.md),
[GRAIN and ANCHOR](APSIS-POPULATIONS.md) and [the acceptance bank](APSIS-ACCEPTANCE.md).

This port brings back GRAIN model 4, the owner-selected Lower texture balance: material excitation
is halved before the modal bank, and root forcing, contact timing and leveling are unchanged. New
GRAIN patches and GRAIN's three factory presets now use model 4. Saved model-3 GRAIN recipes, and
recipes with no saved model, keep model 3 and their original audio. PASSAGE and ANCHOR stay on
model 3. `ApsisLowerTextureTest` matches native PCM24 hashes against twelve frozen audition
references (six model 3, six Lower texture), now a text fixture in
`synth/src/test/resources/apsis-lower-texture`.

| Gate | Status | Evidence |
|---|---|---|
| Six melodic voices | **Fail: three of six** | PASSAGE, GRAIN and ANCHOR. TETHER, VESSEL and CORONA remain Python listening prototypes. |
| Presets | Pass | Twelve: six PASSAGE recipes and three each for GRAIN and ANCHOR (`ApsisPresets`). |
| Playable keygroups | Not yet verified | Dedicated keygroup export is recorded as later work in [APSIS](APSIS.md); this port adds none. |
| Premade kit | Partial | A sixteen-pad PASSAGE musical bank (`SynthKits.apsis`, exported by `:synth:generateApsisKit`) and the download-only GRAIN/ANCHOR acceptance bank exist. Neither is on the starter shelf. |
| Chord-tool compatibility | Not yet verified | No chord-tool evidence is recorded for APSIS. |
| Articulation: HOLD | Partial | HOLD ≥ .99 renders a settled loop; held pads are gated full-sample loops and held previews repeat until STOP. Physical phone/MPC listening of the joins is open. STRUM: not applicable, not a picked or thumbed string. |
| Percussion, drum kits | Not applicable | A melodic engine. |
| Velocity: rendering | Pass | Native velocity changes the excitation spectrum before output leveling. Model 4 replays identically through native touch, edits and pad recipes at .35, .65 and 1 (`ApsisLowerTextureTest`). The hardware's velocity-to-amplitude curve is unmeasured. |
| Saved audio | Pass in software | Explicit models replay their original sound; GRAIN accepts 3 and 4, ANCHOR 3, PASSAGE 1 to 3 (`ApsisLowerTextureTest`, `ApsisIntegrationTest`). |
| Round robin | Not yet verified | APSIS has no re-strike (`Restrike.canRestrike` is false), so no round robin exists through rendering, saving, playback or export. |
| Hardware playback | Not verified | The acceptance bank's MPC checklist has not been reported back; phone render latency is unmeasured. |

- **Sound character, as recorded in [GRAIN and ANCHOR](APSIS-POPULATIONS.md):** "the owner selected
  the Python GRAIN/ANCHOR identities". "On 8 November the owner selected Lower texture for Grain
  Passage, Scattered Grains and Held Grains." The soft, hard and C3 engineering cards "have no
  separate owner verdict". [APSIS](APSIS.md) records that "The owner found model 2 more
  distinctive; the musical bank still awaits the owner's listening decision."
- **Overall: incomplete.** The voice count, keygroups, chord tools, round robin and hardware
  playback remain open.
