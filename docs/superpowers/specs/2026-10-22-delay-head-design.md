# The DELAY pedal: ECHO gains a HEAD knob

**Status:** design, 2026-10-22, written from the owner's two listens of the pedal board's Stage 1b page; not built. The plan follows this document; the DELAY chip joins the PEDALS row only if the gate at the end passes.
**Builds on:** the pedal board spec (`2026-10-04-pedal-board-design.md`), its brainstorm (`2026-10-19-pedal-board-brainstorm.md`, idea 2, DELAY), the Stage 1b listens (`PedalListensGenerator`, PR #609 and PR #619), ECHO (`synth/.../Echo.kt`), and ENSEMBLE's SECTION, the precedent for a macro added to a section that already ships.
**Roadmap row:** the pedal board's row, S30 in `docs/SYNTH_ROADMAP.md`; this extends it.

## Why

A delay pedal repeats what goes into it. On a drum hit the repeat lands in silence and is heard as the hit again; on a held note it lands on the note itself, which is still sounding, and only thickens it. The owner heard exactly that on the Stage 1b page: "delay doesn't really seem to have the intended effect of choir and jangle. You don't hear the attack repeat like with a short hit." Measured on the page's clips, the first repeat lands 225 ms in, where the snare is 46 dB under its hit (the repeat adds 37 dB) and the jangle is still at the hit's level (the repeat moves the level by half a decibel).

A follow-up sheet put three answers beside the plain DELAY. The owner's verdict picked one of them, and this design builds it into ECHO.

**Success:** one DELAY chip that repeats a drum hit the way the plain DELAY does, and brings a held note's attack back as a repeat you can hear; every recipe and saved kit that uses ECHO today renders bit for bit as it does now; the chip passes the owner's gate, or it does not ship.

## What the owner heard and picked

- **2026-10-21, the Stage 1b page:** "Everything sounds good, but delay doesn't really seem to have the intended effect of choir and jangle. You don't hear the attach repeat/echo like with a short hit."
- **2026-10-22, the long-note sheet** (ROLL at two divisions, three attack-fed echo prototypes, and the plain DELAY at 100, on the jangle and the choir): "Delay at 100 and head at 120 are the most like a typically delay. The roll has a different vibe, more mechanical but I do like it as a secondary option."

"Delay at 100" is ECHO at the brainstorm's DELAY values (TIME 0.75, REPEAT 0.85, TONE 0.35, MIX 0.5). "Head 120" is the prototype `PedalListensGenerator.attackEcho` with `Feed.HEAD`: the same values, but ECHO's repeat line fed by only the first 120 ms of the note (its last 40 ms a raised-cosine fade), while the whole note plays on. Two details of that prototype matter here. It ignored MIX: its repeats were added at full level and then lifted again so the first repeat peaked where the note's own attack peaked (a gain of 1.27 on the jangle, 1.37 on the choir, 1.47 on the snare, measured). So the clip the owner liked ran its repeats about 8 to 9 dB hotter than the plain DELAY's MIX 0.5, as well as feeding them differently.

The two picks are read here as one answer: on a drum hit the two are the same delay (the first repeat is the same audio, below), and on a held note the head-fed one is the one that brings the attack back, so the chip is the head-fed recipe. If the owner meant the plain one is better on long notes too, the chip keeps HEAD off and nothing else in this design changes; that is the first question at the end.

## What the numbers say

Measured 2026-10-22 with a scratch test on commit `c5274e0f`, at the DELAY values, on the four gate sources (the snare, the choir, and both MAGNET guitars as the kit has them, through their landing amps). The scratch test is not committed; the plan's tests pin the numbers that matter. "HEAD on" is the head-fed feed at the given MIX with no lift.

**The first repeat is the same either way.** A repeat's first 120 ms is the repeat of the note's first 120 ms, which the head feeds whole, so the first repeat's window reads identically with HEAD on and off at the same MIX. HEAD changes what happens after it: whether the repeat stops when the head has passed, or keeps repeating the sustained body.

**HEAD makes the repeats discrete.** The level in the gap after the first repeat of the head, before the second lands, against the level during the repeat, at MIX 1.0 and AMT 100:

| Source | HEAD off | HEAD on |
|---|---|---|
| Choir | +1.3 dB | −10.8 dB |
| Chug | −0.7 dB | −3.4 dB |
| Jangle | 0.0 dB | −1.5 dB |
| Snare | −39.9 dB | silence |

**MIX is what lets a guitar's pick through its own body.** The jangle's attack is only 2 dB over the body it sustains into, so its repeat has to be loud to be heard. How far the first repeat's peak stands over the 20 ms before it on the jangle, and the snare's first repeat against its hit, with HEAD on:

| Recipe MIX | Jangle, AMT 100 | Jangle, AMT 70 | Snare, AMT 100 | Snare, AMT 70 |
|---|---|---|---|---|
| 0.5 | 1.8 dB | 2.4 dB | −8.1 dB | −11.3 dB |
| 0.75 | 3.1 dB | 3.5 dB | −4.6 dB | −7.8 dB |
| 1.0 | 4.3 dB | 4.6 dB | −2.1 dB | −5.3 dB |

The clip the owner liked stood 5.3 dB on the jangle (and 1.5 dB peak, 2.0 dB rms on the choir, against 0.1 and 1.1 at recipe MIX 1.0). The plain DELAY the owner liked on the snare put its first repeat 8.1 dB under the hit. No single MIX reproduces both: the long-note pick wants the repeats hot, and a hot repeat on a drum is a loud, dub-style echo.

## Decisions taken

1. **A macro on ECHO, not a section.** The head-fed echo is ECHO's loop with one difference, what feeds it. A second section would copy that loop, the one-quantity-in-two-places defect the house keeps finding. ECHO gains HEAD, default 0 and neutral 0, and at 0 it runs the very code that runs today, the way ENSEMBLE's SECTION does below its `SECTION_OFF`.
2. **HEAD's job is the feed; the level stays MIX's.** The prototype's lift (a gain found from the first repeat's peak) is dropped: it is an input-dependent second level control beside MIX, and with MIX at its top it only added 2 to 3.3 dB. The recipe sets MIX instead.
3. **The recipe is MIX 1.0, so the first tap is the balanced one.** At the pad sheet's default AMT 70 the recipe's MIX fades to 0.7: the jangle's pick stands 4.6 dB (the liked clip, 5.3) and the snare's first repeat is 5.3 dB under the hit (the liked plain DELAY, 8.1). AMT 100 is the hot end: the jangle at 4.3, the snare at 2.1 under. MIX 0.75 is the measured fallback if the drums are too loud (table above).
4. **HEAD reaches full at the first tap.** As WAH's weights do, HEAD is stretched by 1.5 and capped at 1, so the recipe's HEAD 1.0 fades to 0.7 at AMT 70 and still feeds only the head. Below AMT about 67 the body starts to feed the repeats again, smoothly.
5. **One chip, DELAY**, on the PEDALS row after the gate. Its recipe is `delayed`. ECHO already sits after the amp in the rack, where a rig puts a delay, so on a guitar pad the pad sheet's way (the chip on the baked audio) is already the rig's way: no dependency on the pedal board's Stage 3.
6. **ROLL stays the secondary option, unchanged.** It is in the app today on the KEYED row, and the owner likes its "more mechanical" vibe on long notes. Nothing is built for it here; the brainstorm's tempo-synced delay (the keyed ON BEAT twin) is a separate, later idea.

