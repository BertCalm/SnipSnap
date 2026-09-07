# Design gap — `design/HANDOFF.md` vs the code

Written against the OILSLICK handoff synced on 2026-08-28 (Design Canvas export,
`design-sync/`), read against branch `claude/mobile-mpc-drum-sampler-t58x74`.

> **Re-verified 2026-08-29 at `00835bf`.** The first pass read a checkout 119
> commits behind origin. After merging waves OO–SS, two findings below changed:
> §2b is **closed** (TAKES + BIN is fully built) and §1c is **re-scoped** (the new
> `synth/Eras.kt` supplies the treatments rather than new hand-written chains).
> Everything else re-checked and still stands.

> **Re-verified 2026-09-07 (wave DDD).** Everything below still stands as
> history; what changed is scope. Since the handoff synced, the app grew a
> workshop the handoff never drew: the pad sheet's SHAPE, MUTATE (with
> ROULETTE, DRIFT and rooms as partners), OUTSIDE (REAMP, ROOM, KEEP ROOM),
> PAD FROM ANYTHING, DE-SAMPLE, TUNE, BODY, WOBBLE; SCULPT / STRETCH / TEXTURE
> and IN KEY on KIT; SHARE, BACKUP and the share door's landing; INSTRUMENTS
> and ROOMS on the shelf; GRAIN FIELD and the SURFACE. All of it was written
> blind against the existing vocabulary. The pad sheet outgrew the handoff's
> §PAD SHEET (wireframe 1d) — ten steppers and six cards in one scroll — so a
> design pass drew three directions on the *Pad Sheet v2 and Rooms* canvas and
> **Direction A** (group boxes with pixel legends, one open at a time, the
> nav pinned) was chosen and built; its boards and spec live under
> [`design/pad-sheet-v2/`](../design/pad-sheet-v2/README.md), which now
> supersedes §PAD SHEET here. `UI_DESIGN.md` records the settled rules.
>
> **Still undrawn**, in value order: the SHARE / BACKUP chooser hand-off (the
> landing itself is settled: wave FFF put its trouble - skipped kits, a
> refusal - in the language's honest little message box, `MessageBox`, and
> left a clean landing its toast); the TEXTURE panel and the KIT
> action row, which has grown past the artboard's; GRAIN FIELD and SURFACE,
> which arrived from another branch with no boards at all. A fresh handoff
> export from the design canvas would close these in one go.

The handoff is unusually implementable — it states tokens in the form Compose
consumes and names repo symbols (`PeaksPyramid`, `StarterKits`,
`KIT_BEST_PRACTICES`) as the data behind screens the prototype fakes. Most of
those symbols exist. What follows is only where design and code disagree.

---

## 0. Read this first: the handoff targets a branch that isn't this one

The LOOP section is headed *"Android Plan 03 · Task 6 — use these, not hardcoded
grey."* That is a direct note to `app/src/main/kotlin/com/snipsnap/app/LoopGrid.kt`,
whose palette object carries the comment *"hardcoding one keeps this task about
the grid."* Both the `:loop` model and the `:app` Compose surface live **only** on
`plan-03-live-android`.

|  | this branch | `plan-03-live-android` |
|---|---|---|
| modules | json xpm audio kit mpc3 synth **cli shell** | json xpm audio kit mpc3 synth **loop app** |
| Compose | none | `LoopGrid.kt`, `AndroidAudioSink.kt` |
| `pluginManagement { google() }` | absent | present (AGP + androidx need it) |

`git rev-list --left-right --count HEAD...plan-03-live-android` → **53 / 24**.
Neither side is an ancestor: plan-03 has the Android toolchain and 24 commits of
loop engine; this branch has 53 commits of `:shell`/`:cli`/testkit that plan-03
has never seen, and plan-03's `settings.gradle.kts` doesn't include `:cli` or
`:shell` at all.

**Every §2 LOOP finding is unactionable until that's resolved** — merge forward,
rebase plan-03, or cherry-pick `:loop` + `:app` + the `pluginManagement` block.
That's a call for whoever owns the branch strategy, not one this report makes.

---

## 1. Wrong on screen today (in `:shell`, this branch)

> **Note, 2026-08-29: every finding in this section is closed.** See 1a, 1b, 1c below.

