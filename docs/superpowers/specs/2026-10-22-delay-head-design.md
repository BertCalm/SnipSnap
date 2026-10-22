# The DELAY pedal: ECHO gains a HEAD knob

**Status:** design, 2026-10-22, written from the owner's two listens of the pedal board's Stage 1b page and the owner's request "Write the DELAY spec: ECHO gains a HEAD knob"; reviewed adversarially before commit; not built. The plan follows this document; the DELAY chip joins the pedals only if the gate at the end passes.
**Builds on:** the pedal board spec (`2026-10-04-pedal-board-design.md`), its brainstorm (`2026-10-19-pedal-board-brainstorm.md`, idea 2, DELAY), the pedal library design (`2026-10-21-pedal-library-and-board-design.md`, proposed, not built: the owner's "five or six on a pad at once" direction) and its benchmark (`2026-10-21-pedal-board-benchmark.md`, F2, the fade law), the Stage 1b listens (`PedalListensGenerator`, PR #609 and PR #619), ECHO (`synth/.../Echo.kt`), and ENSEMBLE's SECTION, the precedent for a macro added to a section that already ships.
**Roadmap row:** the pedal board's row, S30 in `docs/SYNTH_ROADMAP.md`; this extends it.

## Why

A delay pedal repeats what goes into it. On a drum hit the repeat lands in silence and is heard as the hit again; on a held note it lands on the note itself, which is still sounding. The owner's first listen of the Stage 1b page found the attack does not come back on the choir and the jangle: "delay doesn't really seem to have the intended effect of choir and jangle. You don't hear the attach repeat/echo like with a short hit." Measured on the page's own clips (PR #619): where the first repeat lands, 225 ms in, the snare is about 46 dB under its hit and the repeat lifts that moment 37 dB; the jangle is only 2 dB under its hit and the repeat moves it half a decibel.

A follow-up sheet put ROLL and three attack-fed echo prototypes beside the plain DELAY. The owner's verdict named the plain DELAY and one prototype together, and asked for that prototype's mechanism as a knob on ECHO. This design builds it.

**Success:** ECHO gains HEAD, and every recipe and saved kit that uses ECHO today renders bit for bit as it does now; one DELAY chip that is a delay on a drum hit and brings a held note's attack back as a repeat you can hear (how loud its drum repeats should be is a gate question, below); the chip passes the owner's gate, or it does not ship.

## What the owner heard and asked for

- **2026-10-21, the Stage 1b page:** "Everything sounds good, but delay doesn't really seem to have the intended effect of choir and jangle. You don't hear the attach repeat/echo like with a short hit."
- **2026-10-22, the long-note sheet** (ROLL at two divisions, three attack-fed prototypes, the plain DELAY at 100; on the jangle and the choir only): "Delay at 100 and head at 120 are the most like a typically delay. The roll has a different vibe, more mechanical but I do like it as a secondary option."
- **2026-10-22, the request for this spec:** "Write the DELAY spec: ECHO gains a HEAD knob".

None of the page's chip answers reached its answer store except one (SWELL on the jangle: yes); the owner answered in chat, so the page's own questions (which delay for a long note, whether the attack-fed echo should be a section, the slate) have no stored answer.

"Delay at 100" is ECHO at the brainstorm's DELAY values (TIME 0.75, REPEAT 0.85, TONE 0.35, MIX 0.5); on the long-note sheet it is the same clip the first listen faulted on these two sources, so the second verdict ranks the faulted clip level with the head-fed one. "head at 120" is the page's clip "Head 120": the prototype `PedalListensGenerator.attackEcho` with `Feed.HEAD`, the same values, but ECHO's repeat line fed by only the first 120 ms of the note (its last 40 ms a raised-cosine fade) while the whole note plays on. That prototype also ignored MIX and lifted each channel's repeats so its first repeat peaked where the note's own attack peaked (gains of 1.27 on the jangle and 1.54 and 1.37 on the choir's two channels, measured with the prototype itself). So the clips the owner liked ran their repeats about 8 to 10 dB hotter than the plain DELAY's MIX 0.5 (8.1 dB on the jangle; 8.7 and 9.8 on the choir's channels), as well as feeding them differently.

