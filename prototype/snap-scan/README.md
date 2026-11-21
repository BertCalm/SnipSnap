# SNAP SCAN prototype

Python harness for [the SNAP SCAN design](../../docs/superpowers/specs/2026-11-21-snap-scan-design.md).
These are not Kotlin tests. Every number they print is labelled "Python harness" in the spec.

| File | What it does |
|---|---|
| `scan_proto.py` | The model. It ports `Snap.table` (HORIZON), `Snap.cycle` and `Snap.synthesize`, plus the proposed 16-strip read, the neighbour alignment, the scan and a bounded fingerprint. Run it alone to get the synthetic-photo report. |
| `real_photos.py` | Reads a photo the way the app does: EXIF rotation, long side shrunk to 512 px, Rec. 601 luma. It also ports `Snap.look` / `Snap.macrosFrom` and computes the proposed PORTRAIT readings. |
| `calib.py` | Library: timbre distance in third-octave bands over the first 400 ms, plus `snd` (SCAN render) and `today` (today's render). |
| `calib2.py` | Full calibration over a folder of images. It writes `calib_report.json` next to itself. |
| `listen.py` | Renders a listening set (today, SCAN, round-robin take 2, full SCAN) for four photos. |
| `distinct.py` | Round 0b: stacks every layer (L0 today to L5 colour layers) and measures how far apart different photos land, in timbre and in loudness shape, with pitch held. It writes `distinct_report.json`. |
| `listen2.py` | Round 0b listening set: today against L4, plus a round-robin take, for six photos at their own pitch. |
| `families.py` | Round 0c: the photo's own amounts, four sound-makers (BREATH, PLUCK, STRIKE, TONE) and a four-octave register. It calibrates contrast and vchange (v1b) on the same set and writes `families_report.json`. Set `SNAP_TAU` to change the blend width. |
| `listen3.py` | Round 0c listening set: two photos per family plus one same-scene pair. |
| `calib_report.json` | The owner's calibration result: 100 uploads, 99 unique, 69 camera photos, 30 screenshots. |

The owner's photos are **not** in the repository and must never be added. Only derived numbers
(percentiles, distances, counts) are kept. The scripts read GPS presence as a count and never read
the coordinates.

```sh
python3 prototype/snap-scan/scan_proto.py /tmp/out            # synthetic photos
python3 prototype/snap-scan/calib2.py /path/to/photo/folder    # rewrites calib_report.json
python3 prototype/snap-scan/listen.py /path/to/folder /tmp/out # needs the four named uploads
```

They need `numpy`, `scipy` (for the Luthier `snipkit` output stage) and `Pillow`.
