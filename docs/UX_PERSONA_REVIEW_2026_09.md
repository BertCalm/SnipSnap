# Five users, five prior mental models: the third journey pass

## Why a third pass, and what is different about it

The first review asked, per feature: is it wired, reachable, labelled,
explained. The second asked whether the shape of the app matches the shape
of the work. Both audited SnipSnap **against itself** — its own stated
conventions, its own code.

Neither could answer the question this pass asks: *what does this app feel
like to someone who arrives already knowing a different machine?* A control
is not confusing in the abstract. It is confusing relative to what someone
expected. And the same fact — "the TAPE screen has reels and a transport" —
is meaningless decoration to one user and instant fluency to another.

So this pass walks five arrivals:

| | Persona | Arrives knowing |
|---|---|---|
| **P1** | 14, no DAW, no sampler, no hardware | TikTok, maybe BandLab once |
| **P2** | MPC hardware owner, uses no sampling apps | programs, banks, keygroups, `.xpm`, SD cards |
| **P3** | Koala user, has never touched an MPC | one screen, 16 pads, resample, instant |
| **P4** | MPC owner who *also* uses Koala and similar | both of the above |
| **P5** | Teenage Engineering: KO II, OP-1, Pocket Operators | tape, patterns, punch-in FX, cryptic-by-design |

## Method, and its limit — which is sharper here than in the last two passes

Everything about **the app** below is read at source and cited. Everything
about **the personas** is my model of them, built from the documented
behaviour and vocabulary of the reference products — not from user research,
not from a test session, not from talking to a single actual teenager. I
cannot run the app, and I have not watched anyone use it.

That distinction matters more than usual, because the previous pass had
thirty-four accuracy defects found in it across three review rounds, and the
ones that mattered were the *causal* claims — confident statements about how
something behaves. So, explicitly:

- **Claims about the app** (what a screen does, what a string says, what
  exists): verified, cited, falsifiable. Hold me to these.
- **Claims about the persona** (what they expect, what they'd look for
  first): argued, not measured. These are hypotheses worth testing with five
  real people, and cheap to test. Treat them as the *questions* this document
  raises, not its answers.

Where I say "P3 will look for X", read "I believe P3 will look for X, and
this is checkable in an afternoon."

---

## The headline: the app has no ending for four of the five

**Verified, and it is the finding of this pass.** Every path by which audio
or data leaves SnipSnap:

| Path | What leaves | Source |
|---|---|---|
| EXPORT, 8 formats | a **sampler instrument** (MPC program folder, Expansion, XPN, XTD, XPJ, SFZ, DecentSampler) or **MIDI notes with no sound** | `ExportFormats.kt:35-69` |
| SHARE ▸ THIS KIT AS ONE FILE | a packed kit archive only SnipSnap reads | `App.kt:1787` |
| SHARE on a room row | a `.snip-room` only SnipSnap reads | `KitsScreen.kt:138`; `KitShelf.kt:115` |
| CHART | `text/plain` | `GrooveScreen.kt:1235` |

GROOVE's **BOUNCE** does render the pads' bus — into **SNIPS**, the in-app
list, where a snip can be "played, assigned to a pad, opened in TAPE, or
deleted" (`KitsScreen.kt:105`). There is no share on that row.

**So: you cannot get an audio file of your finished beat out of this app.**
Not to send a friend, not to post, not to drop in a video, not to import to
a DAW as audio. The MIDI export is explicitly "THE GROOVES ONLY, NO SOUNDS"
(`ExportFormats.kt:60`).

For **P2** that is fine and possibly correct — they want a program to load
on the MPC, and they'll record the audio off the MPC. For **P1, P3 and P5**
it is the end of the road at the exact moment they have something they like.
A fourteen-year-old who makes a beat they're proud of wants to send it to
someone within about nine seconds. **P4** straddles it: fine for the MPC
half of their life, dead end for the Koala half.

I want to be careful about *why* this is worth flagging so strongly: it is
not merely a missing feature. It is that the app's own advertised four-step
loop (`Personality.kt:64`) terminates in **DUB IT** — write to a card — and
four of five personas own no card and have no MPC to put it in. **The app
tells every new user, on the empty shelf, that the last thing they will do
is the one thing most of them cannot do.**