**The reading this design takes.** The owner asked for HEAD by name, so HEAD is built. What the verdict leaves open is the chip's recipe: both clips are a delay to the owner's ear on these sources, and the head-fed one is the one that answers the first complaint, so the chip's recipe turns HEAD on. That is a reading, not the owner's words. The gate plays HEAD off at the same level and the plain DELAY as the owner heard it, so the gate can overturn it: if HEAD off wins, the chip's recipe is the plain one and HEAD ships as a macro for kits and the CLI.

## What the numbers say

Measured 2026-10-22 with two scratch tests on commit `c5274e0f`, the author's (`ScratchDelayProbe`) and the review's (`ReviewProbe`, which runs the real `attackEcho` and reproduces the shipped ECHO bit for bit at HEAD 0), at the DELAY values, on the four gate sources (the snare, the choir, and both MAGNET guitars as the kit has them, through their landing amps). Neither scratch test is committed; the plan's first step re-creates the probe in test source, and the tests pin the numbers decision 3 rests on. "HEAD on" below is the head-fed feed at the given MIX, with no lift.

**The first repeat's onset is the same either way.** A repeat's first 80 ms (the window less its fade) is the repeat of the note's first 80 ms, which the head feeds whole, so the first repeat's onset, and its peak on these sources, reads the same with HEAD on and off at the same MIX, up to the peak match's one gain (the review measured the two renders identical at 20, 60 and 79 ms into the repeat and diverging from 81 ms). HEAD changes what follows: whether the repeat stops when the head has passed, or keeps repeating the sustained body.

**HEAD makes the repeats discrete.** The rms of the whole output, dry note included, from 10 ms after the head's first repeat ends to 5 ms before the second repeat lands (355 to 445 ms at AMT 100), against the rms of the first repeat's first 20 ms (225 to 245 ms), at MIX 1.0 and AMT 100:

| Source | HEAD off | HEAD on |
|---|---|---|
| Choir | +1.3 dB | −10.8 dB |
| Chug | −0.7 dB | −3.4 dB |
| Jangle | 0.0 dB | −1.5 dB |
| Snare | −39.9 dB | silence |

Both levels include the dry note, which keeps ringing under the repeats on the guitars; that is why HEAD moves the jangle only 1.5 dB.

