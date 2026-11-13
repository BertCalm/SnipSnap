# Vocabulary ledger

**Status: owner has ruled on Section F (2026-11-09). Names in Sections A, B and C are final unless a row says open. This document renames nothing by itself; the renames land in PR 5b (navigation) and PR 5c (tools).** It lists every name a player navigates by or acts on, says what each one means, and proposes changes. The rulings in Section F make the proposed names final. The renames and a `ConventionTest` law that checks Section D come in the pull requests after this one.

This is part 1 of clarity PR 5 (`docs/superpowers/specs/2026-10-27-ui-clarity-design.md` section 7.2). Every `file:line` below was taken from this branch (origin/claude/mobile-mpc-drum-sampler-t58x74 at the time of writing), and a sample of 28 was re-read against the file. The spec's own line references (`ChopScreen.kt:1602`, `OrbitScreen.kt:1389`, `SnapScreen.kt:1439`) have drifted and are not reused.

How to read it:

- Labels are ALL CAPS on screen, so names are written in caps and in backticks. Messages and hints are sentence case (clarity PR 5d); when a sentence names a control it keeps the control's name in caps, exactly as labelled. Anything in backticks is a literal on-screen string or a code identifier.
- "Today" is what the code draws or says now. "Proposed" is a suggestion, not a decision.
- A name written as `Keep` means the name stays and the control that opens it carries a one-line plain description.

## The rule

- A name says what the thing does in words a producer already knows.
- A brand-ish feature name (for example AQUARIUM) is allowed only if the control that opens it carries a one-line plain description.
- Each name means exactly one thing in the app.
- When two things share a name, the secondary use is renamed. The primary use keeps the name.
- Engine and character names stay. They are instrument identities with their own docs.

## Voice guide

Copied from the spec (section 7.5). It is at the top of `Personality.kt` (clarity PR 5d).

- Say what happened, or what to do next.
- Buttons are verbs.
- Name things exactly as they are labelled on screen.
- Errors say what failed and what to try.
- No metaphors, jokes or period references.
- An ellipsis means work in progress, and nothing else.

**Case** (owner ruling D3, clarity PR 5d). Sentence case for anything that is a sentence: hints, empty states, descriptions, errors, dock messages, HELP and sheet row descriptions. A message ends in a full stop, `…` (work in progress) or `?`. CAPS stays for place and stage tabs, buttons, section titles, chip labels and pad ids. In `Copy` and `Places` a label is marked `@field:CopyLabel`, a caption (a sentence fragment read beside a control) `@field:CopyCaption`, and an unmarked string is a message; `PersonalityTest` holds each kind to its law.

---

## Section A: Approved (owner, D5)

Status values: **live** (drawn that way now), **partly** (some surfaces say the new name, others do not), **not yet**. No D5 rename is fully live.

| Today | New | Status | Evidence |
|---|---|---|---|
| `SHELF` (place) | `LIBRARY` | not yet | `Navigation.kt:215`, `Chrome.kt:82`, `Places.kt:235` (HELP key), `Chrome.kt:362` (`SHELF ▸` route) |
| `TAPE` (place) | `SAMPLE` | not yet | `Navigation.kt:216`, `Places.kt:241` (HELP key) |
| `DECK` (stage) | `RECORD` | not yet | `Navigation.kt:223` |
| `CUT` (stage) | `CHOP` | not yet | `Navigation.kt:224`, `Places.kt:29` (door `CUT ▸`). Chop surfaces already say CHOP in `Places.kt:136` (`CHOP ALL`) and in `ChopScreen.kt` toasts, so both words are on screen today |
| `OUT` (stage) | `EXPORT` | partly | Stage and door still say OUT: `Navigation.kt:227`, `Places.kt:32`. The kit hand-off door already says `EXPORT ▸`: `Personality.kt:590` |
| `MAKE` (place) | `CREATE` | not yet | `Navigation.kt:218`, `Places.kt:254` (HELP key) |
| `SETUP` (bench) | `SETTINGS` | partly | The gear chip is already named `SETTINGS`: `Personality.kt:1200`, drawn at `Chrome.kt:264`. The bench label is still `SETUP`: `Navigation.kt:286`, `Chrome.kt:88` |
| `TAPE PROPERTIES` (screen title) | `SETTINGS` | not yet | `PropertiesScreen.kt:101` |
| `SNIPS` (bench) | `SAVED SAMPLES` (ruling 3) | not yet | `Navigation.kt:268`, `SnipsScreen.kt:331`. `SNIP` and `SNIPPED` stay as the verb (ruling 2). `BOUNCE TO SNIPS` becomes `BOUNCE TO SAMPLES` |
| `PROJECT ▸ PADS`, `BEAT` | unchanged | live | `Navigation.kt:225,226`. The spec row only changes `OUT` |

Notes:

- The approved places become `LIBRARY`, `SAMPLE`, `PROJECT`, `CREATE`. The approved stages become `RECORD` and `CHOP` under SAMPLE, and `PADS`, `BEAT`, `EXPORT` under PROJECT.
- Two collisions come from these approvals, `RECORD` (four meanings) and `SAMPLE` against `SAMPLES`. Rulings 1 and 3 settle them: the dock keeps `LISTEN`, the bench is `SAVED SAMPLES`.

---

## Section B: Proposed renames for the owner

Starts from the spec's Proposed table, then adds every other jargon or brand-ish name found. "Where" cites the label's definition or first drawn site. A `Keep` row for a brand-ish name (ruling 9) gives the description the opening control must carry. Those lines are required copy.