Two previous reviews could not find this, because from inside the app's own
logic the export pipeline is complete and correct. It only appears when you
ask what the user wanted to walk away holding.

---

## The second headline: the advertised loop omits the thing most people mean by "making a beat"

`FIRST_RUN_LOOP` is `TAPE ▸ CHOP ▸ KIT ▸ EXPORT` and
`FIRST_RUN_LOOP_NOTE` is `"RECORD IT, CUT IT, PLAY IT, DUB IT. FOUR TABS,
IN ORDER."` (`Personality.kt:46-64`).

Sequencing is not in it. **GROOVE is 7th of twelve in a horizontally
scrolling menu row** (`Chrome.kt:217-230`) whose scroll affordances are
non-interactive cues rather than buttons (prior finding J12).

For P2 this is defensible: an MPC owner sequences *on the MPC*, and the app
is a program builder. For everyone else, "make a beat" means *a loop that
plays back*, and the app buries the screen that does it while advertising a
four-step journey that ends in a file format.

---

# P1 — fourteen, no DAW, no sampler, no hardware

**What they arrive with:** the word "beat". Possibly "sample" from hearing
it in interviews. They have never set a BPM, never seen a pad grid, never
heard "one-shot", "transient" or "program".

### The first ninety seconds go well

Genuinely — this is the strongest onboarding surface in the app and it
deserves saying before the criticism:

- Empty shelf offers **NEW KIT ▸ STARTERS** and eight starters with
  plain-English blurbs: *"The house kit. Kick, snare, hats that choke, the
  works."* · *"Dice-rolled drums. The dice are audited - they can't roll a
  bad kit."* · *"Everything through a ~9-bit converter. 1987 in a kit."* ·
  *"Choirs and grain clouds. Atmosphere, not drums."* (`StarterKits.kt:62-105`)
- Pick one → KIT → sixteen pads → **tap, it makes a sound.** Reward in
  under a minute, no configuration.
- **LUCKY DIP with `TAPS REROLL`** is the single most legible control in the
  app for this persona. It is a slot machine. No one needs it explained.

Two things are worth noticing about those blurbs. They are the **only
sentence-case copy in the app** — everything else is all-caps — and they are
the only copy that explains by *analogy* rather than by naming. That is not
a coincidence; it is what makes them work.

### P1.1 — "DUB IT" is the last word of the tutorial and it means nothing

`"RECORD IT, CUT IT, PLAY IT, DUB IT."` To this persona "dub" is dubbed
anime, or a genre their dad likes. The app means "copy onto a card". They
own no card. *(Source: `Personality.kt:64`; export destination
`ExportScreen.kt` card leg.)*

### P1.2 — the exit does not exist (see headline)

They will finish a beat and find no way to send it to anyone.

### P1.3 — sequencing is not presented as part of the app

Per the second headline. For P1 this is the difference between "an app that
makes noises" and "an app that makes beats". If they never scroll to GROOVE
they never make a beat, and the tutorial never told them to.

### P1.4 — the vocabulary wall

Words a beginner meets in normal use, none of which the app defines
on screen: CHOP, HITS, EAR, GRID, GHOSTS, ONE-SHOT, CHOKE, KEYGROUP, ZONE,
PROG, BANK, SNIP, TAKE, RE-TRIM, DE-SAMPLE, DUB, TRANSIENT, XPM, SFZ,
EXPANSION, DECENTSAMPLER. HELP is a flat list of 20 bullets
(`Personality.kt:430-450`) written in the same compressed voice as the UI —
it names features rather than teaching concepts, and it is 12th of twelve in
the menu.

### P1.5 — ALL CAPS at length

Short all-caps labels are a strong style choice. All-caps *sentences* are
measurably slower to read because word-shape cues vanish, and HELP's lines
run long: `"· HOLD A PAD, RE-TRIM ▸: TAPE OPENS ON ITS CUT. BACK ONTO
LANDS IT."` The starters prove the app can drop to sentence case without
losing its voice.

### Outside expectation: differentiator or risk?

