"""Browser QA for the published-study layout; requires Playwright and Chromium."""
import argparse
import json
from pathlib import Path
from playwright.sync_api import sync_playwright


def main(url, output):
    results, errors = [], []
    def check(name, ok):
        results.append(dict(name=name, passed=bool(ok)))
        assert ok, name
    with sync_playwright() as p:
        browser = p.chromium.launch(executable_path='/usr/bin/chromium', headless=True,
                                    args=['--no-sandbox', '--disable-dev-shm-usage'])
        context = browser.new_context(viewport=dict(width=1440, height=1000))
        page = context.new_page()
        page.on('pageerror', lambda e: errors.append(str(e)))
        page.goto(url, wait_until='networkidle')
        page.locator('#clip-grid .clip-card').first.wait_for()
        manifest = context.request.get(url.rstrip('/') + '/data/manifest.json').json()
        check('143 unique clips', len(manifest['clips']) == len({c['id'] for c in manifest['clips']}) == 143)
        check('Six identities are the default', page.evaluate('selectedGroup === "identities"') and page.locator('#clip-grid .clip-card').count() == 6)
        check('No autoplay', page.evaluate('audio.paused && !audio.context'))
        check('Prototype scope is visible', 'not additional Android voices' in page.locator('.study-copy').inner_text())
        groups = {'identities': 6, 'voice-music': 6, 'voice-range': 12, 'voice-touch': 12, 'voice-controls': 24, 'voice-hold': 6,
                  'music': 7, 'pads': 16, 'all': 143}
        for group, count in groups.items():
            page.locator(f'[data-filter="{group}"]').click()
            check('Section count ' + group, page.locator('#clip-grid .clip-card').count() == count)
        page.locator('[data-filter="identities"]').click()
        rows = [c for c in manifest['clips'] if c.get('group') == 'identities']
        for row in rows:
            page.locator(f'[data-clip-id="{row["id"]}"] .play-button').click()
            page.wait_for_function('id => currentClip?.id === id && !audio.paused && audio.buffer?.duration > 0', arg=row['id'])
            check('Decode and play ' + row['voice'], page.evaluate('audio.buffer.sampleRate > 0 && audio.buffer.numberOfChannels === 1 && audio.buffer.getChannelData(0).some(v => Math.abs(v) > .00001)'))
        check('One playing card', page.locator('.play-button.is-playing').count() <= 1)
        page.locator('.mode-switch label').filter(has_text='Raw').click()
        check('Raw radio selection', page.locator('input[name="audio-mode"][value="raw"]').is_checked())
        page.wait_for_function('audio.src.includes("_raw.wav") && !audio.paused')
        check('Raw switch uses raw buffer', page.evaluate('audio.src.includes("_raw.wav")'))
        page.locator('#stop-all').click()
        page.wait_for_function('audio.paused')
        check('Stop silences playback', page.evaluate('audio.paused && !audio.source'))
        page.locator('[data-filter="voice-hold"]').click()
        page.locator('#clip-grid .play-button').first.click()
        page.wait_for_function('!audio.paused && audio.source?.loop === true')
        check('Held buffer loops', page.evaluate('audio.source.loop && repeats.get(currentClip.id)'))
        page.locator('#stop-all').click()
        for name in ('downloads/Apsis-Voice-Study-Source.zip', 'downloads/APSIS-VOICE-STUDY.md', 'voices/reports/validation.json', 'voices/reports/readback-and-controls.json'):
            response = context.request.get(url.rstrip('/') + '/' + name)
            check('Download ' + name, response.status == 200 and len(response.body()) > 0)
        page.locator('[data-filter="pads"]').click()
        check('Existing native pad map loads', page.locator('#pad-map .native-pad').count() == 16)
        page.set_viewport_size(dict(width=375, height=812))
        page.locator('[data-filter="identities"]').click()
        check('Mobile fits viewport', page.evaluate('document.documentElement.scrollWidth <= innerWidth + 1'))
        page.screenshot(path=str(Path(output).with_suffix('.png')), full_page=True)
        check('No browser errors', not errors)
        browser.close()
    Path(output).write_text(json.dumps(dict(checks=results, errors=errors), indent=2) + '\n')
    print(json.dumps(dict(checks=len(results), passed=True)))


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('url')
    p.add_argument('output')
    args = p.parse_args()
    main(args.url, args.output)
