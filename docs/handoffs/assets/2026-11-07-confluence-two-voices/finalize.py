#!/usr/bin/env python3
"""Finalize six dry clips using constant-gain EBU R128 matching; Python NumPy/SciPy +FFmpeg.
No app code or production recipe is changed. Run after the Kotlin/Java scratch renderer.
"""
from pathlib import Path
import argparse,datetime,hashlib,json,math,re,struct,subprocess,wave,zipfile
import numpy as np
from scipy.signal import resample_poly,welch

ap=argparse.ArgumentParser();ap.add_argument('--root',type=Path,default=Path(__file__).resolve().parent);args=ap.parse_args();root=args.root.resolve();rate=44100;frames=132300
(root/'reports').mkdir(exist_ok=True);(root/'work').mkdir(exist_ok=True)
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()

def read(path):
 with wave.open(str(path)) as w:
  assert(w.getnchannels(),w.getframerate(),w.getsampwidth(),w.getnframes())==(1,rate,3,frames)
  b=np.frombuffer(w.readframes(frames),np.uint8).reshape(-1,3).astype(np.int32)
 i=b[:,0]|b[:,1]<<8|b[:,2]<<16;i=np.where(i&0x800000,i-0x1000000,i)
 return np.maximum(-1,i.astype(np.float32)/np.float32(8388607)).astype(np.float32)

def write(path,x):
 v=np.floor((np.clip(x,-1,1).astype(np.float32)*np.float32(8388607)).astype(float)+.5).astype(np.int32)
 u=v.astype(np.uint32)&0xffffff;b=np.stack([u&255,(u>>8)&255,(u>>16)&255],axis=1).astype(np.uint8)
 with wave.open(str(path),'wb') as w:w.setnchannels(1);w.setsampwidth(3);w.setframerate(rate);w.writeframes(b.tobytes())

def loudness(path):
 p=subprocess.run(['ffmpeg','-hide_banner','-nostats','-i',str(path),'-af','ebur128=metadata=1:peak=true,ametadata=print','-f','null','-'],capture_output=True,text=True,check=True)
 text=p.stdout+p.stderr;matches=re.findall(r'lavfi\.r128\.I=([-+0-9.]+)',text)
 if not matches:raise ValueError('FFmpeg emitted no R128 metadata')
 peaks=re.findall(r'Peak:\s*([-+0-9.]+) dBFS',text)
 return {'integratedLufs':float(matches[-1]),'truePeakDbtp':float(peaks[-1]) if peaks else None}

def measure(x):
 a=x.astype(float);return {'rms':float(np.sqrt(np.mean(a*a))),'peak':float(np.max(abs(a))),'dc':float(np.mean(a)),'nonfiniteSamples':int(np.sum(~np.isfinite(a))),'railSamples':int(np.sum(abs(a)>=.999999)),'last250msPeak':float(np.max(abs(a[-11025:])))}

