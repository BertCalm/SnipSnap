# Journey review: the remediation plan

The plan for `docs/UX_JOURNEY_REVIEW_2026_09.md`'s 47 findings.

The review's own conclusion drives the shape of this: the findings are not
47 independent mistakes. Three architectural decisions generate most of
them, so the plan is **sequenced by root cause, not by severity**, with
one exception at the top. Fixing a root cause closes a class; fixing
instances one at a time re-opens the class on the next screen built.

---

## Two facts that constrain everything below

**1. `:app` has no *behavioural* tests — but its source is already under
test.** *(Corrected: this section first said ":app has no tests. At all."
That is wrong, and it was steering the plan badly.)* `app/src/` does
contain `main` and nothing else, and `settings.gradle.kts` includes `:app`
only where an Android SDK exists — so nothing **runs** a composable in CI.
But `:shell`'s `ConventionTest` reads `../app/src/main/kotlin` as text and
asserts laws over it: ten of them, walking every `.kt` file, with a
`stripCommentsAndStrings` helper so they don't match prose. Its own class
KDoc names the bug shape it exists for — "a pattern applied correctly at
some call sites and incorrectly (or not at all) at a sibling site a few
lines or one file away", four of which shipped in a single day.

So there are two kinds of coverage available, and the distinction decides
how each PR below is verified:

- **Behavioural** — does this composable do the right thing? Not available
  without a Compose harness (Robolectric or instrumented), which needs
  `:app` in the build for tests. Genuinely out of reach right now.
- **Structural** — does every site of this shape agree? Already available,
  already in CI, and the right tool for a wide mechanical migration, where
  human review is weakest precisely because the hunks look alike.

Pushing a decision down into `:shell`/`:kit` (as the DRIFT fix did with
`MutateSheet.DRIFT_FRACTION`) remains the way to get a *behavioural*
assertion. A convention law is how to hold a *contract* across call
sites."

