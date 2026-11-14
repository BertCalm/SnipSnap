# SPLAY bodies on the phone (picker, patch, starter, keys)

The five model-3 bodies are WIRE, RUB, AIR, BAR and SKIN. The owner kept them on 14 Nov 2026 (rounds
splay-voices-1 to -3; see [SPLAY_WIRE_RUB.md](SPLAY_WIRE_RUB.md) and
[SPLAY_AIR_BAR_SKIN.md](SPLAY_AIR_BAR_SKIN.md)). This makes them SPLAY voices a player can reach.

## What a player gets

- **SYNTH picker:** SPLAY now lists six voices: TONGUE, WIRE, RUB, AIR, BAR and SKIN. Picking a body
  starts from its kept setting. That is TONGUE's 16-mechanism controls at C4, velocity .8, seed 41001,
  and it plays exactly the same-settings take the owner kept (S2, S9, S13, S17, S20). The controls are
  TONGUE's model-2 macros (TRACE, SEED GAP, ENERGY, CONTACT, POPULATION), because the bodies are struck
  by model 2's catches.
- **Presets:** each body has one factory preset, `16 mechanisms`, its kept setting. TONGUE keeps its
  five population presets.
- **Saving:** a body saves as a SPLAY patch at model 3 and reopens and replays exactly, as a user preset,
  a draft or a pad recipe. TONGUE stays on models 1 and 2. The format refuses a body at model 1 or 2,
  and TONGUE at model 3.
- **Starter:** SPLAY BODIES (`splay-bodies`) puts WIRE, RUB, AIR, BAR and SKIN on A01–A05. Each pad is
  its body's kept same-settings take, rendered from its recipe. It is shelved next to SPLAY POPULATIONS
  and appended last to the stored starter order.
- **MAKE INSTRUMENT:** a body builds the same finite keys as TONGUE: MIDI 36–84, roots every three
  semitones plus the saved root, three gain-only touch levels, no loop.
- **CLI:** `snipsnap synth SPLAY WIRE --preset 1` renders the kept setting. `--model` accepts 3 for a body
  and 1–2 for TONGUE.

## What is not new sound, and what is

- **Heard already:**
  - Every body's default and preset render the kept same-settings take. The tests pin each one's hash.
  - The starter pads are those same takes.
- **Not heard yet: the keys.** MAKE INSTRUMENT renders roots from C2 to C6 at touch levels .24, .52
  and .8. The owner heard each body from C3 to C5 at .7, and at C4 at .3, .6 and 1.
  - So the keys outside that range, and the soft and full touches across the range, are new.
  - Round `splay-bodies-1` (B1–B5) plays each body's real key samples: C2, A2, F♯3, D♯4, C5, A5 and C6
    at full touch, then C2, C4 and C6 at the softest touch.
  - This branch waits for those verdicts.

## Still open

- **HOLD on the phone:** RUB and AIR hold in the engine, with kept loops S12 and S16, but the patch and
  the picker stay finite. Held keys are separate work.
- **Velocity in the keys:** still three gain-only levels, as for TONGUE. The engine's touch-dependent
  timbre is only heard at each level's own velocity.
- **Round robin and chord tools.**
- **Device:** the picker and MAKE INSTRUMENT are tested on the emulator in CI; no phone or MPC has played
  a body.