| Thing | For P1 | Call |
|---|---|---|
| Cassette metaphor, reels, pencil rewind | Has never used a cassette. Reads as *aesthetic*, carries **zero** explanatory load. Retro is fashionable, so it still helps. | **Differentiator, but it teaches nothing.** Don't let it carry meaning it can't carry for this persona. It is also the origin of prior finding J39 — reels as invisible controls. |
| "THE MACHINE GUESSES; ARGUE WITH IT" (`Personality.kt:423`) | Perfect. Sets expectation, grants permission, in six words. | **Differentiator.** This is the tone the rest of the app should aim at. |
| Auto-classified, auto-named pads | They don't know what a kick *is* in a waveform. The machine doing it is pure gift. | **Differentiator, and under-sold.** |
| Invisible 480 ms long-press to the PAD SHEET | They will not find 3,611 lines of the app. | **Risk.** (prior J15) |

---

# P2 — MPC owner who uses no sampling apps

**What they arrive with:** program, pad bank A–D, sixteen pads, chop-to-
program, drum vs keygroup program, `.xpm`, project, sequence, track, Q-Link.
They put SD cards in things as a matter of routine.

**This is the persona the app is actually built for, and its front door
never says so.** The export list is unusually serious MPC support: a folder
"ANY MPC CAN OPEN", an Expansion that "LANDS AS A TILE IN THE MPC'S OWN
BROWSER", XPN, MPC 3 native `.XTD`, a whole `.XPJ` session
(`ExportFormats.kt:35-69`). Pad tags, banks, choke groups, keygroups,
velocity layers and `smpl`-chunk root notes are all MPC-correct. Nothing on
the shelf, in the first-run note, or in HELP's first four lines tells an MPC
owner that this app speaks their format fluently — HELP mentions it once,
ninth word of the fourth bullet: *"THE MPC IS ONE OF THEM."*

### P2.1 — the one MPC word the app reuses, it reuses wrongly

**This is the sharpest finding of the persona pass after the headline.**

On an MPC, a **program** is the sound set — the thing this app calls a
**KIT**. In GROOVE, SnipSnap labels its five pattern variations `PROG A ·
THE BREAK`, `PROG B · SWUNG`, `PROG C · HALF-TIME`, `PROG D · SPARSE`,
`PROG E · EDITED` (`GrooveScreen.kt:174-187`).

