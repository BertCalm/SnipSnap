"""Playwright checks for the before/after articulation audition."""
import argparse
import json
from pathlib import Path
from playwright.sync_api import sync_playwright


def main(url, output):
    checks=[];errors=[]
    def check(name, condition):
        checks.append(dict(name=name,passed=bool(condition)))
        assert condition,name
    with sync_playwright() as p:
        browser=p.chromium.launch(executable_path='/usr/bin/chromium',headless=True,args=['--no-sandbox','--disable-dev-shm-usage'])
        context=browser.new_context(viewport=dict(width=1440,height=1000))
        page=context.new_page();page.on('pageerror',lambda e:errors.append(str(e)))
        page.goto(url,wait_until='networkidle');page.locator('#clip-grid .clip-card').first.wait_for()
        manifest=context.request.get(url.rstrip('/')+'/data/manifest.json').json()
        check('147 unique clips',len(manifest['clips'])==len({e['id'] for e in manifest['clips']})==147)
        check('Before/after opens first',page.evaluate('selectedGroup==="attack-revision"') and page.locator('#clip-grid .clip-card').count()==4)
        check('No autoplay',page.evaluate('audio.paused && !audio.context'))
        rows=[e for e in manifest['clips'] if e['group']=='attack-revision']
        for row in rows:
            page.locator(f'[data-clip-id="{row["id"]}"] .play-button').click()
            page.wait_for_function('id=>currentClip?.id===id && !audio.paused && audio.buffer?.duration>0',arg=row['id'])
            check('Decode '+row['id'],page.evaluate('audio.buffer.numberOfChannels===1 && audio.buffer.getChannelData(0).some(v=>Math.abs(v)>.00001)'))
        check('Revised file has a fresh URL',page.evaluate('audio.src.includes("attack2")'))
        page.locator('.mode-switch label').filter(has_text='Raw').click()
        page.wait_for_function('audio.src.includes("_raw.wav") && !audio.paused')
        check('Raw mode',page.locator('input[value="raw"]').is_checked())
        page.locator('#stop-all').click();page.wait_for_function('audio.paused && !audio.source')
        check('Stop',page.evaluate('audio.paused && !audio.source'))
        page.locator('[data-filter="identities"]').click()
        check('Six identities retained',page.locator('#clip-grid .clip-card').count()==6)
        for voice in ('grain','anchor','tether'):
            ident=voice+'_identity_C4'
            page.locator(f'[data-clip-id="{ident}"] .play-button').click()
            page.wait_for_function('id=>currentClip?.id===id && !audio.paused && audio.buffer?.duration>0',arg=ident)
            check('Preferred reference plays '+voice,page.evaluate('!audio.src.includes("attack2")'))
        page.locator('#stop-all').click()
        for group,count in [('voice-music',6),('voice-range',12),('voice-touch',12),('voice-controls',24),('voice-hold',6),('music',7),('pads',16),('all',147)]:
            page.locator(f'[data-filter="{group}"]').click()
            check('Section '+group,page.locator('#clip-grid .clip-card').count()==count)
        page.locator('[data-filter="voice-hold"]').click()
        page.locator('[data-clip-id="vessel_held_cycle"] .play-button').click()
        page.wait_for_function('!audio.paused && audio.source?.loop && audio.src.includes("attack2")')
        check('Revised hold loops',page.evaluate('audio.source.loop'))
        page.locator('#stop-all').click()
        for name in ['downloads/Apsis-Attack-Study-Source.zip','downloads/APSIS-ATTACK-MEMORY.md','attacks/reports/attack-validation.json']:
            response=context.request.get(url.rstrip('/')+'/'+name)
            check('Download '+name,response.status==200 and len(response.body())>0)
        page.set_viewport_size(dict(width=375,height=812));page.locator('[data-filter="attack-revision"]').click()
        check('Mobile fits',page.evaluate('document.documentElement.scrollWidth<=innerWidth+1'))
        page.screenshot(path=str(Path(output).with_suffix('.png')),full_page=True)
        check('No JavaScript errors',not errors)
        browser.close()
    Path(output).write_text(json.dumps(dict(checks=checks,errors=errors),indent=2)+'\n')
    print(json.dumps(dict(checks=len(checks),passed=True)))


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('url');p.add_argument('output')
    args=p.parse_args();main(args.url,args.output)
