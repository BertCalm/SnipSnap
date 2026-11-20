"""Build the STAGGER round-1 listening page from the generator's manifest.

Run:  python3 scripts/stagger_page/build_page.py testkit/stagger-round1 OUT_DIR [mp3] [before-folder]
Writes OUT_DIR/index.html, which references the WAVs by their paths under the generator's folder; publish the
page with those files beside it. No audio is converted or committed.
"""
from __future__ import annotations

import html
import json
import os
import sys


PAGE = '''<title>{TITLE}</title>
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
<h1>{H1}</h1>
<p class="sub">{SUB}</p>
{SECTIONS}
<div class="ask"><h2>What to answer</h2><ol>{ASK}</ol></div>
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
'''
SUB1 = 'CARDBOARD at {BPM} BPM: the fourteen hits, the box kick at three cutoffs, the glass source at three pitches, and the groove under nine clock laws. Every clip is a Kotlin render (kit version {VERSION}), nothing is sampled, and nothing has been heard before you. One player: a new clip stops the last.'
ASK1 = '\n<li>The kick: which cutoff, and does the muffled kick still land on your phone?</li>\n<li>The clock: which groove is the pocket, and is the drag too much, too little, or a scatter rather than a lean?</li>\n<li>The snare: a crack, or still too bright or too dull?</li>\n<li>Any pad that is wrong for this kit, and whether glass earns a pad on the cinematic kit.</li>\n'


def render(m, title, h1, sub, ask, body):
    return (PAGE.replace("{TITLE}", title).replace("{H1}", h1).replace("{SUB}", sub).replace("{ASK}", ask)
            .replace("{BPM}", str(m["bpm"])).replace("{VERSION}", str(m["version"])).replace("{SECTIONS}", "\n".join(body)))


def sections_html(sections):
    return ['<section><h2>%s</h2><p class="lead">%s</p><div class="grid">%s</div></section>' % (html.escape(t), html.escape(l), "".join(i)) for t, l, i in sections]


def build_1b(m, files, clip, out):
    """Round 1b: the owner heard round 1 as 'too much like drum machine sounds'. Every drum beside its EMBER
    replacement, the kick at three cutoffs, slices of the kit in its room, the pads, the groove."""
    pads = sorted(p for p in files if p.startswith("pads/") and not p.endswith("-dry.wav"))

    def pad_name(p):
        return os.path.basename(p)[3:-4].replace("-", " ").title()

    drums = []
    for p in sorted(f for f in files if f.startswith("compare/") and f.endswith("-round1.wav")):
        slug = os.path.basename(p)[:-len("-round1.wav")]
        drums.append('<div class="pad"><h3>%s</h3><div class="row">%s%s</div></div>' % (
            html.escape(slug.replace("-", " ").title()), clip(p, "Round 1 (synth drums)"), clip("compare/%s-ember.wav" % slug, "Round 1b (acoustic models)")))
    sections = [
        ("Each drum, then and now", "Round 1's drums came from THUMP and SKIN, the synth drum engines: a swept sine is a drum machine by construction. "
         "Round 1b sources every drum from EMBER, the calfskin and fitted-cymbal kit you approved by ear, through its club response and warm chain. Same muffle, same foley print, same clock. Neither clip has the kit print.", drums),
        ("The box kick", "EMBER's kick open, then muffled at three cutoffs; the last clip is round 1's synth kick at 300 Hz for the contrast.",
         [clip("kick/ember-open.wav", "EMBER open"), clip("kick/ember-lp400.wav", "EMBER 400 Hz"), clip("kick/ember-lp300.wav", "EMBER 300 Hz (the kit's)"),
          clip("kick/ember-lp250.wav", "EMBER 250 Hz"), clip("kick/thump-lp300.wav", "Round 1's kick, 300 Hz")]),
        ("Slices of the kit in its room", "The other way to not sound like a machine: the whole kit played in its room and cut at a hit, so the pad carries its neighbours' bleed, as a chopped record does. "
         "A kick slice, a snare slice and a hat slice from the groove, each with four takes.",
         [clip("slices/kick-slice.wav", "Kick slice"), clip("slices/kick-slice-takes.wav", "Kick slice, four takes"), clip("slices/snare-slice.wav", "Snare slice"),
          clip("slices/snare-slice-takes.wav", "Snare slice, four takes"), clip("slices/hat-slice.wav", "Hat slice"), clip("slices/hat-slice-takes.wav", "Hat slice, four takes")]),
    ]
    rows = []
    for p in pads[:14]:
        base = p[:-4]
        rows.append('<div class="pad"><h3>%s</h3><div class="row">%s%s%s%s</div></div>' % (
            html.escape(pad_name(p)), clip(p, "Printed"), clip(base + "-dry.wav", "No kit print"),
            clip("strips/" + os.path.basename(base) + "-takes.wav", "Four takes"), clip("strips/" + os.path.basename(base) + "-velocity.wav", "Soft, medium, hard")))
    sections.append(("The kit as it ships", "Every pad through the kit's print, without it, its four takes and three zones.", rows))
    sections.append(("The phrases and the groove", "The timpani roll into the gong; the groove under the kit's clock, straight, and under CYPHER's independent scatter.",
                     [clip("pads/15-timpani-roll.wav", "Timpani roll"), clip("pads/16-cardboard-groove.wav", "Cardboard groove (the kit's clock)"),
                      clip("groove/cardboard-straight.wav", "Groove, straight"), clip("groove/cardboard-humanised-10ms.wav", "Groove, humanised 10 ms")]))
    page = render(m, "Stagger Round One B", "Stagger, round one b",
                  "You heard round 1 as too much like drum machine sounds. Round 1b keeps the kit and swaps every drum's source for an acoustic model, and shows a second way: slices of the kit played in its room. CARDBOARD at {BPM} BPM, kit version {VERSION}. Nothing is sampled; nothing has been heard before you. One player: a new clip stops the last.",
                  "<li>Do the EMBER drums stop sounding like a machine, or is it the slices that do?</li><li>The kick: which cutoff, and does it land on your phone?</li><li>Any drum still wrong?</li>",
                  sections_html(sections))
    os.makedirs(out, exist_ok=True)
    with open(os.path.join(out, "index.html"), "w") as f:
        f.write(page)
    print("wrote", os.path.join(out, "index.html"), len(files), "clips")
    return 0


