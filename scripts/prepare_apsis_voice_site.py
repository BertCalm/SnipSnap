"""Add the verified Python voice study to the existing Apsis listening Site."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
from zipfile import ZipFile, ZIP_DEFLATED


def main(study, site):
    study, site = Path(study), Path(site)
    repo = Path(__file__).resolve().parents[1]
    dist = site / 'dist'
    new = json.loads((study / 'manifest.json').read_text())
    assert len(new['clips']) == 66
    assert (study / 'reports/readback-and-controls.json').is_file()
    assert new['report']['studySourceSha256'] == hashlib.sha256((repo / 'scripts/apsis_voice_study.py').read_bytes()).hexdigest()
    old = json.loads((dist / 'data/manifest.json').read_text())
    legacy = [c for c in old['clips'] if c.get('prototype') != 'Python/Numba voice study 1']
    assert len(legacy) == 77
    # Keep an independent pre-edit ledger of every original clip and referenced asset.
    before = dict(clips=legacy, hashes={})
    for c in legacy:
        for key in ('rawFile', 'matchedFile', 'recipeFile', 'scoreFile', 'patchFile'):
            name = c.get(key)
            if name and (dist / name).is_file():
                before['hashes'][name] = hashlib.sha256((dist / name).read_bytes()).hexdigest()
    (study / 'reports/preserved-passage.json').write_text(json.dumps(before, indent=2) + '\n')
    shutil.copytree(study, dist / 'voices', dirs_exist_ok=True)
    old.update(title='Apsis / Voice identities', clips=new['clips'] + legacy,
               clipCount=143, voiceStudy=new['report'],
               voiceStudyScope='Python/Numba candidates, not new Android voices. Study PASSAGE is an approximation; the existing Kotlin PASSAGE bank is preserved separately.')
    (dist / 'data/manifest.json').write_text(json.dumps(old, separators=(',', ':')) + '\n')
    shutil.copy2(repo / 'docs/APSIS-VOICE-STUDY.md', dist / 'downloads/APSIS-VOICE-STUDY.md')
    source_names = ['scripts/apsis_voice_study.py', 'scripts/test_apsis_voice_study.py',
                    'scripts/verify_apsis_voice_study.py', 'scripts/prepare_apsis_voice_site.py',
                    'scripts/test_apsis_voice_site.py',
                    'docs/APSIS-VOICE-STUDY.md', '.claude/skills/luthier/scripts/snipkit.py',
                    '.claude/skills/luthier/scripts/probes.py']
    with ZipFile(dist / 'downloads/Apsis-Voice-Study-Source.zip', 'w', ZIP_DEFLATED) as z:
        for name in source_names:
            z.write(repo / name, name)
        z.write(study / 'reports/validation.json', 'reports/validation.json')
        z.write(study / 'reports/readback-and-controls.json', 'reports/readback-and-controls.json')
        z.writestr('requirements.txt', 'numpy\nscipy\nnumba\n')
        z.writestr('README.txt', 'Apsis voice identity study. Python/Numba prototypes, not Kotlin voices or app-compatible patch recipes. See docs/APSIS-VOICE-STUDY.md for reproduction and limits.\n')
    groups = [
        ('identities', 'SAME NOTE', 'Six identities. One orbit.', 'All six candidates use C4, native velocity .65 and the same macros. PASSAGE is a Python study reference; its existing Kotlin bank remains below.'),
        ('voice-music', 'SAME PHRASE', 'Give each voice the same phrase.', 'C3, G3 and E-flat3 arrive 1.8 seconds apart with the same native touch pattern. Complete approaches and tails remain.'),
        ('voice-range', 'RANGE', 'From C2 to C6.', 'Check which identities keep their character across the keyboard. Each register take is independently matched for listening.'),
        ('voice-touch', 'TOUCH', 'A soft and a firm encounter.', 'Native velocity .30 and 1.00. One frozen listening gain per voice preserves the change in strength and color.'),
        ('voice-controls', 'MOVEMENT & EXCHANGE', 'Change the encounter and the response.', 'STRETCH and COUPLE at .15 and .85. Each comparison family keeps a shared gain; these are changes inside the prototype network.'),
        ('voice-hold', 'HELD VOICES', 'Let the identity return.', 'Six settled cycles repeat by default. These are driven prototype loops with measured preceding-cycle recurrence, not new native MPC programs.'),
    ]
    html = (dist / 'index.html').read_text()
    html = re.sub(r'<meta name="description" content="[^"]*">', '<meta name="description" content="Compare six Apsis voice identities on the same notes and phrase. Explore touch, register and held returns alongside the existing PASSAGE MPC bank.">', html)
    if 'data-filter="identities"' not in html:
        buttons = '\n'.join(f'<button type="button" data-filter="{key}" aria-pressed="{str(i == 0).lower()}"><span>{i+1:02d}</span> {label.title()}</button>' for i, (key, label, _, _) in enumerate(groups))
        html = html.replace('data-filter="music" aria-pressed="true"', 'data-filter="music" aria-pressed="false"')
        html = html.replace('<nav class="group-filters" aria-label="Listening sections" id="group-filters">', '<nav class="group-filters" aria-label="Listening sections" id="group-filters">\n' + buttons)
        for i, key in enumerate(('music', 'pads', 'playing', 'coupling', 'start', 'voicings', 'motion', 'hold'), 7):
            html = re.sub(r'(data-filter="' + key + r'"[^>]*><span>)\d+(</span>)', lambda m: m[1] + f'{i:02d}' + m[2], html)
    html = html.replace('MUSIC · NATIVE BANK', 'VOICE IDENTITIES · STUDY 04')
    html = html.replace('Hear the orbit in a musical phrase, then play the native pad bank. Close passages, ringing replies and held returns.', 'One orbit, six responses. Compare plucked tethers, ringing vessels, airy coronas, granular contacts and pressure-driven bass with a PASSAGE study reference.')
    html = html.replace('Hear the musical examples', 'Compare the voice identities')
    html = html.replace('Download 16-pad MPC bank', 'Existing PASSAGE MPC bank')
    html = html.replace('<p class="scope-note">Musical studies <span>·</span> Native pad previews <span>·</span> Dry synthesis</p>', '<p class="scope-note">Python prototypes <span>·</span> Five new candidates <span>·</span> Android voices pending</p>')
    html = html.replace('STUDY<br><strong>03</strong>', 'STUDY<br><strong>04</strong>')
    html = html.replace('Put the orbit in a phrase.', 'Choose the identities worth developing.')
    html = html.replace('Start with the musical examples, then audition the exported pads or compare touch and resonant exchange. The earlier voicing studies remain available. Hardware listening acceptance is still pending.', 'Start with Same note, then Same phrase. These are Python/Numba voice prototypes, not additional Android voices. Their PASSAGE reference uses the same study harness. The existing Kotlin PASSAGE examples and native bank are retained in the later sections.')
    html = html.replace('01 / MUSICAL EXAMPLES', '01 / SAME NOTE').replace('Hear it in a phrase.', 'Six identities. One orbit.')
    html = html.replace('Musical examples explore how the instrument answers notes, space and touch. Each card describes its score and listening gain.', groups[0][3])
    html = html.replace('A small system.<br>A changing encounter.', 'An encounter.<br>A different response.')
    html = html.replace('The musical examples and native bank use the current performance model. The original and Round 2 studies remain in Voicing history and the comparison sections. The instrument uses synthesis without an external effects chain; listening and hardware acceptance remain open.', 'The new study changes excitation, modal losses and resonant response inside one shared orbital model. The existing musical examples and native bank still use Kotlin PASSAGE. All study audio is mono and dry. Candidate names, musical usefulness and the production port await listening.')
    html = html.replace('Prototype source ↓', 'Existing PASSAGE source ↓')
    if 'Apsis-Voice-Study-Source.zip' not in html:
        html = html.replace('<nav class="footer-evidence" aria-label="Source and audition evidence">', '<nav class="footer-evidence" aria-label="Source and audition evidence"><a href="downloads/Apsis-Voice-Study-Source.zip" download>Voice study source ↓</a><a href="downloads/APSIS-VOICE-STUDY.md" download>Voice design & limits ↓</a><a href="voices/reports/validation.json" download>Voice measurements ↓</a>')
        html = html.replace('  </main>', '''    <section class="about-study wrap" aria-labelledby="why-built-title">
      <p class="eyebrow">THE DESIGN CHOICE</p><div><h2 id="why-built-title">Why it is built this way</h2>
      <p>A common orbit and the same notes make the voice differences easier to judge. Each candidate changes how a contact begins, what resonates, and how its energy falls away.</p>
      <p>A separate root anchor keeps the note clear while the upper body changes. One diagnostic identity per voice leaves the preset decisions until after listening.</p>
      <p><a href="downloads/Apsis-Voice-Study-Source.zip" download>Download the reproducible study source ↓</a></p></div>
      <div class="listening-prompts"><p>Decisions for this round</p><ol><li>Does each candidate earn its own voice?</li><li>Which register suits it best?</li><li>Does soft touch change its character usefully?</li><li>Which two should become production voices next?</li></ol></div>
    </section>\n  </main>''')
    (dist / 'index.html').write_text(html)
    js = (dist / 'app.js').read_text()
    if '"identities":' not in js:
        declarations = '\n'.join(f'  {json.dumps(key)}: ' + json.dumps(dict(number=f'{i+1:02d}', name=label, title=title, description=desc)) + ',' for i, (key, label, title, desc) in enumerate(groups))
        js = js.replace('const GROUPS = {', 'const GROUPS = {\n' + declarations)
        # Double-quoted keys above are also the idempotency marker on later runs.
    for i, key in enumerate(('music', 'pads', 'playing', 'coupling', 'start', 'voicings', 'motion', 'hold'), 7):
        js = re.sub(r'(\n\s+' + key + r":\s*\{\s*number:\s*)'[^']*'", lambda m: m[1] + f"'{i:02d}'", js)
    js = js.replace("let selectedGroup = 'music';", "let selectedGroup = 'identities';")
    js = js.replace("renderGroup(clips.some(clip => clip.normalizedGroup === 'music') ? 'music' :", "renderGroup(clips.some(clip => clip.normalizedGroup === 'identities') ? 'identities' : clips.some(clip => clip.normalizedGroup === 'music') ? 'music' :")
    if "'STUDY TYPE'" not in js:
        js = js.replace('  const measurements = [', "  const measurements = [\n    ['STUDY TYPE', typeof clip.prototype === 'string' ? clip.prototype : undefined],")
        js = js.replace("  if (clip.loop) meta.append", "  if (typeof clip.prototype === 'string') meta.append(element('span', 'clip-badge', 'PROTOTYPE'));\n  if (clip.loop) meta.append")
        js = js.replace("element('a', '', 'Recipe ↓')", "element('a', '', typeof clip.prototype === 'string' ? 'Study settings ↓' : 'Recipe ↓')")
    (dist / 'app.js').write_text(js)
    # All original metadata and referenced assets must survive the focused edit.
    after = json.loads((dist / 'data/manifest.json').read_text())
    by_id = {c['id']: c for c in after['clips']}
    assert all(by_id[c['id']] == c for c in legacy)
    assert all(hashlib.sha256((dist / n).read_bytes()).hexdigest() == h for n, h in before['hashes'].items())
    print(json.dumps(dict(clips=len(after['clips']), newClips=len(new['clips']), preservedClips=len(legacy), preservedAssets=len(before['hashes']))))


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('study')
    p.add_argument('site')
    args = p.parse_args()
    main(args.study, args.site)
