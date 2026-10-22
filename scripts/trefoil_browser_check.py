import argparse, json, pathlib, time
parser=argparse.ArgumentParser()
parser.add_argument('--url',default='http://127.0.0.1:8766/')
parser.add_argument('--output',default='/tmp/trefoil-browser-check')
args=parser.parse_args()
base=args.url.rstrip('/')
from playwright.sync_api import sync_playwright
root=pathlib.Path(args.output);root.mkdir(parents=True,exist_ok=True)
errors=[]; failures=[]
with sync_playwright() as p:
 browser=p.chromium.launch(headless=True,executable_path='/usr/bin/chromium',args=['--no-sandbox'])
 page=browser.new_page(viewport={'width':1440,'height':1000},device_scale_factor=1,accept_downloads=True)
 page.on('pageerror',lambda e:errors.append(str(e)))
 page.on('response',lambda r:failures.append([r.status,r.url]) if r.status>=400 else None)
 page.goto(base+'/',wait_until='networkidle')
 page.wait_for_selector('.clip');assert page.locator('.clip').count()==5
 assert page.evaluate('document.documentElement.scrollWidth <= innerWidth')
 page.screenshot(path=str(root/'desktop.png'),full_page=True)
 page.get_by_role('button',name='Listen to the weave').click()
 page.wait_for_function('document.getElementById("audio").currentTime > .15')
 assert page.locator('audio').count()==1
 page.get_by_role('button',name='Pause selected recording').click()
 at=page.locator('#audio').evaluate('(a)=>a.currentTime');page.wait_for_timeout(200)
 assert abs(page.locator('#audio').evaluate('(a)=>a.currentTime')-at)<.05
 page.locator('#seek').evaluate('(e)=>{e.value=2;e.dispatchEvent(new Event("input",{bubbles:true}))}')
 page.wait_for_function('Math.abs(document.getElementById("audio").currentTime-2)<.1')
 page.get_by_role('button',name='Raw',exact=True).click()
 page.wait_for_function('document.getElementById("audio").currentSrc.includes("ordered-raw.wav") && Math.abs(document.getElementById("audio").currentTime-2)<.1')
 page.get_by_role('button',name='Matched',exact=True).click()
 page.locator('#verdict').select_option('keep');page.locator('#note').fill('QA note: contact and changing pitch color')
 with page.expect_download() as dl:page.get_by_role('button',name='Export listening notes').click()
 payload=json.loads(pathlib.Path(dl.value.path()).read_text());assert payload['notes']['ordered']['verdict']=='keep'
 page.reload(wait_until='networkidle');assert page.locator('#note').input_value().startswith('QA note:')
 # The paired player keeps both buffers on one clock and switches only their
 # gains. Verify the actual output of the intentionally silent branch as well.
 page.get_by_role('button',name='Play comparison',exact=True).click()
 page.wait_for_function('deck.playing && !deck.loading')
 assert page.evaluate('audio.paused && deck.sources.length === 2 && deck.context.state === "running"')
 page.wait_for_timeout(250);before=page.evaluate('playbackPosition()')
 page.locator('#compare-b').click();page.wait_for_timeout(100)
 assert page.evaluate('playbackPosition()')>before+.04
 assert page.evaluate('deck.gains[0].gain.value === 0 && deck.gains[1].gain.value === 1')
 page.keyboard.press('a');assert page.locator('#compare-a').get_attribute('aria-pressed')=='true'
 page.get_by_role('button',name='Pause comparison',exact=True).click()
 at=page.evaluate('playbackPosition()');page.wait_for_timeout(150);assert abs(page.evaluate('playbackPosition()')-at)<.005
 page.locator('#seek').evaluate('(e)=>{e.value=2;e.dispatchEvent(new Event("input",{bubbles:true}))}')
 page.get_by_role('button',name='Raw',exact=True).click()
 page.wait_for_function('!deck.loading && Math.abs(playbackPosition()-2)<.01')
 page.get_by_role('button',name='Matched',exact=True).click()
 page.wait_for_function('!deck.loading && Math.abs(playbackPosition()-2)<.01')
 page.locator('#compare-focus').uncheck()
 page.get_by_role('button',name='Play comparison',exact=True).click()
 page.wait_for_timeout(100);before=page.evaluate('playbackPosition()')
 page.locator('#compare-focus').check();page.wait_for_timeout(60)
 assert before-.02<page.evaluate('playbackPosition()')<before+.3
 page.locator('#seek').evaluate('(e)=>{e.value=3.4;e.dispatchEvent(new Event("input",{bubbles:true}))}')
 page.wait_for_timeout(350);assert .4<=page.evaluate('playbackPosition()')<.9
 page.locator('#compare-pair').select_option('transfer');page.wait_for_function('!deck.loading')
 page.get_by_role('button',name='Play comparison',exact=True).click();page.wait_for_function('deck.playing')
 page.evaluate('window.deckMeter=deck.context.createAnalyser();deckMeter.fftSize=2048;deck.gains.forEach(g=>g.connect(deckMeter))')
 page.wait_for_timeout(150)
 receiver_peak=page.evaluate('(()=>{const x=new Float32Array(deckMeter.fftSize);deckMeter.getFloatTimeDomainData(x);return Math.max(...x.map(Math.abs))})()')
 assert receiver_peak>1e-5,receiver_peak
 page.locator('#compare-b').click();page.wait_for_timeout(180)
 silent_peak=page.evaluate('(()=>{const x=new Float32Array(deckMeter.fftSize);deckMeter.getFloatTimeDomainData(x);return Math.max(...x.map(Math.abs))})()')
 assert silent_peak<1e-7,silent_peak
 page.locator('#compare-pair').select_option('memory');page.wait_for_function('!deck.loading')
 page.get_by_role('button',name='Play comparison',exact=True).click();page.wait_for_function('deck.playing')
 assert page.evaluate('deck.sources.every(s=>s.buffer.duration===2.5) && playbackPosition()>=3.5')
 page.locator('#verdict').select_option('indistinct');page.locator('#note').fill('QA comparison: no clear difference')
 with page.expect_download() as dl:page.get_by_role('button',name='Export listening notes').click()
 payload=json.loads(pathlib.Path(dl.value.path()).read_text())
 assert payload['notes']['memory-0']['verdict']=='indistinct'
 assert payload['notes']['memory-0']['comparison']['pair']=='memory'
 page.keyboard.press('Escape');assert not page.evaluate('deck.playing')
 # Starting an individual recording releases both paired sources.
 page.get_by_role('button',name='Listen to the weave').click();page.wait_for_function('audio.currentTime>.1')
 assert page.evaluate('!deck.active && deck.sources.length===0')
 for section,count in [('Comparisons',8),('Stems',5),('Transfer proof',8),('Control sweeps',5),('Notes & touch',9),('Extremes',2)]:
  page.get_by_role('tab',name=section,exact=True).click();assert page.locator('.clip').count()==count,(section,page.locator('.clip').count())
 page.get_by_role('tab',name='Control sweeps',exact=True).click()
 page.get_by_role('button',name='EXCHANGE',exact=True).click();assert page.locator('.clip').count()==5
 assert 'EXCHANGE' in page.locator('.clip-title').first.text_content()
 page.get_by_role('tab',name='Transfer proof',exact=True).click()
 page.get_by_role('button',name='Off · B alone',exact=True).click()
 page.wait_for_function('document.getElementById("audio").currentSrc.includes("transfer-off-1")')
 silent=page.evaluate('''async()=>{let ctx=new AudioContext();let b=await ctx.decodeAudioData(await (await fetch('audio/transfer-off-1-matched.wav')).arrayBuffer());let max=0;for(let x of b.getChannelData(0)) max=Math.max(max,Math.abs(x));await ctx.close();return max}''');assert silent==0
 page.locator('#engineering').evaluate('(e)=>e.open=true')
 assert 'excitation' in page.locator('#metrics').inner_text().lower()
 page.get_by_role('button',name='On · B alone',exact=True).click()
 page.wait_for_function('document.getElementById("audio").currentSrc.includes("transfer-on-1")')
 decoded=page.evaluate('''async()=>{let ctx=new AudioContext();let b=await ctx.decodeAudioData(await (await fetch('audio/transfer-on-1-matched.wav')).arrayBuffer());let max=0;for(let x of b.getChannelData(0)) max=Math.max(max,Math.abs(x));await ctx.close();return {peak:max,rate:b.sampleRate,channels:b.numberOfChannels,duration:b.duration}}''');assert decoded['peak']>0
 page.get_by_role('button',name='Stop playback',exact=True).click();assert page.locator('#audio').evaluate('(a)=>a.paused && a.currentTime===0')
 mobile=browser.new_page(viewport={'width':390,'height':844},device_scale_factor=1)
 mobile.on('pageerror',lambda e:errors.append(str(e)))
 mobile.goto(base+'/',wait_until='networkidle');mobile.wait_for_selector('.clip')
 assert mobile.evaluate('document.documentElement.scrollWidth <= innerWidth')
 mobile.screenshot(path=str(root/'mobile.png'),full_page=True)
 mobile.get_by_role('button',name='Play comparison',exact=True).click();mobile.wait_for_function('deck.playing')
 mobile.locator('#compare-b').click();mobile.wait_for_timeout(100)
 assert mobile.evaluate('deck.side===1 && deck.playing && audio.paused')
 mobile.get_by_role('button',name='Hear the transfer test').click();assert mobile.locator('.clip').count()==8
 mobile.get_by_role('button',name='Play Strike A · exchange on',exact=True).click();mobile.wait_for_function('document.getElementById("audio").currentTime>.1')
 mobile.get_by_role('button',name='Stop playback',exact=True).click()
 assert not errors,errors
 assert not failures,failures
 result={'desktop':{'viewport':[1440,1000],'noOverflow':True},'mobile':{'viewport':[390,844],'noOverflow':True},'checks':['one audible player at a time','paired buffer clock continuity','A/B gain switching and keyboard shortcuts','paired pause/seek/raw/matched continuity','focus-loop boundaries and mode continuity','actual paired receiver output vs silent branch','paired sources stop before individual playback','play/pause/seek/stop','all seven collections','macro sweep selector','local notes survive reload','JSON notes download','real receiver WAV decoding','disconnected receiver exact silence','mobile playback'],'pairedOutput':{'receiverPeak':receiver_peak,'disconnectedPeak':silent_peak},'receiver':decoded,'errors':errors,'failedHttp':failures}
 (root/'browser-report.json').write_text(json.dumps(result,indent=2));print(json.dumps(result,indent=2))
 browser.close()