ROUNDS = {
    2: {"title": "Stagger Round Two", "short": "Stagger, round two", "intro": "", "ask": "", "beside": ""},
    4: {"title": "Stagger Round Two C", "short": "Stagger, round two c",
        "intro": "You asked for a bit of compression, then heard round 2b as echoy: its press lifted the room baked into every hit. This round's press is lighter and faster, catches the peak, and leaves the room where it was; the grooves keep only their bus. ",
        "ask": "<li>Is the echo gone, and is what is left still a bit of compression or none at all?</li>",
        "beside": "Round 2 had no press on the pads; round 2b's press lifted the room (echoy); this round's press catches the peak and leaves the room where it was, and the grooves take no second press."},
    7: {"title": "Stagger Round Three B", "short": "Stagger, round three b",
        "intro": "You heard round 3 as flat, muffled and muddy. Measured, the snare was the main cause: its head rang around 300 Hz for a quarter second, so most of every backbeat sat in the muddy band, and its crack was filtered away three times over (the snare, the converter, a dark shelf on the grooves). Round 3's kick knock added boom on top. This round damps and tunes up the snare and gives it its wires, opens its filter, shortens the kick's knock, takes the dark shelf off the grooves and brings the hats up a little. ",
        "ask": "<li>Is the mud gone, does the snare crack now, and is anything too bright or too thin?</li>",
        "beside": "Round 2 had the room and no press; round 2d dry; round 3 boxed kicks (heard as muddy); this round opens the snare and drops the dark shelf."},
    6: {"title": "Stagger Round Three", "short": "Stagger, round three",
        "intro": "You heard round 2d as light taps, the drums not hit hard enough. Measured, two things: the kicks were subs, 98 percent of their energy under 150 Hz, which a phone cannot play, and in every groove the snare sat 8 to 14 dB under the kick, with the hat as loud as the snare. This round gives every kick a box (a damped tom under the sub, nothing above 500 Hz, no click), brings the snares level with the kicks in the grooves, and drops the hats to a whisper. ",
        "ask": "<li>Do the drums hit now, on the speaker you listened on last time, and does the kick read as a box rather than a tap?</li>",
        "beside": "Round 2 had the room and no press; round 2c the room and a light press; round 2d dry; this round boxes the kicks, brings them level with the snares and drops the hats."},
    5: {"title": "Stagger Round Two D", "short": "Stagger, round two d",
        "intro": "You heard round 2c as room reverb. It was: every EMBER drum carries the club it was built in, and the grooves played those hits through a second room. This round dries every drum (the club at its nearest, then a gate on what is left) and takes the room off the grooves; only the timpani keep their hall. ",
        "ask": "<li>Is the room gone, and are the dry drums still drums or back to a drum machine?</li>",
        "beside": "Round 2 had the room and no press; round 2c the room and a light press; this round is dry, with the light press kept on the hits."},
}


