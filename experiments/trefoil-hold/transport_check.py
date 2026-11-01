"""Render the production Web Audio graph, including real wrap/transition samples.

This tests audition playback, not the independent resonator recurrence proof.
"""
import argparse
import json
from pathlib import Path
from playwright.sync_api import sync_playwright

SCRIPT = r"""async()=>{
 const report={checks:[],rates:[],transitions:{}};
 function check(name,ok){if(!ok)throw Error(name);report.checks.push(name)}
 const rms=x=>Math.sqrt(x.reduce((a,b)=>a+b*b,0)/x.length);
 function maxStep(x){let m=0;for(let i=1;i<x.length;i++)m=Math.max(m,Math.abs(x[i]-x[i-1]));return m}
 const cases=['ordered','still','no-exchange','memory','bass','bright'];
 for(const rate of [44100,48000]){
  for(const name of cases){
   const bytes=await HeldDownloads.bytes(name+'-loop.wav');
   const scratch=new OfflineAudioContext(1,1,rate),buffer=HeldTransport.pcmBuffer(scratch,bytes);
   check(name+': original 44100 Hz PCM frames at '+rate,buffer.sampleRate===44100);
   const start=Math.round(.05*rate),end=Math.ceil(start+3*buffer.duration*rate);
   const context=new OfflineAudioContext(1,end+Math.ceil(.04*rate),rate);
   const voice=HeldTransport.schedule(context,buffer,{when:start/rate,loop:true});
   HeldTransport.retire(voice,end/rate);
   const rendered=(await context.startRendering()).getChannelData(0);
   let maxGap=0,maxJump=0;
   for(let cycle=1;cycle<3;cycle++){
    const at=Math.round(start+cycle*buffer.duration*rate),window=rendered.subarray(at-128,at+128);
    let gap=0;for(const x of window){gap=Math.abs(x)<1e-12?gap+1:0;maxGap=Math.max(maxGap,gap)}
    maxJump=Math.max(maxJump,maxStep(window));
   }
   let bodyMax=0;for(let i=start+Math.ceil(.02*rate);i<end;i++){const phase=(i-start)%(buffer.duration*rate);if(phase<129||buffer.duration*rate-phase<129)continue;bodyMax=Math.max(bodyMax,Math.abs(rendered[i]-rendered[i-1]))}
   check(name+': no silent gap at wraps / '+rate,maxGap<=1);
   check(name+': wrap steps remain within independent interior steps / '+rate,maxJump<=bodyMax*1.2+1e-7);
   let error=null;
   if(rate===44100){
    const continuation=HeldTransport.pcmBuffer(context,await HeldDownloads.bytes(name+'-returns.wav')).getChannelData(0);
    error=0;for(let i=buffer.length;i<buffer.length*3;i++)error=Math.max(error,Math.abs(rendered[start+i]-continuation[i]));
    check(name+': real preceding/continuing PCM is preserved',error<1e-7);
   }
   report.rates.push({name,outputRate:rate,bufferRate:buffer.sampleRate,maxSilentGapSamples:maxGap,wrapMaxStep:maxJump,bodyMaxStep:bodyMax,continuationMaxError:error});
  }
 }
 // A DC test signal isolates transport discontinuities from the note waveform.
 for(const smooth of [false,true]){
  const rate=44100,context=new OfflineAudioContext(1,rate,rate),positive=context.createBuffer(1,rate,rate),negative=context.createBuffer(1,rate,rate);
  positive.getChannelData(0).fill(.25);negative.getChannelData(0).fill(-.25);
  if(smooth){
   const voices=[HeldTransport.schedule(context,positive,{when:.05,loop:true})];
   for(let i=0;i<18;i++){const when=.20+i*.002;for(const voice of voices)HeldTransport.retire(voice,when);voices.push(HeldTransport.schedule(context,i%2?positive:negative,{when,loop:true}))}
   for(const voice of voices)HeldTransport.retire(voice,.4);
  }else{
   const source=context.createBufferSource();source.buffer=positive;source.loop=true;source.connect(context.destination);source.start(.05);source.stop(.4);
  }
  const data=(await context.startRendering()).getChannelData(0),key=smooth?'smoothed':'hard';
  report.transitions[key]={maxStep:maxStep(data),peak:Math.max(...data.map(Math.abs))};
 }
 check('hard-edge mutation reproduces full-scale-relative step',report.transitions.hard.maxStep>=.249);
 check('ramps bound start/stop and rapid opposite-polarity switching',report.transitions.smoothed.maxStep<.0011);
 check('rapid switches do not sum above source headroom',report.transitions.smoothed.peak<=.250001);
 const rate=44100,probe=new OfflineAudioContext(1,1,rate),release=HeldTransport.pcmBuffer(probe,await HeldDownloads.bytes('memory-release.wav'));
 const context=new OfflineAudioContext(1,release.length+4410,rate),start=2205;
 HeldTransport.schedule(context,release,{when:start/rate});const out=(await context.startRendering()).getChannelData(0),raw=release.getChannelData(0);
 let error=0;for(let i=1000;i<raw.length-1000;i++)error=Math.max(error,Math.abs(out[start+i]-raw[i]));
 check('Section 2 body and natural tail retain original samples',error<1e-7);
 report.section2BodyMaxError=error;report.count=report.checks.length;return report;
}"""


