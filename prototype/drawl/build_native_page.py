"""DRAWL round 1's listening page: each voice as the owner heard it (Python, round 0d) beside the
native Kotlin engine, clip for clip, with compare_kotlin.py's numbers under each voice.

    python3 prototype/drawl/build_native_page.py PY_AUDITION_DIR KOTLIN_AUDITION_DIR COMPARE_JSON OUT [ROUND]

ROUND is 1 (default: the kept voices) or 2 (strums down and up, takes 2 and 3, not yet heard natively).

Writes OUT/site/index.html and its MP3s (needs ffmpeg), reusing build_page.py's look and A/B
section. Only DRAWL renders go on the page.
"""
import json
import os
import shutil
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import build_page as bp  # noqa: E402

VOICES = [("cone", "CONE · resonator"), ("bottle", "BOTTLE · electric slide"), ("lap", "LAP · lap steel"),
          ("steel", "STEEL · pedal steel"), ("flattop", "FLATTOP · country flat-top"), ("gut", "GUT · western nylon")]
CLIPS = [("Default", "voices", "mid"), ("Soft pick", "velocity", "soft"), ("Hard pick", "velocity", "hard"),
         ("Long slide, wide vibrato", "pairs", "slide1-waver1"), ("Another take", "takes", "take1")]
CLIPS_2 = [("Strum down", "strum", "down"), ("Strum up", "strum", "up"),
           ("Take 3", "takes", "take2"), ("Take 4", "takes", "take3")]
PROBES = [("Brightness (centroid)", "centroid_hz", "Hz"), ("Decay", "decay_db_per_s", "dB/s"),
          ("Wide vibrato depth", "vibrato_pp_wide", "cents p-p"), ("Wide vibrato rate", "vibrato_hz_wide", "Hz"),
          ("Hard over soft", "hard_over_soft_db", "dB"), ("Take spread", "take_spread_db", "dB")]


def main():
    py, kt, compare, out = sys.argv[1:5]
    second = len(sys.argv) > 5 and sys.argv[5] == "2"
    clips = CLIPS_2 if second else CLIPS
    cmp = json.load(open(compare))
    ab = os.path.join(out, "ab")
    for side in ("before", "after"):
        os.makedirs(os.path.join(ab, side), exist_ok=True)
    for v, _ in VOICES:
        for _, folder, tag in clips:
            shutil.copy(os.path.join(py, folder, f"{v}-{tag}.wav"), os.path.join(ab, "before", f"{v}-{tag}.wav"))
            shutil.copy(os.path.join(kt, folder, f"{v}-{tag}.wav"), os.path.join(ab, "after", f"{v}-{tag}.wav"))
    json.dump({v.upper(): cmp[v]["python_heard"] for v, _ in VOICES}, open(os.path.join(ab, "before.json"), "w"))
    json.dump({v.upper(): cmp[v]["kotlin"] for v, _ in VOICES}, open(os.path.join(ab, "after.json"), "w"))
    manifest = {
        "id": "2" if second else "1",
        "title": "Strums and takes, native beside the prototype" if second else "The native engine beside what you kept",
        "intro": ("Each pair plays the Python prototype you kept, then DRAWL's native Kotlin engine, the one the app "
                  "will play. They never match sample for sample: the pick and scrape noise come from SnipSnap's own "
                  "generator, so every strike is a new one, as a different take would be. Listen for whether each voice "
                  "is still the voice you kept."),
        "groups": [{"title": title, "what": "", "rows": [[label, f"{v}-{tag}"] for label, _, tag in clips],
                    "probes": [] if second else [[n, v.upper(), None, k, u] for n, k, u in PROBES]} for v, title in VOICES],
    }
    json.dump(manifest, open(os.path.join(ab, "manifest.json"), "w"))
    site = os.path.join(out, "site")
    os.makedirs(os.path.join(site, "audio"), exist_ok=True)
    section = bp.ab_section(ab, site)
    page = (bp.TEMPLATE.replace("{{AB}}", section).replace("{{CARDS}}", "")
            .replace("ROUND 0D · PYTHON HARNESS · NOTES HEARD", f"ROUND {'2' if second else '1'} · NATIVE KOTLIN · NOT HEARD")
            .replace("<title>DRAWL Round 0</title>", f"<title>DRAWL Round {'2' if second else '1'}</title>"))
    start = page.index('<p>Six voices on one mechanism')
    end = page.index('</div>', start)
    intro2 = ("<p>You kept the native voices. Round 2 adds STRUM (a chord picked string by string: a bar slides the "
              "whole chord, the pedal steel raises its inner notes, fretted chords are not slid) and deals the takes "
              "through ROBIN. These are the parts you have not heard from the native engine: each voice's strum down and "
              "up, and its third and fourth takes, beside the prototype's.</p>"
              "<p>The 30 renders you kept in round 1 are now pinned: a test fails if a single sample of them changes.</p>")
    page = page[:start] + (intro2 if second else
        "<p>You kept all six voices of the Python prototype. This is round 1: the same design built into SnipSnap's "
        "synth engine in Kotlin, rendered by the code the app runs. Nobody has heard these native renders yet.</p>"
        "<p>Under each voice, the numbers are the same measurements taken on both. The two sit within the spread "
        "between one take and the next on every voice; the electric slide's whine band averages 1.4 dB lower across "
        "its four takes, the take you heard having been the brightest of them.</p>") + page[end:]
    fs = page.index('<div class="foot">')
    fe = page.index('</div>\n</div>', fs) + len('</div>\n')
    page = page[:fs] + (
        '<div class="foot"><p><span class="eyebrow">Under the hood</span> The native engine keeps the string read you '
        'heard rather than the house loop, which moved the pedal steel further than its own takes differ. A default '
        'note renders in about 70 ms on a desktop.</p></div>\n') + page[fe:]
    open(os.path.join(site, "index.html"), "w").write(page)
    print(os.path.join(site, "index.html"))


if __name__ == "__main__":
    main()
