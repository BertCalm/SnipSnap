# Follow-up fixes report

Worktree: `.claude/worktrees/oilslick-followups`, branch `claude/oilslick-followups`,
base commit `cf9d619`.

## Status: all six fixes landed

- **F1** — `.gitignore` gained `.claude/` and `.superpowers/` with a one-line
  comment (`# local agent state - worktrees and scratch, not project source`)
  above them, matching the file's terse style.
- **F2** — `SchemesTest.kt`'s `selection is visible against the chrome it
  sits on` now asserts the >40-luma separation for both `accent` and
  `winFrame` against `gray`, with a message naming which of the two failed.
  Ran alone: all 8 schemes pass both assertions.
- **F3** — `StarterKits.kt`'s KDoc now states the regenerability guarantee
  and names its one exception: VELOCITY's recipe rebuilds only the main
  hit, not the soft velocity layers that define that kit.
- **F4** — `KitBuilder.eraPad` returns the pad untouched at `amount <= 0f`
  (checked after slot validation, before `requireNotChained` and any file
  I/O). `eraKit` gets the same guard — returns 0 at `amount <= 0f`, after
  its existing `targets.isNotEmpty()` check, without ever calling `eraPad`.
  New test `eraPad at amount 0 is a no-op and leaves the bin empty` in
  `KitBuilderTest.kt`, matching that file's `KitBuilderModel`-driven idiom
  (`m.eraPad(...)`, `m.binContents()`).
- **F5** — `docs/APP_PLAN.md`: "six schemes" → "eight schemes" (line ~20);
  the stale `design/Main.dc.html` (`t-chrome`…`t-clear`) citation (line
  ~64) now points at `design/Schemes.dc.html` (`t-metal`…`t-vapor`) —
  verified that file actually defines all eight current token classes,
  unlike `Main.dc.html`. Also caught and fixed a third stale mention at
  line ~184 ("porting the six-token system" → "eight-token system"),
  same file, same class of error, not separately called out in the task
  but clearly in scope of "stale scheme references."
  `docs/SYNTH_ROADMAP.md` line ~184: "SNACK BAR scheme users" (a retired
  scheme) reworded to "the loudest scheme's users" rather than reassigned
  to a current scheme — SNACK_BAR's joke depended on it being eye-searing
  bright yellow (`--gray:#ffd400`); none of the 8 current schemes are
  light (`every scheme is dark now` is itself an asserted invariant), so
  no current scheme earns that insult. De-specifying was the accurate
  minimal fix.
- **F6** — `KitBuilder.treatPad`'s `require` message reworded to
  `"pad $slot is velocity-layered - \`clearGhostLayers($slot)\` before
  treating"`, matching the neighbouring chain-refusal's exact shape
  (`"pad $slot is a round-robin chain - \`robin --undo\` before $doing
  it"`). Considered pointing at the CLI's `era` verb instead (it does
  handle layered pads correctly, per the task's own note) but rejected
  it: `era` applies Time Machine aging, not the FX treatment the user
  asked for — pointing there would swap in a different sound, not a way
  forward to the one they wanted.

## Left alone, deliberately

- `docs/APP_PLAN.md` line ~20's `(Oilslick, Clear)` — these name real
  prototype files (`design/TapeOS Oilslick.dc.html`, `design/TapeOS
  Clear.dc.html`), cited again a few lines later in the same doc; not
  stale.
- `replaceAudio`'s parallel `require(pad.velocityLayers.isEmpty())`
  message (same file, ~line 261) — same cryptic shape as F6's, but F6
  scoped the fix to `treatPad` only, so it's untouched.
- `design/Main.dc.html` itself still carries four retired token classes
  (`t-chrome`, `t-ferric`, `t-metal`, `t-snackbar`). It's no longer cited
  by `APP_PLAN.md` after the F5 fix, but F5 scoped to the two docs, not
  the design file.
- No tape-formulation terms ("chrome bias", "ferric oxide") found in
  either doc — nothing to preserve-by-leaving-alone there.

## Gaps spotted, not fixed (out of scope)

- **No CLI verb clears velocity layers.** `addGhostLayers` is reachable
  via `chop --ghosts`; there is no inverse CLI command wrapping
  `clearGhostLayers`. Confirmed by grepping the full `Main.kt` dispatch
  table, grepping for `clearGhostLayers` and `velocityLayers =
  emptyList` across `cli/`, and scanning `cli/` filenames for
  ghost/layer/velocity commands — none exist. F6's message therefore
  names a real Kotlin API (used by tests and eventually the app), not a
  runnable CLI command. Worth wiring a CLI verb for it later so the
  message is directly actionable from `snipsnap treat`.
- **`eraPad`'s new guard sits before `requireNotChained`.** A
  round-robin-chained pad given `amount <= 0f` now returns silently
  instead of refusing with the chain message. This is correct under the
  "leaves the pad and its files untouched" spec (nothing happens either
  way), but it is a visible behavior change worth a reviewer's eyes: the
  no-op check now short-circuits the chain check.
- **`EraCommand`'s printed message is stale at `aged == 0`.** With
  `amount 0`, `eraKit` now correctly returns 0 and `EraCommand` prints
  "0 pad(s) through the <era> at 0.00 - originals in the bin, recipes
  recorded" — the trailing clause is inaccurate at count 0 (nothing
  went to the bin, no recipe was recorded). Not a failure path (no
  exception), just a cosmetic mismatch; out of F4's stated scope
  (add the guard, add a bin-emptiness test), left as-is.

## Test results

`./gradlew test`: **901 tests, 0 failures** (baseline 900 + 1 new test
from F4; F2 extended an existing test in place, adding no count).
