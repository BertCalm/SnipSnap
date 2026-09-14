# MUTATE — the card, and a proposal

Five artboards asking one question: why doesn't MUTATE feel intuitive?

Live canvas:
https://claude.ai/code/artifact/68512741-2b1f-4625-9c00-dd2a815b6fdf

Every `*.dc.html` is one artboard (390 × 844, OILSLICK), same conventions as
[`../pad-sheet-v2/`](../pad-sheet-v2/README.md): open a board in a browser
with `support.js` beside it, or seed them back onto a canvas with
`canvas.json`. Tokens are lifted from `Schemes.kt:157-175` — note `ink2` is
`#8D80AE` here, not the `#7A6AA0` the older boards carry: the code lightened
it for contrast (see `ContrastTest`) and these boards follow the code.

## The finding

**MUTATE is a listening tool presented as a form.** `PadSheetScreen` has an
`audition(snip, level, shape)` and wires it to the pad's HIT button, the
auto-play after an edit lands, and the binned original — and the MUTATE card
calls none of them. Nothing on the card makes a sound, so the loop is: pick a
word you don't know, pick a tag you can't hear, commit a destructive write,
listen, undo. Every iteration costs a file write.

| Board | What it shows |
|---|---|
| `AsBuilt.dc.html` | **Today.** Nine rows of chips, one knob whose meaning changes silently between moves (AT · HZ · MIX · WET · BANDS), MUTATE. Drawn from `PadSheetScreen.MutateCard` and `MutateSheet` |
| `Main.dc.html` | **Proposal, at rest.** The pairing is the hero: both parents named, drawn and audible (`▶ HEAR MINE` / `▶ HEAR THEIRS`) before anything happens. The partner picker collapses to one line — that is where the room comes from. The move carries a result-line; the knob carries its meaning |
| `Heard.dc.html` | **Proposal, heard.** `▶ HEAR THE RESULT` renders and plays without writing; the third waveform is what you would keep. KEEP IT lights only once you have heard it, so the destructive step is last rather than first |
| `Drift.dc.html` | **Proposal, the way in.** A pad with no partner chosen: DRIFT is the whole card (the crate picks, you say how far), and hand-picking folds under one line. The bet is that most first uses should be one tap |
| `Moves.dc.html` | **The six moves** as a copy sheet — what you get, not what the DSP does, each naming its knob in the same breath. The move *names* do not change: they are in the CLI, the recipe and the lineage |

## What makes it cheap

`Mutate.apply` already has a clean seam (`Mutate.kt:136-150`): the
`when (mode)` block is a pure function of two aligned sounds and the knob —
everything before it is reading, everything after is the recipe and the
write. Extracting it as `render(base, sources, mode, …): Snip` is a pure
refactor, and it is what lets the card audition a result without touching the
model.

Not drawn, deliberately: the partner picker expanded (it is today's chip rows,
one level down), and any change to the six move names.