**2. The `enabled` fix is much cheaper than the review implied.**
`Chrome.kt:158` — `fun Modifier.tapeClick(label: String?, enabled: Boolean
= true, ...)` — already exists, already defaults correctly, and already
does the right accessibility thing (keeps the semantics node, exposes
Compose's `disabled()` state). `SegmentButton` and `DeckButton` simply
never pass it. This is not a new mechanism to design. It is **one
parameter forwarded in two private functions**, plus the call sites.

That second fact reorders the plan: what the review called an
architectural root cause is, mechanically, a small change. It moves up.

---

## Sequence

### PR 0 — a test seam for `:app` decisions *(enabler, optional but recommended)*

Not a finding. The reason to do it first is that PRs 1–4 all change
*when a control refuses*, and there is currently no way to assert that in
CI.

Proposal: a new `:appcore` JVM module (or a new package in `:shell`)
holding the pure predicates the composables currently inline — `fun
chopBenchBusy(rechop: Boolean, send: Boolean, humming: Boolean): Boolean`
and friends. No Compose, no Android, so it builds and tests in the cloud
session and in CI's `jvm-tests` job. Composables then read the predicate
instead of re-typing the boolean expression.

**Decision needed from you:** whether this is worth a PR of pure
plumbing before any user-visible fix, or whether we accept
"compiles + read carefully" as the standard for this whole wave. I'd
recommend doing it, *narrowly* — only the busy predicates PRs 1–4 touch,
not a general refactor.

---

### PR 1 — J1, the DRIFT correctness bug *(S1, alone)*

Not a usability issue: DRIFT commits a WAV rewrite with a stale fraction
and then displays a different number. On the default path it writes a 0%
blend and draws MIX 50%.

**Fix:** `onDrift` must not set `mutateMode` and then read a value keyed
on `mutateMode` in the next statement. Pass the mode and fraction
explicitly to the write, computed before the assignment:

```
val fraction = MutateSheet.KNOBS[Mutate.Mode.MORPH.name]?.default ?: 0f
```

— i.e. DRIFT decides its own fraction rather than inheriting whatever the
previous move's knob happened to hold. That is what the toast already
claims it does.

**Why alone:** it is the only finding in the document that is a bug in
shipped audio behaviour rather than a judgement about the interface. It
should be reviewable and revertable without any UX change attached to it.

**Verification:** the `MODES.first() == STACK` / `KNOBS has no STACK`
chain is assertable in `:shell` today (`Mutate.kt`, `MutateSheet.kt`), so
this one *can* have a real test even before PR 0.

---

### PR 2 — the `enabled` contract *(closes J8, J27, J28, J38, J40)*

Forward `tapeClick`'s existing `enabled` through `SegmentButton`
(`ChopScreen.kt:1522`) and `DeckButton` (`TapeScreen.kt:1256`), then
replace every inline `if (!busy)` guard at the 13 + 17 call sites with a
real `enabled = !busy`.

Three specific notes:

- `SegmentButton` must also stop announcing `selected = active` while
  disabled (`:1533`).
- `DeckButton` already has `active`, which dims the label and **does not**
  gate the click. Do not add a second axis: make `active` forward to
  `enabled`, or rename it. A control that looks refused and fires anyway
  is the worst of the three states.
- `▶ HIT` (J8) is one word — `enabled = !busy` — and its two siblings in
  the same Row already have it. It rides along here.

**Why second:** it is the largest ratio of defects-closed to
lines-changed in the document, it needs no product decision, and every
later PR that wants to refuse a control depends on it existing.

**Open question I want your call on:** whether a disabled control should
*also* toast on tap. The app's stated rule (`ChopScreen.kt:1136-1137`) is
"dimmed, not disabled; the toast explains" — but Compose's `disabled()`
swallows the tap, so you get the dim and lose the toast. Pick one:
(a) dim only, and accept no explanation; (b) dim + keep a tap handler
purely to toast the reason. (b) is more work per call site and is the one
that actually honours the rule as written.

---

### PR 3 — the three irreversible writes *(S1: J2, J3, and `MAKE PAD ▸`)*

Three disk writes that destroy user work with no *durable* warning and no
bin. J2 does have a two-tap confirm; the other two have nothing.

- **J2** — the confirm exists and is invisible. The first tap writes with
  `overwrite = false`, gets `WouldOverwrite`, arms `session.overwriting`
  and toasts the doomed path by name; the second tap replaces. So the fix
  is **not** "add a confirm" — it is to make the arm *renderable and
  perishable*: give the armed state its own button label (`REPLACE KIT`,
  not `WRITE KIT`) and an expiry, so the arm cannot survive an hour and a
  tab switch behind an identical-looking button.
- **J3** — the card leg has no confirm *at any point*. Put it behind the
  same arm as the phone leg. Note that `CardWriter.replaceExisting`
  swallows its own failures (`CardWriter.kt:142-155`), so the honest fix
  also surfaces *which* outcome happened — replaced, or duplicated.
- **`MAKE PAD ▸`** — hard-codes `overwrite = true` *and* reseeds
  `Random.nextLong` per press, so pressing twice destroys the render you
  liked. `MAKE INSTRUMENT` is deterministic and only needs the confirm;
  `MAKE PAD` needs the confirm **and** a way to keep a result you like.

**Decided: fresh name per press.** It removes the destruction rather than
asking about it, which is the right shape for a button whose whole purpose
is to be pressed repeatedly — a confirm on every press of an explore-by-
rolling control is a dialog you learn to dismiss without reading.

**Shipped, and smaller than expected.** `:kit` already refused to clobber:
`OneNote.export`/`PadFromAnything.export` default to `overwrite = false`
and `writePackage` throws `DestinationExists` when either the `.xty` **or**
its `_[TrackData]` folder is present. Both screens were passing
`overwrite = true` and insisting past a guard that was already there. So
the fix is to ask for a free name and take the default.

The counting rule now lives once, as `Names.freshStem(base) { taken }`, and
takes its occupancy test as an argument rather than a directory — there
were three callers and all three define "taken" differently (a shelf
folder; a `.wav`/`.json` pair; a program file *or* its data folder), so a
directory-shaped helper would have forced a fourth copy immediately.
`KitShelf.freshName` and `Rooms.freshName` were folded onto it unchanged.

One knock-on: `Copy.PAD_MADE`/`INSTRUMENT_MADE` said "ON THE SHELF." with
no name, which was fine when there was only ever one. Press three times now
and three different pads pile up, so the toast names what landed
(`Copy.madeNamed`), matching what the shelf shows.

---

### PR 4 — RE-CHOP and the ungated pinned controls *(S1: J4, J5, J6, J7, J23)*

The remaining S1s, all of the shape "a control that isn't gated destroys
work in flight."

- **J5** — RE-CHOP calls `rechop()` where every other control on the bench
  calls `rechopKeeping()`. Make it carry overrides like its neighbours, or
  confirm. Carrying is the smaller change and the less surprising one.
- **J6** — add `humming` to RE-CHOP's gate (`:944`). One term.
- **J4 + J23** — gate the pad-nav arrows (`PadSheetScreen.kt:2476-2492`)
  on `busy`, which stops both the dead-grey-screen state and the
  discarded measured room.
- **J7** — the share-sheet import reload fires unconditionally during a
  live CATCH. Guard the reload, or defer it until the catch finishes.

**Why grouped:** all four are a missing term in a boolean, they are all
S1, and they all become one-liners *after* PR 2 exists.

**Audited and fixed, September 2026.** Every line number above is stale —
the files moved 10-600 lines after the earlier PRs — so the audit walked
each finding against the code rather than the reference. All five were
still live; none had been fixed incidentally. Recorded because the
speculation went both ways first, and only the reading settled it.

| finding | the site as of the fix | what was wrong |
|---|---|---|
| J5 | `ChopScreen.kt:961` | bare `current.rechop()` |
| J6 | `ChopScreen.kt:950` | gate lacked `humming` |
| J4 + J23 | `PadSheetScreen.kt:2560`, `:2571` | arrows gated only on a slot existing |
| J7 | `App.kt:863`, `TapeScreen.kt:625` | import reload had no catch guard |

Two were more than a missing term:

- **J6 nearly got cleared by mistake.** `rechopTo` *does* carry `humming`,
  and a reader who checks it concludes the bench is covered. The RE-CHOP
  button is a different function and was the only control on the bench
  without the term.
- **J5 was a behaviour fork, not a typo.** RE-CHOP was also the only way to
  get a *clean* re-chop that drops per-slice overrides. Fixed by carrying
  them, as the plan preferred: it is the smaller change and it makes the
  button behave like its neighbours. If a deliberately clean re-chop turns
  out to be wanted it should be its own named control, not an unannounced
  side effect of the one button that behaved differently.

**J7's fix is a deferral, not a refusal.** The share is left unconsumed and
`catchInFlight` is a key of the import effect, so finishing the catch
re-runs it and the import lands then. Nothing is dropped and nothing has to
be explained. The flag is cleared by a `DisposableEffect` because a
`LaunchedEffect` is cancelled rather than completed on the way out, and a
flag stuck true would defer every later import forever - the same lesson
EXPORT's overwrite arm produced.

---

### PR 5+ — `remember(key)` as storage *(J20–J26, J22, and the J24 half of J1)*

This is root cause 2 and the largest body of work: eighteen pieces of
user state keyed to something that changes during ordinary use. It does
**not** fit in one PR and should not be attempted as one.

Split by screen, in this order (most destructive first):

1. **CHOP** (J20, J21) — ten pieces keyed to `model`, reassigned at five
   sites, two of which are helpers the whole bench calls through.
   **Done — see the audit below; one of the ten was the bug.**
2. **PAD SHEET** (J23, J24, J26) — knobs that reset on move switch, and
   DEPTH/BLOOM which never persist at all while looking identical to three
   sliders above them that do. **Done — see the audit below.**
3. **TAPE** (J22) — a snip landing replaces the tape under the finger.
   **Done — see the audit below; the first of these testable for real.**
4. **KIT / TEXTURE** (J25) — the source pad jumps when the kit's lowest
   slot changes, because the key is *the value of the lowest slot*.
   **Done — see the audit below. PR 5 is complete.**

The mechanical fix is the same each time: hoist the state above the thing
that re-keys it, or key it on something stable (a slot id, not a model
instance). The judgement each time is *which* — and that is per-screen,
which is why this is several PRs.

#### CHOP, audited (step 1)

The count was right and the reading was not. All ten `remember(model)` in
`ChopContent` were examined one at a time against a single question: does
this **describe the chop**, or does it **record what the player chose**?

| State | Verdict |
|---|---|
| `revision` | Describes. A recompose counter for row overrides mutated in place; new rows, new count. |
| `pickerFor` | Describes. The open picker names a slice by its 1-based `n`, and a re-chop renumbers what that `n` means, so closing it is correct rather than rude. Its own MERGE and SPLIT close it explicitly; every *other* model swap (RE-CHOP, HITS, EAR, CUT, GRID, AUTO, the hum's landing) relies on the re-key, which is why the allowlist entry matters — unkey this and the picker survives onto a slice that is no longer the one it was opened on. |
| `melodicPlaced`, `pitchLabels`, `melodicBusy` | Describe. MELODIC's placement is pitch detection over *these* rows. |
| `humming`, `humStart` | Describe. A hum is sung against one model's source; `stopHum` clears the flag itself, so the key is belt-and-braces. |
| `voice` | Describes. Plays this model's audio; the `DisposableEffect` on the same key releases it. |
| `classicError` | Describes. The failure of a derivation over this model. |
| **`layout`** | **Records. The bug.** |

So nine of ten were already right, and the review's wider list (the
melodic placement, the `A2`/`C#4` labels, the picker closing) named
*symptoms of the tenth* rather than ten separate faults: with `layout`
snapping back to CLASSIC, the placement it had reset alongside was never
noticed as lost.

`layout` is now keyed on `initialModel` — a genuinely new source is a
different job and should start on CLASSIC; a re-chop of the same source is
not. `cutOpen` was always unkeyed, which is what makes this an
inconsistency rather than a policy.

**The fix had a second half that the finding did not mention.** MELODIC's
placement was computed *only inside the MELODIC button's own click
handler*, which was sufficient exactly as long as `layout` died with the
model — the only route back to MELODIC was tapping it. Once the layout
survives a re-chop, a re-chop while MELODIC is showing clears the
placement and no tap follows, so `melodicPlaced ?: emptyList()` would read
empty *forever*. Persisting the layout alone would have replaced a visible
annoyance with a silent one. The placement is now a `LaunchedEffect(model,
melodic)` — keyed on both, because on `melodic` alone a re-chop leaves a
stale placement drawn over new rows, and on `model` alone the pitch pass
runs for players who never opened MELODIC.

Three laws in `:shell`'s `ConventionTest` hold this: an allowlist naming
each of the nine with its reason (so the *next* `remember(model)` is a
decision rather than a default), one that `layout` is not among them, and
one that the placement is rebuilt by an effect rather than by a tap.

**J21 is untouched and still open** — it is a different mechanism
(`hitsOf` returning null for GRID/HUMMED/ladder modes, so `onByHits`
constructs fresh defaults) and belongs with the CHOP mode work, not here.

#### PAD SHEET, audited (step 2)

Twenty `remember(slot…)` here, and unlike CHOP most of them are **right**:
`slot` changes when the pad-nav arrows move to another pad, and a pad sheet
resetting per-pad state is what a per-pad sheet *is*. The three findings are
about the pieces that are not per-pad, and the audit confirms all three.

| Finding | What was wrong | Fix |
|---|---|---|
| **J24** | `pendingMutateKnob` keyed on `mutateMode`, `pendingOutsideKnob` on `outsideMove`. Each move's knob means its own thing, so the value cannot simply carry across — but keying it on the *selected move* destroyed it on the way to the comparison the move row exists to invite. | One value per move, in a `mutableStateMapOf` keyed on `slot`. Untouched moves still open on their own default. |
| **J23** | `measuredRoom` keyed on `slot`, so one tap of `►` discarded a multi-second live mic capture. | Keyed on `entry.dir`. **KEEP ROOM shelves the measurement kit-level, named after the kit** — the pad that happened to be open is incidental to every part of that. Not unkeyed: carrying it across a kit switch would let KEEP ROOM name it after a kit it was not measured for. |
| **J26** | `pendingDepth`/`pendingBloom` keyed on `slot`, so making a run of pads at one DEPTH meant dialling it again for each. | Keyed on `entry.dir`. They are settings for MAKE PAD — a tool's settings, not a property of whatever the tool was last pointed at. |

**Half of J26 is deliberately not fixed here.** The finding's other
complaint is that DEPTH/BLOOM *look identical* to LEVEL/PAN/TUNE two rows
above, which write to the pad. They cannot be made to write: the pad has no
DEPTH or BLOOM field to hold. Making the difference legible is a question
about what the control should say it is — words and IA, PR 8, not storage.

**The near-miss worth recording.** `MutateSheet.DRIFT_FRACTION` is MORPH's
own knob default, and its KDoc exists to stop the value *written* and the
value *shown* from diverging — the card "had been reading whatever fraction
the previous move's stepper happened to hold", so a DRIFT tap "blended none
of the neighbour in while the card then redrew MIX at 50%". Per-move memory
put that divergence back within reach from the other side: DRIFT uses
`DRIFT_FRACTION`, then switches the card to MORPH, which now has a
remembered value to land on. The knob would have read one number while the
drift that just ran used another. DRIFT now writes the fraction it used into
MORPH's own memory, and a law holds it.

This is the second time in two steps that **a piece of state was correct
only because of a bug beside it** — in CHOP it was the click-handler
placement, here it was the knob's display. Worth treating as the standing
question for steps 3 and 4: not just "should this key change", but "what
was relying on it not changing".

**Observed but not fixed, deliberately.** `mutateMode`, `outsideMove`,
`partner` and `pickedKit` are all `remember(slot)` and are arguably the same
class as DEPTH/BLOOM — tool selections, not pad properties, so landing back
on the first move for every pad is the same annoyance. No finding names
them, and fixing them would widen the PR on our own initiative. Recorded
here so the next pass can decide rather than rediscover.

#### TAPE, audited (step 3)

**The one that could be tested for real.** Zoom and odometer are not
separate `remember`s — they live inside `TapeDeckModel`, which is itself
`remember(tapeData)`, so a reload replaces all of it at once. And
`TapeDeckModel` lives in **`:shell`**, which has a real test source set. The
first two steps could only be held by source-scanning laws; this one has ten
actual behavioural tests.

The existing idle guard (`!model.playing && !model.hasSelection`) already
refuses a reload *mid-edit*. Widening it to cover zoom would have been the
wrong fix twice over: it would refuse the reload forever because somebody
once pinched, and it treats a view preference as if it were unfinished work.
The reload is allowed to happen; what has to survive it is the view.

`TapeDeckModel` gained three things, all in `:shell` and all tested:

- **`View(pxPerSec, odometer)`** — deliberately those two and nothing else.
- **`restoreView`** — clamped to the zoom ladder's own ends, with a
  non-finite or non-positive value falling back to the first rung rather
  than being honoured. `coerceIn` *propagates* NaN instead of clamping it,
  and px-per-second reaches the waveform as a column count, so that one bad
  value would draw nothing at all with no exception to say why.
- **`ViewCarrier`** — holds the previous *deck*, not a snapshot of its view.
  That is what makes it correct without hooking every control: zoom changes
  by button, by pinch and by ladder-snap, and the pinch runs in a gesture
  loop that does not report every frame to the screen. Reading the view at
  the moment of replacement cannot miss one. The first deck a carrier sees
  is left exactly as it opened — there is nothing yet to carry, and imposing
  a default would be inventing a view the player never set.

**The line the tests actually defend** is which half carries. `position`,
`inFrame` and `outFrame` are frame offsets into *one particular recording*;
carried onto a different tape they would put the playhead and the IN/OUT
marks somewhere nobody chose, or past its end. That test was proved live by
temporarily making `restoreView` carry them, and it failed naming the
playhead.

One convention law remains necessary despite the real tests, and the reason
is worth stating: `TapeDeckViewTest` proves the carrier *carries*, not that
TAPE *uses* it. Deleting `.also(viewCarrier::adopt)` from `:app` compiles
cleanly and no `:shell` test notices. That gap between "the mechanism is
correct" and "the screen calls it" is exactly what the source-scanning laws
are for.

#### KIT / TEXTURE, audited (step 4) — PR 5 complete

Three sub-findings in four adjacent lines. Two are **J24's exact shape** and
took J24's exact fix; the third is the worst single bug in the whole of
PR 5.

**The bad one: a destructive action's target moving on its own.** SOURCE
names the pad SCULPT and STRETCH will turn into a tape, and `SCULPT ▸ NEW
TAPE` has no per-pad confirm — so whatever SOURCE points at when GO is
pressed is what gets rendered over. It was keyed on `sources.firstOrNull()`,
**the value of the kit's lowest assigned slot**. Pick A07, let a capture or
a chop land on A01, press GO: it renders A01. Nothing in between said the
target had moved.

**That key was doing two jobs, which is why this is a replacement and not a
deletion** — the standing question from steps 1 and 2, arriving for the
third time. Re-pointing on a kit change was the bug. Taking a first value
once the kit has pads at all was *not*: `sources` is empty on the first
composition, so a plain `remember(entry.dir)` would leave SOURCE stuck at
null forever. A remembered pick with a fallback does the second job without
the first:

```kotlin
var pickedSource by remember(entry.dir) { mutableStateOf<Int?>(null) }
val sourceSlot = pickedSource?.takeIf { it in sources } ?: sources.firstOrNull()
```

The `in sources` test is load-bearing in its own right: without it, picking
a pad and then deleting it leaves SOURCE naming a pad that is gone.

**The other two** — `mode` keyed on the panel, `fraction` keyed on the panel
and mode — are PAD SHEET's move knobs again, down to the reasoning: each
panel has its own modes and each mode its own knob, so neither can simply
carry across, but keying them that way threw the player's setting away by
the act of looking at the other one, which is what the chips are *for*. Same
fix: a value per panel, and a value per panel-and-mode.

That J24's fix transferred unchanged to a different screen is the useful
result here. The shape — *a control keyed on the very selection the control
exists to let you compare* — is now worth grepping for directly rather than
waiting for a review to name it.

---

### PR 5 as a whole

| Step | States examined | Real bugs | What the finding did not say |
|---|---|---|---|
| CHOP | 10 | 1 | The placement was computed only in a click handler, correct *only because* `layout` died with the model |
| PAD SHEET | 20 | 3 | `DRIFT_FRACTION`'s display was honest *only because* the knob reset on move-switch |
| TAPE | 1 model, 2 fields | 1 | Nothing hidden — but the obvious fix (widen the idle guard) was wrong |
| KIT / TEXTURE | 4 | 3 | The old key was also doing a legitimate second job |

**Three times in four, the fix opened a second hole the finding never
mentioned**, and every time the tell was a KDoc explaining why some existing
constant or effect was written the way it was. Reading those, not just the
code, is what caught them. The standing question this leaves for the
remaining PRs: not "should this key change" but **"what was relying on it
not changing"**.

### A second standing question, for the laws rather than the code

Three laws written during PR 5 and PR 6 were **vacuous when first run**, and
all three failed the same way: they asked whether something *correct*
existed, when the property was that nothing *incorrect* did.

| Law | What it asked | What it missed |
|---|---|---|
| The `onCatchInFlight` check (PR 4) | the parameter exists, a use exists | that they were in different functions — CI caught it |
| The `barsAfter` law (J17) | a `barsAfter` call exists | the *other* stepper using plain arithmetic |
| The EXPORT-guard law (J10) | `pads.isNotEmpty()` appears in the file | that it was nowhere near the offer |

Two of the three were caught only by deliberately breaking the code and
watching the law stay green. So: **write the regression first, then the
law**, and for anything of the form "X always goes through Y", enumerate
every X rather than searching for one good one.

---

### PR 6 — the cut seams *(J10, J17, J37)*

Capabilities that exist in the model with no control attached.

- **J10** — CHOP and EXPORT receive **zero** programmatic navigations,
  though they are steps 2 and 4 of the loop the app advertises. Offer CHOP
  when a capture lands; offer EXPORT when a kit is ready. The review calls
  this the cheapest change in the document and I agree. **Done.**

  Decided with the user: **a toast with an action**, on both — non-blocking
  and ignorable, rather than navigating outright. Being moved without asking
  is the same complaint as a destructive control changing its own target.

  It was not quite the cheapest change, because **the app had no actionable
  toast**. `LandingNote` looked like the answer and is the wrong weight — it
  *stays until read*, and an offer after every capture and every kit would
  become a toll on the loop it is trying to help. So `ToastOverlay` gained
  an optional door, sharing the message's merged semantics node so TalkBack
  announces the line and offers the action together.

  **The dwell had to change with it.** `TOAST_DWELL_MS` is 2600 — right for
  a line you only have to read, wrong for one you have to *reach*: an offer
  that vanishes at 2.6 seconds is a target that sometimes catches the thumb
  and sometimes does not, which teaches nobody where the door is. A toast
  carrying a door gets `TOAST_OFFER_DWELL_MS` instead. Still a dwell, not a
  box that waits.

  EXPORT is offered only when the kit actually has pads — a door onto an
  empty EXPORT is a worse answer than no door.

  **The copy was off-voice and the suite said so.** Both offers were written
  as questions first ("CHOP IT INTO PADS?") and the full-stop law refused
  them. Checking before widening the law was the right move: they were the
  only two question marks in the whole of `Copy`. The house voice does not
  ask — it states what happened and what to do next ("CLEAR SOFT HITS
  FIRST, THEN STACK."), and the door beside the line is what makes it an
  offer rather than an instruction.

  **A known limitation, for the IA work.** The offer is time-limited, and
  seven seconds is tight for a TalkBack user to find and activate the door.
  The offer is a nudge, not the only route — both screens remain reachable
  from the menu — but if the handoffs matter, a non-timed route to "the next
  step" belongs in the IA pass rather than in a longer and longer toast.
- **J17** — attach a bar-length control to the `recordBars` seam that
  already exists, and say somewhere that PROG C changes pattern length.
- **J37** — velocity is hard-coded to `1f`, so `SOFT HITS` can be switched
  on and never heard. **Done.**

  Decided with the user: **tap position on the pad**. The audio path was
  never the missing piece — `PadHit.resolve` has always chosen a layer by
  velocity. What was missing is that *a tap on glass carries no force*, so
  the grid had nothing to pass and passed `1f`. Position is the one thing a
  tap does carry.

  `PadHit.velocityAt(y, height)` is in `:shell` and tested: **the bottom of
  the pad is the full hit and the top is `SOFTEST`**, which is deliberately
  **not** silent (a pad that makes no sound reads as broken, not as soft), a
  touch reported outside the cell is clamped rather than extrapolated, and a
  zero or non-finite height answers a *centre* hit rather than dividing —
  the same lesson as `restoreView`'s NaN zoom and `Audition.barOf`'s zero
  `stepsPerBar`.

  **The direction was wrong when this paragraph was first written, and the
  user found it on a phone before any test did.** "Seems to work, but is
  louder at the bottom of the pad?" — it was, because `PadGrid.kt` had
  carried the opposite convention since long before this work ("top of the
  pad is softest, bottom is full velocity"), used by PLAY, GROOVE and KEYS,
  and KIT had been built inverted beside it with a different floor, a
  different accessible value and a legend advertising the wrong way round.
  Four copies of one quantity. **The app had already answered the question I
  put to the user; I should have grepped for the existing convention before
  asking which gesture to use.** `PadHit` now owns it and the other three
  read it.

  **The floor is not a number chosen by eye.** `StackTakes.windows` puts the
  soft/live boundary at MIDI 63 with one soft zone and at 41 with two, so a
  floor that cleared one could still miss the other. The tests assert the
  floor lands in the softest zone by resolving real layered pads through
  `PadHit.resolve` rather than by restating the constant.

  Where the soft/live line falls on screen is therefore *the pad's own*, not
  a tuned split point. The legend says the direction ("TAP HIGH ON A PAD FOR
  A SOFTER HIT") rather than the rule, because a direction is something a
  thumb can act on.

  **The floor was 0.35 and is now 0.20, because 0.35 was a defect rather
  than a preference.** `PadHit.SOFTEST`'s own KDoc claimed MIDI 44 sat
  "inside the softest zone `StackTakes.windows` lays out for one soft zone
  (MIDI 1..63) and for two (1..41)". 44 is not inside 1..41. On a pad
  stacked two or three deep the softest layer could not be reached from
  the grid at all, so SOFT HITS built layers nothing could trigger — and
  0.20 is the value this floor held before the inversion fix moved it,
  picked at the time for exactly this reason and never re-argued after.

  Two tests were involved and only one of them was any good. The bad one
  was `assertEquals(0.35f, PadHit.SOFTEST)` — a test that restates the
  constant, so it passed throughout. The good one pinned the limitation
  deliberately (`a stacked pad's softest take sits below the touch floor -
  a known limit`) and its failure message asked whoever moved the floor to
  come and say so, which is exactly what happened. The codebase knew; the
  KDoc beside the constant was the thing that was wrong.

  The cost is accepted rather than dodged: PLAY, GROOVE and KEYS share
  this curve, so their softest touch is quieter than it was.

  The accessible path keeps full velocity on purpose: a synthesized click
  has no position, and guessing one would hand TalkBack users an arbitrary
  velocity instead of the pad's plain whole sound. A law holds both halves
  and was proved to fire on each.

---

### PR 7+ — journeys and information architecture *(J11–J19, J30–J36, J39)*

Everything left that requires a **product decision rather than a fix**. I
am deliberately not proposing solutions here, because these are yours.

**Audited first, and three of them turned out not to be decisions at all.**
The section was written as one band; it is really two. These three are bugs
in the same family as PRs 3–6 — the app doing something and not saying so —
with no design choice inside them, and are **done**:

- **J35 — a refused EXPORT said nothing.** Not a missing feature: the box
  for this already existed, and `LandingNote`'s own KDoc lists "a backup
  preflight refused part of" among the things it is for. EXPORT's own
  preflight refusal did not use it. The old justification ("the refreshed
  checklist below is the message") is defeated by the checklist being the
  first card in a scroll with the button pinned at the bottom — on a phone
  the row that changed is very likely off-screen at the moment of the tap.
  `LandingNote.exportBlocked` now names every FAIL, folded like every other
  note. EXPORT is the first *screen* to raise the box; the share landing,
  the refusal and BACKUP all raise it from `App`.

- **J32 — HUM stated its one rule after the tape was already audible.** A
  one-line reorder, held by a law, because it is the only rule on that
  screen whose worth depends on arriving before the thing it governs.
  Honest about the limit: this makes the rule readable in time to stop and
  start again, not in time to have had headphones on already. Saying it
  before the press is a question about the button's affordance, and that
  one *is* a decision.

- **J30 — CHOP never names the tape it is about to slice.** Held back
  deliberately: it adds a visible row, and the velocity bug is a fresh
  reminder that UI added without seeing the screen is how two conventions
  came to disagree. TAPE already names its source in the cassette row, so
  there is a pattern to copy rather than invent — it needs eyes, not a
  design.

**What is left here really is yours:**

- **J11** — Back means "go to the shelf," not "go back." Real back stack,
  or keep the policy and stop calling the button Back?
- **J12 + J14** — twelve peers in a flat strip, of which KIT is the hub
  (11 of 24 navigations) sitting 4th and named one letter from KITS.
  Grouping, a second row, an overflow, a drawer — none were ever on the
  table. Also: rename KIT or KITS.
- **J15 + J16** — the largest surface in the app (3,611 lines) behind an
  invisible 480 ms hold with no press feedback, and two of its three doors
  fail to retire its own discovery hint.
- **J18 + J19** — your original complaint. PROG A–E as a prev/next
  carousel over five options, labelled index-first, with the only
  hand-editing door mid-scroll under the third heading. CHOP already owns
  the right control for this.
- **J30–J33** — CHOP never names the tape it is about to slice, names the
  kit it creates only after writing it, and shows a layout decision as
  sixteen unlabelled rectangles.
- **J34 + J35** — EXPORT's eight good explanations are shut by default,
  and a blocked write says nothing at all.
- **J36** — nothing on the KIT grid distinguishes a treated pad from a raw
  one, though the pad sheet already computes exactly those summaries.
- **J39** — the tape counter and pencil rewind are invisible controls.

---

### PR 7 — researched, not chosen by taste

You asked for the questions, then asked me to research them and do what is
provably best. This records what the evidence said, including the two places
it said **don't**.

The sources, in the order they settled things: the repo's own written law
(`UI_DESIGN.md`, `PERSONALITY.md`), the app's own exporter, measurable facts
in the code, and the Android platform contract.

#### Settled by the exporter — done

- **J18 + J19 — PROG A–E.** The decisive fact is not a design opinion: the
  letters are an argument to `GrooveProgram.compute` and **nothing else**.
  They are not MPC clip slots, and no exporter in the app has ever written
  one. What the exporter *does* write is the word — `GrooveVariations`
  suffixes each derived clip with `Swing`, `Half`, `Sparse`. So the screen
  and the SD card disagreed about what these things are called, and the
  screen was the one making it up.

  The five programs are now `CAPTURED · SWING · HALF · SPARSE · YOURS`, all
  visible at once in a segment row — CHOP's own control, whose KDoc already
  argues a picker should "agree with every other picker in the app rather
  than invent its own third convention".

  **The law caught my first attempt.** I wrote `SWUNG`, because it reads
  better. The exporter writes `Swing`, the law refused it, and the law was
  right: a player who picks a program here and then looks for it on the
  hardware has to recognise it. `SWING` is also what the stepper two rows
  down is called, which is correct rather than a collision — that stepper
  sets the percent this program applies.

  **Two programs are exempt, on facts rather than taste.** `CAPTURED` is the
  base clip and carries no suffix, so there is no exporter word to match.
  `YOURS` is found again on disk by `GrooveEdit.NAME_SUFFIX`, which is
  `" E"` — a marker inside `groove.json`, not a name, and not renameable
  without migrating every kit already saved. The letter stays on disk and
  the screen stops showing it.

  J19's three doors — `STEPS`, `EDIT STEPS`, `EDIT THIS TAKE` — all fork
  into the same program, and the toast that follows all three now says
  YOURS. They say where they go: `START YOURS` from nothing, `FORK TO
  YOURS` from either of the other two.

#### Settled *against* changing — evidence says the finding is wrong

- **J39 — the "invisible" tape counter and pencil rewind.** Not a
  discoverability bug. `PERSONALITY.md` catalogs both as **hidden eggs**
  under law 4: *"One visible gag per screen; the rest are hidden. Discovered
  delight beats displayed delight."* Making them visible would break the
  written law, not serve it.

  The review's sharper point — that TalkBack announces `REWIND PENCIL` and
  `SPIN BACK BY EAR` while a sighted user gets nothing — is real but is not
  fixable in the direction it implies. An interactive element must carry an
  accessible name (WCAG 2.2 §4.1.2); removing the label to even the score
  would trade a working control for a broken one. Law 4 governs the *visual*
  surface, and that asymmetry is the correct trade, not a defect.

  Recorded while checking: both gestures have drifted from the doc — it
  specifies a tap on the cassette and a triple-tap on the readout; the app
  has a hold on the left reel and a single tap. Noted in `PERSONALITY.md`,
  not "fixed", because which gesture is right is a design call and both work
  today.

- **J15's box-state complaint.** "Box state resets per kit" is the
  *designed* behaviour, settled on a canvas in wave DDD and written down:
  `UI_DESIGN.md`, "the pad sheet folds" — *"One box open at a time; the open
  box is remembered per kit, so the next pad opens on the same bench."*
  Changing it to per-pad would contradict a settled decision. The rest of
  J15 (no press feedback on the 480 ms hold) stands and is below.

#### Settled, still to do — in the order the evidence is strongest

- **J15's press feedback.** `UI_DESIGN.md`'s rules of the language:
  *"Every control is RAISED, PRESSED or SUNKEN — pressing a control flips
  its light source."* The pad does not; its only press animation *decays*,
  so it darkens as the sheet opens. A written rule the code breaks.
- **J16.** The hint is retired only inside `KitScreen`'s `onLongPress`,
  though two other doors open the sheet — and the hint's own KDoc claims
  opening the sheet is *"the only event that proves they found it"*. The
  KDoc and the code disagree; the KDoc is right.
- **J33.** `GridPreview` draws sixteen `Box`es with no text and **no
  semantics node**. An informative element with no accessible name (WCAG 2.2
  §4.1.2), on a screen where every other picker has one.
- **J36.** `UI_DESIGN.md` already establishes the language for encoding pad
  state in a line treatment — *"dashed shell = unclassified"*. A treated pad
  gets a rim in that same language rather than a new badge.
- **J11 — Back.** Android's contract is explicit and external: Back moves
  *"in reverse chronological order through the history of screens the user
  has recently worked with"*, popping a back stack. One root handler sending
  Back to KITS from every tab is not that.
- **J12 + J14 — KIT vs KITS.** Measured: **11 of 24** direct navigations land
  on KIT, next is 4. And `Copy` says SHELF 41 times against KITS 22. The tab
  is the only place the shelf is called KITS, one letter from the hub. The
  *grouping* half of J12 stayed open at the time of writing — `UI_DESIGN.md`
  lists the shelf's **form** as still undecided, so regrouping the strip
  looked like pre-empting a decision that is the user's. **Taken since**,
  and the worry was misplaced: drawing seams at boundaries the tab order
  already had settles nothing about whether the shelf becomes a file
  manager. That question stays open.
- **J30.** CHOP's three-tier fallback can slice the open kit's longest
  sample without ever naming it. Acting on an unstated assumption, not a
  layout preference.
- **J34.** `why`'s own KDoc says it exists because *"the user was choosing
  between eight names and no reasons"*; shut by default returns them to
  exactly that.

#### Not settled by evidence — were yours, and were taken

All four were put back as choices with pros, cons and a recommendation;
all four recommendations were taken.

- **J12's grouping** — **done.** A 2dp Win9x groove at each of the three
  seams the tab order already had: the shelf, the four flow tabs, the five
  instruments, the two utilities. The width was not chosen by eye. The
  order above the tab list was arranged so EXPORT lands in the run that
  shows without a drag, so a separator wide enough to push it back out
  would undo that silently — the sim's own arithmetic says 2dp changes
  nothing and 4dp costs a tab, and it now recomputes that on every run
  rather than taking the note's word for it.
- **J31** — **done.** The worry recorded here was that naming the kit first
  "adds a naming step to the primary action", and that is the one thing it
  must not do: the fix is a line under the button reading `LANDS AS <NAME>`,
  no extra tap and no field to fill. It could not go *on* the button, which
  was the first thing tried: `PrimaryAction` draws one centred `displayBig`
  line with no overflow handling inside a `weight(2f)` box, so an appended
  kit name would have run out of the rim. The sibling can carry its own
  destination only because it is a full-width secondary in smaller type.
- **The velocity floor** — **done**, and it was not taste after all.
  Classified here as needing ears, then found to be arithmetic: the KDoc
  claimed MIDI 44 sat inside the softest zone at one and two soft zones,
  and at two the zone ends at 41. The floor is 0.20 (MIDI 25), inside the
  softest window at every depth `StackTakes` builds.
- **KIT's two legend lines** — taste, and left alone.

---

### PR 8 — words *(J13, J41–J46, and J47's three carried items)*

The S3 band, one pass. **Done**, in two PRs: J43 turned out to be a dead
control rather than a wording bug and went first, on its own; everything
else is the words pass.

Every item here was re-verified at source before it was touched,
**including J47's three**, which the review explicitly flagged as carried
on the prior review's word. All three were still true, and one was worse
than stated.

- **J13** — the first-run note glossed step 3 (KIT) as "PLAY IT", and PLAY
  is a real tab six places along the same menu row. It now reads **"RECORD
  IT, CUT IT, KIT IT, DUB IT"**, which is the app's own verb for that step
  (`orbitBounced` already said "TRIM IT, CHOP IT, KIT IT").

  The law that exists for exactly this was looking one line too high: it
  held `FIRST_RUN_LOOP_STAGES` — the four tab names — to `MENU_ITEMS`, and
  never read the sentence underneath that glosses them. It now refuses any
  menu-tab name in the note that is not one of the four stages.

- **J41** — `GRID` named the CUT bench's equal-parts segment, its `GRID ×N`
  readout, the snap-to-pulse row (`ON THE GRID`), the send button, and the
  landing toast. Three different meanings on one screen.

  The snap row is now **`SNAP · CUTS ONTO THE PULSE`** and the destination
  is now **`SEND TO PADS`** / "N SLICES ON THE PADS" — PADS being what the
  app calls the sixteen everywhere else. `GRID` is left meaning one thing on
  CHOP: cut into equal parts.

  The `16TH` half is **not** renamed. A `Ladder.Rung` and a `GridSnap` both
  legitimately mean a sixteenth; what made the collision unlearnable was
  that the two rows shared a word in their legends as well. With one row
  called SNAP and the other ZOOM, the same chip name under two different
  legends is a distinction a player can actually see.

- **J42** — HELP's ZOOM line listed the four rungs and not `COUNT`, which is
  the chip the row sits on **by default**. So the one state a new user is
  actually in was the one state HELP never mentioned. `COUNT` is now
  `Ladder.COUNT_LABEL`, the row is `Ladder.ROW_LABELS`, and HELP's line is
  built from it — one quantity, one place. A law refuses the literal back
  onto the screen and checks HELP names every chip.

- **J44** — bigger than the wording. `TAPED. NO TAKEBACKS.` is false, and so
  are the other three lines of the rotation: `commitSelection()` is a pure
  read and `onCommit` puts the range in a `remember`ed `lastCommit`.
  **Nothing is taped, nothing is cut, and nothing lands on a shelf to be
  renamed.** The rotation is gone rather than rewritten, because the J10
  offer fires on the same event and already says the true thing.

  **Which surfaced a defect of my own from PR 6.** The offer's door lived in
  its own `var` beside the message, and TAPE's KEEP fired a plain toast on
  the very next line — so the offer's sentence was replaced and its door
  left on screen under the COMMIT line, on the offer's longer dwell. The
  comment beside the two vars claimed "a door can never outlive its
  message"; nothing made that true. The door is now **derived** — it shows
  only while the toast on screen is the sentence it was offered with — and a
  law keeps it derived.

  Same shape as the three finds in PR 5, arriving from the other side: there
  the tell was a KDoc explaining why a constant was written as it was; here
  it was a comment asserting a behaviour the function does not have. Two of
  those in this one PR — the other is INSTANT KIT's "Read before
  `commitSelection()`, which clears it", also corrected.

- **J45** — `HITS…` is the stepper's *readout*, and pressing ◀ HIT before
  the finder had run threw that same label back as the app's answer. Its
  sibling refusal is a sentence with a next step; so is this now.

- **J46** — `SEND IT SOMEWHERE` is true of two of the four buttons under it.
  MIDI and CHART write a file where you stand; SONG ▸ and ORBIT ▸ leave the
  screen — and they were interleaved, leaving the `▸` glyph as the only
  signal. The row is sorted by what the button does and the legend names
  both halves in the order they sit: **`WRITE IT OUT · OR TAKE IT
  FURTHER`**. One row still, because the comment there records why a single
  row was chosen and that reasoning is unchanged.

- **J47 · prior #25** — confirmed and worse than the row says. ORBIT's
  `bounceToTape` writes through `SnipStore.import`, the same door LOOP's and
  GROOVE's bounces use, and **TAPE is a different screen**. All three
  buttons now name SNIPS *before* the tap, all three landing lines name it
  after, the function is `bounceToSnips`, and a law holds all six strings to
  one destination word and refuses `TAPE` in any of them.

- **J47 · prior #27** — confirmed. HELP's first line used `CATCH` as a
  generic verb for capture while `CATCH A HIT` is a named feature on that
  same screen, described seventeen lines further down the same HELP. It
  reads `RECORD A SOUND` now, which is also the verb J13's note uses.

- **J47 · prior #30** — confirmed, but only one of the four boxes was a
  verbatim repeat. TREATMENT's card said "TREATMENT" under a legend reading
  TREATMENT; the other three prefixed the legend to a gloss ("MUTATE · ONE
  HIT FROM TWO"). All four now carry the gloss alone. SHAPE's needed
  rewriting rather than trimming: "SHAPE · CARD RENDERS IT" used CARD to
  mean the exported program, on a screen where card means the thing the
  words are printed on.

**One thing checked and deliberately left.** The names behind the screen —
`ChopReviewModel.GridSnap`, `ChopReview.sendToGrid()`, `ChopScreen`'s
`onSentToGrid` callback — still say Grid. None of them reaches a player,
and renaming them would put a large mechanical diff in a PR whose whole
claim is that it only changed words. `bounceToTape` was renamed because it
was *wrong*, not merely old: it named a screen the function never writes
to.

---

## What I recommend, if the whole sequence is too much

**PR 1, PR 2, PR 3.** In that order.

PR 1 because it is a real bug in audio output. PR 2 because it is the
best ratio in the document and unblocks everything after it. PR 3 because
"the app destroyed my work and didn't ask" is the only category here that
a user cannot work around once they understand the app.

Everything from PR 5 onward is real but is improvement, not repair.

---

## Decisions I need from you before starting

1. **PR 0** — build the `:app` test seam first, or accept
   compile-and-read as the bar for this wave?
2. **PR 2** — disabled controls: dim only, or dim *and* toast the reason?
3. ~~**PR 3** — `MAKE PAD ▸`: confirm, fresh name per press, or an explicit
   keep step?~~ **Answered: fresh name per press.** Shipped; see PR 3 above.
4. **Sequence** — is "correctness → contract → data loss → everything
   else" the right weighting, or do you want the journey work (PR 7,
   which is what you originally asked about) pulled forward?

On 4 specifically: your original ask was GROOVE and the between-screens
flow, which is PR 7. I have put it late because it is the part that needs
your design judgement rather than mine, and because the PRs before it
stop the app losing people's work. That ordering is a recommendation, not
a constraint — say the word and PR 7 goes first.
