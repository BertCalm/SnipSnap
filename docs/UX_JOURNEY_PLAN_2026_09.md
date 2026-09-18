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

---

### PR 6 — the cut seams *(J10, J17, J37)*

Capabilities that exist in the model with no control attached.

- **J10** — CHOP and EXPORT receive **zero** programmatic navigations,
  though they are steps 2 and 4 of the loop the app advertises. Offer CHOP
  when a capture lands; offer EXPORT when a kit is ready. The review calls
  this the cheapest change in the document and I agree.
- **J17** — attach a bar-length control to the `recordBars` seam that
  already exists, and say somewhere that PROG C changes pattern length.
- **J37** — velocity is hard-coded to `1f`, so `SOFT HITS` can be switched
  on and never heard.

---

### PR 7+ — journeys and information architecture *(J11–J19, J30–J36, J39)*

Everything left that requires a **product decision rather than a fix**. I
am deliberately not proposing solutions here, because these are yours:

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

### PR 8 — words *(J13, J41–J46, and J47's three carried items)*

The S3 band, one pass: `GRID` naming five things, `16TH` naming two,
`ZOOM` naming a control that is called `COUNT`, `TAPED. NO TAKEBACKS.`
being false, a readout string fired as a toast, and `SEND IT SOMEWHERE`
grouping file-writes with navigation.

Cheap, and worth doing last so it isn't re-churned by PRs 5–7.

**Note:** J47's three items are carried on the prior review's word and
were not re-verified. Re-check them at source before acting.

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
