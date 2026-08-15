# SnipSnap

An Android app for grabbing sound off your phone — from other apps, from video,
from the mic — trimming it into one-shots, laying them out on a 4×4 pad grid,
and exporting a drum kit your Akai MPC can load.

> You heard it. You snipped it. It's on pad A03.

**Status:** concept / design. No app code yet.

## The loop

```
capture (rolling buffer)  →  trim  →  assign to 4×4 grid  →  export .xpm + WAVs  →  MPC
```

## Targets

| | |
|---|---|
| Platform | Android only, minSdk 29 |
| Hardware | Akai MPC One, MPC Live II, MPC Live III |
| Export format | MPC 2-era `.xpm` drum program + 44.1 kHz WAVs, as a folder |
| Not supported | `.xpn` expansion installers (desktop MPC Software only — irrelevant here) |

## Docs

- [`docs/CONCEPT.md`](docs/CONCEPT.md) — product shape, MVP cut, architecture
- [`docs/ANDROID_CAPTURE.md`](docs/ANDROID_CAPTURE.md) — how capture actually works and where it breaks
- [`docs/MPC_EXPORT.md`](docs/MPC_EXPORT.md) — XPM/folder formats and export paths
- [`reference/README.md`](reference/README.md) — **start here to unblock the exporter**

## Next step

The XPM writer cannot be built safely from documentation. It needs a real kit
exported off real hardware as a golden template. See
[`reference/README.md`](reference/README.md) for the five-minute procedure.