**MIX is what lets a guitar's pick through its own body.** The jangle's attack is only 2 dB over the body it sustains into (PR #619), so its repeat has to be loud to be heard. On the jangle: the peak of the first repeat's first 25 ms over the peak of the 20 ms ending 5 ms before it. On the snare: the rms of the first repeat's first 20 ms against the rms of the hit's first 20 ms. HEAD on throughout:

| Recipe MIX | Jangle, AMT 100 | Jangle, AMT 70 | Snare, AMT 100 | Snare, AMT 70 |
|---|---|---|---|---|
| 0.5 | 1.8 dB | 2.4 dB | −8.1 dB | −11.3 dB |
| 0.75 | 3.1 dB | 3.5 dB | −4.6 dB | −7.8 dB |
| 1.0 | 4.3 dB | 4.6 dB | −2.1 dB | −5.3 dB |

The liked head clip stood 5.3 dB on the jangle. On the choir, as the page played it (folded to mono), the liked clip measures 0.5 dB peak and 3.0 dB rms by the jangle's metric, against −1.3 and 1.8 at recipe MIX 1.0 at AMT 100, and 0.8 and 2.1 at AMT 70. The plain DELAY the owner passed on the snare (at 70 and 100 on the Stage 1b page, "Everything sounds good") put its first repeat 11.3 and 8.1 dB under the hit. At a matched AMT, recipe MIX 1.0 puts the snare's repeat 6.0 dB hotter than that plain DELAY: twice its level. No single MIX reproduces both: the long-note pick wants the repeats hot, and a hot repeat on a drum is a loud, dub-style echo.

**AMT 70 is not just quieter.** The pad sheet's first tap fades every macro toward its neutral, so at AMT 70 the repeats are 151 ms apart instead of 225 ms (both liked clips were at 225 ms), the feedback is 0.51 instead of 0.66, and the loop's low-pass is near 2.0 kHz instead of 2.4. On the jangle, AMT 100 is not louder than 70 (the pick stands 4.3 dB at 100, 4.6 at 70); only the drums get hotter with AMT.

## Decisions taken

1. **A macro on ECHO, not a section.** The owner asked for a HEAD knob on ECHO (the page had offered "a DELAY section with an ATTACK knob", with no stored answer; the request settles it). The head-fed echo is ECHO's loop with one difference, what feeds it; a second section would copy that loop, the one-quantity-in-two-places defect the house keeps finding. HEAD defaults to 0 with neutral 0, and at 0 the loop renders today's samples exactly (below).
2. **HEAD's job is the feed; the level stays MIX's.** The prototype's lift, a gain found per channel from the first repeat's peak, is dropped: it is an input-dependent second level control beside MIX, and with MIX at its top it added 2.0 to 3.8 dB on the clips the owner heard. The recipe sets MIX instead.
3. **The recipe is MIX 1.0, and the gate can take it down.** At the pad sheet's default AMT 70 that brings the jangle's pick to 4.6 dB (the liked clip, 5.3) and the choir to 2.1 dB rms on the page's mono fold (the liked clip, 3.0). The cost is on drums: the snare's first repeat 5.3 dB under the hit at 70 and 2.1 under at 100, 6 dB hotter than the plain DELAY the owner passed at each AMT. The gate plays MIX 0.75, and HEAD on at the plain DELAY's MIX 0.5 (which keeps the snare where the owner passed it and nearly all of HEAD's separation on the choir, −9.9 dB, at the cost of the jangle falling back to 1.8 dB). Whichever the owner picks is the recipe's MIX.
4. **HEAD reaches full at the first tap.** As WAH's weights do (`Wah.reach`), HEAD is stretched by 1.5 and capped at 1, so the recipe's HEAD 1.0 fades to 0.7 at AMT 70 and still feeds only the head. Below AMT about 67 the body starts to feed the repeats again, smoothly.
5. **One chip, DELAY, with its recipe `delayed`, after the gate, wherever pedals live then.** If the pedal library's registry lands first, DELAY is one entry in it (word DELAY, key `delayed`, side POST, not movable, `gated` false until this gate passes, `fade` false), not a `CHARACTER_FOR` line. If DELAY lands first, it is a line in `PadSheet.CHARACTER_FOR` on today's fixed row, where it takes one of the six slots the pedal board spec planned for DRIVE, COMP, WAH, TREM, FLANGE and FUZZ, which is the owner's choice. Either way ECHO already sits after the amp, where a rig puts a delay, so on a guitar pad the pad sheet's way (the chip on the baked audio) is already the rig's way: no dependency on the pedal board's Stage 3.
6. **ROLL stays where it is.** It is in the app today on the TIME row (a keyed treatment, drawn beside SLAP and GATE), and the owner likes it as "a secondary option". This design reads that as: nothing is built for it. If the owner meant a second delay offered beside DELAY, that is a separate library entry (question 4). The brainstorm's tempo-synced delay (the keyed ON BEAT twin) is a separate, later idea.

## The macro

| Macro | Default | Neutral | Meaning |
|---|---|---|---|
| HEAD | 0 | 0 | how much of the repeats is fed by the note's head alone: 0 repeats the whole note (ECHO today), 1 repeats only the head |

