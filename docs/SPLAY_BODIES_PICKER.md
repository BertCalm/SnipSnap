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
- **The keys, kept in round `splay-bodies-1`.** MAKE INSTRUMENT renders roots from C2 to C6 at touch
  levels .24, .52 and .8. Before this round the owner had heard each body from C3 to C5 at .7, and at C4
  at .3, .6 and 1.
  - Round `splay-bodies-1` (B1–B5) played each body's real key samples: C2, A2, F♯3, D♯4, C5, A5 and C6
    at full touch, then C2, C4 and C6 at the softest touch.
  - **KEEP on all five (14 Nov 2026), no notes.** That includes WIRE's top keys (A5, C6), which reach the
    engine's .94 level ceiling; the owner kept them as heard.
  - `SplayBodyKeysTest` pins those 50 key samples (ten per body) and rebuilds the five desk clips byte
    for byte:

    | Clip | PCM16 WAV SHA-256 |
    | --- | --- |
    | B1 WIRE | `3be596bbd93629e37d2d0a5a0a0897bb797712c176984581f2f379a0ec7a7c31` |
    | B2 RUB | `de0dee472c8f9c96326cac94b5620384a0b12b76bac6e8aa838d78e62939b6c6` |
    | B3 AIR | `b3e26306c797091586e39dfb6b96b3d302cbf11fd31866337503b4b6ba61e01e` |
    | B4 BAR | `643b104416e6b9db84738360d4cd3f3083e1369641a53ba353550dc6f3d76eab` |
    | B5 SKIN | `a6c441abc4ee3d0a83ea9fa83c99e000c6343d99f82a7e397e7acf0f46e3b009` |

  - The middle touch level (.52) and the in-between roots were not on the desk. They are the same
    engine at a note and touch between ones the owner kept.

## Still open

- **HOLD on the phone:** RUB and AIR hold in the engine, with kept loops S12 and S16, but the patch and
  the picker stay finite. Held keys are separate work.
- **Velocity in the keys:** still three gain-only levels, as for TONGUE. The engine's touch-dependent
  timbre is only heard at each level's own velocity.
- **Round robin and chord tools.**
- **Device:** the picker and MAKE INSTRUMENT are tested on the emulator in CI; no phone or MPC has played
  a body.
