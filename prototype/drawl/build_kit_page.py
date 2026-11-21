"""DRAWL round 3's listening page: the DRAWL starter's sixteen pads and DRAWL TAKES' touches and takes,
as the app renders them (DrawlAuditionGenerator's kit/ and kit-takes/ folders).

    python3 prototype/drawl/build_kit_page.py KOTLIN_AUDITION_DIR OUT

Writes OUT/site/index.html and its MP3s (needs ffmpeg), in build_page.py's look. Besides each pad on
its own, the page plays short performances made by laying the app's own pad renders end to end and
overlapping, as a player hitting pads would: nothing in them is re-synthesised. Each performance is
scaled by one number, only if its overlapping notes would otherwise clip, so the levels within it stand.
"""
import html
import json
import os
import subprocess
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import build_page as bp  # noqa: E402
import measure  # noqa: E402

SR = 44100
ROWS = [[13, 14, 15, 16], [9, 10, 11, 12], [5, 6, 7, 8], [1, 2, 3, 4]]  # an MPC's pads, top row first
TOUCH = ["soft", "medium", "hard"]


def write_wav(path, x):
    """16-bit mono WAV, for ffmpeg."""
    import wave
    w = wave.open(path, "wb")
    w.setnchannels(1)
    w.setsampwidth(2)
    w.setframerate(SR)
    w.writeframes((np.clip(x, -1, 1) * 32767).astype("<i2").tobytes())
    w.close()


def lay(hits, tail=0.0):
    """hits: [(seconds, samples)]. Sum them at their start times; scale once only if the sum would clip."""
    end = max(int(t * SR) + len(x) for t, x in hits) + int(tail * SR)
    out = np.zeros(end)
    for t, x in hits:
        s = int(t * SR)
        out[s:s + len(x)] += x
    peak = np.max(np.abs(out))
    return out / peak * .98 if peak > .98 else out


def button(label, src):
    return (f'<button class="clip" type="button" data-src="{src}">'
            f'<span class="tri" aria-hidden="true">▶</span>{html.escape(label)}</button>')