## The macro

| Macro | Default | Neutral | Meaning |
|---|---|---|---|
| HEAD | 0 | 0 | how much of the repeats is fed by the note's head alone: 0 repeats the whole note (ECHO today), 1 repeats only the head |

**The head window.** The first `min(HEAD_SEC, 0.8 × delay)` of the pad, with `HEAD_SEC` 120 ms, its last third a raised-cosine fade to zero: 120 ms with a 40 ms fade at the recipe's values, the prototype the owner heard. The window shrinks with a short TIME so a repeat of the head always ends before the next repeat lands (at TIME 0.375, a 116 ms delay, the window is 93 ms). The 120 ms, the third and the 0.8 are shape, from the heard prototype and the discrete-repeat rule.

**The feed.** With `w(f)` the window (1 inside, the fade, then 0) and `reach = min(1, 1.5 × HEAD)`, the bus is fed `x × (1 − reach × (1 − w(f)))` instead of `x`: the whole note at reach 0, the head alone at reach 1. Everything else is ECHO's: one delay, one one-pole low-pass in the loop so each repeat is darker, repeats counted until they fall under −60 dB, the 1.1 s tail ceiling, the dry note added back untouched at full level, `MIX` scaling the repeats, and the peak match.

**The bypass.** `HEAD ≤ 0.001` runs today's loop, written out as it is now, so every recipe that does not set HEAD (the bank's `slapback`, SIREN's landing echo, every saved kit) renders byte for byte as before. The window is the same for every channel and the bus runs per channel, so a stereo pad stays stereo-identical and a mono pad stays mono. HEAD does not change the tail's length.

