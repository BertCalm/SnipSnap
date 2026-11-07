"""Exercise the native trial's actual audio graph, matched comparisons and MPC ZIP."""
import argparse
import gzip
import io
import json
from pathlib import Path
import struct
import wave
import zipfile
from playwright.sync_api import sync_playwright


def main(url,output):
    output.mkdir(parents=True,exist_ok=True);checks=[];errors=[];failed=[]
    with sync_playwright() as p:
        browser=p.chromium.launch(executable_path='/usr/bin/chromium',headless=True,
            args=['--no-sandbox','--autoplay-policy=no-user-gesture-required'])
        context=browser.new_context(viewport={'width':1440,'height':1000},accept_downloads=True)
        page=context.new_page();page.on('pageerror',lambda e:errors.append(str(e)))
        page.on('response',lambda r:failed.append([r.status,r.url]) if r.status>=400 else None)
        page.goto(url);page.wait_for_function('window.trefoilNativePlayer?.readyState===1')
        page.locator('#start').click();page.wait_for_function('!window.trefoilNativePlayer.paused && window.trefoilNativePlayer.currentTime>.1')
        checks.append('actual Kotlin loop playback')
        page.locator('#seek').evaluate('(e)=>{e.value="1.1";e.dispatchEvent(new Event("input",{bubbles:true}))}')
        page.locator('[data-id="reference-loop"]').click()
        page.wait_for_function('document.querySelector("#title").textContent.startsWith("Python") && !window.trefoilNativePlayer.paused')
        assert 1.1<page.evaluate('window.trefoilNativePlayer.currentTime')<2
        rms_reference=page.evaluate('(()=>{const d=window.trefoilNativePlayer._buffer.getChannelData(0);return Math.sqrt(d.reduce((a,b)=>a+b*b,0)/d.length)})()')
        page.locator('[data-id="native-loop"]').click()
        page.wait_for_function('document.querySelector("#title").textContent.startsWith("Kotlin") && !window.trefoilNativePlayer.paused')
        rms_native=page.evaluate('(()=>{const d=window.trefoilNativePlayer._buffer.getChannelData(0);return Math.sqrt(d.reduce((a,b)=>a+b*b,0)/d.length)})()')
        assert abs(rms_native-rms_reference)<1e-6
        checks.append('A/B preserves position and matches actual prepared-buffer RMS')
        page.locator('[data-id="native-release"]').click()
        page.wait_for_function('window.trefoilNativePlayer.src.endsWith("kotlin-release.wav.gz") && !window.trefoilNativePlayer.paused')
        with page.expect_download() as dl:page.locator('#wav').click()
        dl.value.save_as(str(output/'native-release.wav'))
        assert (output/'native-release.wav').read_bytes()==gzip.decompress(context.request.get(url+'kotlin-release.wav.gz').body())
        checks.append('packed native note-off downloads the exact original WAV bytes')
        page.evaluate('window.trefoilNativePlayer.currentTime=6.2')
        page.wait_for_function('document.querySelector("#event").textContent.includes("Power has stopped")')
        page.locator('[data-id="reference-release"]').click()
        page.wait_for_function('window.trefoilNativePlayer.src.endsWith("ordered-release.wav") && !window.trefoilNativePlayer.paused')
        assert page.evaluate('window.trefoilNativePlayer.currentTime')>6.2
        checks.append('natural note-off A/B and power-stop marker')
        page.locator('[data-id="sampler"]').click()
        page.wait_for_function('window.trefoilNativePlayer.src.endsWith("sampler-demo.wav") && !window.trefoilNativePlayer.paused')
        page.evaluate('window.trefoilNativePlayer.currentTime=2.8')
        page.wait_for_function('document.querySelector("#event").textContent.includes("Repeat passed")')
        page.evaluate('window.trefoilNativePlayer.currentTime=5.5')
        page.wait_for_function('document.querySelector("#event").textContent.includes("Key lifted")')
        checks.append('actual exported-sample demo crosses a repeat then lifts the key')
        page.locator('#note').fill('MPC trial: repeat and key lift checked');page.locator('#verdict').select_option('keep')
        page.reload();page.wait_for_function('window.trefoilNativePlayer?.readyState===1')
        page.locator('[data-id="sampler"]').click();page.wait_for_function('document.querySelector("#note-title").textContent==="The MPC trial"')
        assert page.locator('#note').input_value()=='MPC trial: repeat and key lift checked'
        with page.expect_download() as dl:page.locator('#export').click()
        dl.value.save_as(str(output/'notes.json'))
        assert json.loads((output/'notes.json').read_text())['notes']['sampler']['verdict']=='keep'
        checks.append('per-section note persistence and export')
        with page.expect_download() as dl:page.locator('a[href="Trefoil-Held-MPC.zip"]').click()
        dl.value.save_as(str(output/'mpc.zip'))
        with zipfile.ZipFile(output/'mpc.zip') as z:
            assert z.testzip() is None
            descriptor=next(n for n in z.namelist() if n.endswith('.instrument.json'))
            data=json.loads(z.read(descriptor));prefix=descriptor.rsplit('/',1)[0]+'/'
            assert [zone['root'] for zone in data['zones']]==[48,60,72]
            for zone in data['zones']:
                raw=z.read(prefix+zone['sample'])
                with wave.open(io.BytesIO(raw)) as w:assert (w.getnchannels(),w.getsampwidth(),w.getframerate(),w.getnframes())==(1,3,44100,zone['frames'])
                at=12;smpl=None
                while at+8<=len(raw):
                    tag=raw[at:at+4];size=struct.unpack_from('<I',raw,at+4)[0]
                    if tag==b'smpl':smpl=raw[at+8:at+8+size]
                    at+=8+size+(size&1)
                assert smpl is not None
                assert struct.unpack_from('<I',smpl,12)[0]==zone['root']
                assert struct.unpack_from('<II',smpl,44)==(zone['loopStart'],zone['frames']-1)
        checks.append('downloaded MPC ZIP passes CRC and all three PCM24 roots/absolute RIFF loops agree')
        for name in ('DESIGN.md','reference-check.json','verification.json','native-source.zip'):
            assert context.request.get(url+name).status==200
        with page.expect_download() as dl:page.locator('#wav').click()
        dl.value.save_as(str(output/'native.wav'))
        with wave.open(str(output/'native.wav')) as w:assert (w.getnchannels(),w.getsampwidth(),w.getframerate())==(1,3,44100)
        checks.append('native WAV and evidence/source downloads')
        page.screenshot(path=str(output/'desktop.png'),full_page=True)
        mobile=browser.new_context(viewport={'width':390,'height':844}).new_page();mobile.goto(url)
        mobile.wait_for_function('window.trefoilNativePlayer?.readyState===1')
        assert mobile.evaluate('document.documentElement.scrollWidth<=innerWidth')
        mobile.locator('#start').click();mobile.wait_for_function('!window.trefoilNativePlayer.paused')
        mobile.screenshot(path=str(output/'mobile.png'),full_page=True)
        checks.append('390px layout and actual playback')
        assert not errors,errors
        assert not failed,failed
        browser.close()
    report={'checks':checks,'count':len(checks),'errors':errors,'failedHttp':failed,
            'rmsNative':rms_native,'rmsReference':rms_reference,'desktop':[1440,1000],'mobile':[390,844]}
    (output/'browser-report.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report,indent=2))


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--url',default='http://127.0.0.1:8766/held/native/')
    p.add_argument('--output',type=Path,required=True);a=p.parse_args();main(a.url,a.output)
