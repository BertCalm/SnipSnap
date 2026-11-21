"""DRAWL round 4's listening page: each voice's MAKE INSTRUMENT keys, played by the app's own sample
player from the saved package (DrawlKeysAuditionGenerator's keys/ folder and keys.txt).

    python3 prototype/drawl/build_keys_page.py KEYS_AUDITION_DIR OUT

Writes OUT/site/index.html and its MP3s (needs ffmpeg), in build_page.py's look.
"""
import html
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import build_page as bp  # noqa: E402

VOICES = [("cone", "CONE · resonator"), ("bottle", "BOTTLE · electric slide"), ("lap", "LAP · lap steel"),
          ("steel", "STEEL · pedal steel"), ("flattop", "FLATTOP · country flat-top"), ("gut", "GUT · western nylon")]
CLIPS = [("Key walk", "walk"), ("Soft, medium, hard", "touch"), ("One key, four times", "takes"), ("Chords: I IV V I", "chords")]


def button(label, src):
    return (f'<button class="clip" type="button" data-src="{src}">'
            f'<span class="tri" aria-hidden="true">▶</span>{html.escape(label)}</button>')


def main():
    src, out = sys.argv[1:3]
    site = os.path.join(out, "site")
    audio = os.path.join(site, "audio")
    os.makedirs(audio, exist_ok=True)
    facts = {}
    lines = open(os.path.join(src, "keys.txt")).read().splitlines()
    for i, line in enumerate(lines):
        if not line.startswith(" "):
            name, rest = line.split(": ", 1)
            facts[name] = (rest, lines[i + 1].strip() if i + 1 < len(lines) else "")
    cards = []
    for v, title in VOICES:
        buttons = []
        for label, tag in CLIPS:
            fn = f"{v}-{tag}.mp3"
            bp.mp3(os.path.join(src, "keys", f"{v}-{tag}.wav"), os.path.join(audio, fn))
            buttons.append(button(label, f"audio/{fn}"))
        size, chords = facts[v.upper()]
        cards.append(f"""
<section class="voice" id="{v}" aria-labelledby="h-{v}">
  <header class="vhead">
    <h2 id="h-{v}"><span class="id">{html.escape(title.split(' · ')[0])}</span> <span class="vname">{html.escape(title.split(' · ')[1])}</span></h2>
    <p class="blurb">{html.escape(size)}. {html.escape(chords)}.</p>
  </header>
  <div class="clips">{"".join(buttons)}</div>
</section>""")
    page = (bp.TEMPLATE.replace("{{AB}}", "").replace("{{CARDS}}", "".join(cards))
            .replace("ROUND 0D · PYTHON HARNESS · NOTES HEARD", "ROUND 4 · NATIVE KOTLIN · KEYS NOT HEARD")
            .replace("<title>DRAWL Round 0</title>", "<title>DRAWL Round 4</title>")
            .replace("Proposed engine · western and cosmic country strings", "Engine · western and cosmic country strings"))
    start = page.index('<p>Six voices on one mechanism')
    end = page.index('</div>', start)
    page = page[:start] + (
        "<p>You kept both kits. Round 4 makes DRAWL playable on a keyboard. MAKE INSTRUMENT turns any single-note "
        "DRAWL sound into keys: the voice's own two octaves, a sampled note every three keys, soft, medium and "
        "hard picking, and two takes on each. Every note rings out and ends; letting go of a key fades it.</p>"
        "<p>Everything here was played the way the app plays it: the keys were saved as the instrument package "
        "the app shelves and exports to the MPC, read back from disk, and played by the app's own sample player. "
        "The walk includes the keys between sampled notes, which play their neighbour a semitone up or down. The "
        "chords are the chord tool's, moved into each voice's range. Nobody has heard these keys yet.</p>") + page[end:]
    fs = page.index('<div class="foot">')
    fe = page.index('</div>\n</div>', fs) + len('</div>\n')
    page = page[:fs] + (
        '<div class="foot"><p><span class="eyebrow">Under the hood</span> In the tests every one of the chord tool\'s '
        '960 voicings plays on real DRAWL keys, every voice\'s keys survive the phone\'s reload and MPC 3 and MPC 2 '
        'read-back, and DRAWL is on the SYNTH picker with TOUCH, TAKE and STRUM. The picker itself is checked by '
        'the app build and its emulator tests, not on a phone yet.</p></div>\n') + page[fe:]
    open(os.path.join(site, "index.html"), "w").write(page)
    print(os.path.join(site, "index.html"))


if __name__ == "__main__":
    main()
