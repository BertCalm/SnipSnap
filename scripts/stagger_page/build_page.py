"""Build the STAGGER round-1 listening page from the generator's manifest.

Run:  python3 scripts/stagger_page/build_page.py testkit/stagger-round1 OUT_DIR
Writes OUT_DIR/index.html, which references the WAVs by their paths under the generator's folder; publish the
page with those files beside it. No audio is converted or committed.
"""
from __future__ import annotations

import html
import json
import os
import sys


def main(argv):
    src = argv[1]
    out = argv[2] if len(argv) > 2 else src
    m = json.load(open(os.path.join(src, "manifest.json")))
    files = {f["file"]: f for f in m["files"]}

    def clip(path, label):
        f = files[path]
        return ('<button class="clip" type="button" data-src="%s" aria-pressed="false"><span class="play" aria-hidden="true"></span>'
                '<span class="label">%s</span><span class="secs">%.2f s</span></button>' % (html.escape(path), html.escape(label), f["seconds"]))

    pads = sorted(p for p in files if p.startswith("pads/") and not p.endswith("-dry.wav"))

    def pad_name(p):
        return os.path.basename(p)[3:-4].replace("-", " ").title()

    sections = []
    # 1. the kick
    sections.append(("The box kick", "The identity decision. The muffle clips the kick first and filters it second, so a phone still hears a thud. "
                     "Pick the cutoff: 250 Hz is the profile, 400 Hz is safer on a phone. The last clip is the filter with no clipper ahead of it, the version that goes missing on a phone.",
                     [clip("kick/box-open.wav", "Open kick (no muffle)"), clip("kick/box-lp400.wav", "Muffle 400 Hz"), clip("kick/box-lp300.wav", "Muffle 300 Hz (the kit's)"),
                      clip("kick/box-lp250.wav", "Muffle 250 Hz"), clip("kick/box-lp300-nodrive.wav", "300 Hz, no clipper")]))
    # 2. the pads
    rows = []
    for p in pads[:14]:
        base = p[:-4]
        rows.append('<div class="pad"><h3>%s</h3><div class="row">%s%s%s%s</div></div>' % (
            html.escape(pad_name(p)), clip(p, "Printed"), clip(base + "-dry.wav", "No kit print"),
            clip("strips/" + os.path.basename(base) + "-takes.wav", "Four takes"), clip("strips/" + os.path.basename(base) + "-velocity.wav", "Soft, medium, hard")))
    sections.append(("The fourteen hits", "Each pad through the kit's print, the same strike without it, its four round-robin takes, and its three velocity zones. "
                     "Timpani, gong, zap and punch carry their own converter-then-pitch print either way.", rows))
    # 3. glass
    sections.append(("Glass", "A pane breaking: a new source, not on CARDBOARD, on the cinematic kit's list. Unconverted, then through the foley print at three pitches, then four takes (four panes).",
                     [clip("glass/glass-dry.wav", "Unconverted"), clip("glass/glass-down5.wav", "5 semitones down"), clip("glass/glass-down7.wav", "7 down"),
                      clip("glass/glass-down9.wav", "9 down"), clip("glass/glass-takes.wav", "Four takes at 5 down")]))
    # 4. the clock
    laws = [("kit", "The kit's clock: kick +12 ms, snare -3, hats +8, wobble 10 ms, rho 0.4"), ("straight", "Straight: everything on the grid"),
            ("behind-only", "Biases only, no wobble"), ("narrow-5ms", "Wobble 5 ms"), ("wide-18ms", "Wobble 18 ms"), ("rho-0", "Rho 0: the wobble scatters"),
            ("rho-0.75", "Rho 0.75: the wobble breathes"), ("kick-24ms", "Kick +24 ms"), ("humanised-10ms", "CYPHER's humanised, 10 ms, no biases")]
    sections.append(("The drunk clock", "The same two bars under nine clock laws, every one printed and played in the same room. The references' backbeats sit 5 to 13 ms from the grid; "
                     "the profile says the kick drags and the snare snaps early. Which one is the pocket?",
                     [clip("groove/cardboard-%s.wav" % k, v) for k, v in laws]))
    # 5. the phrases
    sections.append(("The two phrase pads", "A15, the timpani roll into the gong; A16, the groove as the kit ships it (the kit's clock, printed twice for the loop seam).",
                     [clip("pads/15-timpani-roll.wav", "Timpani roll"), clip("pads/15-timpani-roll-dry.wav", "Roll, no kit print"), clip("pads/16-cardboard-groove.wav", "Cardboard groove"),
                      clip("pads/16-cardboard-groove-dry.wav", "Groove, no kit print")]))

    body = []
    for title, lead, items in sections:
        body.append('<section><h2>%s</h2><p class="lead">%s</p><div class="grid">%s</div></section>' % (html.escape(title), html.escape(lead), "".join(items)))

    page = '''<title>Stagger Round One</title>
<meta name="claude:viewport-fit-sides" content="cover">
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Anton&family=IBM+Plex+Sans:wght@400;600&family=IBM+Plex+Mono:wght@400;500&display=swap">
<style>
/* Layout: one column of sections, each a grid of clip buttons; a single shared player so A/B listening never overlaps. */
:root {
  --bg: #d9cdb3; --panel: #e8dfca; --ink: #1f1b16; --muted: #5d5346; --rule: #b7a98b; --accent: #1f5a46; --accent-ink: #f1ece0; --hot: #8a3b1f;
  --display: "Anton", "Impact", "Arial Narrow", sans-serif; --body: "IBM Plex Sans", "Helvetica Neue", Arial, sans-serif; --mono: "IBM Plex Mono", "SFMono-Regular", Menlo, monospace;
}
@media (prefers-color-scheme: dark) { :root:not([data-theme="light"]) { --bg: #1d1a15; --panel: #2a251d; --ink: #ece4d2; --muted: #a79b85; --rule: #4a4033; --accent: #6fbf9a; --accent-ink: #10201a; --hot: #e0875f; color-scheme: dark } }
:root[data-theme="dark"] { --bg: #1d1a15; --panel: #2a251d; --ink: #ece4d2; --muted: #a79b85; --rule: #4a4033; --accent: #6fbf9a; --accent-ink: #10201a; --hot: #e0875f; color-scheme: dark }
body { background: var(--bg); color: var(--ink); font-family: var(--body); line-height: 1.5; padding-left: max(16px, env(safe-area-inset-left, 0px)); padding-right: max(16px, env(safe-area-inset-right, 0px)); padding-block: 24px 64px; }
main { max-width: 960px; margin: 0 auto; }
h1 { font-family: var(--display); font-weight: 400; font-size: clamp(2.6rem, 8vw, 5rem); line-height: .95; letter-spacing: .01em; margin: 0 0 8px; text-wrap: balance; text-transform: uppercase; }
.sub { color: var(--muted); max-width: 65ch; margin: 0 0 32px; }
.sub code, .lead code { font-family: var(--mono); font-size: .9em; }
section { border-top: 2px solid var(--rule); padding-block: 24px 8px; }
h2 { font-family: var(--display); font-weight: 400; font-size: clamp(1.6rem, 4vw, 2.2rem); letter-spacing: .02em; text-transform: uppercase; margin: 0 0 6px; }
h3 { font-size: .8rem; letter-spacing: .08em; text-transform: uppercase; color: var(--muted); margin: 0 0 6px; font-weight: 600; }
.lead { max-width: 65ch; margin: 0 0 18px; }
.grid { display: flex; flex-wrap: wrap; gap: 10px; }
.pad { flex: 1 1 100%; min-width: 0; background: var(--panel); border-radius: 6px; padding: 12px 14px; }
.row { display: flex; flex-wrap: wrap; gap: 8px; }
.clip { display: inline-flex; align-items: center; gap: 10px; min-width: 0; background: var(--panel); color: var(--ink); border: 1px solid var(--rule); border-radius: 999px; padding: 8px 14px 8px 10px; font: inherit; cursor: pointer; text-align: left; }
.pad .clip { background: var(--bg); }
.clip:hover { border-color: var(--accent); }
.clip:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }
.clip[aria-pressed="true"] { background: var(--accent); color: var(--accent-ink); border-color: var(--accent); }
.play { width: 0; height: 0; border-left: 10px solid currentColor; border-top: 6px solid transparent; border-bottom: 6px solid transparent; margin-left: 4px; flex: none; }
.clip[aria-pressed="true"] .play { width: 10px; height: 12px; border: 0; border-left: 4px solid currentColor; border-right: 4px solid currentColor; }
.label { min-width: 0; }
.secs { font-family: var(--mono); font-size: .8rem; color: var(--muted); font-variant-numeric: tabular-nums; }
.clip[aria-pressed="true"] .secs { color: var(--accent-ink); opacity: .85; }
.ask { background: var(--panel); border-left: 4px solid var(--hot); padding: 12px 16px; margin: 28px 0 0; max-width: 65ch; }
.ask h2 { font-size: 1.2rem; margin-bottom: 8px; }
.ask ol { margin: 0; padding-left: 20px; }
@media (prefers-reduced-motion: no-preference) { .clip { transition: background .12s, border-color .12s; } }
</style>
<main>
<h1>Stagger, round one</h1>
<p class="sub">CARDBOARD at {BPM} BPM: the fourteen hits, the box kick at three cutoffs, the glass source at three pitches, and the groove under nine clock laws. Every clip is a Kotlin render (kit version {VERSION}), nothing is sampled, and nothing has been heard before you. One player: a new clip stops the last.</p>
{SECTIONS}
<div class="ask"><h2>What to answer</h2><ol>
<li>The kick: which cutoff, and does the muffled kick still land on your phone?</li>
<li>The clock: which groove is the pocket, and is the drag too much, too little, or a scatter rather than a lean?</li>
<li>The snare: a crack, or still too bright or too dull?</li>
<li>Any pad that is wrong for this kit, and whether glass earns a pad on the cinematic kit.</li>
</ol></div>
</main>
<script>
(function () {
  var audio = new Audio();
  var current = null;
  function stop() { if (current) { current.setAttribute("aria-pressed", "false"); current = null; } }
  audio.addEventListener("ended", stop);
  audio.addEventListener("error", stop);
  document.querySelectorAll(".clip").forEach(function (b) {
    b.addEventListener("click", function () {
      if (current === b) { audio.pause(); stop(); return; }
      stop();
      audio.src = b.getAttribute("data-src");
      current = b;
      b.setAttribute("aria-pressed", "true");
      audio.play().catch(stop);
    });
  });
})();
</script>
'''.replace("{BPM}", str(m["bpm"])).replace("{VERSION}", str(m["version"])).replace("{SECTIONS}", "\n".join(body))
    os.makedirs(out, exist_ok=True)
    with open(os.path.join(out, "index.html"), "w") as f:
        f.write(page)
    print("wrote", os.path.join(out, "index.html"), len(files), "clips")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
