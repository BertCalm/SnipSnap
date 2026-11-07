"""Browser checks for the Kotlin/Python GRAIN and ANCHOR comparisons."""
import argparse
import gzip
import json
from pathlib import Path
from playwright.sync_api import sync_playwright


def main(url, output):
    checks, errors = [], []
    def check(name, condition):
        checks.append(dict(name=name, passed=bool(condition)))
        assert condition, name
    with sync_playwright() as p:
        browser = p.chromium.launch(executable_path='/usr/bin/chromium', headless=True,
                                   args=['--no-sandbox', '--disable-dev-shm-usage'])
        context = browser.new_context(viewport=dict(width=1440, height=1000))
        page = context.new_page()
        page.on('pageerror', lambda e: errors.append(str(e)))
        page.goto(url, wait_until='networkidle')
        page.locator('#clip-grid .clip-card').first.wait_for()
        response = context.request.get(url.rstrip('/') + '/data/manifest.json.gz')
        manifest = json.loads(gzip.decompress(response.body()))
        check('159 unique clips', len(manifest['clips']) == len({c['id'] for c in manifest['clips']}) == 159)
        check('Kotlin comparison opens first', page.evaluate('selectedGroup === "kotlin-port"') and page.locator('#clip-grid .clip-card').count() == 12)
        check('No autoplay', page.evaluate('audio.paused && !audio.context'))
        rows = [c for c in manifest['clips'] if c['group'] == 'kotlin-port']
        for row in rows:
            page.locator(f'[data-clip-id="{row["id"]}"] .play-button').click()
            page.wait_for_function('id => currentClip?.id === id && !audio.paused && audio.buffer?.duration > 0', arg=row['id'])
            check('Decode ' + row['id'], page.evaluate('audio.buffer.numberOfChannels === 1 && audio.buffer.getChannelData(0).some(v => Math.abs(v) > .00001)'))
            if row['loop']:
                check('Held playback loops ' + row['id'], page.evaluate('audio.source.loop'))
        page.locator('.mode-switch label').filter(has_text='Raw').click()
        page.wait_for_function('audio.src.includes("_raw.wav") && !audio.paused')
        check('Raw switches during playback', page.locator('input[value="raw"]').is_checked())
        page.locator('#stop-all').click()
        page.wait_for_function('audio.paused && !audio.source')
        check('Stop releases the player', page.evaluate('audio.paused && !audio.source'))
        for group, count in [('attack-revision', 4), ('identities', 6), ('voice-music', 6),
                             ('voice-range', 12), ('voice-touch', 12), ('voice-controls', 24),
                             ('voice-hold', 6), ('music', 7), ('pads', 16), ('all', 159)]:
            page.locator(f'[data-filter="{group}"]').click()
            check('History section ' + group, page.locator('#clip-grid .clip-card').count() == count)
        check('Recorded orbital traces survive compression', sum(bool(c.get('orbitTrace')) for c in manifest['clips']) > 0)
        for name in ['downloads/APSIS-POPULATIONS.md', 'populations/prototype-comparison.json',
                     'downloads/Apsis-Attack-Study-Source.zip', 'downloads/SnipSnap-Apsis-Kit.zip']:
            response = context.request.get(url.rstrip('/') + '/' + name)
            check('Download ' + name, response.status == 200 and len(response.body()) > 0)
        page.set_viewport_size(dict(width=375, height=812))
        page.locator('[data-filter="kotlin-port"]').click()
        check('Mobile fits', page.evaluate('document.documentElement.scrollWidth <= innerWidth + 1'))
        page.screenshot(path=str(Path(output).with_suffix('.png')), full_page=True)
        check('No JavaScript errors', not errors)
        browser.close()
    Path(output).write_text(json.dumps(dict(checks=checks, errors=errors), indent=2) + '\n')
    print(json.dumps(dict(checks=len(checks), passed=True)))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('url')
    parser.add_argument('output')
    args = parser.parse_args()
    main(args.url, args.output)