**Constants and helpers** (in `Echo`, `internal` where tests call them): `HEAD_OFF = 0.001f`; `HEAD_SEC = 0.12f`; `reach(macros) = if (HEAD <= HEAD_OFF) 0f else (1.5f * HEAD).coerceIn(0f, 1f)`; `headFrames(delay, rate) = min((HEAD_SEC * rate).toInt(), (0.8f * delay).toInt())`, computed in Float as the prototype does, with the fade `headFrames / 3`. At the recipe's values the delay is 9931 frames and the window 5292 (120 ms, the last 1764 faded: the prototype the owner heard); at TIME 0.375 the delay is 5126 and the window 4100 (93 ms); at TIME 0 the delay is 2646 and the window 2116 (48 ms). The window shrinks with a short TIME so a repeat of the head always ends before the next repeat lands. The 120 ms, the third, the 0.8 and the 1.5 are shape, from the heard prototype, the discrete-repeat rule and WAH's stretch.

**The feed.** With `w(f)` the window (1 inside, the raised-cosine fade, then 0), the bus is fed `x × (1 − reach × (1 − w(f)))` instead of `x`: the whole note at reach 0, the head alone at reach 1. Everything else is ECHO's: one delay, one one-pole low-pass in the loop so each repeat is darker, repeats counted until they fall under −60 dB, the 1.1 s tail ceiling, the dry note added back untouched at full level, MIX scaling the repeats, and the peak match. HEAD does not change the tail's length.

**One loop, bit for bit at HEAD 0.** At `HEAD <= HEAD_OFF` the reach is exactly 0f, so the feed factor is exactly 1f (`1f − 0f × (1f − w)` is 1f, and `x × 1f` is `x` for every finite float, -0f included; Kotlin on the JVM rounds each operation separately and fuses nothing). So the existing loop, with that one factor added, renders today's samples bit for bit, and the hash pins below prove it; no second copy of the loop is kept (ENSEMBLE kept a separate path for SECTION; here the only change is a multiply by exactly 1f). The window is the same for every channel and the bus runs per channel, so a stereo pad stays stereo-identical and a mono pad stays mono.

## The recipe

`delayed` = `echo {TIME 0.75, REPEAT 0.85, TONE 0.35, MIX 1.0, HEAD 1.0}`, appended at the end of `Treatments.EXTRA` as it stands at the plan's base (after `rockwahed` on `c5274e0f`). No macro sits on its neutral (all five neutrals are 0). What the pad sheet's default AMT 70 makes of it: repeats 151 ms apart (225 ms at 100), feedback 0.51 (0.66), the loop's low-pass near 2.0 kHz (2.4 kHz), MIX 0.7, and HEAD at full through the stretch. If the owner wants the liked 225 ms spacing at the first tap, the recipe can author TIME higher instead (TIME 1.0 gives 206 ms at 70 and 350 ms at 100); the benchmark's fade law (F2) is for gain macros and does not answer spacing.

