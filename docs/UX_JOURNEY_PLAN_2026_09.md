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

**Decision needed:** for `MAKE PAD`, is the answer a confirm, a
non-overwriting fresh name per press, or an explicit "KEEP THIS ONE"
before the next roll? I lean **fresh name per press** — it matches
`shelf.freshName` used elsewhere in the app, and it removes the
destruction rather than asking about it.

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

---

### PR 5+ — `remember(key)` as storage *(J20–J26, J22, and the J24 half of J1)*

This is root cause 2 and the largest body of work: eighteen pieces of
user state keyed to something that changes during ordinary use. It does
**not** fit in one PR and should not be attempted as one.

Split by screen, in this order (most destructive first):

1. **CHOP** (J20, J21) — nine pieces keyed to `model`, reassigned at five
   sites, two of which are helpers the whole bench calls through.
2. **PAD SHEET** (J23, J24, J26) — knobs that reset on move switch, and
   DEPTH/BLOOM which never persist at all while looking identical to three
   sliders above them that do.
3. **TAPE** (J22) — a snip landing replaces the tape under the finger.
4. **KIT / TEXTURE** (J25) — the source pad jumps when the kit's lowest
   slot changes, because the key is *the value of the lowest slot*.

The mechanical fix is the same each time: hoist the state above the thing
that re-keys it, or key it on something stable (a slot id, not a model
instance). The judgement each time is *which* — and that is per-screen,
which is why this is several PRs.

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
3. **PR 3** — `MAKE PAD ▸`: confirm, fresh name per press, or an explicit
   keep step?
4. **Sequence** — is "correctness → contract → data loss → everything
   else" the right weighting, or do you want the journey work (PR 7,
   which is what you originally asked about) pulled forward?

On 4 specifically: your original ask was GROOVE and the between-screens
flow, which is PR 7. I have put it late because it is the part that needs
your design judgement rather than mine, and because the PRs before it
stop the app losing people's work. That ordering is a recommendation, not
a constraint — say the word and PR 7 goes first.
