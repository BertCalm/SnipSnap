# Reference kits — closing out the unverified bits

**Status: no longer blocking.** The XPM structure was recovered from a program
saved by MPC standalone firmware 2.9.1.2 (see
[`../docs/XPM_STRUCTURE.md`](../docs/XPM_STRUCTURE.md)) and the `:xpm` module
writes it today.

But that structure is second-hand. A program exported off *your* hardware turns
every open question in
[Unverified](../docs/XPM_STRUCTURE.md#unverified) — instrument numbering base,
gap handling, version header, element naming — from a debate into a diff.

## The procedure (~5 minutes, per device)

1. On the MPC, start a new project and add a **Drum** program.
2. Assign a sample to **all 16 pads of bank A**. Any samples — factory content
   is fine. All 16 matters: a partially-filled program won't show how empty vs
   filled pads are encoded.
3. Give a few pads distinct **colours** and **names**, and nudge a couple of
   **tune** / **level** / **pan** values off default. This makes it obvious
   which XML field maps to which parameter — and pad colour is the one thing
   the recovered template shows nothing about, since every value in its
   `ProgramPads` blob is zero.
4. Save the program: `Save As` → name it `SnipSnapRef` → save to SD or USB.
5. Copy the resulting `.xpm` off the card.

**MPC One first** — it's the acceptance target, and a program that loads there
loads on all three. A Live III export is the second most useful, since it's the
only one on the 3.x firmware line.

## Where to put them

```
reference/golden/
├── one-2x/
│   └── SnipSnapRef.xpm
├── liveii-2x/
│   └── SnipSnapRef.xpm
└── liveiii-36/
    └── SnipSnapRef.xpm
```

Name the folder `<device>-<firmware>` so we can tell later which file came from
where — that matters when firmware changes the format under us.

**`.xpm` files only.** Don't commit the WAVs; they're large, they're likely
Akai factory content, and the writer doesn't need them. A `.gitignore` in
`golden/` enforces this.

## Also useful, if easy

A second export of the *same* kit with one single parameter changed (say, pad
A05's tune) — diffing two near-identical files is the fastest way to locate a
field with certainty.

## The other half: does our output load?

Separately from harvesting references, the writer's output needs to survive
contact with hardware:

1. `gradle :xpm:test` — proves no accidental drift
2. Generate a 16-pad kit with real WAVs beside it
3. Load it on the MPC One
4. Confirm all 16 pads fire, on the right pads, at the right pitch, with the
   hat mute group choking

If the kit comes up **shifted by exactly one pad**, that's the instrument
numbering base — flip `XpmWriter(instrumentBaseIndex = 1)` and it's solved.