| Today | Where | What it does | Proposed | Why |
|---|---|---|---|---|
| `PAD SHEET` | `Navigation.kt:248` | The screen where one pad is edited. | `PAD EDITOR` | The spec's word. The editor header already reads `PAD A01` (`PadSheetScreen.kt:3428`) and its back chip reads `PAD`. Only help text, a toast and spoken names say PAD SHEET: `KitScreen.kt:794`, `KitScreen.kt:1720`, `MakeScreen.kt:208`. |
| `LISTEN · MIC` (dock) | `Places.kt:34`, `Places.kt:42` | Keeps a rolling 60 seconds of the mic or app audio so SNIP can save it. Nothing is recorded until SNIP. | Keep `LISTEN` with the source shown (ruling 1). | The spec said `RECORD · MIC`; the owner ruled against it. It already shows the source (`LISTEN · APP AUDIO`). |
| `WRITE KIT` | `ExportWizard.kt:245` | Writes the kit's files to the card or phone. | `EXPORT KIT` | Spec. One verb for the whole export path. |
| `WRITE SESSION` | `ExportScreen.kt:1232` | Writes the whole project as an MPC session. | `EXPORT PROJECT` | Spec. |
| `WRITING — DO NOT EJECT` | `ExportWizard.kt:246`, `ExportScreen.kt:1232` | Busy label while files are written. | `EXPORTING…` | Spec. If the card warning is still needed it is a sentence, not the button label. |
| `DUBBING…`, `READY TO DUB`, `DUB COMPLETE` | `ExportWizard.kt:257-259` | Progress words of the same export. | `EXPORTING…`, `READY TO EXPORT`, `EXPORT COMPLETE` | Spec. Also frees `DUB` for the treatment chip (Section C). |
| `WRITE ANOTHER ✓` | `ExportWizard.kt:252` | Resets the export for another kit. | `EXPORT ANOTHER` | Same verb. |
| `SIDE A: PROGRAMS · SIDE B: SAMPLES`, `ALL n FILES ON TAPE. GO MAKE THE THING.` | `ExportWizard.kt:66`, `ExportWizard.kt:269` | Progress captions. | `n OF m FILES` and `ALL m FILES WRITTEN.` | Voice guide: no metaphors. |
| `BIN` | `Navigation.kt:269`, `BinScreen.kt:93` | Where deleted kits and samples wait before they are gone for good. | `TRASH` | Spec. Also removes the second BIN (Section C). |
| `VERSIONS + BIN` | `Navigation.kt:261`, `KitScreen.kt:1047` | Earlier versions of a pad, with restore. | `VERSIONS` | Spec. |
| `X-RAY` | `Places.kt:138`, `Navigation.kt:270`, `XRayScreen.kt:71` | Reads an MPC file and shows what is inside. Never imports it. | `INSPECT FILE` | Spec. The tool row already carries the line `INSPECT A FILE` (`Places.kt:139`). |
| `DOUBLES` | `Places.kt:140`, `Navigation.kt:271`, `DoublesScreen.kt:123` | Finds pads across kits that sound nearly the same. Shows the distance, never a verdict (`Personality.kt:2582`). | `FIND SIMILAR SOUNDS` (spec: `FIND DUPLICATES`) | ruling 6. The tool never claims a pad is a duplicate. |
| `BREED · MIX TWO KITS ▸` | `Personality.kt:1284`, `Personality.kt:1253` | Crosses this kit's recipes with a second kit to make a child kit. | `COMBINE KITS` | Spec. The button already carries the plain line. |
| `REMIX BANK B` | `KitScreen.kt:977`, `Places.kt:192` | Fills an empty bank B with a varied twin of every bank A pad. | `MAKE BANK B VARIATIONS` | Spec. Related prose uses `EVIL TWIN` (`Personality.kt:1218`, `Personality.kt:1239`). |
| `OUTSIDE` (box) | `PadSheetBoxes.kt:30`, `PadSheetScreen.kt:3988` | Plays the pad out of the speaker or through a pedal and records it back over the pad. | `RE-AMP AND ROOM` | Spec. ruling 7. |
| `SNAP` (Create tool) | `Navigation.kt:274`, `Personality.kt:3408` | Turns a picture into a sound. | `PHOTO TO SOUND` (spec: `IMAGE TO SOUND`) | ruling 5. Frees `SNAP` for the Chop row. |
| `SURFACE` | `Navigation.kt:275`, `Personality.kt:3409` | A touch surface you play with a finger. It can print what you play. | `TOUCH SURFACE` | Spec. |
| `GROOVE` (screen header) | `GrooveScreen.kt:3184`, `Chrome.kt:86` | The header of the BEAT stage. | `BEAT` | One name per screen. The stage is already `BEAT` (`Navigation.kt:226`). |
| `ARRANGE` (screen header) | `ArrangeScreen.kt:248` | The header of the SONG bench. | `SONG` | One name per screen. The bench is `SONG` (`Navigation.kt:265`) and its door says `SONG ▸` (`GrooveScreen.kt:1962`). |
| `GRV` | not found | The spec lists it. No `GRV` string exists in `app/` or `shell/` main code. | none | Could not verify. May be a short form on a surface not in this tree. |
| `KIT` (AppScreen label) | `Chrome.kt:83` | The routing id for the pads screen. The `◄ KIT` back label only appears in comments (`Chrome.kt:164,194`). | `PADS` | The spec row (`PADS, KIT` back labels) looks already resolved on screen: back chips come from `Navigation.backChip` (`Navigation.kt:192-200`) and say `PADS`. Change the enum label so it cannot leak. Whether the `AppScreen` labels are drawn anywhere was not confirmed. |
| `STACK THE TAKES` | `Navigation.kt:259`, `PadSheetScreen.kt:3617` | Layers a pad's earlier versions as soft-to-hard hit zones. | `STACK VERSIONS` | The things stacked are the versions (`VERSIONS`). ruling 10. |
| `SOFT HITS` | `PadSheetScreen.kt:1821` | Adds quieter layers to a pad so soft taps sound different. | Keep. Line: `QUIETER LAYERS FOR SOFT TAPS` | Plain enough. Its toast says `GHOST LAYERS ON.` (`Personality.kt:1899`), which is renamed in Section C. |
| `STRIKE STRIP` and `STRIKE ON/OFF`, `STAY`, `RETURN`, `VOL ON/OFF` | `StrikeStrip.kt:162,170,173` | A thumb strip on the pad grid. Where the thumb sits picks soft or hard layers. | `VELOCITY STRIP` | `STRIKE` names four things today (strip, FX, level, a starter). ruling 12. |
| `STRIKE FX` | `PadSheetScreen.kt:1858`, `PadSheetScreen.kt:4651` | Gives a pad dry, echo and room layers chosen by hit strength. | `FX BY VELOCITY` | Same call. |
| `MUTATE` (box, button, toast) | `PadSheetBoxes.kt:31`, `PadSheetScreen.kt:4021`, `Personality.kt:2130` | Combines this pad with a partner sound to make a new hit. | `COMBINE SOUNDS` | ruling 4. The closed strip already says `MIX WITH ANOTHER SOUND` (`PadSheetBoxes.kt:194`). Undo becomes `UNDO COMBINE`. |
| `MORPH` (MUTATE mode) | `Mutate.kt:42`, `MutateSheet.kt:49` | The sound between the pad and the partner, with a MIX amount. | `BLEND` | SURFACE also has a MORPH (Section C). |
| `TRANSPLANT` (MUTATE mode) | `Mutate.kt:42`, `MutateSheet.kt:51` | The pad's hit timing wearing the partner's overall tone. | `BORROW TONE` | Plain. A medical metaphor says nothing about sound. |
| `DRIFT` | `PadSheetScreen.kt:2420`, `MutateSheet.kt:87` | One tap: picks a neighbouring sound from the shelf and blends toward it. | `RANDOM BLEND` | Says what it does. |
| `ROULETTE` | `PadSheetScreen.kt:2298` | Deals a random partner from the shelf. | `RANDOM PARTNER` | Plain. |
| `AQUARIUM` | `AquariumSection.kt:28`, `AquariumPanel.kt:81`, `KitScreen.kt:925`, `OrbitAquariumPanel.kt:27`, `SplitAquariumPanel.kt:34`, `SurfaceAquariumPanel.kt:25` | A six-effect rack with presets and 52 controls, on a pad, a kit's live mix, ORBIT, SPLIT and SURFACE. | Keep. Line: `SIX EFFECTS WITH PRESETS` | The pad editor strip already says `SIX EFFECTS · PRESETS · 52 CONTROLS` (`AquariumSummary.kt:14`). The other four panels carry no line. ruling 9. |
| `ORBIT` / `ORBIT ▸` | `Navigation.kt:264`, `GrooveScreen.kt:1958`, `OrbitScreen.kt:2080` | A circular sequencer: loops of different lengths run against each other. | Keep. Line: `LOOP RINGS OF DIFFERENT LENGTHS AGAINST EACH OTHER` | The door `ORBIT ▸` has no line today. ruling 9. |
| `LOOP ▸` / `LOOP` (bench) | `Navigation.kt:266`, `GrooveScreen.kt:1966`, `Personality.kt:3150` | A six-track grid where samples are laid down as loops. | Keep. Line: `SIX TRACKS OF LOOPED SAMPLES` | The door has no line today. |
| `SONG ▸` | `GrooveScreen.kt:1962`, `Navigation.kt:265` | Lays the beat out as a song you keep and edit. | Keep. Line: `ARRANGE THIS BEAT AS A SONG` | Door has no line. |
| `GRAIN ▸` / `GRAIN FIELD` | `PadSheetScreen.kt:4181`, `Navigation.kt:257`, `GrainFieldScreen.kt:635` | Plays a pad as a cloud of tiny grains by dragging or tilting. | Keep. Line: `PLAY THIS SOUND AS GRAINS. DRAG OR TILT` | Hints exist on screen (`Personality.kt:3093`, `Personality.kt:3095`) but not on the door. |
| `CONFLUENCE VOICES` | `Navigation.kt:284`, `Personality.kt:3411` | Turns recorded speech passages into small voice groups saved as pads. | Keep | The Create row already says `RECORDED PASSAGES INTO SMALL VOICE GROUPS`. |
| `MY VOICE` | `Navigation.kt:282`, `MakeScreen.kt:95` | Turns a short vowel you sing into a voice kept on this phone. | Keep | The Create row already says `A SHORT VOWEL INTO A PERSONAL VOICE. KEPT ON THIS PHONE.` |
| `SYNTH` | `Navigation.kt:273`, `Personality.kt:3407` | Pick an engine, tune its sliders, send the sound to a pad. | Keep | The row says `TUNE AN ENGINE.` |
| `INSTRUMENTS` | `Navigation.kt:276`, `Personality.kt:3410` | Sounds you made, played on 16 pads. | Keep | The row says `WHAT YOU MADE, ON 16 PADS.` |
| `KEYS` (bench) | `Navigation.kt:277`, `MakeScreen.kt:235` | An on-screen keyboard for playing an instrument. | `KEYBOARD` | `KEY` is also the kit's musical key (Section C). |
| `LIVE` (bench) | `Navigation.kt:278` | Reads the camera live and plays the picture as sound. | `LIVE CAMERA` | The door in SNAP already says `LIVE CAMERA` (`SnapScreen.kt:943`). |
| `DRAW` (bench) | `Navigation.kt:279` | Draw a line or a wave and hear it. | Keep | Plain. |
| `PHOTO FIELD` (bench) | `Navigation.kt:280`, `SnapScreen.kt:1044` | Cuts a photo into grains you play in GRAIN FIELD. | `PHOTO GRAINS` | `FIELD` alone does not say what it is. Low priority. |
| `TELEPHONE` (SNAP voice) | `SnapScreen.kt:1005`, `Personality.kt:1127` | Re-reads the photo's sound through eight generations of copies. | `EIGHT COPIES` | Plain words for the effect. |
| `CLOUD`, `SPECTRUM` (SNAP voices) | `SnapScreen.kt:987`, `SnapScreen.kt:996` | `CLOUD`: the picture as a grain cloud. `SPECTRUM`: the picture's columns as the sound's spectrum. | Keep both with one-line sublabels | Both are producer words. They only need a line. |
| `HORIZON`, `PLUMB`, `AROUND` (SNAP line modes) | `SnapScreen.kt:1499-1528` | Which direction the drawn line is read: across, down, around the centre. | `ACROSS`, `DOWN`, `AROUND` | `ACROSS` and `DOWN` are the screen words already (`SnapScreen.kt:1526-1527`). Code names only. |
| `SCULPT` | `TextureKits.kt:71`, `Places.kt:189` | Grows granular loop textures (cloud, scrub, swarm) from a sound. | Keep. Line: `GROW GRAIN TEXTURES FROM THIS SOUND` | A verb, and the strip names the modes. |
| `STRETCH`, `FREEZE` | `TextureKits.kt:82`, `TextureKits.kt:88` | Slows a sound into a wash, or holds one instant of it. | Keep | Both are standard producer words. |
| `DUST` / `DUST ALL` | `PadSheet.kt:61`, `KitScreen.kt:996` | Adds the tape's own hiss and room under a pad or the whole kit. | Keep. Line already present: `THE TAPE'S OWN HISS AND ROOM` | Present at `KitScreen.kt:996`. |
| `SMEAR` | `PadSheet.kt:52` | A destructive smear treatment. The code comment says it is unrelated to `TAIL`. | Keep | Not verified what it sounds like. Needs the luthier. |
| `TAIL` (chip) | `PadSheet.kt:76` | Removes the attack and keeps the wash. | Keep | Character names stay. |
| `INSTANT KIT` | `TapeScreen.kt:1129`, `KitFromCard.kt:10` | One tap: chops the recording with defaults and puts the cuts on pads. | Keep | Plain. |
| `CATCH A HIT` | `TapeScreen.kt:1166`, `KitFromCard.kt:47` | Lets you play the pad grid as the chopper. | Keep. Line: `PLAY THE PADS TO CUT AS YOU HEAR` | Brand-ish. Check `docs/CATCH.md` for the exact promise before the line is final. |
| `READ AS GROOVE ▸`, `COPY GROOVE ▸` | `TapeScreen.kt:1184`, `TapeScreen.kt:1191` | Reads a recording's rhythm into a beat pattern / copies its feel. | ruling 11 | One name for pattern and groove. |
| `FIND BREAK` | `TapeScreen.kt:1224` | Finds the drum break inside a long recording and sets IN and OUT around it. | Keep | Producer word. |
| `GHOSTS` (CHOP) | `ChopScreen.kt:1488`, `Personality.kt:2506` | Cuts the gaps between the hits, each named for the hit before. | `BETWEEN HITS` | `GHOST` is also a treatment chip (Section C). |
| `FOLD` (CHOP) | `ChopScreen.kt:1099`, `Personality.kt:2500` | One pad per sound, with repeated hits cycling under it. | Keep. Line: `ONE PAD PER SOUND` | Toast already says it (`Personality.kt:2500`). |
| `HUM` (CHOP) | `ChopScreen.kt:1490` | Hum a rhythm and the cuts and labels follow your mouth. | Keep | Plain. |
| `SNAP` (CHOP row) | `ChopScreen.kt:1550`, `Personality.kt:2498` | Pulls the cuts onto the beat. | Keep (becomes the only SNAP once the Create tool is renamed) | Producer word. |
| `NOT SURE` (CHOP chip) | `ChopScreen.kt:1809` | The classifier could not label the slice. | Keep | Plain. |
| `FEEL TIGHTER`, `FEEL LOOSER`, `FEEL · AS PLAYED` | `GrooveScreen.kt:2475,2485,2500` | Move hits toward or away from the grid. | Keep | Producer words. |
| `FORK TO YOURS` | `GrooveScreen.kt:2166` | Copies a built-in pattern into your own editable pattern. | `COPY TO YOURS` | `FORK` is an engine name (Section C). |
| `SHAPE THE GROOVE` | `GrooveScreen.kt:2559` | Swing and feel controls. | `SWING AND FEEL` | Says what is inside. |
| `BOUNCE TO SNIPS` | `Personality.kt:2995`, `Personality.kt:3029` | Renders the pattern to a sample. | `BOUNCE TO SAMPLES` | Follows the SNIPS rename. |
| `DE-SAMPLE · THE NEAREST PATCH` | `PadSheetScreen.kt:4147` | Finds the synth patch that sounds closest to the pad. | `FIND NEAREST SYNTH` | Plain. |
| `MAKE SYNTH · REPLACES THE WAV` | `PadSheetScreen.kt:3125` | Replaces the pad's recording with a synth sound. | Keep | Already plain. |
| `PAD FROM ANYTHING · HOLD IT FOREVER`, `MAKE PAD · INSTRUMENT` | `PadSheetScreen.kt:4107`, `PadSheetScreen.kt:4129` | Turns the pad into a long sustained sound. | `SUSTAIN ANY SOUND`, `MAKE SUSTAINED INSTRUMENT` | `PAD` is also the drum pad (Section C). |
| `SPREAD · SCALE ACROSS PADS ▾` | `PadSheetScreen.kt:4197` | Spreads one sound across a bank, tuned to a scale. | Keep | Already says what it does. |
| `ROUND ROBIN` | `PadSheetScreen.kt:1765`, `KitRobinRow.kt:93` | Plays alternate versions of a hit so repeats do not sound identical. | Keep. Its `TAKES` count becomes `ALTERNATES` | Producer term. `TAKES` is overloaded (Section C). |
| `CHOKE GRP` | `PadSheetScreen.kt:3515` | Pads in one group cut each other off. | `CHOKE GROUP` | Spell it out. |
| `UNTREAT`, `COPY LAST TREATMENT`, `PASTE` | `PadSheetScreen.kt:1430`, `PadSheetScreen.kt:3892`, `PadSheetScreen.kt:1733` | Remove, copy and paste a pad's treatment. | `REMOVE EFFECT`, `COPY LAST EFFECT`, `PASTE EFFECT` | The box is a set of effects. Low priority; one word for it. |
| `TREATMENT` (box) | `PadSheetBoxes.kt:28` | Effects and ageing on a pad. | `EFFECTS` | The screen that holds it is already `EFFECTS` (`Navigation.kt:254`). A box and a screen with the same name is acceptable. Low priority. |
| `SHAPE` (box) | `PadSheetBoxes.kt:29` | Attack, decay, cutoff, resonance. | Keep | Plain. |
| `MAKE` (box) | `PadSheetBoxes.kt:32` | Things made from this pad (instrument, spread, grain). | `MAKE FROM THIS PAD` | The box's own heading is that already (`PadSheetScreen.kt:4070`). |
| `PEDALS` | `PadSheetScreen.kt:1345` | Guitar-pedal style effects on a pad. | Keep | Plain to the intended player. |
| `VARIATIONS ▸`, `EFFECTS ▸` (doors) | `Places.kt`-adjacent: `Personality.kt:2118-2119` | Open the pad editor's two sub-screens. | Keep | Plain. |
| `PAD CAPTURE` | `Navigation.kt:262`, `PadCaptureScreen.kt:553` | Record straight onto one pad with GRAB or HOLD. | `RECORD ON PAD` | See `RECORD` in Section C. |
| `GRAB`, `GRABBED` | `KeysScreen.kt:263`, `PadCaptureScreen.kt:302` | Saves the last seconds you heard. | `SAVE LAST 16 S` where it says 16 | Section C, keep-what-you-heard words. |
| `CATCH YOUR FIRST SOUND` | `Places.kt:49` | First-run button that starts the mic. | `LISTEN FOR YOUR FIRST SOUND` | `CATCH` already names a tool (Section C). |
| `FRESH TAPE`, `NEW TAPE` | `Personality.kt:2773`, `KitsScreen.kt:698` | A new recording or new sample. | `NEW SAMPLE` | Voice guide: name things as labelled. After SAMPLE is approved, TAPE is not the noun. |
| `ARMED · 00:41 ON TAPE` | `Places.kt:93`, `Places.kt:96` | The dock's counter of buffered seconds. | `LISTENING · 00:41 BUFFERED` | Same reason. |
| `TAPE ROLLING.` | `Personality.kt:284`, `Personality.kt:337` | The mic session has started. | `LISTENING.` | Same reason. |
| `SWITCH TO TAPE COUNTER` | `TapeScreen.kt:1296` | Shows the position as a counter instead of time. | `SHOW COUNTER` | Same reason. Low priority. |
| `SCRAMBLE` | `SynthScreen.kt:3299` | A button beside the engine's sliders. Inferred to randomise them. | `RANDOMISE` | Not verified. Check before renaming. |
| `⚄ DICE` / `ROLL THE DICE` | `OrbitScreen.kt:1467` | Fills the ring with a random pattern. | `RANDOM FILL` | Plain. |
| `SPREAD` (ORBIT chip) | `OrbitScreen.kt:1465`, `OrbitPanels.kt:84-86` | Spaces a chosen number of hits as evenly as the steps allow. | `SPACE EVENLY` | `SPREAD` is also the pad tool (Section C). |
| `ONE TURN OUT` | `OrbitScreen.kt:2038` | The length one lap of the set takes, before it can export. | Needs a plain line | Not clear from the heading. Not verified what it controls. |
| `PRINT TO TAPE` | `GrainFieldScreen.kt:504` | Saves what you played in GRAIN FIELD as a new sample. | `SAVE AS SAMPLE` | `PRINT` is a jargon verb; saving is what happens. |

---

## Section C: Collisions

Each row is a name with more than one meaning. The primary use keeps the name; the proposal renames the secondary use. Rows 1 to 6 start from the spec. The rest were found by grepping the code and by following the approved and proposed renames through.

| # | Name | Primary use (keeps the name) | Secondary use (proposed rename) | Proposed rename |
|---|---|---|---|---|
| 1 | `STACK` | Pad tool `STACK THE TAKES`: `Navigation.kt:259`, `PadSheetScreen.kt:3617` | MUTATE mode: `Mutate.kt:42`, `MutateSheet.kt:40` | `LAYER` |
| 2 | `SPLICE` | Pad tool `TAPE SPLICE`, joins two takes at one cut: `Navigation.kt:258`, `TapeSpliceScreen.kt:313`, `PadSheetScreen.kt:3642` | MUTATE mode, the pad's attack plus the partner's body: `Mutate.kt:42`, `MutateSheet.kt:47` | `JOIN` |
| 3 | `SPLIT` | Pad tool that takes a pad apart into sines, transient and air: `Navigation.kt:260`, `SplitScreen.kt:566`, `KitScreen.kt:1027` | (a) MUTATE mode, pad below a crossover and partner above: `MutateSheet.kt:48`. (b) CHOP row `SPLIT AT ITS NEXT HIT`: `ChopScreen.kt:1316`, `Personality.kt:2483`. (c) `AppScreen.SPLIT`: `Chrome.kt:100` (id only) | (a) `CROSSOVER` (spec: `SEPARATE`, see ruling 4). (b) `CHOP AT NEXT HIT` |
| 4 | `ROOM` | A measured room kept on the shelf: `PadSheetBoxes.kt:56` (OUTSIDE), `PadSheetScreen.kt:5586` | (a) MUTATE mode: `Mutate.kt:42`, `MutateSheet.kt:50`. (b) Pedal `ROOM` (a drum-room reverb): `Pedals.kt:149`. (c) STRIKE FX slider: `PadSheetScreen.kt:4643`. (d) CONFLUENCE slider: `ConfluenceVoicesScreen.kt:273`. (e) Clock line `OCTAVE` or `ROOM`: `PadSheetScreen.kt:5031,5049`. (f) `RE-AMP AND ROOM`, if proposed in Section B is taken | (a) `ROOM TONE` (b) `REVERB` (c) `REVERB` (d) `REVERB` (e) `UNPITCHED`. ruling 8 |
| 5 | `SNAP` | Chop row that pulls cuts onto the beat: `ChopScreen.kt:1550`, `Personality.kt:2498` | (a) Create tool: `Navigation.kt:274`. (b) Toast `SNAPPED TO THE HIT.`: `Personality.kt:475` (same meaning as the Chop row, fine). (c) Synth control `BACKROOM · MORE SNAP`: `BackroomSnareControls.kt:26` (a snare sound, fine). (d) Brand lockup SNIP and SNAP: `MarkLockup.kt:55,74` (brand, stays) | (a) `PHOTO TO SOUND`. The rest stay |
| 6 | `SPREAD` | Pad tool, one sound across a bank in a scale: `PadSheetScreen.kt:4197`, `Personality.kt:1014` | ORBIT chip, even spacing of hits: `OrbitScreen.kt:1465`, `OrbitPanels.kt:84-86` | `SPACE EVENLY` |
| 7 | `TAPE` | The place that holds recording and chopping: `Navigation.kt:216` (becomes `SAMPLE` by D5) | (a) Treatment chip, tape wear: `PadSheet.kt:67`. (b) Noun for any recording: `Places.kt:93`, `Personality.kt:284`, `Personality.kt:2773`. (c) Counter mode: `TapeScreen.kt:1296`. (d) Title `TAPE PROPERTIES`: `PropertiesScreen.kt:101` (D5 renames it) | After D5 only (a) remains, and it keeps its name as a character. Rename the noun uses in (b) and (c) as in Section B |
| 8 | `CUT` | The Tape stage: `Navigation.kt:224`, `Places.kt:29` (becomes `CHOP` by D5) | (a) Cutoff abbreviation in the SHAPE strip: `PadSheetBoxes.kt:97` (`CUT 1.2K`). (b) Slider `CUT · AGAINST THE ATTACK`: `ChopScreen.kt:1538`. (c) Box legend: `ChopScreen.kt:1479` | (a) `CUTOFF` (b) `CHOP POINT` (c) `CHOP` |
| 9 | `ORBIT` | The beat tool: `Navigation.kt:264`, `GrooveScreen.kt:1958` | SNAP line voice `ORBIT`: code only. The screen already says `AROUND`: `SnapScreen.kt:1528`; the enum is documented at `SnapScreen.kt:1499` | Already resolved on screen. Optionally rename the enum |
| 10 | `RECORD` (created by D5) | `RECORD` stage, the sampling place: `Navigation.kt:223` (after D5) | (a) Dock `LISTEN` if renamed to `RECORD`: `Places.kt:34`. (b) BEAT recording of pad hits: `Personality.kt:175`, `GrooveScreen.kt:1930`. (c) `PAD CAPTURE` `RECORD`: `PadCaptureScreen.kt:553`. (d) MY VOICE `RECORD 6 SECONDS`: `MyVoiceScreen.kt:374` | Keep `LISTEN` on the dock (ruling 1). Rename (c) as `RECORD ON PAD` in the title. (b) and (d) are plain verbs inside one screen and stay |
| 11 | `SAMPLE` and `SAVED SAMPLES` (created by D5 and ruling 3) | `SAVED SAMPLES` bench, the list of saved sounds (today `SNIPS`): `Navigation.kt:268` | `SAMPLE` place (D5). Also `CAPTURE A SAMPLE`: `KitScreen.kt:1611`, `ONE SAMPLE`: `Personality.kt:1901`, `SAMPLED RANGE`: `SynthScreen.kt:5373`, `SIDE B: SAMPLES`: `ExportWizard.kt:66` | Settled by ruling 3 |
| 12 | `SNIP` | The dock verb, saves the last 60 s: `Places.kt:60` | Noun `SNIP`, `SNIPS`, `SNIPPED`: `Navigation.kt:268`, `Places.kt:53`, `Personality.kt:2995` | Nouns become `SAMPLE`. Verb `SNIP` stays (ruling 2) |
| 13 | `DUB` | Treatment chip, generation loss: `PadSheet.kt:79` | Export words `DUBBING…`, `READY TO DUB`: `ExportWizard.kt:257-259`; `DUB DONE`, `DUBBED.`: `Personality.kt:2615`, `Personality.kt:2607` | Export words become `EXPORT…` (Section B) |
| 14 | `GHOST`, `GHOSTS` | CHOP segment, the gaps between hits: `ChopScreen.kt:1488`, `Personality.kt:2506` | (a) Treatment chip `GHOST`: `PadSheet.kt:76`. (b) `GHOST LAYERS ON.` in the SOFT HITS toast: `Personality.kt:1899`, `PadSheetScreen.kt:1821`. (c) `GHOSTS ON. MUTATE WANTS ONE SAMPLE`: `Personality.kt:2079` | The chip keeps its name (characters stay). CHOP becomes `BETWEEN HITS`. Toasts say `SOFT HITS` |
| 15 | `CONTOUR` | Engine `CONTOUR`: `EngineCatalog.kt:94` | Treatment chip `CONTOUR`: `PadSheet.kt:79` | `FILTER SWEEP` (engines win) |
| 16 | `FORK` | Engine `FORK`: `EngineCatalog.kt:74` | `FORK TO YOURS`: `GrooveScreen.kt:2166` | `COPY TO YOURS` |
| 17 | `RING` | An ORBIT ring: `OrbitScreen.kt:1465` region | (a) Treatment chip `RING`: `PadSheet.kt:79`. (b) SNAP `RING ▸`: `SnapScreen.kt:1788` (a ring, same meaning). (c) OHM `RING`: `OhmControls.kt:121` | Chip becomes `RING MOD`. (b) is a real ORBIT ring and stays |
| 18 | `SECTION` | A song section: `Personality.kt:1681`, `ArrangeScreen.kt:251` | Treatment chip `SECTION` (six independent players): `PadSheet.kt:88` | Not renamed (ruling 14). The chip stays `SECTION`. Open: needs a sound check for a better name |
| 19 | `MAKE` | The place (becomes `CREATE`): `Navigation.kt:218` | Box `MAKE` in the pad editor: `PadSheetBoxes.kt:32`. Synth button `MAKE`: `SynthScreen.kt:5399`. `MAKE_A_SOUND` heading: `Personality.kt:3392` | Box becomes `MAKE FROM THIS PAD` (Section B). The others are verbs inside a screen |
| 20 | `◄ PAD` | The back chip over the pad editor: `Navigation.kt:248` (`backName = "PAD"`) | Stepper `◄ PAD` on SURFACE: `SurfaceScreen.kt:1350`. `◄ A01` stepper: `PadSheetScreen.kt:4240`. `PAD ◄`: `SplitScreen.kt:558` | Steppers say `PREVIOUS PAD`. The `◄` arrow is reserved for Back |
| 21 | `TAKE`, `TAKES` | An earlier version of a pad: `TapeSpliceScreen.kt:313`, `StackTakesScreen.kt:339`, `Personality.kt:2278` | (a) Round robin count `TAKES`: `PadSheetScreen.kt:3589`, `KitRobinRow.kt:124`. (b) A recorded BEAT pass, `UNDO TAKE`: `GrooveScreen.kt:2161` | (a) `ALTERNATES`. (b) stays `TAKE`, it is the musician's word. ruling 10 |
| 22 | `BIN` | The trash: `Navigation.kt:269` | `VERSIONS + BIN` and prose `THE BIN HOLDS NO EARLIER TAKE`: `Navigation.kt:261`, `Personality.kt:2278` | Both go away under `TRASH` and `VERSIONS` |
| 23 | `CATCH` | Tape tool `CATCH A HIT`: `TapeScreen.kt:1166` | First-run `CATCH YOUR FIRST SOUND`: `Places.kt:49` | `LISTEN FOR YOUR FIRST SOUND` |
| 24 | `SNIP`, `GRAB`, `CATCH`, `LISTEN` | Four words for keeping what was just heard: `Places.kt:60`, `KeysScreen.kt:437`, `Places.kt:49`, `Places.kt:34` | Same action, different words | One verb family: `LISTEN` arms, `SNIP` saves (ruling 2). `GRAB` becomes `SAVE LAST 16 S` |
| 25 | `KEY` and `KEYS` | `KEY`, the kit's musical key: `Places.kt:201` | Bench `KEYS`, an on-screen keyboard: `Navigation.kt:277`. Synth heading `KEYS AND CHORDS`: `Personality.kt:676` | Bench becomes `KEYBOARD` |
| 26 | `LIVE` | The kit's `LIVE` mix state: `KitScreen.kt:926` | Bench `LIVE` (camera): `Navigation.kt:278`. `LIVE ON`: `AquariumPerformancePanel.kt:79` | Bench becomes `LIVE CAMERA` |
| 27 | `PAD` | The drum pad | A sustained pad sound: `PadSheetScreen.kt:4107`, `PadSheetScreen.kt:4129`. Engine kind `PADS & FX`: `EngineCatalog.kt:25` | `SUSTAIN ANY SOUND`. The engine kind can stay |
| 28 | `MORPH` | SURFACE mode blending four corners: `TouchSurface.kt:23`, `SurfaceScreen.kt:1501` | MUTATE mode: `Mutate.kt:42` | `BLEND` |
| 29 | `GATE` | Synth `GATE` and ORBIT `GATE LONGER`: `SynthScreen.kt:3631`, `OrbitScreen.kt:1584` (note length) | Treatment chip `GATE` (hit chopped on the grid): `PadSheet.kt:88`. Pedal `HUSH`, which is a noise gate: `Pedals.kt:169` | Chip becomes `TRANCE GATE`. `HUSH` becomes `NOISE GATE` |
| 30 | `TOUCH` | Synth `TOUCH LAYERS`: `SynthScreen.kt:5382` | Pedal `TOUCH`, a touch-controlled wah: `Pedals.kt:126`. `TOUCH SURFACE` if proposed | Pedal becomes `TOUCH WAH` |
| 31 | `GROOVE` | The rhythm read from a recording: `TapeScreen.kt:1184,1191`, `Personality.kt:1358` | Screen header `GROOVE`: `GrooveScreen.kt:3184`. `SHAPE THE GROOVE`: `GrooveScreen.kt:2559` | Header becomes `BEAT`. ruling 11 |
| 32 | `STRIKE` | Strip that picks soft to hard layers: `StrikeStrip.kt:162` | `STRIKE FX` (`PadSheetScreen.kt:1858`), a starter named STRIKE FX (`docs/STRIKE_STRIP.md`), `STRIKE STRENGTH` (`StrikeStrip.kt:199`) | `VELOCITY STRIP`, `FX BY VELOCITY`. ruling 12 |
| 33 | `VOICE`, `VOICES` | A synth engine's voice (kick, snare, ...): `Personality.kt:666` | `MY VOICE` (a person's voice): `Navigation.kt:282`. `CONFLUENCE VOICES`: `Navigation.kt:284`. SURFACE `VOICES`: `SurfaceScreen.kt:123` | Keep. All three are described on their rows. SURFACE `VOICES` needs a plain line. Not verified what it counts |
| 34 | `INSTRUMENT` | A playable 16-pad sound made by you: `Navigation.kt:276` | Engines are described as instrument identities (`docs/` and spec). Expansion contents `INSTRUMENTS IN IT`: `Personality.kt:711` | Same meaning in practice. Keep |
| 35 | `EXPORT` (spec rows) | The `EXPORT` stage after D5 | `OUT`, `EXPORT SONG` (`ArrangeScreen.kt:422`), `EXPORT ▸` door (`Personality.kt:590`), `MIDI EXPORT` (`GrooveScreen.kt:1359`) | Same verb, different object. Each says its object. Keep |

---

## Section D: Everything else that keeps its name

### D1. Navigation labels (the `ConventionTest` law table)

Every label in `Navigation.kt`. The last column says where the name goes next.

**Places** (`Navigation.kt:214-219`, shown on the place row)

| Today | Meaning | Ledger |
|---|---|---|
| `SHELF` | Your projects, kits, samples and instruments. | Approved rename to `LIBRARY` |
| `TAPE` | Record a sound and chop it. | Approved rename to `SAMPLE` |
| `PROJECT` | The open project's pads, beat and export. | Keep |
| `MAKE` | Make a sound from nothing and send it to a pad. | Approved rename to `CREATE` |

**Stages** (`Navigation.kt:222-228`)

| Today | Place | Meaning | Ledger |
|---|---|---|---|
| `DECK` | TAPE | Record from the mic, an app or a shared file. | Approved rename to `RECORD` |
| `CUT` | TAPE | Chop the recording onto pads. | Approved rename to `CHOP` |
| `PADS` | PROJECT | The 16-pad grid of the active kit. | Keep |
| `BEAT` | PROJECT | The project's patterns. | Keep |
| `OUT` | PROJECT | Export the kit or the project. | Approved rename to `EXPORT` |

**Benches** (`Navigation.kt:246-290`; the back name is what a chip over this bench reads)

| Label | Back name | Home | Meaning | Ledger |
|---|---|---|---|---|
| `PAD SHEET` | `PAD` | PROJECT | Edit one pad. | Proposed `PAD EDITOR` (B) |
| `EFFECTS` | `EFFECTS` | PROJECT | The pad editor's effects page. | Keep |
| `VARIATIONS` | `VARIATIONS` | PROJECT | The pad editor's variations page. | Keep |
| `GRAIN FIELD` | `GRAIN FIELD` | PROJECT | Play a pad as grains. | Keep with line (B) |
| `SPLICE` | `SPLICE` | PROJECT | Join two earlier takes of a pad at one cut. | Keep (primary use) |
| `STACK THE TAKES` | `STACK` | PROJECT | Layer earlier versions as hit zones. | Proposed `STACK VERSIONS` (B) |
| `SPLIT` | `SPLIT` | PROJECT | Take a pad apart. | Keep (primary use) |
| `VERSIONS + BIN` | `VERSIONS` | PROJECT | Earlier versions of a pad. | Proposed `VERSIONS` (B) |
| `PAD CAPTURE` | `PAD CAPTURE` | PROJECT | Record onto one pad. | Proposed `RECORD ON PAD` (B) |
| `ORBIT` | `ORBIT` | PROJECT | Circular sequencer. | Keep with line (B) |
| `SONG` | `SONG` | PROJECT | Lay the beat out as a song. | Keep |
| `LOOP` | `LOOP` | PROJECT | Six-track loop grid. | Keep with line (B) |
| `SNIPS` | `SAMPLES` | SHELF | The list of saved sounds. | Final: `SAVED SAMPLES` (ruling 3) |
| `BIN` | `BIN` | SHELF | Deleted kits and samples. | Proposed `TRASH` (B) |
| `X-RAY` | `X-RAY` | SHELF | Inspect an MPC file. | Proposed `INSPECT FILE` (B) |
| `DOUBLES` | `DOUBLES` | SHELF | Find similar-sounding pads. | Proposed (B, F6) |
| `SYNTH` | `SYNTH` | MAKE | Tune an engine. | Keep |
| `SNAP` | `SNAP` | MAKE | A picture into a sound. | Proposed `PHOTO TO SOUND` (B) |
| `SURFACE` | `SURFACE` | MAKE | A touch surface. | Proposed `TOUCH SURFACE` (B) |
| `INSTRUMENTS` | `INSTRUMENTS` | MAKE | Sounds you made, on 16 pads. | Keep |
| `KEYS` | `KEYS` | MAKE | On-screen keyboard. | Proposed `KEYBOARD` (B) |
| `LIVE` | `LIVE` | MAKE | Live camera as sound. | Proposed `LIVE CAMERA` (B) |
| `DRAW` | `DRAW` | MAKE | Draw a line or wave and hear it. | Keep |
| `PHOTO FIELD` | `PHOTO FIELD` | MAKE | A photo cut into grains. | Proposed `PHOTO GRAINS` (B) |
| `MY VOICE` | `MY VOICE` | MAKE | A sung vowel into a voice. | Keep |
| `CONFLUENCE VOICES` | `VOICES` | MAKE | Recorded passages into voice groups. | Keep |
| `SETUP` | `SETUP` | SHELF (gear) | The settings screen. | Approved rename to `SETTINGS` |
| `HELP` | `HELP` | SHELF (gear) | Help, one section per place. | Keep |
| `MIDI` | `MIDI` | SHELF (gear) | What is plugged in and what it sends. | Keep |

**Back labels** (`Navigation.backChip`, `Navigation.kt:192-200`)

The chip reads `◄ ` plus the back name of the bench under the top one, else the stage label, else the place label. So every stage label and place label above is also a back label (`◄ DECK`, `◄ CUT`, `◄ PADS`, `◄ BEAT`, `◄ OUT`, `◄ SHELF`, `◄ MAKE`, and `◄ TAPE`, `◄ PROJECT` where a place has no stage). Every bench back name in the table above is a back label. The back names that differ from their label are `PAD`, `STACK`, `VERSIONS`, `VOICES`, `SAMPLES`, and, from the tool renames, `INSPECT` (for `INSPECT FILE`), `SIMILAR` (for `FIND SIMILAR SOUNDS`), `PHOTO` (for `PHOTO TO SOUND`), `GRAINS` (for `PHOTO GRAINS`), `RECORD PAD` (for `RECORD ON PAD`) and `SURFACE` (for `TOUCH SURFACE`). Each is short because a chip holds 11 characters. `SAMPLES` (the back name of `SAVED SAMPLES`: the full label is 13 characters and a chip holds 11).

Back labels that do not come from `backChip`:

| Today | Where | Note |
|---|---|---|
| `◄ PAD A01` (page form) | `PadSheetScreen.kt:3428` | The back label plus the pad tag, on the editor's sub-pages. |
| `◄ SYNTH` | `SynthScreen.kt:4748` | The held-note sub-screen's back chip. |
| `◄ SNAP` | `SnapScreen.kt:1116`, `LiveSnapScreen.kt:227` | Two SNAP-owned screens. Becomes `◄ PHOTO TO SOUND` if B is accepted. |
| `◄ CHOOSE DIFFERENT TAKES` | `TapeSpliceScreen.kt:280`, `TapeSpliceScreen.kt:408` | A step back inside SPLICE. |
| `BACK TO <name>` | `Chrome.kt:199` | Spoken form of any `◄ ` chip. |
| `◄ KIT` | comments only: `Chrome.kt:164,194` | Not drawn today (spec row looks resolved; `AppScreen.KIT` label at `Chrome.kt:83` remains). |

**Doors and dock labels** (`Places.kt:29-91`, `Places.kt:123-139`)

| Today | Where | Meaning |
|---|---|---|
| `CUT ▸` | `Places.kt:29` | Dock door to CUT (D5: `CHOP ▸`). |
| `PADS ▸` | `Places.kt:30` | Dock door to PADS. |
| `BEAT ▸` | `Places.kt:31` | Dock door to BEAT. |
| `OUT ▸` | `Places.kt:32` | Dock door to OUT (D5: `EXPORT ▸`). |
| `LISTEN · MIC`, `LISTEN · APP AUDIO` | `Places.kt:34,42` | Dock chip, source shown once chosen. See F, call 1. |
| `SNIP` | `Places.kt:60` | Dock button: saves the last 60 seconds. |
| `STOP` | `Places.kt:63` | Dock button: ends the listening session. |
| `RECORD FROM`, `MIC`, `APP AUDIO` | `Places.kt:91,83,87` | The source sheet and its two rows. |
| `PLAY THE BEAT`, `STOP THE BEAT` | `Places.kt:66,67` | Transport chip, spoken names. |
| `SHARE`, `SHARE THIS BEAT` | `Places.kt:74,75` | Beat share chip and its spoken name. |
| `OPEN SETTINGS` | `Places.kt:52` | Opens Android's page for this app. |
| `+ FROM SHELF`, `+ KIT`, `SWITCH KIT`, `PICK A STARTER`, `TAPS REROLL` | `Places.kt:123-130,155-158` | Kit switcher and starter sheet (D5: `+ FROM LIBRARY`). |
| `TOOLS`, `BACKUP`, `CHOP ALL`, `X-RAY`, `DOUBLES` | `Places.kt:133-141` | The library's tools sheet and its rows. |
| `RENAME`, `DELETE`, `REMOVE A KIT` | `Places.kt:147-152` | Project row menu. |
| `KEY ▾`, `KEY` | `Places.kt:201,204` | Kit key picker and its sheet title. |
| `BANK B`, `THE WHOLE KIT'S SOUND`, `VERSIONS AND OTHER KITS` | `Places.kt:192-198` | THIS KIT group headings. |
| `SHELF ▸`, `TAPE ▸`, `KEYS ▸`, `MY VOICE ▸`, `EXPORT ▸` | `Chrome.kt:362`, `KitScreen.kt:248`, `MakeScreen.kt:235,95`, `Personality.kt:590` | Empty-state and hand-off doors. Each follows its destination's new name. |
| `ORBIT ▸`, `SONG ▸`, `LOOP ▸`, `START YOURS` | `GrooveScreen.kt:1958-1965` | BEAT's four buttons. |
| `EFFECTS ▸`, `VARIATIONS ▸` | `Personality.kt:2118,2119` | Pad editor doors. |
| `STACK ▸`, `SPLICE ▸`, `RE-TRIM ▸`, `GRAIN ▸`, `AUDITION ▸` | `PadSheetScreen.kt:3617,3642,3626,4181,4213` | Pad editor tool doors. |

