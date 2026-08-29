# Design gap — `design/HANDOFF.md` vs the code

Written against the OILSLICK handoff synced on 2026-08-28 (Design Canvas export,
`design-sync/`), read against branch `claude/mobile-mpc-drum-sampler-t58x74`.

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

### 1a. `Scheme.amber` is one field doing two jobs — GROOVE's needle renders cyan

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

### 1b. Six load-bearing tokens have no field at all

`Scheme` predates the new table. Missing: **`raised`** (button/empty-pad
gradient), **`win`** (window body), **`win-frame`** (the 3px OILSLICK gradient
that *replaces* the old bevel highlight/shadow pair), **`desk-glow`**, **`ink3`**
(third text tier), **`accent`** (`#E040C8`, selection + row inset bar).

The `win-frame` one is structural: `Scheme` still carries `grayHi`/`grayEdge`/
`grayMid`/`grayDark`, which exist to build bevels. OILSLICK doesn't have bevels.

### 1c. `Treatments.chain()` rejects the pad sheet's own default range

```kotlin
require(amount > 0f && amount <= 1f) { "amount is (0, 1], got $amount" }
```

The PAD SHEET specs *"AMT 0-100 in 5s"* — so AMT=0 is reachable by one stepper
tap and throws. Separately, the segmented control is `NONE / CRUSH / TAPE / DIRT`,
but `Shuffle.TREATMENTS` is `reversed / crushed / slapback / washed / punched`:

- `NONE` is not a treatment name — it must be UI-level absence that never calls
  `chain()` (otherwise `IllegalArgumentException: unknown treatment 'NONE'`).
- `CRUSH` → `crushed` is the only confident mapping. `TAPE` and `DIRT` match no
  entry; `FxChain` has a `tape` macro but no `tape` treatment. **Needs a
  designer/code decision, not a guess.**

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

### 2b. TAKES + BIN — shipped copy is writing a check the code can't cash

`Personality.kt:77` already ships `DELETE_SNIP = "EJECTED. THE BIN KEEPS IT 30
DAYS."` There is no bin. Nothing in `audio cli json kit mpc3 shell synth xpm`
matches a retention model, a deleted-item store, or a day countdown. The nearest
things are `TreatCommand --undo` (a one-deep per-pad undo that *calls itself* a
bin in its help text) and `KitBackup` (whole-kit zip export/restore).

X2.3 needs, and none of it exists: a takes archive written on every save
(`T4…T2` + `NOW`), a bin with per-item `reason` + `27D LEFT` countdown, RESTORE,
BACK, and EMPTY THE BIN NOW.

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

### 2d. Pad-sheet prefs — `KitPad` covers most of it, misses two

`KitPad` has `level`, `pan`, `tuneCoarse`/`tuneFine`, `muteGroup` (= CHOKE GRP),
`oneShot`, `source` (the Y1.3 provenance line), `recipe`. Missing: **`ghosts`**
(W5.3) and **`treat` / `amt`** as persisted UI state — `recipe` stores the
resulting `FxChain` but not which segment was picked or at what amount, so the
sheet can't restore its own control positions.

*(Verified: the handoff's "hats default choke ON per KIT_BEST_PRACTICES" citation
is real — `docs/KIT_BEST_PRACTICES.md:68-70`, "Closed hat and open hat share a
mute group (1-32) so triggering one chokes the other.")*

### 2e. BANK B treatment tag isn't renderable from stored state

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

### 2f. Schemes — the artboards are ahead of the code, and of the handoff prose

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

### 2g. Personality copy — every new line is unwritten

All 13 lines under *"Shipped copy adds"* are absent from `Personality.kt`, as are
4 of the 5 PAD SHEET toasts — 17 missing of the 18 checked, including `"BANK B LIT. YOUR KIT, BUT
EVIL. RECIPES KEPT."`, `"FORKED TO PROG E. A–D STAY UNTOUCHED."`, `"MELODIC. THE
PADS BECOME A SCALE, LOW LEFT."`, `"GHOST LAYERS ON…"`, `"NO CONFIDENT PITCH…"`.

The older shipped list is in good shape — 10 of 11 present. The one absentee is
`"SNACK BAR. SHIPPED OUT OF RESPECT."`, which is consistent with SNACK_BAR being
on the retirement list anyway.

---

## 3. Questions for the designer — still open

*(CLEAR: resolved 2026-08-28 — cut. See §2f.)*

1. **Rock Salt or Permanent Marker?** The Type table says Rock Salt 11–15px
   *"(was Permanent Marker; tweakable)"*, but the LOOP block-cell spec still says
   *"Permanent Marker 11px"*. `Type.MARKER` is a single constant and can't be
   both.

2. **FRESH TAPE roster — 6 vs 6, only 3 overlap.**

   | handoff | `StarterKits.ALL` |
   |---|---|
   | Chip · Cloud · Melodic | CHIP · CLOUD · MELODIC ✓ |
   | Thump | `FACTORY` (`ThumpKits.classic()`) — rename? |
   | Shuffle | `LUCKY DIP` (`Shuffle.kit`) — rename? |
   | **Velocity** | **no entry** (`synth/Velocity.kt` exists, no builder wired) |
   | — | `LUCKY DIP A/B` — ships in code, absent from the design |

   The design also specs REROLL SEED for all six; only `LUCKY DIP` and
   `LUCKY DIP A/B` are `seeded = true`, so REROLL is a no-op on the other four.

3. **`.XPJ` label.** Y3.3 adds `MPC SESSION (.XPJ) — KITS + GROOVES` to the FORMAT
   cycler. `ExportFormats` already has `xpj` but labels it `MPC 3 PROJECT (.XPJ)`.
   Copy drift, and the design's label promises grooves ride along.