So the app took the MPC's word for *the sounds* and used it for *the
patterns* — while calling the actual program a "kit". For the one persona
who knows the word, it points at the wrong object. A new word (P1's problem)
costs a lookup; a **stolen word costs a wrong model**, and wrong models are
much more expensive to unlearn. Every other app-invented term (SNIP, CATCH,
DUB, GROOVE) is at least unambiguous.

### P2.2 — their vocabulary doesn't navigate

Their words → the app's screens:

| They'd say | They'd look for | It's called | Where |
|---|---|---|---|
| program | PROGRAM | **KIT** | 4th in menu |
| sample | SAMPLE | **SNIP** / TAPE | 2nd |
| chop to program | CHOP | CHOP → **SEND TO GRID** | 3rd |
| sequence | SEQUENCE | **GROOVE** | 7th, off-screen |
| Q-Links | — | **SURFACE** | 10th, off-screen |
| sample edit / trim | TRIM | TAPE IN/OUT, or pad **RE-TRIM ▸** | behind a long-press |

### P2.3 — the card write is most dangerous for exactly this persona

Prior finding J3: the card leg of the export overwrites a same-named folder
with **no confirm at any point**, best-effort, outcome provider-dependent
(`ExportScreen.kt:481-487`; `CardWriter.kt:142-155`). P2 is the only persona
who actually has an SD card with existing expansions on it. The one user
whose data is genuinely at risk is the one the app is built for. **This
raises J3's priority above where the plan currently has it.**

### P2.4 — the loop starts at "record", they often start at "I have a WAV"

`TAPE ▸ CHOP ▸ KIT ▸ EXPORT` opens with recording. An MPC owner frequently
begins from an existing file. The app *does* accept a shared-in file
(`ShareInbox.kt:118`) and has CHOP ALL for bulk `.wav`
(`KitsScreen.kt:103`) — neither is in the advertised loop.

### Outside expectation: differentiator or risk?

| Thing | For P2 | Call |
|---|---|---|
| HUM — beatbox the chop, cuts follow your mouth | Exists on no MPC. | **Differentiator. Possibly the flagship one.** |
| GHOSTS, FOLD, the classifier, LUCKY DIP, EVIL TWINS, BREED, DE-SAMPLE | None of it exists on an MPC. This is the reason to open a phone instead of the hardware. | **Differentiator.** |
| Auto-layout onto CLASSIC by drum class | Replaces the tedious part of chop-to-program. | **Differentiator, under-sold.** |
| Renaming program→kit, sample→snip | Costs them navigation. | **Risk, low.** It's a style they'll absorb. |
| Renaming *pattern*→"PROG" | Costs them a wrong model. | **Risk, high.** Not a style choice — a collision. |

---

# P3 — Koala user, has never touched an MPC

**What they arrive with:** Koala's model. One screen. Record → it's on a pad
immediately. Chop. Sequencer along the bottom. FX pads you can slam live.
Resample constantly. Nothing is more than about two taps away, and the
whole app is essentially one surface with modes.

This is the **widest gap of the five** — not because SnipSnap is worse, but
because it is organised on an opposite principle.

### P3.1 — twelve destinations against roughly one

Koala: one screen, modes. SnipSnap: twelve menu entries plus KEYS, SPLIT and
the PAD SHEET (`Chrome.kt:217-230`, `AppScreen` enum `:64-88`). Their core
loop — sample, pad, sequence, resample — crosses **TAPE → CHOP → KIT →
GROOVE**, four destinations, with GROOVE off the visible strip.

### P3.2 — record-to-pad is not the advertised path

In Koala, recording *is* pad assignment. Here the advertised path is TAPE →
CATCH/KEEP → CHOP → SEND TO GRID → KIT. The app **does** have the direct
routes — `INSTANT KIT` (`TapeScreen.kt:1087`) and `CATCH A HIT`, holding a
pad as the sound goes by (`:1118`) — and CATCH is genuinely delightful. But
they are two of roughly twenty controls on a dense screen, and the first-run
note points elsewhere.

### P3.3 — "resample" is not a word this app says

Searched: it appears **only in code comments and provenance strings**
(`SurfaceEngine.kt:9`, `PadSheetScreen.kt:2591` "resampled from …") — never
as UI vocabulary. The capability exists twice (SURFACE's `PRINT`
`SurfaceScreen.kt:731`, GROOVE's BOUNCE → SNIPS) under two other names, on
two screens, neither of which is the pad screen where a Koala user would
reach for it. For a persona whose central verb is "resample", the app never
says the word.

### P3.4 — FX are a different kind of thing here

Koala FX are **live and performable** — hold a pad, hear it now, it isn't
committed. SnipSnap TREATMENTs **render to file**: they are applied to a
pad, take measurable time (`ETERNAL` measured 1.77 s on a *desktop* JVM, against SKIM 480 ms / DUB
247 ms / GHOST 162 ms — `Personality.kt:456`; a phone is several times
slower), and
rewrite the WAV. That's the MPC model, not the Koala one. A Koala user will
reach for a filter sweep as *performance* and find a *render job*.

**This is a real architectural difference, not a labelling one**, and it is
the one place where I would not simply recommend changing the app to match
expectations — see the split-decisions section.

### P3.5 — the exit (headline) hits this persona hardest

Koala users bounce loops to send and to post. SnipSnap's eight export
formats are six sampler-instrument formats plus MIDI-without-sound plus a
session file.

### Outside expectation: differentiator or risk?

| Thing | For P3 | Call |
|---|---|---|
| Depth: MUTATE, DE-SAMPLE, GRAIN, SPLIT into sines/transient/air, BREED | Koala is deliberately shallow. This is genuinely more sound design than they have. | **Differentiator — arguably the whole pitch to this persona.** |
| Render-to-file FX instead of live FX | Slower, committed, but *produces a real instrument* | **Both.** Differentiator for output quality, friction for flow. Split decision below. |
| Cassette aesthetic vs Koala's clean modern UI | Distinctive, memorable | **Differentiator.** |
| Twelve screens | Against a one-screen mental model | **Risk, high.** |
| No audio out | Against a share-constantly habit | **Risk, highest.** |

---

# P4 — MPC owner who also uses Koala

**What they arrive with:** both models, and the specific frustration that
motivates this app's existence — Koala is fast but its output doesn't land
cleanly on an MPC; the MPC is powerful but slow to build programs on.

**SnipSnap is aimed squarely at this person.** Phone-speed sound design,
MPC-native output. That is a real gap in the market and the app genuinely
fills it.

Because they can navigate both worlds, their frictions are not
comprehension — they are **damage and precision**:

### P4.1 — destructive defaults cost the expert most

They do more per session, so they lose more per accident. From the prior
review, all verified: RE-CHOP silently discarding every corrected chip
(J5), the CHOP `model` re-keying that erases nine pieces of state (J20),
the export overwrite arm that no rendering path reads (J2), the card leg
with no warning at all (J3), `MAKE PAD ▸` reseeding and overwriting per
press.

### P4.2 — they will notice what isn't there

MIDI in/out and clock sync, sample-rate control, and (per the headline) an
audio bounce. `docs/MIDI_SYNC.md` exists as a design doc; the feature does
not.

### P4.3 — P2.1's PROG collision is worse for them

They hold *three* meanings of the word now: MPC program (sounds), Koala
sequence, SnipSnap PROG (pattern variation).

### P4.4 — provenance is good and half-hidden

The app tracks where a pad came from (`resampled from`, `importedFrom`,
`origin` — `PadSheetScreen.kt:2580-2591`). This is exactly what a
power user wants and it lives inside the long-press sheet.

### Outside expectation: differentiator or risk?

| Thing | For P4 | Call |
|---|---|---|
| Koala-speed capture → MPC-native export | **This is the product.** | **The differentiator.** Nothing else on the phone does this well. |
| Auto-classification driving real MPC pad layout | Removes the tedious half of their workflow | **Differentiator.** |
| Destructive, unconfirmed writes | They have the most to lose | **Risk, high.** |
| No audio bounce | Half their use cases | **Risk, medium** (they can record off the MPC) |

---

# P5 — Teenage Engineering: KO II, OP-1, Pocket Operators

**What they arrive with:** tiny screens, cryptic labels, physical encoders,
manuals that are half poem. Tolerance for undiscoverable interfaces is
**high** — on a PO, learning the machine *is* the product. KO II: sample
anything instantly, punch-in FX held live, resample, SD card. OP-1: four
tracks on a literal **tape**, with a transport, reels, and splice/lift/drop.

### The cassette metaphor is not decoration for this persona — it is prior art

This is the most interesting finding of the five. What is pure aesthetics to
P1 is **literal shared vocabulary** with the OP-1. The TAPE screen's reels,
transport, IN/OUT markers, tape counter and `SWITCH TO REAL TIME /
SWITCH TO TAPE COUNTER` (`TapeScreen.kt:1229`) map onto something they have
already learned. The **pencil rewind** (`:1416` `REWIND PENCIL`, `:1417`
`SPIN BACK BY EAR`) is exactly the species of joke TE ships deliberately.

So the same design decision is, across the five: meaningless-but-pretty
(P1), neutral (P2, P4), distinctive (P3), and **instantly legible (P5)**.
That is worth knowing before anyone "fixes" it.

### P5.1 — they expect pattern *slots*, and PROG A–E is not that

On a PO you have sixteen pattern slots you fill and chain. On KO II you have
banked patterns. SnipSnap's PROG A–E is **four algorithmically generated
read-only variations plus one editable fork** (`GrooveProgram.kt`,
`PROG_NAMES` `GrooveScreen.kt:174-187`). Five slots, of which **one** is
yours.

This will read as broken rather than different: they will tap PROG B, make
an edit, and discover it isn't a slot. Prior finding J18 flagged that A–D
aren't marked read-only; **for this persona that's not a labelling nit, it's
the core interaction model failing to match.**

### P5.2 — no pattern chaining, no song mode in the expected place

Chaining patterns into an arrangement is the PO's whole second half. GROOVE
has `SONG ▸` in its SEND row (`GrooveScreen.kt:2007`) (prior J46 notes that row mixes navigation with
file writes), but nothing that reads as "chain these patterns".

### P5.3 — punch-in FX vs macros-and-print

KO II's punch-in FX are held-live and momentary. SURFACE is a macro pad with
`LATCH` and `PRINT` (`SurfaceScreen.kt:731,768`) — closer to an OP-1 style
performance surface than to punch-in FX, and its output is a print, not a
live effect on the master. Adjacent, not the same.

### P5.4 — the export gap again, differently

TE hardware puts WAVs on a card or over USB as a matter of course. Getting
audio out is assumed. See headline.

### Outside expectation: differentiator or risk?

| Thing | For P5 | Call |
|---|---|---|
| Tape metaphor, reels, pencil rewind, transport | **Shared vocabulary with the OP-1.** | **Strong differentiator.** Do not sand this off. |
| Invisible long-press to a huge hidden screen | Normal. Expected, even. Learning the machine is the fun. | **Neutral-to-positive for P5 only** — and a real defect for P1/P3. Split decision. |
| Compressed, poetic, all-caps voice | Exactly TE's register. | **Differentiator.** |
| PROG A–E as four read-only variations | Against a pattern-slot model | **Risk, high.** |
| Machine-generated variations at all (LUCKY DIP, EVIL TWINS, SCRAMBLE) | TE ships randomisers everywhere. | **Differentiator.** |
| No audio out | Against a card-and-USB habit | **Risk, high.** |

---

# Cross-cutting: what all five agree on

These need no positioning decision. If a fact is friction for a fourteen-
year-old *and* an MPC veteran *and* a TE owner, it is simply a defect.

| # | Unanimous friction | Verified at |
|---|---|---|
| **X1** | **No audio file of your work can leave the app.** | `ExportFormats.kt:35-69`; `KitsScreen.kt:105`; every `ShareOut` caller |
| **X2** | The advertised loop ends in **DUB IT / EXPORT to a card**, which only P2 and P4 can use. | `Personality.kt:46,64` |
| **X3** | **Sequencing is absent from the advertised loop** and GROOVE is 7th of twelve in a scrolling strip. | `Personality.kt:46`; `Chrome.kt:217-230` |
| **X4** | Twelve flat, unordered menu peers; the four "in order" steps are not visually grouped as a sequence. | `Chrome.kt:217-230` (prior J12) |
| **X5** | Destructive writes with no durable warning. Worst for P2/P4, but it destroys P1's work identically. | prior J2, J3, J5, J20 |
| **X6** | HELP is a flat 20-bullet feature list, in the same compressed voice as the UI, 12th in the menu. | `Personality.kt:428-450` |

**X1, X2 and X3 are one problem wearing three hats**: the app has a clear
idea of what finishing looks like, and it is an MPC owner's idea of
finishing. Everything downstream of that assumption is coherent — which is
precisely why two internal audits couldn't see it.

---

# Cross-cutting: the split decisions — these are yours, not mine

Each of these is friction for one persona and an asset for another. There is
no "correct" answer in the source code; the answer depends on who the app is
for. I've given a recommendation, but the call is a positioning call.

### S1 — The cassette metaphor

Meaningless-but-attractive (P1), neutral (P2/P4), distinctive (P3),
**natively legible (P5)**.

**Recommendation: keep it entirely, and stop asking it to do explanatory
work.** It is free aesthetic differentiation and it's genuinely earned with
P5. But it cannot teach P1 anything, so the reels must not be the *only*
affordance for a control (prior J39), and "DUB" must not be the only word
for "get it out".

### S2 — Invisible long-press to the PAD SHEET

Expected and enjoyed by P5. A wall for P1 and P3. It hides 3,611 lines.

**Recommendation: keep the gesture, add a visible second door.** This is not
a compromise — it's what the app already does for RE-TRIM and DOUBLES' `GO
▸`. The gesture stays for people who like gestures; a visible route exists
for people who don't. (Prior J15/J16.)

### S3 — Render-to-file treatments vs. live performable FX

The MPC model (SnipSnap's) makes better instruments. The Koala model makes
better flow. P3 will feel the difference immediately and P2 will not notice.

**Recommendation: keep render-to-file; it's load-bearing for the export
story.** But the *latency* is the felt problem, not the model — a treatment
that takes multiple seconds on a phone with no live preview reads as
brokenness. An audition-before-commit would close most of the gap without
changing the architecture.

### S4 — Twelve screens vs. one surface

P3's model is one screen; P2's MPC is many screens; P5's hardware is many
modes on one screen.

**Recommendation: don't reduce the feature set, group the menu.** Four
"loop" steps as an ordered group, instruments as a second, utilities as a
third. (Prior J12/J14.) Twelve peers is the problem, not twelve
destinations.

### S5 — PROG A–E

P2/P4: the word points at the wrong object. P5: the *structure* is wrong
(variations, not slots). P1: meaningless.

**Recommendation: this one has no defender. Rename it, and reconsider the
model.** It is the only item in this document that is friction for all five
personas *and* a deliberate design choice rather than an oversight. At
minimum the word "PROG" should go. Whether A–D should become user slots is a
bigger question — but note that no persona expects what is currently there.

---

# The vocabulary ledger

Every invented or reused term, against what each persona would call it.
"—" means the persona has no word for it because the concept is new to them.

| SnipSnap | What it is | P1 (novice) | P2/P4 (MPC) | P3 (Koala) | P5 (TE) |
|---|---|---|---|---|---|
| **KIT** | the sound set | "the sounds" | **program** | kit / project | patch / kit |
| **SNIP** | one recorded sample | "a sound" | **sample** | **sample** | sample |
| **TAPE** | the recorder/editor | "recording" | sample edit | record | **tape** ✓ |
| **CATCH** | grab a sound as it plays | — | sample | record | sample |
| **KEEP** | commit the selection | "save" | keep/trim | — | lift/drop |
| **CHOP** | slice into pads | — | **chop** ✓ | **chop** ✓ | slice |
| **GROOVE** | the sequencer | "the beat" | **sequence** | **sequencer** | pattern |
| **PROG A–E** | *pattern variations* | — | **program (WRONG OBJECT)** | sequence | **pattern slot (WRONG MODEL)** |
| **DUB** | write to card | — | export/copy | export | save to card |
| **SURFACE** | macro pad + print | — | Q-Links | FX pads | punch-in FX |
| **ORBIT** | polymetric ring sequencer | — | — | — | — |
| **PLAY** | the drum-pad performance view | "play" | pads | pads | keys |
| **DE-SAMPLE / MUTATE / BREED / EVIL TWINS / DUST / GHOSTS / DRIFT** | sound-design ops | — | — | — | — |
| *(none)* | **resample** | — | **resample** | **resample** | **resample** |

Two observations fall out of this table:

1. **CHOP is the only term shared with both P2 and P3.** It's the app's one
   piece of common ground with the sampling world, and it's the third step
   in a four-step loop, which is right.
2. **The bottom row is the interesting one.** "Resample" is the word three
   of five personas use, the app performs the operation in two places, and
   it is not on screen anywhere.

---

# What I'd do about it

Mapping onto the existing plan (`docs/UX_JOURNEY_PLAN_2026_09.md`) rather
than replacing it. The prior plan's PR 1 (J1, the DRIFT bug) still goes
first — a correctness bug outranks all of this.

**New, and ahead of most of the journey work:**

1. **An audio bounce that leaves the app (X1).** BOUNCE already renders the
   bus into SNIPS; a SNIPS row needs a SHARE, and EXPORT arguably needs a
   ninth format that is just "the loop, as audio". This is the smallest
   change in this document with the largest reach: it is the difference
   between a dead end and a finished product for three of five personas, and
   `ShareOut.send` already exists and is used by four other callers.
2. **Reword the loop (X2/X3).** Four steps that end in "DUB IT" describe one
   persona's journey. Whether the fix is a fifth step, a different fourth
   step, or two named paths is a positioning decision — but as written, the
   app's own tutorial is wrong for most of its users.
3. **Retire "PROG" (S5).** The only finding here that is friction for all
   five personas and is a deliberate choice.

**Re-prioritised from the prior plan:**

4. **J3 (card overwrite, no warning) moves up.** P2 is the persona most
   likely to have a card full of existing expansions, and the persona the
   app is built for.
5. **J12/J14 (menu grouping) gains weight**, because it is X3 and X4 and S4
   at once — the same fix serves the novice, the Koala user and the TE user
   for different reasons.

**Cheap and high-leverage:**

6. **Say "resample" somewhere.** The feature exists twice under two other
   names.
7. **Tell P2 the app speaks MPC, on the shelf.** The deepest thing about
   this app is invisible until the 4th step of its own tutorial.
8. **Let HELP teach concepts, not list features** — and let it use the
   starters' sentence-case voice, which already demonstrably works.

---

# What to test with real people, and how cheaply

Everything in the persona columns above is a hypothesis. Five sessions, one
person per persona, twenty minutes each, one task: **"make something and
send it to me."**

That task alone tests X1, X2, X3 and S4 simultaneously, and it is the task
the app currently cannot complete for four of the five. If nothing else in
this document gets acted on, that sentence is the test worth running.