### 1a. ~~`Scheme.amber` is one field doing two jobs~~ — CLOSED, `warn` split out

**Struck 2026-08-29.** `Scheme` now carries `warn` as its own field, separate
from `amber` — OILSLICK's needle reads `warn = 0xFFB000` while `amber` stays
cyan. `SchemesTest`'s `warn is warm in every scheme` and `OILSLICK separates
its warn from its amber slot` hold both apart for good.

The new token table splits what the code fuses:

| handoff token | value in OILSLICK | used for |
|---|---|---|
| `lcd-alt` | `#40E0E8` cyan | the "amber slot" — secondary LCD text |
| `warn` | `#FFB000` | **needle, onset bars, warnings** |

`Schemes.kt:115` has a single `amber = 0x40E0E8`. GROOVE specs *"fixed needle at
y=96 (amber `warn` in OILSLICK-cyan slot)"* — i.e. the needle is deliberately the
one thing that is **not** cyan. Reading `scheme.amber` for it paints it cyan and
the needle vanishes into the readouts. This needs a new field on `Scheme`, not a
rename.

### 1b. ~~Six load-bearing tokens have no field at all~~ — CLOSED, all six added

**Struck 2026-08-29.** `Scheme` now carries `raised`, `win`, `winFrame`,
`deskGlow`, `ink3` and `accent`; `SchemesTest`'s `OILSLICK matches the handoff
token table exactly` pins all six against the handoff's values.

`Scheme` predates the new table. Missing: **`raised`** (button/empty-pad
gradient), **`win`** (window body), **`win-frame`** (the 3px OILSLICK gradient
that *replaces* the old bevel highlight/shadow pair), **`desk-glow`**, **`ink3`**
(third text tier), **`accent`** (`#E040C8`, selection + row inset bar).

The `win-frame` one is structural: `Scheme` still carries `grayHi`/`grayEdge`/
`grayMid`/`grayDark`, which exist to build bevels. OILSLICK doesn't have bevels.

### 1c. ~~`Treatments.chain()` rejects the pad sheet's own default range~~ — CLOSED, AMT=0 no longer throws

**Struck 2026-08-29.** Both guards now read `require(amount in 0f..1f)`, with
an explicit `amount <= 0f` bypass returning an untreated chain instead of
throwing — `Treatments.kt` and `Eras.kt:47` both fixed, as the finding asked.

```kotlin
require(amount > 0f && amount <= 1f) { "amount is (0, 1], got $amount" }
```

The PAD SHEET specs *"AMT 0-100 in 5s"* — so AMT=0 is reachable by one stepper
tap and throws. Separately, the segmented control is `NONE / CRUSH / TAPE / DIRT`,
but `Shuffle.TREATMENTS` is `reversed / crushed / slapback / washed / punched`:

- `NONE` is not a treatment name — it must be UI-level absence that never calls
  `chain()` (otherwise `IllegalArgumentException: unknown treatment 'NONE'`).
- `CRUSH` → `crushed` was the only confident mapping against `Shuffle.TREATMENTS`.
  **Resolved 2026-08-29:** `synth/Eras.kt` (wave QQ) already models `sp1200`,
  `mpc60`, `tape` and `phone` as era-faithful chains, so the card maps
  CRUSH→`sp1200`, TAPE→`tape`, DIRT→`mpc60` instead of authoring anything. It
  drives `KitBuilder.eraPad`, which — unlike `treatPad` — ages a pad's velocity
  layers too.

The AMT defect is worse than first reported: the same `require(amount > 0f …)`
guard sits in **both** `Treatments.kt:20` and `Eras.kt:47`.

---

## 2. Missing capability

### 2a. LOOP — the model is a near-perfect match, the screen is not

On plan-03, `com.snipsnap.loop` already matches the handoff almost line for line:

| handoff | code | |
|---|---|---|
| 6 fixed tracks | `Session.TRACK_COUNT = 6` | ✓ |
| chains of 1–8 blocks | `MAX_CHAIN = 8`, `require(chain.isNotEmpty())` | ✓ |
| playhead = `interval % chain.length` | `Arrangement.indexAt` | ✓ |
| full cycle = LCM of chain lengths | `Arrangement.cycleIntervals` | ✓ |
| block types LOOP / PAT | `LoopBlock` / `PatternBlock` | ✓ |
| header tap = mute | `Track.engaged` | ✓ |
| BOUNCE | `Bouncer.kt` | ✓ |