def main():
    kt, out = sys.argv[1:3]
    site = os.path.join(out, "site")
    audio = os.path.join(site, "audio")
    tmp = os.path.join(out, "wav")
    os.makedirs(audio, exist_ok=True)
    os.makedirs(tmp, exist_ok=True)
    pads = json.load(open(os.path.join(kt, "kit.json")))
    names = {p["pad"]: p["name"] for p in pads}
    notes = {p["pad"]: p["note"] for p in pads}

    def pad(n):
        return measure.load(os.path.join(kt, "kit", f"{n:02d}.wav"))[0]

    def take(n, z, t):
        return measure.load(os.path.join(kt, "kit-takes", f"{n:02d}-z{z}-t{t}.wav"))[0]

    def mp3(name, x=None, src=None):
        wav = src or os.path.join(tmp, f"{name}.wav")
        if x is not None:
            write_wav(wav, x)
        bp.mp3(wav, os.path.join(audio, f"{name}.mp3"))
        return f"audio/{name}.mp3"

    # The DRAWL starter, pad by pad, laid out as on an MPC.
    grid = []
    for row in ROWS:
        cells = []
        for n in row:
            src = mp3(f"pad{n:02d}", src=os.path.join(kt, "kit", f"{n:02d}.wav"))
            cells.append(f'<div class="padcell"><span class="padno">A{n:02d}</span>{button(names[n], src)}</div>')
        grid.append(f'<div class="padrow">{"".join(cells)}</div>')
    walk = mp3("walk", lay([(i * .42, pad(n)) for i, n in enumerate([1, 2, 3, 4, 5, 6, 7, 8, 7, 6, 5, 4])]))
    strums = mp3("strums", lay([(0, pad(15)), (1.8, pad(16)), (3.6, pad(15))]))
    swell = mp3("swell", lay([(0, pad(11)), (2.2, pad(14)), (4.8, pad(13))]))
    leads = mp3("leads", lay([(i * 2.4, pad(n)) for i, n in enumerate([9, 10, 11, 12])]))

    kit_section = f"""
<section class="voice" id="kit" aria-labelledby="h-kit">
  <header class="vhead">
    <h2 id="h-kit"><span class="id">DRAWL</span> <span class="vname">The starter kit, in D major pentatonic</span></h2>
    <p class="blurb">Sixteen finite pads, laid out as on your MPC (A01 bottom left). The bottom two rows walk the resonator up the scale from D3 to F#4. The third row holds the four lead voices, and the top row two long slides and two strums: the flat-top's open D, the key's home chord, and the resonator's open A, its five chord.</p>
  </header>
  <div class="padgrid">{"".join(grid)}</div>
  <div class="clips">{button("Resonator walk (A01 to A08 and back)", walk)}{button("Strums: D, A, D", strums)}{button("Leads: A09 to A12", leads)}{button("Pedal steel, pedal slide, lap slide", swell)}</div>
  <p class="ask"><span class="eyebrow">LISTEN FOR</span> Do the resonator's notes up the scale still sound like the twang you kept at its root? Does each row earn its place, or would you swap a pad?</p>
</section>"""

    # DRAWL TAKES: each pad hit four times soft, four medium, four hard.
    cells = []
    for row in ROWS:
        row_cells = []
        for n in row:
            hits = [((z * 4 + t) * .7 + z * .6, take(n, z, t)) for z in range(3) for t in range(4)]
            src = mp3(f"takes{n:02d}", lay(hits))
            row_cells.append(f'<div class="padcell"><span class="padno">A{n:02d}</span>{button(names[n], src)}</div>')
        cells.append(f'<div class="padrow">{"".join(row_cells)}</div>')
    takes_section = f"""
<section class="voice" id="takes" aria-labelledby="h-takes">
  <header class="vhead">
    <h2 id="h-takes"><span class="id">DRAWL TAKES</span> <span class="vname">Picking strength and round robin</span></h2>
    <p class="blurb">The same sixteen pads, each with soft, medium and hard picking and four takes on each. Every button plays one pad hit twelve times: four soft, four medium, four hard, each hit the next take, as a repeated pad deals them in the app and on an MPC 3. On an MPC 2 each strength plays its first take only, the format limit you recorded on 14 Nov.</p>
  </header>
  <div class="padgrid">{"".join(cells)}</div>
  <p class="ask"><span class="eyebrow">LISTEN FOR</span> Does a harder pick sound harder, not only louder? Do the four takes sound like one player's four passes, none of them out of place?</p>
</section>"""

    page = (bp.TEMPLATE.replace("{{AB}}", kit_section).replace("{{CARDS}}", takes_section)
            .replace("ROUND 0D · PYTHON HARNESS · NOTES HEARD", "ROUND 3 · NATIVE KOTLIN · KITS NOT HEARD")
            .replace("<title>DRAWL Round 0</title>", "<title>DRAWL Round 3</title>")
            .replace("Proposed engine · western and cosmic country strings", "Engine · western and cosmic country strings"))
    start = page.index('<p>Six voices on one mechanism')
    end = page.index('</div>', start)
    page = page[:start] + (
        "<p>You kept round 2's strums and takes, and HOLD stays as it is: every DRAWL note rings out and "
        "ends, at most 8 seconds, and never loops. Round 3 puts DRAWL on the shelf. It now has a factory "
        "bank of 18 presets: each voice's default note, its long slide and its strum, all renders you have "
        "already heard. It also has two starter kits, DRAWL and DRAWL TAKES, the sounds on this page. These "
        "are the app's own renders of the kits; nobody has heard them as kits.</p>"
        "<p>The performances (the walk, the strums, the twelve repeated hits) are the pad renders laid end "
        "to end and overlapping, as your fingers would play them. Nothing in them is made again.</p>") + page[end:]
    page = page.replace("</style>", ".padgrid { display: grid; gap: 4px; }\n"
                        ".padrow { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 4px; }\n"
                        ".padcell { display: grid; gap: 2px; }\n"
                        ".padcell .clip { width: 100%; justify-content: flex-start; padding: 0 8px; font-size: 12px; }\n"
                        ".padcell .clip[aria-pressed=\"true\"] { padding: 0 7px; }\n"
                        ".padno { font: 11px var(--mono); letter-spacing: 1px; color: var(--ink2); }\n"
                        "@media (max-width: 520px) { .padrow { grid-template-columns: repeat(2, minmax(0, 1fr)); } }\n</style>")
    fs = page.index('<div class="foot">')
    fe = page.index('</div>\n</div>', fs) + len('</div>\n')
    page = page[:fs] + (
        '<div class="foot"><p><span class="eyebrow">Under the hood</span> Every pad saves, reloads, plays and exports '
        'to MPC 3 and MPC 2 in the app\'s tests, every take read back from the files. DRAWL TAKES is about nine minutes '
        'of audio, some 72 MB, smaller than ACCORD TAKES.</p></div>\n') + page[fe:]
    open(os.path.join(site, "index.html"), "w").write(page)
    print(os.path.join(site, "index.html"))


if __name__ == "__main__":
    main()