## The recipe and the chip

`delayed` = `echo {TIME 0.75, REPEAT 0.85, TONE 0.35, MIX 1.0, HEAD 1.0}`, appended to `Treatments.EXTRA` after `rockwahed`. No macro sits on its neutral (all five neutrals are 0). What the pad sheet's default AMT 70 makes of it: repeats 151 ms apart (225 ms at 100), feedback 0.51 (0.66), the loop's low-pass near 2.0 kHz (2.4 kHz), MIX 0.7, and HEAD at full through the stretch.

The chip word is DELAY, on the PEDALS row through `PadSheet.CHARACTER_FOR`, and only after the gate. DELAY is on no other row (ECHO is the display word for SLAP on the TIME row, and DELAY appears nowhere on the sheet today). The row holds DRIVE and COMP, with WAH waiting on its own gate; DELAY would be the third or fourth of six, so it fits whatever the owner decides about a swappable slate.

## Tests and the gate

ECHO tests, in `FxTest`'s ECHO block or a new `EchoTest`, each mutation-checked, bars at the measured value minus a stated margin:

- **HEAD 0 is ECHO today, byte for byte.** Before any change, the plan records SHA-256 hashes of `Echo.process` on the kick, the snare and the choir at `slapback`'s values, SIREN's `LANDING_ECHO`, ECHO's defaults and three seeded random settings; the test pins them, with HEAD absent and with HEAD 0. A one-operation change in the bypass branch must fail it.
- **HEAD makes the repeats discrete.** On the choir at the recipe's values, the gap after the first repeat falls by at least the measured margin with HEAD 1 against HEAD 0 (measured above: +1.3 dB to −10.8 dB); on a synthetic held tone with a click at its start, likewise.
- **The first repeat is unchanged by HEAD** at the same MIX (the window covers it), within the peak match's scaling.
- **The window shrinks with TIME**: at TIME 0 (a 60 ms delay) the head window is 48 ms and the repeats still have a gap.
- **The stretch**: HEAD 2/3 and HEAD 1 render the same samples.
- **The rack's contract tests pass unchanged** (they iterate every section and every macro): deterministic, finite, peak within 0.05, stereo-identical, JSON round trip, AMT 0 on every neutral, no mono widening. No exemption is added.
- **`delayed`** sets only `echo`, no macro on its neutral, a bypass at AMT 0, and `TreatmentsTest`'s names list ends with it.

**The owner's gate (short).** Rendered by the real ECHO, not the prototype, on the snare, the choir, the jangle and the chug: the pad as the kit has it; `delayed` at AMT 70 and 100; the same values with HEAD 0 (the same level, so HEAD alone is heard); the plain DELAY the owner already liked (MIX 0.5, HEAD 0) at 100; and `delayed` at recipe MIX 0.75 at 70 and 100 on the snare and the jangle. The questions: is it a delay on each source; on the drums, are the repeats too loud at 70, and at 100; does HEAD bring the attack back on the held notes; MIX 1.0 or 0.75; should DELAY go on the PEDALS row. The chip joins the row only if the gate passes. Once HEAD ships, the long-note sheet's head clip is rendered by the real ECHO and the prototype's head feed is deleted, so the head feed lives in one place.

## Registration and hazards