What's missing is entirely presentational, and `LoopGrid.kt` currently has none
of it: fixed **track colours** (`Track` has a free-form `name`, no colour, and no
notion that track 0 is DRUMS `#E8542E`); **block display name** (`LoopBlock` holds
only `sampleFile`, and the cell renders `"${b+1}"` rather than type-tag-over-name);
`heightIn(30.dp, 46.dp)` (cell uses `aspectRatio(1.2f)`); muted `" ✕"` suffix;
the dashed `"+"` add slot; the selected state; transport / BPM steppers / cycle
readout / BOUNCE; landscape 816×362; the rebake overlay.

> **One spec bug worth fixing in the design, not the code.** The cycle readout is
> `L INT · L*4 BARS · mm:ss` at `bars*4*60/bpm` sec — it hardcodes 4 bars per
> interval. `Session.VALID_BARS = [1, 2, 4, 8]` and `barsPerInterval` is a
> constructor arg. The Compose readout must multiply by `session.barsPerInterval`
> or it will lie on any non-4 session.

### 2b. ~~TAKES + BIN~~ — CLOSED, it was already built

**Struck 2026-08-29.** The first pass read a stale checkout and reported that
`Personality.kt:77`'s `"EJECTED. THE BIN KEEPS IT 30 DAYS."` had nothing behind
it. It does. `shell/KitBuilder.kt` carries the whole X2.3 domain:

| X2.3 asks for | code |
|---|---|
| every save archives a take | `archiveTake()`, called from the save path |
| the take list, `T4…T2` | `takes(): List<File>`, `TAKES_DIR ".takes"`, `MAX_TAKES 32` |
| RESTORE an older take | `restoreTake(take): Kit` |
| every delete goes to the bin | `moveToBin(fileName)`, `BIN_DIR ".bin"` |
| the `27D LEFT` countdown | `BIN_KEEP_DAYS 30.0`, `purgeBin(olderThanDays, nowMillis)` |
| BACK, from the bin | `restoreFromBin(originalName)` |
| EMPTY THE BIN NOW | `emptyBin()` |

What remains is a screen, not a model — and the copy for it is still unwritten
(§2g).

### 2c. GROOVE PROG E — A–D map cleanly, E has nowhere to live

`GrooveVariations` covers the derived programs exactly as spec'd:
`standard()` → A, `swing(clip, percent)` → B, `halfTime()` → C, `sparse()` → D,
plus `humanize(clip, amount, seed)` for A's reseed. All pure functions of the
base clip — matching *"B–D are pure functions of A — never stored."*

PROG E is the exception the design calls out: `gUser` as `[lane, step, vel][]`,
cloned from the *current* program's quantized steps, persisted, re-editable. No
Kotlin equivalent. Note that `loop.Step(step, slot, velocity, microOffset)` is
almost exactly this shape — if `:loop` lands on this branch, E may not need a new
type.

### 2d. ~~Pad-sheet prefs — `KitPad` covers most of it, misses two~~ — CLOSED, `treat`/`amt` now persist

**Struck 2026-08-29.** `PadRecipe.treatment` and `PadRecipe.amount` persist the
segment and AMT the section calls "the remaining half"; `Treatments.apply`
writes both, and `Shuffle.withRemixBank` now does too.

`KitPad` has `level`, `pan`, `tuneCoarse`/`tuneFine`, `muteGroup` (= CHOKE GRP),
`oneShot`, `source` (the Y1.3 provenance line), `recipe` — and, since the merge,
`attack`, `decay`, `cutoff`, `resonance`, `humanize` and `chain`.

**Correction, 2026-08-29:** this section originally listed **`ghosts`** (W5.3) as
missing. It is not. `KitBuilder.addGhostLayers(slot, softZones)` (`:204`) renders
the soft variants and writes them as velocity layers, `clearGhostLayers(slot)`
(`:328`) removes them, and `ChopCommand.kt:306` already calls the first. Whether a
pad has ghosts *is* `pad.velocityLayers.isNotEmpty()` — a derived fact, not a field
to store. The original finding assumed a boolean was needed and never checked
whether the capability already existed under a different name.

