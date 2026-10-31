"""Exercise actual preset recordings, native velocity pad samples and portable kit downloads."""
import argparse,json,pathlib,zipfile
from playwright.sync_api import sync_playwright
p=argparse.ArgumentParser();p.add_argument('--url',default='http://127.0.0.1:8766/playable/');p.add_argument('--output',default='/tmp/trefoil-bank-browser');a=p.parse_args()
root=pathlib.Path(a.output);root.mkdir(parents=True,exist_ok=True);errors=[];failures=[]
with sync_playwright() as w:
 browser=w.chromium.launch(headless=True,executable_path='/usr/bin/chromium',args=['--no-sandbox'])
 page=browser.new_page(viewport={'width':1440,'height':1000},accept_downloads=True)
 page.on('pageerror',lambda e:errors.append(str(e)));page.on('response',lambda r:failures.append([r.status,r.url]) if r.status>=400 else None)
 page.goto(a.url,wait_until='networkidle');page.wait_for_selector('.preset')
 assert page.locator('.preset').count()==12 and page.locator('.pad').count()==16 and page.locator('.demo').count()==2
 assert page.evaluate('document.documentElement.scrollWidth<=innerWidth')
 page.screenshot(path=str(root/'desktop.png'),full_page=True)
 page.get_by_role('button',name='Play Seed Pluck',exact=True).click();page.wait_for_function('audio.currentTime>.1')
 page.get_by_role('button',name='Pause selected recording',exact=True).click()
 page.locator('#seek').evaluate('(e)=>{e.value=.5;e.dispatchEvent(new Event("input",{bubbles:true}))}')
 page.get_by_role('button',name='Soft',exact=True).click()
 page.wait_for_function('audio.currentSrc.includes("seed-1.wav") && Math.abs(audio.currentTime-.5)<.02')
 assert page.evaluate('audio.volume<.6')
 page.locator('#verdict').select_option('keep');page.locator('#note').fill('QA: a compact playable pluck')
 with page.expect_download() as d:page.get_by_role('button',name='Export notes').click()
 note=json.loads(pathlib.Path(d.value.path()).read_text());assert note['notes']['seed']['verdict']=='keep'
 page.reload(wait_until='networkidle');page.get_by_role('button',name='Play Seed Pluck',exact=True).click()
 assert page.locator('#note').input_value().startswith('QA:')
 page.get_by_role('button',name='Play Weave Study',exact=True).click();page.wait_for_function('audio.currentTime>.1 && audio.currentSrc.includes("weave-study.wav")')
 page.get_by_role('button',name='Play Crossings',exact=True).click();page.wait_for_function('audio.currentTime>.1 && audio.currentSrc.includes("crossings.wav")')
 page.get_by_role('button',name='Soft',exact=True).click()
 page.locator('[data-pad="1"]').click();page.wait_for_function('state.voices.size>0')
 assert page.evaluate('audio.paused && state.padMode && state.cache.has(state.manifest.pads[0].zones[0].sample)')
 page.locator('[data-pad="2"]').click();page.locator('[data-pad="3"]').click()
 page.wait_for_function('state.voices.size>=2')
 page.get_by_role('button',name='Hard',exact=True).click()
 assert page.evaluate('state.voices.size>=2 && state.padMode')
 page.keyboard.press('Escape');page.wait_for_function('state.voices.size===0')
 # A pending decode must never start a voice after stop.
 page.evaluate('hit(16);stopAll()')
 page.evaluate('async()=>{await Promise.all(state.cache.values());await new Promise(r=>setTimeout(r,30))}')
 assert page.evaluate('state.voices.size===0 && audio.paused')
 page.keyboard.press('q');page.wait_for_function('state.voices.size>0')
 page.locator('[data-pad="5"]').focus();page.keyboard.press('Space');page.wait_for_function('state.voices.size===0')
 with page.expect_download() as d:page.locator('#hero-download').click()
 with zipfile.ZipFile(d.value.path()) as z:
  names=z.namelist();assert sum(x.endswith('.wav') for x in names)==48
  assert any(x.endswith('.xtd') for x in names) and any(x.endswith('.xpm') for x in names)
 mobile=browser.new_page(viewport={'width':390,'height':844});mobile.on('pageerror',lambda e:errors.append(str(e)))
 mobile.goto(a.url,wait_until='networkidle');mobile.wait_for_selector('.pad');assert mobile.evaluate('document.documentElement.scrollWidth<=innerWidth')
 mobile.screenshot(path=str(root/'mobile.png'),full_page=True)
 mobile.locator('[data-pad="1"]').click();mobile.wait_for_function('state.voices.size>0')
 mobile.get_by_role('button',name='Stop all sound').click();mobile.wait_for_function('state.voices.size===0')
 assert not errors,errors;assert not failures,failures
 result={'desktop':[1440,1000],'mobile':[390,844],'noOverflow':True,'presetCount':12,'padCount':16,'checks':['real preset playback','touch sample and level selection','touch switch retains paused position','local notes and JSON export','two musical demos','actual exported soft zone','polyphonic pads','touch changes retain sounding pads','pending decode cancellation','keyboard pads and Space/Escape stop','48-sample MPC ZIP download','mobile pad playback'],'errors':errors,'failedHttp':failures}
 (root/'browser-report.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result,indent=2));browser.close()