The chip word is DELAY. It appears nowhere on the sheet today (ECHO is SLAP's display word on the TIME row). In the pedal library, DELAY and SLAP set the same section, `echo`, so one board holds one of them: the later pick wins the section and the sheet names the owner (library design, decision 4).

## Tests and the gate

ECHO tests, in a new `EchoTest` beside `FxTest`'s ECHO block, each mutation-checked, bars at the measured value minus a stated margin:

- **HEAD 0 is ECHO today, byte for byte.** Before any change, the plan records SHA-256 hashes (little-endian float samples, as `TrioSingleHitTest` hashes) of `Echo.process` on the kick, the snare and the choir at `slapback`'s values, SIREN's `LANDING_ECHO`, ECHO's defaults, and three settings drawn once with `Random(0)`, `Random(1)` and `Random(2)` over the four names TIME, REPEAT, TONE, MIX and written into the test as four-key literal maps (never re-drawn through `Echo.scramble` or `Echo.MACROS`, which gain a fifth draw). Each is rendered with HEAD absent, 0, 0.0005 and `HEAD_OFF`; all must match the recorded hash. A one-operation change in the loop must fail it.
- **The precedent's pins** (`EnsembleSectionTest`'s three): the HEAD-off values on a mono, a doubled (L = R) and a hard-left pad render identical samples; `{"fx":1,"reverse":false,"echo":{"TIME":0.25,"REPEAT":0.3,"TONE":0.4,"MIX":0.5}}` reads back with no HEAD, writes back without the key, and renders as `slapback`; `Echo.MACROS` names TIME, REPEAT, TONE, MIX, HEAD in that order, HEAD's default and neutral 0, and `Echo.scramble(Random(0))` keeps its first four draws.
- **HEAD on, the contract clauses the shared tests do not reach.** `FxTest` runs determinism, stereo identity and no-widening at each section's defaults, where HEAD is off (only its random rolls, the JSON round trip and AMT 0 reach every macro). So at the recipe's values with HEAD 1: two renders of the snare are identical; a doubled kick and a doubled choir keep identical channels frame by frame; a hard-left pad keeps a silent right channel; a mono pad stays mono.
- **The onset is unchanged by HEAD.** On the choir at the recipe's values, over frames `[0, delay + headFrames − headFrames / 3)`, the HEAD 1 render equals the HEAD 0 render times one constant `k = out1[p] / out0[p]` (p the frame of largest `|out0|` in that range), to 1e-5. The fade and the rest of the repeat are where HEAD acts and are not compared.
- **HEAD makes the repeats discrete.** On the choir at the recipe's values, the gap level defined above with HEAD 1 is at least 9 dB under HEAD 0's (measured 12.1: +1.3 to −10.8 dB). On a synthetic held tone with a click at its start, the plan measures the same quantity first and sets the bar at the measurement minus 3 dB.
- **The window.** `headFrames(Echo.delayFrames(t, 44_100), 44_100)` is 2116, 4100 and 5292 at TIME 0, 0.375 and 0.75. At TIME 0 with HEAD 1 on a held tone, the wet alone (`(out / k − dry) / mix`, with `k = out / dry` on a frame before the first repeat) is under −60 dB against its own peak over `[delay + headFrames + 2 ms, 2 × delay − 1 ms]` (110 to 119 ms).
- **The stretch.** `reach` is 1f at HEAD 2/3 and at 0.7 (the recipe at AMT 70), and 0.9 ± 1e-6 at HEAD 0.6; HEAD 0.7 renders the same samples as HEAD 1, and HEAD 0.6 does not.
- **The numbers decision 3 rests on.** The re-created probe prints both tables; tests pin the jangle's 4.6 dB and the snare's −5.3 dB at the recipe's AMT 70, with stated margins.
- **The rack's contract tests pass unchanged**, with no exemption added (peak within 0.05 holds because ECHO's own peak match runs on any feed).
- **`delayed`** sets only `echo`, no macro on its neutral, a bypass at AMT 0, and `TreatmentsTest`'s names list ends with it.

**The owner's gate (short).** A new sheet, rendered by the real ECHO, on the snare, the choir, the jangle and the chug:
- the pad as the kit has it;
- `delayed` at AMT 70 and 100;
- the same values with HEAD 0, at 70 and 100 (the same level, so HEAD alone is heard);
- the plain DELAY as the owner heard it (MIX 0.5, HEAD 0), at 70 and 100;
- `delayed` at recipe MIX 0.75 at 70 and 100, on the snare and the jangle;
- HEAD on at recipe MIX 0.5 at 100, on the snare, the choir and the jangle.

The questions: is it a delay on each source; on the snare, are the repeats too loud at 70, and at 100; does HEAD bring the attack back on the held notes; MIX 1.0, 0.75 or 0.5; at 70 the repeats are 151 ms apart and at 100 225 ms apart, which is the DELAY; should DELAY join the pedals. The MIX picked becomes the recipe's MIX and the pinned bars move with it; a spacing answer may move TIME as above; if no setting passes, the chip does not ship.

The long-note sheet is then retired with its prototypes: `attackEcho`, `Feed`, `HEAD_SEC`, `HEAD_FADE_SEC`, `DUCK_DB`, `MAX_FEED_GAIN` and the sheet's ten clips leave the generator, its pinned count moves from 58 to 48 plus the new sheet's, and the published page keeps the clips the owner heard (they are not re-rendered under the same ids, which would point the recorded verdict at a different sound).