What is genuinely missing is **`treat` / `amt`** as persisted UI state: `recipe`
stores the resulting chain but not which segment was picked or at what amount, so
the sheet can't restore its own control positions. The name landed with the bank-B
work; the amount is the remaining half.

*(Verified: the handoff's "hats default choke ON per KIT_BEST_PRACTICES" citation
is real — `docs/KIT_BEST_PRACTICES.md:68-70`, "Closed hat and open hat share a
mute group (1-32) so triggering one chokes the other.")*

### 2e. ~~BANK B treatment tag isn't renderable from stored state~~ — CLOSED, name persisted at remix time

**Struck 2026-08-29.** `Shuffle.withRemixBank` now stores `treatment` on the
twin's `PadRecipe` instead of discarding it — the cheap, preferred option the
finding named.

W4.3 wants a *"treatment tag top-right in accent 7px Silkscreen"* on each twin.
`Shuffle.withRemixBank` throws the name away:

```kotlin
val (_, fx) = TREATMENTS[random.nextInt(TREATMENTS.size)]
…  recipe = PadRecipe(fx = fx).toJsonValue()
```

`PadRecipe` persists `patch` + `fx` only. The single lookup in the codebase runs
name → chain (`Treatments.chain`); nothing runs chain → name. So this is not
"add a field to the pad" — it's either persist the name at remix time (cheap,
preferred) or build an exact-match reverse lookup against `Shuffle.TREATMENTS`
(fragile — `amount < 1` scales the macros and breaks equality).

### 2f. ~~Schemes — the artboards are ahead of the code, and of the handoff prose~~ — CLOSED, all eight authored

**Struck 2026-08-29.** `Schemes.ALL` ships all eight (METAL, OILSLICK, PETROL,
INFRARED, ACID, SODIUM, ICE, VAPOR) with `DEFAULT = OILSLICK` — both open
questions below resolved as "ship all eight" and "artboards are authoritative."

**Decided 2026-08-28: CLEAR is cut.** Every light shell goes with it.

`Schemes.ALL` ships six: CHROME, FERRIC, METAL, SNACK_BAR, OILSLICK, CLEAR, with
`DEFAULT = CHROME`. Four of those six are retired (CHROME, FERRIC, SNACK_BAR,
CLEAR) and `DEFAULT` becomes OILSLICK.

`Schemes.dc.html` is the source of values, not the handoff prose — it carries
**eight** complete dark token sets, in exactly the `--gray/--g-hi/--g-e/--g-mid/
--g-dk/--ink/--ink2/--t1/--t2/--title-ink/--desk1/--desk2/--lcd/--lcd-ink/
--amber/--field` shape `Scheme.kt` already declares. So authoring them is a data
edit, not a schema change:

| artboard | `--t1` | `--t2` | `--amber` | in code? |
|---|---|---|---|---|
| `t-metal` | `#0a0a0c` | `#2c3038` | `#ffb000` | ✓ |
| `t-oilslick` | `#5a2ae0` | `#e040c8` | `#40e0e8` | ✓ |
| `t-petrol` | `#2a6ae0` | `#40e890` | `#40e890` | ✗ |
| `t-infrared` | `#9a2ae0` | `#e02a5a` | `#ff8a1a` | ✗ |
| `t-acid` | `#6ab010` | `#20d0e8` | `#20d0e8` | ✗ |
| `t-sodium` | `#c85a10` | `#ffb000` | `#ff6a2a` | ✗ |
| `t-ice` | `#1a5ac8` | `#58c8ff` | `#58c8ff` | ✗ |
| `t-vapor` | `#10b088` | `#40e8c0` | `#b27af8` | ✗ |

Two things to settle before authoring:

- **Eight artboards, five in the prose.** The handoff says *"5 dark/neon schemes
  only"* and names METAL, OILSLICK, PETROL, INFRARED, ACID. SODIUM, ICE and VAPOR
  are drawn to the same completeness with no status marker distinguishing them.
  Ship all eight or hold three back?
- **The prose disagrees with its own artboards** on two values. PETROL is
  described as *"gold `#F0D040`"* — no token in `t-petrol` is gold; `--amber` is
  the mint `#40e890`. ACID is described as *"chartreuse `#A0E020`"* — `t-acid`'s
  `--t1` is the darker `#6ab010`. Taking the artboards as authoritative.