### D2. Other names that keep their meaning

| Today | Meaning |
|---|---|
| `KIT` | An MPC program: 16 pads in banks. The card reads both words (`Places.kt:237`). |
| `PROJECT` | An MPC project: the kits and beat saved together. |
| `PAD` | One of the 16 trigger pads. |
| `BANK A`, `BANK B` | The two pages of 16 pads on the grid. |
| `EXPANSION`, `XPN` | An MPC expansion pack and its single-file form (`Personality.kt:644,716`). |
| `KEYGROUP`, `DRUM` | MPC program types shown by inspect file (`XRayScreen.kt:137`). |
| `ONE-SHOT`, `CHOKE GRP` | Pad play modes (`PadSheetScreen.kt:3511,3515`). `CHOKE GRP` is proposed `CHOKE GROUP` in B. |
| `PERFORMANCE` | The kit's live effect mix group (`KitScreen.kt:925`). |
| `THIS KIT` | The kit-wide tools group (`KitScreen.kt:956`). |
| `KEY`, `IN KEY` | The kit's musical key, and retuning tonal pads to it (`Places.kt:201`, `PadSheet.kt:189`). |
| `ROUND ROBIN` | Alternate hits so repeats vary (`PadSheetScreen.kt:1765`). |
| `PLAY`, `STOP`, `BPM`, `SWING`, `TEMPO`, `BAR`, `STEPS` | Transport and timing words (`GrooveScreen.kt:223`, `Places.kt:63`). |
| `INSTANT KIT`, `USE THIS PART ▸`, `IN`, `OUT` (marks) | Deck actions: one-tap chop, take the selection to CHOP, the selection's start and end (`Personality.kt:468`). |
| `BY HITS`, `GRID`, `AUTO`, `MERGE`, `RE-CHOP` | Chop controls (`ChopScreen.kt:1486,1487,1506,1310,926`). |
| `START YOURS`, `EDIT STEPS`, `CHART`, `SHARE`, `MIDI EXPORT` | Beat controls (`GrooveScreen.kt:1957,2155,1414,1359`; `Places.kt:74`). |
| `+ SECTION`, `LOCK`, `UNLOCK`, `REROLL ⚄`, `PLAY`, `STOP`, `LOOP ON/OFF`, `BASS ON/OFF`, `PULL-UP` | Song controls (`Personality.kt:1672-1735`). |
| `VOICE`, `PRESETS`, `PLAYING`, `USE THIS SOUND` | SYNTH section headings (`Personality.kt:666-672`). |
| `START HERE`, `BASS`, `KEYS AND CHORDS`, `PLUCKS AND STRINGS`, `BREATH AND REEDS`, `VOICES`, `METAL WOOD AND GLASS`, `SKINS`, `MOVING AND STRANGE` | Starter list headings (`Personality.kt:674-682`). |
| `SEND TO PAD`, `SAVE AS PRESET`, `FORGET` | Synth send, preset save and delete (`SnapScreen.kt:1054`, `SynthScreen.kt:4879`, `Personality.kt:635`). |
| `LOOK`, `HAPTICS`, `LISTEN FROM`, `MIDI OUT`, `EXPORT OPENS ON`, `THE RATE`, `WHERE YOUR FILES LIVE`, `THE CARD` | Settings group headings (`PropertiesScreen.kt:112,136`; `Personality.kt:756-793,1185,1193`). |
| `HELP` section keys `SHELF`, `TAPE`, `PROJECT`, `MAKE`, `THE DOCK` | `Places.kt:234-265`. They follow the place names. |
| `DECK`-side tools `READ AS GROOVE`, `COPY GROOVE`, `FIND BREAK` | See B and F, call 11. |
| `STRIP`, `SIDE`, `RELEASE`, `LEVEL` | STRIKE STRIP captions (`Personality.kt:165-168`). Follow the strip rename. |
| `ARM A CORNER`, `LATCH`, `TARGET`, `SHAPE` | SURFACE controls (`SurfaceScreen.kt:1557,1369,129,129`). Described on screen. |
| `MIC`, `APP AUDIO` | Capture sources (`Places.kt:83,87`). |
| `SOLO`, `DEL`, `CLEAR`, `ARP`, `THE SET`, `SECTIONS` | ORBIT ring and set controls (`OrbitScreen.kt:1423,1463,1466,2010,2020,2029`). |
| `PRINT SETTINGS`, `PREVIEW`, `BEFORE`, `KEEP`, `BYPASSED` | Aquarium panel words (`AquariumPanel.kt:120,190,185,205`). |
| `SORT · RECENT`, `SORT · A–Z`, `SHOW · ALL` | Library list controls (`Personality.kt:2390,2392,2405`). |
| `EMPTY BIN`, `RESTORE` | Trash actions (`DeletedSnipsSection.kt:163,141`). Follow `TRASH`. |
| `CHOP ALL`, `BACKUP` | Library tools (`Places.kt:134-137`). |
| `EXPANSIONS`, `KITS IN IT`, `INSTRUMENTS IN IT`, `ADD TO EXPANSION`, `WRITE EXPANSION` | Expansion shelf (`Personality.kt:664,707-718`). `WRITE EXPANSION` becomes `EXPORT EXPANSION` with the other WRITE verbs. |
| `PREFLIGHT`, `FORMAT`, `DESTINATION`, `ON THE CARD` | Export screen sections (`ExportScreen.kt:1350,1518,1425,673`). |