def build_2(m, src, out, ext="wav", before=None):
    """Round 2: the three kits as they ship, read back from their MPC 3 exports by the shelf generator. [ext] is the
    clips' extension beside the page: the WAVs as the generator wrote them, or MP3 copies for a lighter page.
    [before] names a folder of the previous round's clips beside the page (round 2b: the owner asked for a bit of
    compression, and the page plays each kit's kick, snare and groove before and after)."""
    # The round's wording is keyed on the kit version the manifest reports, so a rebuilt page says which round it is.
    round_ = ROUNDS.get(m["kitVersion"], ROUNDS[2])
    def clip(path, label, seconds):
        path = path[:-3] + ext
        return ('<button class="clip" type="button" data-src="%s" aria-pressed="false"><span class="play" aria-hidden="true"></span>'
                '<span class="label">%s</span><span class="secs">%.2f s</span></button>' % (html.escape(path), html.escape(label), seconds))
    sections = []
    if before:
        # [before] is "label=folder,label=folder": the earlier rounds' clips beside the page, each labelled.
        earlier = [(part.split("=")[0], part.split("=")[1]) for part in before.split(",")]
        rows = []
        for kit in m["kits"]:
            items = []
            for slot, what in [(1, kit["pads"][0]["name"]), (2, kit["pads"][1]["name"]), (16, "groove")]:
                pad = kit["pads"][slot - 1]
                for label, folder in earlier:
                    items.append(clip("%s/%s/pad-%02d.wav" % (folder, kit["id"], slot), "%s, %s" % (what, label), pad["seconds"]))
                items.append(clip("%s/pad-%02d.wav" % (kit["id"], slot), "%s, this round" % what, pad["seconds"]))
            rows.append('<div class="pad"><h3>%s</h3><div class="row">%s</div></div>' % (html.escape(kit["title"]), "".join(items)))
        sections.append(("The earlier rounds beside this one", "Each kit's kick, backbeat drum and groove from the earlier rounds, then this round. " + round_["beside"], rows))
    for kit in m["kits"]:
        rows = []
        for pad in kit["pads"]:
            n = "%02d" % pad["slot"]
            items = [clip("%s/pad-%s.wav" % (kit["id"], n), "Printed", pad["seconds"]), clip("%s/dry-%s.wav" % (kit["id"], n), "No kit print", pad["seconds"])]
            if pad["slot"] < 16:
                items.append(clip("%s/touch-%s.wav" % (kit["id"], n), "Soft, medium, hard; four takes each", 12 * (pad["seconds"] + .125)))
            rows.append('<div class="pad"><h3>A%s %s</h3><div class="row">%s</div></div>' % (n, html.escape(pad["name"]), "".join(items)))
        sections.append(("%s, %d BPM" % (kit["title"], kit["bpm"]),
                         {"stagger-cardboard": "The profile itself: the muffled box kick, the crack snare, a hat at a whisper, the foley where a kit keeps its toms and cymbals. A15 the timpani roll into the gong, A16 the groove.",
                          "stagger-matinee": "The cinematic kit: a stage kick and snare on a bigger room, then ten effects through the converter. A15 the timpani riser into the crash gong, A16 a groove with a glass break and a zap pickup.",
                          "stagger-stumble": "The drunkest clock: a humming muffled kick, the rimshot in the snare's place, no hats in the groove, the tape worn. A15 a dragging snare roll, A16 the groove."}[kit["id"]],
                         rows))
    page = render({"bpm": "", "version": m["kitVersion"]}, round_["title"], round_["short"],
                  (round_["intro"] if before else "") +
                  "Three kits on the shelf, every clip read back from the MPC 3 export through the app's own pad resolver. Kit version {VERSION}; the drums are EMBER's, as you chose in round 1b. Nothing has been heard before you. One player: a new clip stops the last.",
                  (round_["ask"] if before else "") + "<li>Are the three kits three kits, each worth its row?</li><li>Any pad wrong for its kit, and does the glass earn its two pads on MATINEE?</li><li>The clocks: CARDBOARD's, MATINEE's and STUMBLE's widest; is any one too drunk or too straight?</li>",
                  sections_html(sections))
    os.makedirs(out, exist_ok=True)
    with open(os.path.join(out, "index.html"), "w") as f:
        f.write(page)
    print("wrote", os.path.join(out, "index.html"), sum(len(k["pads"]) for k in m["kits"]), "pads")
    return 0


def main(argv):
    src = argv[1]
    out = argv[2] if len(argv) > 2 else src
    m = json.load(open(os.path.join(src, "manifest.json")))
    # Round 2 onward: the shelf generator's kits (its manifest's "round" names the round; the wording follows kitVersion).
    if m.get("round") in ("2", "3"):
        return build_2(m, src, out, ext=argv[3] if len(argv) > 3 else "wav", before=argv[4] if len(argv) > 4 else None)
    files = {f["file"]: f for f in m["files"]}

    def clip(path, label):
        f = files[path]
        return ('<button class="clip" type="button" data-src="%s" aria-pressed="false"><span class="play" aria-hidden="true"></span>'
                '<span class="label">%s</span><span class="secs">%.2f s</span></button>' % (html.escape(path), html.escape(label), f["seconds"]))

    pads = sorted(p for p in files if p.startswith("pads/") and not p.endswith("-dry.wav"))

    def pad_name(p):
        return os.path.basename(p)[3:-4].replace("-", " ").title()

    if m.get("round") == "1b":
        return build_1b(m, files, clip, out)
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

    body = sections_html(sections)
    page = render(m, "Stagger Round One", "Stagger, round one", SUB1, ASK1, body)
    os.makedirs(out, exist_ok=True)
    with open(os.path.join(out, "index.html"), "w") as f:
        f.write(page)
    print("wrote", os.path.join(out, "index.html"), len(files), "clips")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