`OILSLICK_SWEEP` already carries the exact 5 conic stops and documents the
API<33 linear fallback. That one's done.

### 2g. ~~Personality copy — every new line is unwritten~~ — CLOSED, all 19 lines shipped

**Struck 2026-08-29.** Every line named below — plus the two found in the
undercount note — is in `Personality.kt`'s `Copy` object, including
`TREATED`, which this wave converted from a hardcoded example into the
`treated(segment, pad)` function its siblings already were.

All 13 lines under *"Shipped copy adds"* are absent from `Personality.kt`, as are
4 of the 5 PAD SHEET toasts — 17 missing of the 18 checked, including `"BANK B LIT. YOUR KIT, BUT
EVIL. RECIPES KEPT."`, `"FORKED TO PROG E. A–D STAY UNTOUCHED."`, `"MELODIC. THE
PADS BECOME A SCALE, LOW LEFT."`, `"GHOST LAYERS ON…"`, `"NO CONFIDENT PITCH…"`.

The older shipped list is in good shape — 10 of 11 present. The one absentee is
`"SNACK BAR. SHIPPED OUT OF RESPECT."`, which is consistent with SNACK_BAR being
on the retirement list anyway.

> **Undercount, found 2026-08-29 while implementing.** This sweep only read the
> handoff's *"Shipped copy adds"* list and its PAD SHEET toasts. Two more shipped
> lines live inside screen-table rows and were missed: `"EVERY SAVE ARCHIVES A
> TAKE. EVERY DELETE GOES TO THE BIN FIRST."` (TAKES + BIN) and `"FEATURES ONLY,
> NEVER AUDIO. NOTHING LEAVES THE PHONE."` (X4.4 teach consent). Nineteen, not
> seventeen. Both added.
>
> Lesson for the next sweep: the handoff carries copy in three places — the
> Personality section's two lists, and inline in the screen table's row prose.

---

## 3. Questions for the designer — all answered

> **Note, 2026-08-29: all three questions below are closed.** See 3.1, 3.2, 3.3.

*(CLEAR: resolved 2026-08-28 — cut. See §2f.)*

1. ~~**Rock Salt or Permanent Marker?**~~ — **CLOSED 2026-08-29.** `Type.MARKER
   = "Rock Salt"`; `SchemesTest`'s `the handwriting is Rock Salt` pins it. The
   Type table's the branch's code follows now; the LOOP block-cell spec is the
   stale one.

   The Type table says Rock Salt 11–15px
   *"(was Permanent Marker; tweakable)"*, but the LOOP block-cell spec still says
   *"Permanent Marker 11px"*. `Type.MARKER` is a single constant and can't be
   both.

2. ~~**FRESH TAPE roster — 6 vs 6, only 3 overlap.**~~ — **CLOSED 2026-08-29.**
   `StarterKits.kt:84` wires `"velocity", "VELOCITY"` — the missing entry this
   question flagged.

   | handoff | `StarterKits.ALL` |
   |---|---|
   | Chip · Cloud · Melodic | CHIP · CLOUD · MELODIC ✓ |
   | Thump | `FACTORY` (`ThumpKits.classic()`) — rename? |
   | Shuffle | `LUCKY DIP` (`Shuffle.kit`) — rename? |
   | **Velocity** | **no entry** (`synth/Velocity.kt` exists, no builder wired) |
   | — | `LUCKY DIP A/B` — ships in code, absent from the design |

   The design also specs REROLL SEED for all six; only `LUCKY DIP` and
   `LUCKY DIP A/B` are `seeded = true`, so REROLL is a no-op on the other four.

3. ~~**`.XPJ` label.**~~ — **CLOSED 2026-08-29.** `ExportFormats.kt:24` now
   labels `xpj` `"MPC SESSION (.XPJ) — KITS + GROOVES"` (Task 9), and the CLI's
   own format note in `cli/Exports.kt` was brought into agreement in this wave.

   Y3.3 adds `MPC SESSION (.XPJ) — KITS + GROOVES` to the FORMAT
   cycler. `ExportFormats` already has `xpj` but labels it `MPC 3 PROJECT (.XPJ)`.
   Copy drift, and the design's label promises grooves ride along.