---

## Section E: Engine and character names

**Draft. Needs a sound check before the owner reviews.** Nothing below was heard. Every line is a one-line summary of the engine's catalogue line in `EngineCatalog.kt`, which the luthier drew from each engine's own doc or KDoc and which a convention law keeps in step with the picker. The spec asked for lines drafted from `docs/<ENGINE>.md`. Fifty-one engines have a doc named for them. Thirty have only a KDoc file under `synth/src/main/kotlin/com/snipsnap/synth/`. The spec said 63 engines. The picker holds 81 (`SynthScreen.kt:5822`, `EngineCatalog.kt`).

The picker groups engines into nine kinds (`EngineCatalog.kt:14-24`): `DRUMS & PERCUSSION`, `BASS`, `KEYS & ORGANS`, `PLUCKED & STRINGS`, `WINDS & BRASS`, `VOICES`, `SYNTH`, `BELLS & MALLETS`, `PADS & FX`. The first kind listed is the engine's main one.

### E1. Engines

| Engine | Kind | What it is | Source |
|---|---|---|---|
| `LATTICE` | SYNTH | Warm, plucked or hollow notes that drop out and come back | `docs/LATTICE.md` |
| `ACCORD` | BELLS & MALLETS, PADS & FX | Pitched brushes, wood and tines finding shared timing | `docs/ACCORD.md` |
| `THUMP` | DRUMS & PERCUSSION | Analog-style kick, snare, hats, clap, tom, cowbell, rim | `synth/.../Thump.kt` |
| `SCANNED CHAIN` | SYNTH, BELLS & MALLETS | Evolving pitched notes: springy plucks, metal and swells | `docs/SCANNED_CHAIN.md` |
| `SKIN` | DRUMS & PERCUSSION | Acoustic-style kick, snare, hats, toms, ride and shaker | `docs/SKIN.md` |
| `ONSET` | DRUMS & PERCUSSION | Kick, snare, hats, tom and pluck, wood or metal percussion | `docs/ONSET.md` |
| `TINES` | BELLS & MALLETS, DRUMS & PERCUSSION | Bells, chimes, kalimba and small percussion hits | `docs/TINES.md` |
| `VELVET` | SYNTH, BASS | Classic subtractive synth: bass, brass, acid and chip | `docs/VELVET.md` |
| `VOX` | VOICES, PADS & FX | Choir, robot, speech, throat singing and beatbox | `docs/VOX.md` |
| `PLUCK` | PLUCKED & STRINGS, BASS | Nylon, harp, koto, banjo, sitar and upright bass plucks | `synth/.../Pluck.kt` |
| `ORPHICA` | PLUCKED & STRINGS, PADS & FX | Plucked harp that breaks into stuttering grains as it rings | `docs/ORPHICA.md` |
| `TONEWHEEL` | KEYS & ORGANS | Drawbar organ: full, soul and stab registrations | `docs/TONEWHEEL.md` |
| `OTIS` | KEYS & ORGANS, WINDS & BRASS | Organ, steam pipe, blues harmonica and shared reed chords | `docs/OTIS.md` |
| `REGISTERS` | KEYS & ORGANS | Pipe organ, reed organ and transistor organ | `docs/REGISTERS.md` |
| `FATHOM` | BASS | Deep, grinding and glassy bass with glide | `docs/FATHOM.md` |
| `RESIN` | BASS, SYNTH | Fat analog-style bass and leads with a singing filter | `docs/RESIN.md` |
| `TIDE` | DRUMS & PERCUSSION, SYNTH | Bongo and conga hits, water drips, gongs and plinks | `docs/TIDE.md` |
| `GLINT` | SYNTH, VOICES | Buzzy synth tones with a sweeping, vowel-like peak | `synth/.../Glint.kt` |
| `SIREN` | PADS & FX | Dub siren: wails, trills, lasers and bird chirps | `synth/.../Siren.kt` |
| `FORK` | KEYS & ORGANS, BELLS & MALLETS | Electric piano and vibraphone-style bars | `synth/.../Fork.kt` |
| `HALOBAR` | KEYS & ORGANS, BELLS & MALLETS | Vibraphone, marimba and xylophone with rhythmic dub echoes | `docs/HALOBAR.md` |
| `FLOTILLA` | PADS & FX | Slow looping textures of soft pulses and wooden knocks | `synth/.../Flotilla.kt` |
| `AEROSTAT` | WINDS & BRASS, DRUMS & PERCUSSION | Tube knock followed by a rising, whistling air tone | `synth/.../Aerostat.kt` |
| `TREMOR` | DRUMS & PERCUSSION | Low drum hits with rattling beads and ringing strings | `synth/.../Tremor.kt` |
| `BALLAST` | BASS | Deep electronic bass with ringing strings and glass taps | `synth/.../Ballast.kt` |
| `CISTERN` | PADS & FX, DRUMS & PERCUSSION | Looping drips and splashes on a drum skin | `synth/.../Cistern.kt` |
| `CIRCUIT` | PADS & FX, WINDS & BRASS | Looping ensemble of breathy tones, rattles and wood clacks | `docs/CIRCUIT.md` |
| `OBBLIGATO` | WINDS & BRASS | Flute and reed pair that play and answer each other | `docs/OBBLIGATO.md` |
| `ALLOY` | SYNTH, BASS | FM-style keys, bass, plucks, brass and strings | `docs/ALLOY.md` |
| `THAW` | BELLS & MALLETS, PADS & FX | Icy glass-like plate tones, struck or rubbed | `docs/THAW.md` |
| `MURK` | PADS & FX, DRUMS & PERCUSSION | Tuned wood knocks and owl hoots, mostly as loops | `docs/MURK.md` |
| `NIMBUS` | BELLS & MALLETS, PADS & FX | Six cymbal-like metal plates that ring, shimmer and roll | `docs/NIMBUS.md` |
| `COROLLA` | BELLS & MALLETS, PADS & FX | Soft metallic chimes that swell into clustered bell tones | `synth/.../Corolla.kt` |
| `UNDERTOW` | PADS & FX, WINDS & BRASS | Looping breathy, hollow tones with ceramic clicks | `docs/UNDERTOW.md` |
| `SUTURE` | PADS & FX, BELLS & MALLETS | Looping bronze-like tones that swell, strain and settle | `docs/SUTURE.md` |
| `TESSERA` | BELLS & MALLETS | Tuned mallet sounds: wood bars, wire strings, metal tubes | `docs/TESSERA.md` |
| `PITCHWHEEL` | PLUCKED & STRINGS, DRUMS & PERCUSSION | Pitched wooden ratchet clicks, plucks and drawn tones | `docs/PITCHWHEEL.md` |
| `REVEL` | DRUMS & PERCUSSION | Interlocking hand-drum phrases from six tuned skins | `docs/REVEL.md` |
| `PRISM` | SYNTH, KEYS & ORGANS | Additive synth: keys, plucks, organ, bass, glass, slow pads | `docs/PRISM.md` |
| `CONTOUR` | SYNTH, BASS | Wavetable synth: bass, keys, plucks, leads and swells | `docs/CONTOUR.md` |
| `STOOP` | SYNTH, BASS | Groove synth: bass, e-piano, clav, organ, lead, chords | `docs/STOOP.md` |
| `LACQUER` | SYNTH, KEYS & ORGANS | Layered keys, glass, brass, bass, pluck and silky tones | `docs/LACQUER.md` |
| `SPOOL` | PADS & FX, VOICES | Tape-machine strings, choir, flute and brass textures | `docs/SPOOL.md` |
| `SKITTER` | SYNTH, BASS | Gritty, unstable synth bass, leads and chirps | `docs/SKITTER.md` |
| `TANGLE` | SYNTH, BASS | Beating, metallic synth bass, leads and pulses | `docs/TANGLE.md` |
| `SHOAL` | BELLS & MALLETS, PADS & FX | Shimmering glass, wood, bowl and wire tones that drift | `docs/SHOAL.md` |
| `RELAY` | SYNTH, KEYS & ORGANS | Keys, bass, plucks and organ that change tone mid-note | `docs/RELAY.md` |
| `EMBOUCHURE` | WINDS & BRASS | Reed, brass, whistle and bowed tones shaped by effort | `docs/EMBOUCHURE.md` |
| `BELLOWS` | WINDS & BRASS, KEYS & ORGANS | Accordion and hurdy-gurdy style reed tones with drones | `docs/BELLOWS.md` |
| `MURMUR` | VOICES, SYNTH | Vowel-like FM tones: choir, bass, keys, pluck and lead | `docs/engines/MURMUR.md` |
| `OSPREY` | PADS & FX | Pitched sea swells with bird and animal calls | `docs/OSPREY.md` |
| `OPENSKY` | PADS & FX, SYNTH | Wide, shimmering saw leads and pads, plus a crystal bell | `synth/.../Opensky.kt` |
| `OVERLAP` | PADS & FX, BELLS & MALLETS | Glassy plucks, swelling textures and pulsing breaths | `docs/OVERLAP.md` |
| `OVEN` | KEYS & ORGANS, PLUCKED & STRINGS | Piano-like hammered strings from felt-soft to hard | `docs/OVEN.md` |
| `FLOCK` | PADS & FX, SYNTH | Swarm of drifting oscillators that gathers into one note | `docs/FLOCK.md` |
| `VENT` | PADS & FX, SYNTH | Unpredictable chaotic tones: round, wire, tearing, pulsing | `docs/VENT.md` |
| `CREASE` | SYNTH | Synth tones that bend and relax: round, pinched, rubbery | `docs/CREASE.md` |
| `TRIO` | PLUCKED & STRINGS | Tres, berimbau and charango plucks, solo or together | `docs/TRIO.md` |
| `NACRE` | PLUCKED & STRINGS, PADS & FX | A plucked string duet with shimmering, watery returns | `docs/NACRE.md` |
| `ORIGAMI` | SYNTH, BELLS & MALLETS | Metallic keys and soft sustained tones with folded overtones | `docs/ORIGAMI.md` |
| `TREFOIL` | SYNTH, BELLS & MALLETS | Pitched three-body tones that touch and exchange energy | `docs/TREFOIL.md` |
| `OHM` | PLUCKED & STRINGS | Warm plucked string with octave and glassy companions | `docs/OHM.md` |
| `MERCURY` | BELLS & MALLETS | Glass and steel tones that are tapped, rubbed and bent | `synth/.../Mercury.kt` |
| `CAUSTIC` | SYNTH, BELLS & MALLETS | Resonant singing tones with a full body and long tail | `docs/CAUSTIC.md` |
| `FILAMENT` | PADS & FX, BELLS & MALLETS | Glassy pitched notes with a shimmering tail | `docs/FILAMENT.md` |
| `APSIS` | PADS & FX, BELLS & MALLETS | Evolving ringing tones whose overtones sweep and linger | `docs/APSIS.md` |
| `TIDAL` | PADS & FX, BELLS & MALLETS | Evolving ringing tones with a rough, string-like edge | `docs/TIDAL.md` |
| `CHAMBER` | DRUMS & PERCUSSION, BELLS & MALLETS | Tuned drum, bar, cowbell and triangle hits in a room | `docs/CHAMBER.md` |
| `LIBRATION` | KEYS & ORGANS, BELLS & MALLETS | Wooden and glassy notes with moving, lingering overtones | `docs/LIBRATION.md` |
| `MERIDIAN` | BELLS & MALLETS, PADS & FX | Swinging contacts leave wood, wire and glass ringing | `docs/MERIDIAN.md` |
| `RIME` | SYNTH, KEYS & ORGANS | Contour bass, bright keys and unfolding ringing textures | `docs/RIME.md` |
| `BORE` | WINDS & BRASS | Blown flute and saxophone tones with breath and chiff | `synth/.../Bore.kt` |
| `ARCO` | PLUCKED & STRINGS | Bowed cello, erhu and sarangi, from slow swells to stabs | `synth/.../Arco.kt` |
| `SCOUR` | PADS & FX, BELLS & MALLETS | Shot-blasted plates that build to a slam, then ring | `docs/SCOUR.md` |
| `KEEL` | BASS | Bass notes whose bright attack settles into a heavy body | `docs/KEEL.md` |
| `CHITIN` | BASS, SYNTH | Bass and leads with a shared sync and phaser sweep | `docs/CHITIN.md` |
| `WICK` | SYNTH, PADS & FX | Plucks, bubbles and swells with answering gate motion | `docs/WICK.md` |
| `EROSION` | DRUMS & PERCUSSION | Dry electrical percussion, from low thuds to bright crackle | `docs/EROSION.md` |
| `RIVET` | DRUMS & PERCUSSION | Industrial drums from a small room to a warehouse | `docs/RIVET.md` |
| `FIRED BRONZE` | DRUMS & PERCUSSION | Skin, clay and bronze through a driven processor and amp | `docs/FIRED_BRONZE.md` |
| `BACKWASH` | DRUMS & PERCUSSION | Damped drums with wavering tails and warm amp grit | `docs/BACKWASH.md` |