def main(url,output):
    output.mkdir(parents=True,exist_ok=True)
    with sync_playwright() as p:
        browser=p.chromium.launch(executable_path='/usr/bin/chromium',headless=True,
            args=['--no-sandbox','--autoplay-policy=no-user-gesture-required'])
        page=browser.new_page();page.goto(url)
        page.wait_for_function('window.trefoilPlayer?.readyState===1')
        report=page.evaluate(SCRIPT)
        page.locator('[data-case="ordered"][data-kind="loop"]').first.click()
        page.wait_for_function('!window.trefoilPlayer.paused')
        page.evaluate('window.trefoilPlayer.currentTime=window.trefoilPlayer.duration-.12;window.wrapSource=window.trefoilPlayer._active.source')
        page.wait_for_function('window.trefoilPlayer.currentTime<.4')
        assert page.evaluate('window.wrapSource===window.trefoilPlayer._active.source && !window.trefoilPlayer.paused')
        report['checks'].append('actual loop wraps on the same AudioBufferSource')
        page.locator('#toggle').click();assert page.evaluate('window.trefoilPlayer.paused')
        page.locator('#toggle').click();page.wait_for_function('!window.trefoilPlayer.paused')
        report['checks'].append('visible Play/Pause controls operate the audio graph')
        page.locator('#seek').evaluate('(e)=>{e.value="1.3";e.dispatchEvent(new Event("input",{bubbles:true}))}')
        assert 1.29<page.evaluate('window.trefoilPlayer.currentTime')<1.6
        report['checks'].append('visible seek control changes the playing phase')
        # Hold a new download in flight, then stop. Its late completion must stay silent.
        queued=browser.new_page();queued.goto(url)
        queued.wait_for_function('window.trefoilPlayer?.readyState===1')
        pending=[];queued.route('**/bright-returns.wav',lambda route:pending.append(route))
        with queued.expect_request('**/bright-returns.wav'):
            queued.locator('[data-case="bright"][data-kind="returns"]').click()
        queued.locator('#stop').click()
        assert pending
        with queued.expect_response('**/bright-returns.wav'):pending[0].continue_()
        assert queued.evaluate('async()=>{await HeldDownloads.bytes("bright-returns.wav");await new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r)));return window.trefoilPlayer.paused}')
        report['checks'].append('Stop cancels queued playback')
        browser.close()
    report['count']=len(report['checks'])
    (output/'transport-report.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps({'checks':report['count'],'transitions':report['transitions'],
                      'section2BodyMaxError':report['section2BodyMaxError']},indent=2))


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--url',default='http://127.0.0.1:8766/held/')
    p.add_argument('--output',type=Path,required=True);a=p.parse_args();main(a.url,a.output)