- **No new section.** `FxChain` and its `SECTIONS` table do not change; `FxChain.VERSION` stays 1; `echo` keeps its saved key. HEAD is appended to `Echo.MACROS`.
- **A kit saved with HEAD cannot be read by an older build.** `FxChain`'s constructor refuses a macro its section does not know, so an older app throws on `echo.HEAD`. ENSEMBLE's SECTION has the same edge; the app updates in one direction on one phone, and MPC exports carry audio, not recipes. Disclosed, not fixed.
- **No seeded draw moves.** `Echo.scramble` has no caller in main source (grepped on `c5274e0f`); BREED crosses only the macro keys its parents already hold; the remix bank indexes `Shuffle.TREATMENTS` with a seeded `Random`, and its `slapback` sets no HEAD.
- **Pinned lists.** `TreatmentsTest`'s names list gains `delayed`; `PadSheetTest`'s inventory and counts change only when the chip lands, after the gate. The ECHO tests that exist (MIX 0 is a copy, repeats land where TIME says, REPEAT lengthens the trail within the tail bound, an echoed kick is still a kick) are untouched by design.
- **HEAD is also a tape echo's word** for its playback-head selector. The app shows no per-section dials, so only the CLI and kit JSON show the word; it is kept because the owner chose it.
- **Known edge: the head is the pad's start, not its hit.** A pad whose hit is not at its first frame (SWELL in front of it, a reversed pad, a trim that leaves silence before the hit) repeats its first 120 ms rather than its hit. A chip replaces a pad's chain today, so only CLI and kit chains that combine SWELL or REVERSE with HEAD reach it. Anchoring the window at the pad's onset is the fix if a listen finds it. Disclosed, not fixed.
- **The tail cap.** The rack lets no pad grow by more than one second past its own end; measured on the Stage 1b clips, it lands on repeats already 48 to 80 dB under the hit. HEAD does not change that.

## Out of scope

A tempo-synced delay (the keyed ON BEAT twin); ducking the note under the repeats and feeding the repeats from SPIKE's attack detection (both heard on the long-note sheet and not picked); a knob for the head's length; ping-pong or any stereo widening (ENSEMBLE alone may widen); TIME past 350 ms; the swappable slate and any second pedal row (waiting on the owner's answer); presets.

## Effort, from footprints

Small: about 30 lines in `Echo.kt` (the window, the feed, the bypass branch), one recipe, the ECHO tests, the gate's clips in the generator, and the chip's two lines after the gate.

## Verification

`./gradlew :shell:cleanTest :cli:cleanTest`, then `./gradlew :synth:test :shell:test :cli:test` (the `:shell` source laws run only when forced), the gate's clips rendered and published, and the owner's gate. A recipe that fails its gate ships without a chip.

## Verified, measured, proposed, heard

- **Verified** (read or grepped on `c5274e0f`): ECHO's loop and macros; `Echo.scramble` has no main-source caller; BREED's `crossMacros` crosses only present keys; `FxChain`'s constructor refuses an unknown macro; ENSEMBLE's `SECTION_OFF` bypass; SIREN's `LANDING_ECHO` and the bank's `slapback` set no HEAD; DELAY appears nowhere on the pad sheet; the PEDALS row holds DRIVE and COMP.
- **Measured** (the scratch test above, 2026-10-22, Kotlin, not committed): every dB figure in this document except the Stage 1b page's own (225 ms, 37 dB, half a decibel, 48 to 80 dB), which are from PR #609 and PR #619.
- **Proposed** (shape): `HEAD_SEC` 120 ms, the fade over the window's last third, the window's cap at 0.8 of the delay, the stretch of 1.5, the recipe's MIX 1.0.
- **Heard** (the owner): the two verdicts quoted above. Nothing in this design has been heard as built.

## Why it is built this way

- **A knob, not a new pedal section.** The sound the owner picked is ECHO with a different input. Putting it in ECHO keeps one delay loop in the code, and every place that already uses ECHO (the SLAP chip, SIREN's landing, saved kits) is untouched because HEAD starts at zero.
- **HEAD decides what repeats; MIX decides how loud.** On the choir, HEAD alone turns a smear of overlapping repeats into separate echoes (10 dB of space between them). On the guitar, the pick is only 2 dB above the ringing string, so the echo also needs to be loud, which is MIX's job. Keeping the two apart means each knob does one thing you can hear.
- **The first tap is the middle, not the extreme.** The pad sheet starts at AMT 70. The recipe is set so 70 gives the balanced delay (the guitar's echo close to what you liked, the snare's repeat a little louder than the plain delay you liked) and 100 gives the hot, dub-style end.
- **The risky decision** is MIX 1.0 on drums. It is what makes the held-note echo audible, and it makes a drum's echo loud at 100. The gate plays MIX 0.75 beside it so you can pick.

## Questions for the owner

1. Delay at 100 and Head 120: equal, or is Head the better one on long notes? This design reads them as one answer and puts HEAD on in the chip.
2. At the gate: on the drums, MIX 1.0 or 0.75?