### E2. Characters (treatment chips) and pedals

Character names keep their names. Descriptions come from the comments beside each entry in `PadSheet.kt` (lines 206-293) and `Pedals.kt` (lines 118-178). Chips whose drawn word differs from the code word are listed with the drawn word first.

| Chip | Row | What it does |
|---|---|---|
| `CRUSH` | WEAR | Twelve-bit, 26 kHz sampler crunch. The crunchiest age. |
| `TAPE` | WEAR | Tape age. |
| `DIRT` | WEAR | Twelve-bit with companding and an 11 kHz lid. Gritty and warm. |
| `SMEAR` | WEAR | A destructive smear. Not verified what it sounds like. |
| `DUST` | WEAR | The tape's own hiss and room under the pad (`docs/DUST.md`). |
| `SWELL` | TRANSIENT | The sound arrives before it strikes. |
| `TAIL` | TRANSIENT | The attack gone, the wash kept. |
| `SMOOTH` (code `SKIM`) | TRANSIENT | The click goes, the thump stays. |
| `GHOST` | TRANSIENT | The tone and attack gone, the breath kept. |
| `SPIKE` | TRANSIENT | The attack leaned on rather than taken away. |
| `PUNCH` | COLOUR | Squashed into crunch, so the transient pops. |
| `RING` | COLOUR | Multiplied by a sine: metal, bells, radio. |
| `DUB` | COLOUR | A dub of a dub of a dub: generation loss. |
| `VINYL` | COLOUR | The record under the hit. |
| `PHASE` | COLOUR | Four swept allpass stages. |
| `CONTOUR` | COLOUR | The ladder filter's contour swept from the hit's onset. |
| `ECHO` (code `SLAP`) | TIME | One tape-delay repeat. |
| `WASH` | TIME | The spring reverb, generously. |
| `ROLL` | TIME | The hit's own head struck again on the kit's grid. |
| `GATE` | TIME | The hit chopped on the kit's grid. |
| `ENSEMBLE` | TIME | Three swimming taps off the finished tone, like a string machine's back half. |
| `SECTION` | TIME | The same section as six independent players. |
| `FLIP` | PLAYBACK | Reversed. Amount only grades the spring tail. |
| `SPIN DOWN` (code `STOP`) | PLAYBACK | The tape capstan lets go. |
| `SPIN UP` (code `START`) | PLAYBACK | The reel spins up. |
| `PITCH` | PLAYBACK | Pitch is speed. Amount fades back toward the original. |
| `IN KEY` (code `TUNE`) | FOLLOWS THE KIT | Every partial retuned to the kit's key. |
| `BODY` | FOLLOWS THE KIT | Resonators tuned to the key, struck by the hit. |
| `WOBBLE` | FOLLOWS THE KIT | A filter sweep on the kit's own grid. |
| `DRONE` (code `ETERNAL`) | FOLLOWS THE KIT | The attack kept, the tail slowed toward forever. |
| `DRIVE` | PEDALS | A drive pedal: the valve amp at the top of its gain (`Pedals.kt:118`). |
| `SUSTAIN` | PEDALS | A compressor (`Pedals.kt:121`). |
| `WAH` | PEDALS | An automatic wah (`Pedals.kt:125`). |
| `TOUCH` | PEDALS | A wah that follows how hard you hit (`Pedals.kt:126`). |
| `ROCK` | PEDALS | The rock wah voicing (`Pedals.kt:127`). |
| `FORGE` | PEDALS | A power boost before the amp, "more powerful without distorting" (`Pedals.kt:130`). |
| `DELAY` | PEDALS | A delay with long, dark repeats (`Pedals.kt:145`). |
| `ROOM` | PEDALS | A drum-room reverb, last in the chain (`Pedals.kt:149`). |
| `FADE IN` | PEDALS | The sound rises over about 450 ms (`Pedals.kt:153`). |
| `VIBRATO` | PEDALS | Pitch wobble for held sounds (`Pedals.kt:158`). |
| `DIVE` | PEDALS | A whammy dive down an octave for held sounds (`Pedals.kt:163`). |
| `HUSH` | PEDALS | A noise gate that shuts the tail (`Pedals.kt:169`). |
| `SUB` | PEDALS | An octave-down copy under the hit (`Pedals.kt:175`). |

