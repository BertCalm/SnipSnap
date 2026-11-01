"""Real Chromium playback/interaction checks for the held audition."""
import argparse
import json
from pathlib import Path
import wave

from playwright.sync_api import sync_playwright


def main(url,output):
    output.mkdir(parents=True,exist_ok=True)
    errors=[]; failed=[]; checks=[]
    with sync_playwright() as p:
        browser=p.chromium.launch(executable_path='/usr/bin/chromium',headless=True,
             args=['--no-sandbox','--autoplay-policy=no-user-gesture-required'])
        context=browser.new_context(viewport={'width':1440,'height':1050},accept_downloads=True)
        page=context.new_page()
        page.on('pageerror',lambda error:errors.append(str(error)))
        page.on('response',lambda response:failed.append({'url':response.url,'status':response.status}) if response.status>=400 else None)
        page.goto(url); page.wait_for_function('document.querySelector("#audio").readyState>=1')
        page.screenshot(path=str(output/'desktop.png'),full_page=True)
        page.locator('#start').click()
        page.wait_for_function('!document.querySelector("#audio").paused && document.querySelector("#audio").currentTime>.15')
        checks.append('actual eight-return playback')
        page.evaluate('document.querySelector("#audio").currentTime=1.2')
        page.locator('[data-case="still"][data-kind="loop"]').click()
        page.wait_for_function('document.querySelector("#audio").src.endsWith("still-loop.wav") && !document.querySelector("#audio").paused && document.querySelector("#audio").currentTime>1.15')
        assert page.locator('#audio').evaluate('(a)=>a.currentTime')<2
        checks.append('aligned A/B playback')
        page.evaluate('document.querySelector("#audio").pause();document.querySelector("#audio").currentTime=.8')
        page.locator('#level').select_option('fixed')
        page.wait_for_function('document.querySelector("#audio").src.endsWith("still-loop-fixed.wav") && document.querySelector("#audio").readyState>=1 && document.querySelector("#audio").currentTime>.79')
        assert page.locator('#audio').evaluate('(a)=>a.paused')
        checks.append('level switch preserves paused position')
        for name in ('no-exchange','memory'):
            page.locator(f'[data-case="{name}"][data-kind="loop"]').click()
            page.wait_for_function('(name)=>document.querySelector("#audio").src.endsWith(name+"-loop-fixed.wav") && !document.querySelector("#audio").paused',arg=name)
        checks.append('exchange and memory comparisons')
        page.locator('[data-case="memory"][data-kind="release"]').click()
        page.wait_for_function('document.querySelector("#audio").src.endsWith("memory-release-fixed.wav") && !document.querySelector("#audio").paused')
        page.evaluate('document.querySelector("#audio").currentTime=6.5')
        page.wait_for_function('document.querySelector("#event").textContent.includes("stored energy is decaying")')
        checks.append('actual note-off playback and power-stop marker')
        page.locator('#stop').click()
        assert page.locator('#audio').evaluate('(a)=>a.paused && a.currentTime===0')
        for name in ('bass','bright'):
            page.locator(f'[data-case="{name}"][data-kind="returns"]').click()
            page.wait_for_function('(name)=>document.querySelector("#audio").src.endsWith(name+"-returns-fixed.wav") && !document.querySelector("#audio").paused',arg=name)
        checks.append('low and high register playback')
        page.locator('#finite').click()
        page.wait_for_function('document.querySelector("#audio").src.includes("playable/audio/weave-2.wav") && !document.querySelector("#audio").paused')
        checks.append('approved finite reference playback')
        page.locator('#start').click();page.locator('#stop').click()
        page.locator('#verdict').select_option('keep');page.locator('#note').fill('QA note: motion and release')
        page.reload();page.wait_for_function('document.querySelector("#audio").readyState>=1')
        assert page.locator('#note').input_value()=='QA note: motion and release'
        with page.expect_download() as dl: page.locator('#export').click()
        dl.value.save_as(str(output/'notes.json'))
        assert json.loads((output/'notes.json').read_text())['notes']['ordered']['verdict']=='keep'
        checks.append('persistent local notes and JSON export')
        with page.expect_download() as dl: page.locator('#download').click()
        dl.value.save_as(str(output/'download.wav'))
        with wave.open(str(output/'download.wav')) as f:
            assert (f.getnchannels(),f.getsampwidth(),f.getframerate())==(1,3,44100)
        checks.append('selected WAV download is mono PCM24 44.1k')
        page.locator('#evidence').evaluate('(d)=>d.open=true')
        for href in ('DESIGN.md','report.json','manifest.json','trefoil-hold-source.zip','trefoil-hold-listening.zip','trefoil-hold-fixed-gain.zip'):
            response=context.request.head(url+href);assert response.status==200,(href,response.status)
        checks.append('design, source, reports and listening pack links')
        mobile=browser.new_context(viewport={'width':390,'height':844}).new_page()
        mobile.goto(url);mobile.wait_for_function('document.querySelector("#audio").readyState>=1')
        assert mobile.evaluate('document.documentElement.scrollWidth <= innerWidth')
        mobile.locator('#start').click()
        mobile.wait_for_function('!document.querySelector("#audio").paused && document.querySelector("#audio").currentTime>.1')
        mobile.screenshot(path=str(output/'mobile.png'),full_page=True)
        checks.append('390px layout without overflow and actual playback')
        assert not errors,errors
        assert not failed,failed
        browser.close()
    report={'checks':checks,'count':len(checks),'errors':errors,'failed_http':failed,
            'desktop':[1440,1050],'mobile':[390,844]}
    (output/'browser-report.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report,indent=2))


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--url',default='http://127.0.0.1:8766/held/')
    p.add_argument('--output',type=Path,required=True);a=p.parse_args();main(a.url,a.output)
