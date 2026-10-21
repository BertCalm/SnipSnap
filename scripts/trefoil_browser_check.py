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
 mobile.get_by_role('button',name='Hear the transfer test').click();assert mobile.locator('.clip').count()==8
 mobile.get_by_role('button',name='Play Strike A · exchange on',exact=True).click();mobile.wait_for_function('document.getElementById("audio").currentTime>.1')
 mobile.get_by_role('button',name='Stop playback',exact=True).click()
 assert not errors,errors
 assert not failures,failures
 result={'desktop':{'viewport':[1440,1000],'noOverflow':True},'mobile':{'viewport':[390,844],'noOverflow':True},'checks':['one audio source','play/pause/seek/stop','raw/matched position continuity','all seven collections','macro sweep selector','local notes survive reload','JSON notes download','real receiver WAV decoding','disconnected receiver exact silence','mobile playback'],'receiver':decoded,'errors':errors,'failedHttp':failures}
 (root/'browser-report.json').write_text(json.dumps(result,indent=2));print(json.dumps(result,indent=2))
 browser.close()