---

## Section F: Rulings (owner, 2026-11-09)

The owner took the recommended option (A) on all 14 decisions, with one exception in ruling 14. Rulings 1 to 14 are law for the renames in clarity PR 5. Engine descriptions in Section E are still a draft and need a sound check.

1. **Dock chip.** Keep `LISTEN · MIC` and `LISTEN · APP AUDIO`.
2. **Dock verb.** Keep `SNIP` and `SNIPPED` as the verb. Every noun says `SAMPLE`, for example `BOUNCE TO SAMPLES`.
3. **Bench name.** The bench is `SAVED SAMPLES` everywhere. The place is `SAMPLE`.
4. **MUTATE modes.** `LAYER`, `JOIN`, `CROSSOVER`, `ROOM TONE`, `BLEND`, `BORROW TONE`.
5. **MUTATE itself.** `COMBINE SOUNDS`, undo is `UNDO COMBINE`.
6. **DOUBLES.** `FIND SIMILAR SOUNDS`.
7. **SNAP (Create tool).** `PHOTO TO SOUND`. The Chop row keeps `SNAP`.
8. **OUTSIDE and ROOM.** `RE-AMP AND ROOM`. `ROOM` means only a measured room kept on the shelf. Amount sliders and the pedal say `REVERB`.
9. **Brand-ish names.** Keep `AQUARIUM`, `ORBIT`, `LOOP`, `SONG`, `GRAIN FIELD`, `SCULPT`, `CONFLUENCE VOICES`, `MY VOICE`. Every control that opens one carries the one-line description from Section B. These lines are required copy.
10. **TAKES and VERSIONS.** Earlier states of a pad are `VERSIONS` (`STACK VERSIONS`). `TAKE` stays for a recorded BEAT pass. The round robin count is `ALTERNATES`.
11. **GROOVE and PATTERN.** The screen header is `BEAT`. `READ AS GROOVE` and `COPY GROOVE` stay.
12. **STRIKE.** `VELOCITY STRIP` and `FX BY VELOCITY`.
13. **Tape metaphors in prose.** Rewritten with the `SAMPLE` rename: `LISTENING`, `NEW SAMPLE`, `SHOW COUNTER`, and the other "on tape" prose.
14. **Chips that clash.** `CONTOUR` becomes `FILTER SWEEP`, `RING` becomes `RING MOD`, `GATE` becomes `TRANCE GATE`, and the pedal `HUSH` becomes `NOISE GATE`. **Exception: the chip `SECTION` is not renamed to `SIX PLAYERS`.** It stays `SECTION` for now. Open: needs a sound check for a better name. The song and ARRANGE uses of `SECTION` keep their name, as does everything else that clashes outside the chip row. `FORK TO YOURS` becomes `COPY TO YOURS` and CHOP `GHOSTS` becomes `BETWEEN HITS` under the same rule.