def f0(x,start,end):
 # Full46ms windows,10ms hop; broad70..600Hz YIN. No reference note enters candidate selection.
 s=resample_poly(x.astype(float),1,4);sr=11025;N=512;hop=110;lo=int(sr/600);hi=int(np.ceil(sr/70));rows=[]
 for c in range(N//2,len(s)-N//2,hop):
  t=c/sr
  if not(start<=t<=end):continue
  a=s[c-N//2:c+N//2].copy();a-=a.mean();rms=np.sqrt(np.mean(a*a))
  ac=np.fft.irfft(abs(np.fft.rfft(a,N*2))**2,N*2)[:hi+2];cs=np.r_[0,np.cumsum(a*a)];lags=np.arange(hi+2);d=np.maximum(0,cs[N-lags]+cs[N]-cs[lags]-2*ac)
  cmnd=np.ones(hi+2);cmnd[1:]=d[1:]/np.maximum(np.cumsum(d[1:])/np.arange(1,hi+2),1e-24)
  choices=[l for l in range(lo+1,hi) if cmnd[l]<.15 and cmnd[l]<=cmnd[l-1] and cmnd[l]<=cmnd[l+1]]
  if not choices or rms<.001:continue
  l=choices[0];curve=cmnd[l-1]-2*cmnd[l]+cmnd[l+1];delta=.5*(cmnd[l-1]-cmnd[l+1])/curve if abs(curve)>1e-12 else 0
  rows.append([t,sr/(l+np.clip(delta,-.5,.5)),1-cmnd[l]])
 return np.array(rows)

def q(v):return {k:float(n) for k,n in zip(('p05','median','p95'),np.percentile(v,[5,50,95]))}

def envelope(x):
 f,p=welch(x[int(.60*rate):int(1.80*rate)].astype(float),rate,nperseg=4096,noverlap=2048,scaling='spectrum');log=np.log(np.maximum(p,1e-18));c=np.fft.irfft(log);lifter=np.zeros_like(c);cut=44
 for k in range(cut+1):
  gain=.5+.5*np.cos(np.pi*k/cut);lifter[k]=gain
  if k:lifter[-k]=gain
 smooth=np.fft.rfft(c*lifter).real*(10/np.log(10));mask=(f>=300)&(f<=5000);a=smooth[mask];a-=a.mean();return f[mask],a

raw_meta=root/'sources.json' if(root/'sources.json').exists() else root/'public-sources.json';sources=json.loads(raw_meta.read_text());render=json.loads((root/'render.json').read_text());target=-22.
# First balance every component separately with the same integrated-loudness measure.
stem_ids=['a','b','double-0','double-1','synthetic-b','synthetic-formant-control'];stems={};stem_info={}
for id in stem_ids:
 x=np.fromfile(root/f'work/stem-{id}.f32','<f4');assert len(x)==frames
 temp=root/f'work/measure-stem-{id}.wav';write(temp,x);before=loudness(temp);gain=float(np.float32(10**((target-before['integratedLufs'])/20)));y=np.multiply(x,np.float32(gain),dtype=np.float32)
 write(root/f'work/lufs-stem-{id}.wav',y);after=loudness(root/f'work/lufs-stem-{id}.wav');stems[id]=y
 stem_info[id]={'input':before,'constantGain':gain,'measuredAfterPcm24':after,'path':f'work/lufs-stem-{id}.wav'}

mix=lambda a,b:np.multiply(np.add(a,b,dtype=np.float32),np.float32(1/np.sqrt(2)),dtype=np.float32)
values={'a-alone':stems['a'],'b-alone':stems['b'],'a-plus-a':mix(stems['double-0'],stems['double-1']),'a-plus-b':mix(stems['a'],stems['b']),'synthetic-b-alone':stems['synthetic-b'],'a-plus-synthetic-b':mix(stems['a'],stems['synthetic-b'])}
titles={'a-alone':'A alone','b-alone':'B alone','a-plus-a':'A + A · same-take doubling control','a-plus-b':'A + B · recorded reference','synthetic-b-alone':'Generated from A · alone','a-plus-synthetic-b':'A + generated voice'}
descriptions={'a-alone':'One actual recorded singer: VocalSet male3, straight /a/, native pitch.','b-alone':'A different actual recorded singer: VocalSet female1, straight /a/, native pitch.','a-plus-a':'Two copies of A with small timing and playback-speed differences.','a-plus-b':'Two independent singers recorded separately, summed in mono; neither source is pitch-corrected.','synthetic-b-alone':'A transformed with a vocal-color change and its own pitch movement. It is generated from A.','a-plus-synthetic-b':'Recorded A plus the same generated-from-A candidate heard in the preceding row. Not a new human recording.'}
processing={
 'a-alone':['Raw source A retained separately. Mean removal and declared 60 ms entrance / 140 ms release shape this finite body crop; native pitch is retained.'],
 'b-alone':['Raw source B retained separately. Mean removal and declared 90 ms entrance / 180 ms release shape this finite body crop; native pitch is retained.'],
 'a-plus-a':['Same A recording twice. Stock ConfluenceVoice TEXTURE playback uses duration ratios 1.0066572428/1.0034866333 and 5.759637 ms relative onset difference from the published default pair.','This isolates the current doubling primitive in a new 3-second dry window; it is not a replay of the complete 30-second app arrangement.'],
 'a-plus-b':['A enters at 0.200 s; actual independent B enters at 0.245 s. Both recordings retain native pitch, with about 30.25 cents median separation.','Components are first matched independently to −22 LUFS, then summed at equal-power gains; one final scalar sets the complete clip to −22 LUFS.'],
 'synthetic-b-alone':['Scratch extension, not unchanged production renderer: source A epochs/periods control input grains; an independent output pulse clock is centered at B’s measured native median.','Fixed formant/pulse-color scale 1.08; source chronology ratio 0.992. Independent target cents(t)=8.5(1−exp(−6max(0,t−.12)))sin(2π(5.23t+.31))+3.5sin(2π(.61t+.07))+2sin(2π(1.37t+.62)).','Enters at 0.245 s,90 ms entrance / 180 ms release. No generated breath or new articulation; this is a held-vowel body.'],
 'a-plus-synthetic-b':['Exactly the same A and generated component stems used for their solo rows, combined after independent component-level matching.','One final constant scalar matches the complete pair to −22 LUFS. No room, stereo placement, harmony, compressor or limiter.']}
clips=[];signals={}
for id,x in values.items():
 temp=root/f'work/pre-final-{id}.wav';write(temp,x);before=loudness(temp);gain=float(np.float32(10**((target-before['integratedLufs'])/20)));y=np.multiply(x,np.float32(gain),dtype=np.float32)
 p=root/f'audio/{id}.wav';write(p,y);actual=read(p);after=loudness(p);m=measure(actual);assert m['peak']<.85 and m['railSamples']==0 and m['nonfiniteSamples']==0 and m['last250msPeak']==0
 repeat=root/'work/determinism.wav';write(repeat,y);assert sha(repeat)==sha(p)
 signals[id]=actual;clips.append({'id':id,'path':f'audio/{id}.wav','title':titles[id],'kind': 'recorded-reference' if id=='a-plus-b' else 'doubling-control' if id=='a-plus-a' else 'dsp-prototype' if id.startswith('synthetic') or id=='a-plus-synthetic-b' else 'actual-singer','description':descriptions[id],'sha256':sha(p),'bytes':p.stat().st_size,'seconds':3.0,'frames':frames,'sampleRate':rate,'channels':1,'processing':processing[id]+['Every staged body has its DC mean removed before its finite envelope. Dry mono, common 3-second window. Final EBU R128 matching uses constantgain only; raw source crops remain separately available.'],'level':{'targetIntegratedLufs':target,'preFinal':before,'finalConstantGain':gain,'measuredAfterPcm24':after},'metrics':m})
spread=max(c['level']['measuredAfterPcm24']['integratedLufs'] for c in clips)-min(c['level']['measuredAfterPcm24']['integratedLufs'] for c in clips);assert spread<=.1
# Track solos only. A monophonic estimator is deliberately not applied to the overlapping pairs.
tracks={id:f0(signals[id],.50,1.85) for id in ['a-alone','b-alone','synthetic-b-alone']}
base=sources['sources'][1]['qualification']['medianHz'];synth=tracks['synthetic-b-alone'];local=synth[:,0]-.245
def target_hz(t):
 grown=1-np.exp(-np.maximum(0,t-.12)*6);c=8.5*grown*np.sin(2*np.pi*(5.23*t+.31))+3.5*np.sin(2*np.pi*(.61*t+.07))+2*np.sin(2*np.pi*(1.37*t+.62));return base*2**(c/1200)
# The target of a finite analysis window is a window-average, not an instantaneous point.
shift=np.linspace(-512/11025/2,512/11025/2,31);target_smoothed=np.mean(target_hz(local[:,None]+shift),axis=1)
a_track=tracks['a-alone'];a_input_time=.20+local/.992;a_interpolated=np.interp(a_input_time,a_track[:,0],a_track[:,1]);errors=1200*np.log2(synth[:,1]/target_smoothed)
pitch={'method':'Python/SciPy broad YIN 70–600 Hz, full 46.44 ms windows / 9.98 ms hop, central 0.50–1.85 s. No target/reference note enters selection. Pair pitch is not estimated.','soloCandidates':{k:{'windows':len(v),'hz':q(v[:,1]),'confidence':q(v[:,2])} for k,v in tracks.items()},'generatedVsIndependentTarget':{'correlation':float(np.corrcoef(synth[:,1],target_smoothed)[0,1]),'medianErrorCents':float(np.median(errors)),'p95AbsoluteErrorCents':float(np.percentile(abs(errors),95))},'generatedVsSourceAInputContourCorrelation':float(np.corrcoef(synth[:,1],a_interpolated)[0,1]),'targetCurveCausalInputs':['elapsed output time','constant native B median Hz'],'identitySuccessInferred':False}
assert pitch['generatedVsIndependentTarget']['p95AbsoluteErrorCents']<15
f,envA=envelope(signals['a-alone']);_,envB=envelope(signals['b-alone']);_,envS=envelope(signals['synthetic-b-alone']);_,envControl=envelope(read(root/'work/lufs-stem-synthetic-formant-control.wav'))
spectral={'method':'Welch 4096 / 50 percent overlap on 0.60–1.80 s; log-power real-cepstrum raised-cosine lifter up to 1 ms removes fine harmonic structure, then gain-normalized envelope comparison 300–5000 Hz. Not a perceptual identity score.','aVsRealBEnvelopeRmsDb':float(np.sqrt(np.mean((envA-envB)**2))),'aVsGeneratedEnvelopeRmsDb':float(np.sqrt(np.mean((envA-envS)**2))),'generatedVsSameClockFormantScale1AblationEnvelopeRmsDb':float(np.sqrt(np.mean((envS-envControl)**2))),'declaredFormantScale':1.08,'formantAblationOnlyDifference':'Scale 1.08 versus 1.00; same input epochs, output F0 curve, duration, envelope; each level-matched independently.'}
# A portable public copy; preserve the private acquisition/qualification JSON unchanged.
public_sources=json.loads(json.dumps(sources));
for s in public_sources['sources']:
 s.pop('path',None);s.pop('originalPath',None);s['path']=s['relativePath']
public_sources['evidence']={'licenseText':'sources/LICENSE-CC-BY-4.0.txt','attribution':'sources/ATTRIBUTION.txt','originalArchiveMemberAndHashes':'Listed per source; original full recordings are retained in the repository fixture archive.'}
(root/'public-sources.json').write_text(json.dumps(public_sources,indent=2,ensure_ascii=False)+'\n')
report={'format':'snipsnap-two-singers-measurement/v1','createdAtUtc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'scope':'Actual Kotlin analysis/stockTEXTURE and scratch independent-clockPSOLA generation; FFmpegEBUR128 matching; independent Python signal measurements. Owner listening pending. No app/bank/CI change.','repositoryRead':'5c028180f','productionRendererSnapshotSha256':sha(root/'src/ConfluenceVoice.kt'),'adapterSourceSha256':sha(root/'src/IndependentSinger.kt'),'rawSourcesUnmodified':all(sha(root/s['relativePath'])==s['sha256'] for s in sources['sources']),'determinism':{'floatPipelineRepeatedIdentically':render['deterministicFloatPcm'],'finalPcm24EncodingRepeatedIdentically':True},'hostCost':{'scope':'Actual calls on this Java 17 JVM, 1 CPU: six clips plus a private formant-scale ablation. Not phone latency. FFmpeg measurement excluded.','prepareAnalysisMilliseconds':render['analysisMilliseconds'],'sixClipRenderMilliseconds':render['renderAllSixMilliseconds']},'conditions':{'mono':True,'dry':True,'matched':True,'seconds':3.0,'sampleRate':rate,'channels':1,'matching':'Constant gain to −22 LUFS integrated by FFmpeg EBU R128 over the common 3-second window. Components matched first, then complete clips; no compressor/limiter. This is measured loudness matching, not a claim of identical perceived loudness.','maximumMeasuredLufsSpread':spread},'componentMatching':stem_info,'pitch':pitch,'spectral':spectral,'clips':clips,'risks':['Generated voice is transformed A, not a new real human or a learned copy ofB.','Short stable vowel bodies do not establish quality on words, consonants or full cultural gestures.','Local pulse dilation is a bounded formant-color hypothesis; it is not a vocal tract model or verified biological identity change.','Independent output clock and spectral changes are numeric properties; they do not prove the owner hears two people.','Both crops end inside a held vowel. The disclosed audition envelopes create finite releases; no new breath is generated.','No room/stereo/harmony or full gesture automaticF0 processing is used.'],'whyBuiltThisWay':['Actual independent B establishes the recorded positive reference before evaluating a synthetic identity.','Dry mono and constant gain matching remove room/width/loudness as alternative explanations.','The separate output clock avoids carrying A native pitch motion into every synthetic part.','Raw sourcecrops and complete processing recipes remain inspectable; no source preparation is hidden.']}
(root/'reports/measurement.json').write_text(json.dumps(report,indent=2,ensure_ascii=False)+'\n')
manifest={'format':'snipsnap-confluence-two-singers-audition/v1','schemaVersion':1,'title':'Two voices, no room','status':'numeric-checks-passed-owner-listening-pending','conditions':report['conditions'],'clips':clips,'sources':public_sources['sources'],'sourcePair':public_sources['pair'],'sourceReferences':[{'label':'Raw source A','url':'sources/a.wav'},{'label':'Raw sourceB','url':'sources/b.wav'},{'label':'Source credits and qualification','url':'public-sources.json'},{'label':'CC BY 4.0 legal text','url':'sources/LICENSE-CC-BY-4.0.txt'},{'label':'Numeric measurement report','url':'reports/measurement.json'},{'label':'Reproducible prototype source','url':'generator.zip'}],'disclosures':report['risks'],'heard':False}
(root/'manifest.json').write_text(json.dumps(manifest,indent=2,ensure_ascii=False)+'\n')
print(json.dumps({'clips':len(clips),'lufs':[(c['id'],c['level']['measuredAfterPcm24']['integratedLufs']) for c in clips],'spread':spread,'pitch':pitch,'spectral':spectral},indent=2))
