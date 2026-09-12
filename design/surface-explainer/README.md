# SURFACE — explained

Two boards answering "what does the MORPH square actually do" and "what
does SET A do" — a request that came out of finding the screen genuinely
hard to follow, not a feature proposal. Live canvas:
https://claude.ai/code/artifact/222288cf-773d-431a-b8ba-d94ce9510014

Same conventions as [`../pad-sheet-v2/`](../pad-sheet-v2/README.md) and
[`../mutate-v2/`](../mutate-v2/README.md): 390×844, OILSLICK, tokens from
`Schemes.kt`. Numbers on both boards are computed, not eyeballed — the
percentages, the bar fills and the corner defaults are the real bilinear
blend (`SurfaceStore.Corner.from`) run over the pictured puck position and
the engine's own `Corner.DEFAULTS`.

| Board | What it shows |
|---|---|
| `Main.dc.html` | The MORPH pad mid-touch: dashed lines from the puck to each corner (line weight = that corner's blend weight), the four percentages, and — added by this board, not on the real screen — what those percentages actually do to the sound: the blended pitch/cutoff/resonance/drive read out as bars |
| `SetCorner.dc.html` | The two-step capture flow: find a sound in XY, tap SET A, and that sound becomes corner A's new preset — then a third panel showing the state with two of four corners captured, C and D still on the factory defaults |

Drawn from `TouchSurface.kt`, `SurfaceStore.kt` and `SurfaceScreen.kt`
(`:shell`, `:app`). Not a redesign — `SurfaceScreen.kt` isn't changed by
this; it's an aid for understanding what's already there.
