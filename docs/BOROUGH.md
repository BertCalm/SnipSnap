# BOROUGH: fitted boom-bap kits and perc stacks (round 0)

Status: CONCEIVE plus a Python sound proof. Not heard, not integrated, not on the shelf.
Repository read: `afb0fa98` (2026-11-18).

The characteristic gesture is **heavy swept kick → papery snare crack with late wires → dark, driven recording chain → a perc stack riding on top**.

## What the owner asked for

Twenty reference loops (80 to 95 BPM, NYC boom-bap: thirteen full kits, three "tops"
loops, three perc stacks, one texture loop) turned into a drum kit collection whose kits
can be mixed and matched with percussion. Replicated, not sampled.

"Replicated" is held to this rule here: every sound starts from oscillators and freshly seeded
noise. The reference loops are reduced to numbers (`extract.py`): hit maps on the 16th grid,
microtiming, and a third-octave band-envelope descriptor per drum role averaged over every clean
occurrence. The fitter (`fit.py`) only compares those numbers. No reference PCM is stored,
mixed, resynthesised with its phases, or put on the listening page. The reference files are not
in the repository; their SHA-256 hashes are in `targets.json` so a later round can prove which
files were measured.

## What the loops measured (Python harness)

Measured by `prototype/borough/extract.py` at mono 44.1 kHz. Whole-loop statistics include every
instrument; they are design evidence, not isolated drum parameters.

- **Darkness.** On the full-kit loops 99% of the energy sits below 1.2 to 4.7 kHz; the tops and
  perc loops reach 7.5 to 18 kHz. Two loops (80 baxter, 90 lafayette) are exactly mono; the
  full kits sit 22 to 56 dB below mid in the side channel; perc stacks are wide (side 4 to 18 dB
  below mid).
- **Patterns.** Every full kit puts the snare on 16ths 4 and 12. Kick placements vary per loop
  (`targets.json` `pattern`).
- **Swing.** Median delay of the 16th offbeat against the straight grid: 20 to 45 ms on eight
  loops at 90 BPM (a 16th is 167 ms, so 56 to 64% swing), near zero on mercer, lafayette, baxter.
- **Perc stacks.** Hudson: a shaker on every 8th (peaks 5.3 to 5.8 kHz) with 400 Hz and 750 Hz
  hand-drum tones and a 950 Hz block. Simpson: tambourine 16ths (jingle peaks 2.6, 5.3, 7.0 kHz),
  an accent with 920 to 1590 Hz peaks on beat 2, an 88/117 Hz thud. Rockaway: a bell pair at
  2.52 and 3.45 kHz with partials at 7.1 and 10.3 kHz, bright sleigh-type accents on 4/8/12, a
  395 to 908 Hz multi-mode knock.

## Landing and nearest siblings

A **kit and stack collection**, delivered later as frozen synthesis like SOUL ROCK, not a new
engine. The shelf is already deep in boom bap (KNEE BREAK, CYPHER BACKROOM and DUST, CLUTTER
BOOMBAP, TIMBER BOOM BAP, CUTTINGS POCKET, HICCUP HOP, the BOOM_BAP SKIN study). What none of
them do, and what earns this collection a place:

1. **Fitted to a measured reference set.** Each kit's kick, snare and hat parameters are an
   optimiser's answer to a named reference's numbers, with the residual reported. The siblings
   are authored by ear.
2. **Mix and match as a contract.** A kit fills A01 to A12; a stack fills A13 to A16. Any of six
   kits plus any of four stacks is a complete bank (24 banks) without moving a drum. `KitMerge`
   already lands a second kit on bank B (CLI `merge`); a stack-onto-A13 combine and its phone
   picker are new work for the integration round.

## Signal flow

```
macro-free recipe (fitted numbers) + zone velocity + take seed
  kick : swept sine body (two-stage decay) + shell mode + beater click + skin noise
  snare: two head modes with strike bend + wire noise (late, own band and decay) + stick click
  hat  : six square partials + noise -> band-pass -> high-pass -> one decay
  perc : shaker grains | jingle resonators | hand-drum membrane | bell modes | block | clap | ride
    -> recording chain per hit: tanh drive (scaled by strike) -> high-shelf tilt -> 2-pole low-pass
    -> 88.2 kHz render -> polyphase 2:1 -> DC high-pass -> trim at -60 dB / release -> mono 44.1 kHz
    -> zone level 0.89 * v^0.8 (the SOUL ROCK law)
```

The Python harness renders at 2x for fitting speed; Kotlin's house stage is 4x. A frozen bank
would be re-rendered at 4x and compared before freezing.

## Velocity and round robin

Three zones (0.35, 0.65, 1.0) and three takes per pad. Velocity changes the strike itself before
the level law: the kick's pitch sweep narrows and its click darkens; the snare follows the
accepted BOOM_BAP wire law (wire level `sqrt(v)`, wire band top `14 kHz * v^0.8`, wire decay
`0.55 + 0.45 sqrt(v)`); hats shorten and darken; drive scales with the strike. Takes re-seed the
noise and move the physics a little (pitch sd 0.4%, decays sd 4%, click and wire level sd 10%).
Shape constants, not measured mechanics.

## Results

Filled in below from `fitted.json` and `verification.json`.
