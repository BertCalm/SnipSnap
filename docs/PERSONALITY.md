# Personality — the delight system

SnipSnap should feel like a machine with opinions: kitschy, easter-eggy,
proud of itself. This document keeps that from decaying into random jokes.

## The four laws

1. **Plausible in 1996.** Every gag must be something a shareware tape deck
   could have shipped: defrag screens, screensavers, pencil rewinds, cracktro
   credits. This is the filter that keeps quirk coherent instead of random.
2. **Delight lives in the chrome, never in the signal path.** UI sounds are
   hard-muted while a capture session is armed, nothing ever renders into an
   export, and play mode never animates at the cost of trigger latency. A
   sampler that pollutes audio to be cute is a broken sampler.
3. **Jokes never gate function.** Funny copy still says exactly what happened
   and what to do. Never fake an error, never move the user's cheese as a
   joke, never interrupt.
4. **One visible gag per screen; the rest are hidden.** Discovered delight
   beats displayed delight. Eggs reward poking at the machine.

A **PERSONALITY slider in Tape Properties — OFF / MILD / FULL** (itself a
Win95 gag, but real) controls quips, deck sounds and the screensaver. FULL is
the default. OFF is respected everywhere without argument.

## Voice

A 90s shareware program crossed with a mixtape-obsessed friend. Confident,
terse, a little smug, never cutesy-apologetic.

| Moment | Copy |
|---|---|
| Boot | `SNIPSNAP.EXE — READY.` |
| Empty shelf | `NOTHING TAPED YET. GO STEAL A SOUND (LEGALLY).` |
| Empty kit | `16 EMPTY PADS. TERRIFYING.` |
| Capture blocked | message box: `TAPE JAM — SPOTIFY BLOCKS THE TAPE. USE THE SCREEN RECORDER, I'LL PULL THE AUDIO OUT.` + [FINE] |
| Landing trouble | message box: the toast's line as title (`1 KIT LANDED ON THE SHELF. 1 SKIPPED.` / `NOTHING LANDED.`), then `SKIPPED · BROKEN: NO KIT.JSON` lines in warn, `LANDED · FUNK` after + [FINE]. A clean landing keeps its toast. |
| Export done | `DUBBED. GO MAKE SOMETHING.` |
| Commit | rotates: `TAPED. NO TAKEBACKS.` · `IT'S OURS NOW.` · `CLEAN CUT. NICE EARS.` |
| Delete snip | `EJECTED. THE BIN KEEPS IT 30 DAYS.` |

## Catalog

### Ship with MVP (cheap, visible, on-brand)

- **Status-bar quips** — the third status cell rotates slowly through deck
  mutterings (`NO DOLBY. WE LIKE HISS.` · `REWIND IS FREE.` · `CHROME BIAS: ON`).
- **Commit ka-chunk** — mechanical deck-button thunk + heavy haptic on
  commit. The one sound that earns being on by default.
- **Rotating commit toasts** (above).
- **Pencil rewind** — tap the cassette: a pencil appears in the left hub and
  winds the tape back to zero, wind sound included. The single most
  cassette-native gag that exists.
- **Defrag chop screen** — auto-chop progress drawn as a defrag block grid
  filling in. Analysis genuinely has progress to show; show it in period.
- **CRT power-off** — leaving play mode collapses the screen to a white line.
- **Tape counter** — triple-tap the position readout to swap it for a
  3-digit mechanical odometer (000–999), like every real deck had.
- **Empty states and error boxes** in voice (table above).

### Post-MVP

- **Screensaver** — idle deck plays flying-cassette bounce on the LCD; any
  touch wakes. (Prototyped already.)
- **Write-protect tabs** — "lock" a snip by punching out its cassette tab;
  locked snips can't be deleted or re-trimmed until unlocked.
- **Side A / Side B** — bank A/B flip as a physical tape-flip animation.
- **Head cleaning** — after ~20 hours of use the deck asks for a head clean;
  a 5-second swab animation and it thanks you. Does nothing. Perfect.
- **Sticker sheet** — decorate kit cassettes with 90s stickers (lightning,
  skulls, `HOT MIX`); doubles as expansion artwork on export.
- **Dubbing sounds** — optional deck-transport clunks during export.
- **Mixtape merit badges** — `FIRST SNIP`, `TAPE HOARDER (100)`,
  `CRATE DIGGER (10 sources)`. Only if it can be done quietly.

### Hidden eggs

- **Konami code on the pads** (A13 A13 A05 A05 A02 A04 A02 A04 …) unlocks a
  hidden scheme: **SLIME** (toxic-green on black).
- **Hold the About-box cassette 5 s** → demoscene cracktro: rasterbars,
  sine-scroller credits, chiptune. The deck was cracked by SNiP CREW, obviously.
- **BPM 133.7** → LCD flashes `ELITE.`
- **Name a kit `TEST`** → `VERY CREATIVE.`
- **Tap the title-bar cassette 10×** → it ejects, bounces off the status bar,
  reinserts itself.

### Rejected, with reasons

- **Interrupting mascot** — Clippy energy. A character that talks *when asked*
  could live in Help; one that volunteers is how delight becomes dread.
- **Fake errors / BSOD gags** — never fake failure in a tool that writes to
  people's SD cards. Trust outranks comedy.
- **Sounds during capture** — law 2. Hard mute while armed, no exceptions.
- **Holiday scheme swaps** — never change user state as a joke.
- **Dial-up handshake on export** — funny once, 14 seconds long.

## Already live in the tape deck prototype

Pencil rewind · screensaver · commit ka-chunk + rotating toasts · status-bar
quips · triple-tap tape counter. Try tapping the cassette.