## Registration and hazards

- **No new section.** `FxChain` and its `SECTIONS` table do not change; `FxChain.VERSION` stays 1; `echo` keeps its saved key. HEAD is appended to `Echo.MACROS`, and only a recipe that sets HEAD writes the key (pinned above).
- **A kit saved with HEAD cannot be read by an older build.** `FxChain`'s constructor refuses a macro its section does not know, so an older app throws on `echo.HEAD`. ENSEMBLE's SECTION has the same edge; the app updates in one direction on one phone, and MPC exports carry audio, not recipes. Disclosed, not fixed.
- **No seeded draw moves.** `Echo.scramble` has no caller in main source (grepped on `c5274e0f`); BREED crosses only the macro keys its parents already hold (`Breed.kt` `crossMacros`); the remix bank indexes `Shuffle.TREATMENTS` with a seeded `Random`, and its `slapback` sets no HEAD.
- **Pinned lists.** `TreatmentsTest`'s names list gains `delayed`; the sheet's inventory and counts (or the library registry's) change only when the chip lands, after the gate. The ECHO tests that exist (MIX 0 is a copy, repeats land where TIME says, REPEAT lengthens the trail within the tail bound, an echoed kick is still a kick) are untouched by design.
- **The word.** HEAD also names a tape echo's playback-head selector. The app and the CLI show no per-section dials (the CLI names characters such as `delayed`, never macros), so only a kit's saved recipe shows the word; it is kept because the owner asked for it by that name.
- **Known edge: the head is the pad's start, not its hit.** A pad whose hit is not at its first frame repeats its first 120 ms rather than its hit. Three ways reach it: a pad trimmed with silence before its hit, from the DELAY chip alone; the CLI's `treat`, which stacks on the current audio (`KitBuilder.treatPad`: "Treatments stack"), so `swelled` then `delayed`; and the sheet, which stacks too when the bin holds no earlier take (`NOT_BINNED`, `PadSheetScreen.kt`), so a bank-B `reversed` twin then the DELAY chip. Anchoring the window at the pad's onset is the fix if a listen finds it. Disclosed, not fixed.
- **The tail cap.** The rack lets no pad grow by more than one second past its own end. On the Stage 1b clips (MIX 0.5; the snare, the choir and the jangle) it landed 48 to 80 dB under the hit (PR #609). `delayed` moves that: with HEAD 0, MIX 1.0 lifts every repeat 6 dB, so the cap lands 34 to 76 dB under the peak on the four gate sources at AMT 100, the chug the hottest (34 dB, and 37 for the plain DELAY at 100); with HEAD on, only the head repeats, so the cap lands on silence on the snare and the choir, and 90 and 52 dB under on the jangle and the chug (the review's scratch, not committed). The gate's HEAD 0 and plain chug clips are where a cut could be heard; the gate's clips re-measure it.
- **Docs edited with the build.** `docs/CLI.md`'s `treat` reference gains `delayed` (CLI and saved kits only until its chip lands); `README.md`'s ECHO entry gains HEAD, as ENSEMBLE's gained SECTION; `Echo.kt`'s KDoc and a comment on the new `MacroSpec`; the S30 row in `docs/SYNTH_ROADMAP.md` records the long-note verdict, HEAD built as a macro and not a section, and the gate.
- **The base moves.** `delayed` goes at the end of `Treatments.EXTRA` as it stands at the plan's base; the pedal library's registry and the SAY design append there too, and whichever lands second re-pins the names list.

## Out of scope

A tempo-synced delay (the keyed ON BEAT twin); ducking the note under the repeats and feeding the repeats from SPIKE's attack detection (both heard on the long-note sheet and not named); a knob for the head's length; ping-pong or any stereo widening (ENSEMBLE alone may widen); TIME past 350 ms; the fade law (F2); the pedal library itself; presets.

## Effort, from footprints

Small: about 20 lines in `Echo.kt` (the constants, `reach`, `headFrames`, the window and the one factor in the loop), one recipe, `EchoTest`, the re-created probe, the gate's sheet in the generator with the long-note sheet retired, the docs above, and the chip's registration after the gate.

## Verification

`./gradlew :shell:cleanTest :cli:cleanTest`, then `./gradlew --no-daemon test` judged by its exit code (the steward skill's rule; the `:shell` source laws run only when forced), the gate's clips rendered and published, and the owner's gate. A recipe that fails its gate ships without a chip.

## Verified, measured, proposed, heard

- **Verified** (read or grepped on `c5274e0f`): ECHO's loop and macros, all with neutral 0; `Echo.scramble` has no main-source caller; BREED's `crossMacros` crosses only present keys; `FxChain`'s constructor refuses an unknown macro; ENSEMBLE's `SECTION_OFF` and `EnsembleSectionTest`'s pins; SIREN's `LANDING_ECHO` and the bank's `slapback` set no HEAD; DELAY appears nowhere on the pad sheet; ROLL is on the TIME row; the PEDALS row holds DRIVE and COMP; the CLI names no macros; `treatPad` stacks and the sheet stacks when `NOT_BINNED`; `FxTest` runs determinism, stereo identity and no-widening at defaults.
- **Measured** (the two scratch tests above, 2026-10-22, Kotlin, not committed): every dB figure and frame count in this document except the Stage 1b pages' own (225 ms, 37 dB, half a decibel, 46 dB, the jangle's 2 dB of attack over its body, 48 to 80 dB), which were measured on the page's WAVs for PR #609 and PR #619.
- **Proposed** (shape): `HEAD_SEC` 120 ms, the fade over the window's last third, the window's cap at 0.8 of the delay, the stretch of 1.5, the recipe's MIX 1.0, the bars' margins.
- **Heard** (the owner): the two verdicts quoted above. Nothing in this design has been heard as built.

## Why it is built this way

- **A knob on ECHO, not a new pedal section.** The head-fed clip you liked is ECHO with a different input, and its repeats were also about 8 to 9 dB louder. The input becomes the HEAD knob, so the code keeps one delay loop and everything that already uses ECHO (the SLAP chip, SIREN's landing, saved kits) is untouched because HEAD starts at zero. The loudness is ECHO's own MIX turned up.
- **HEAD decides what repeats; MIX decides how loud.** On the choir, HEAD turns overlapping repeats into separate ones: between repeats the level drops from about the repeat's own level to 10 dB under it. On the guitars HEAD does little, 1.5 dB on the jangle and 2.7 on the chug, because the string keeps ringing under the repeats; the guitar's pick is only 2 dB above the string, so on the guitars it is MIX, not HEAD, that lifts the echo. The gate plays HEAD off at the same level so you can hear what HEAD alone does.
- **The first tap.** At 70 the guitar's echo stands 4.6 dB over the note (the clip you liked: 5.3) and the repeats are 151 ms apart (the clip you liked: 225 ms). On the snare the repeat is 6 dB louder than the plain delay you passed at the same setting, about twice its level.
- **The risky decision** is MIX 1.0. It lifts the guitar's echo toward the clip you liked, and makes a drum's echo louder than the plain delay you passed. The gate plays 0.75 and 0.5 beside it, and you pick.

## Questions for the owner

1. Delay at 100 and head at 120: equal, or is the head one better on long notes? HEAD is built either way, since you asked for it; this decides whether the chip's recipe turns it on, and the gate plays both.
2. At the gate: on the drums, MIX 1.0, 0.75 or 0.5?
3. At the gate: at the first tap the repeats are 151 ms apart (the clips you liked were 225 ms); keep that, or author the recipe for longer spacing?
4. ROLL "as a secondary option": it stays where it is on the TIME row; or did you mean a second delay offered beside DELAY in the pedal library?
