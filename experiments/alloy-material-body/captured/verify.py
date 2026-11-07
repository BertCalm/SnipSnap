"""Measure actual unchanged-Kotlin output and publish a provisional snapshot pack.

Contrast gates screen a stronger design; they do not establish that anyone has
heard or approved it. The rejected wood-only study is retained unchanged.
"""
import csv,hashlib,json,math,shutil,subprocess,wave,zipfile
from pathlib import Path
import numpy as np
from model import ROOT,R,O,REPO,DESIGNS,patch,original
M=R/'measurements'
jobs=json.loads((R/'jobs.json').read_text())
manifest=json.loads((O/'manifest.json').read_text())
checks=[];failures=[]
def check(condition,label,detail=None):
    row=dict(check=label,passed=bool(condition))
    if detail is not None:row['detail']=detail
    checks.append(row)
    if not condition:failures.append(row)
def load(identity,kind='samples'):
    return np.fromfile(M/f'{identity}_{kind}.f32',dtype='<f4').astype(float)
def pcm(path):
    with wave.open(str(path),'rb') as w:
        return dict(channels=w.getnchannels(),rate=w.getframerate(),width=w.getsampwidth(),frames=w.getnframes(),data=w.readframes(w.getnframes()))
def decoded(path):
    w=pcm(path);b=np.frombuffer(w['data'],dtype=np.uint8).reshape(-1,w['width']).astype(np.int32)
    if w['width']==2:y=b[:,0]|b[:,1]<<8;y=np.where(y&0x8000,y-0x10000,y)/32768.
    elif w['width']==3:y=b[:,0]|b[:,1]<<8|b[:,2]<<16;y=np.where(y&0x800000,y-0x1000000,y)/8388608.
    else:raise ValueError(w['width'])
    return w,y
def rms(a):return float(np.sqrt(np.mean(a*a))) if len(a) else 0.
def window(a,start,end):return a[round(start*44100):min(len(a),round(end*44100))]
def difference(a,b,start=.025,end=.55):
    a=window(a,start,end);b=window(b,start,end);n=min(len(a),len(b));a=a[:n];b=b[:n]
    return rms(a/max(rms(a),1e-12)-b/max(rms(b),1e-12))
def spectrum(a,start=.025,seconds=.3):
    x=window(a,start,start+seconds);n=1<<(len(x)-1).bit_length()
    return abs(np.fft.rfft(x*np.hanning(len(x)),n))**2,n
def spectral_mass(a,start=.025,seconds=.3):
    p,n=spectrum(a,start,seconds);hz=np.fft.rfftfreq(n,1/44100);mask=hz>=30
    bins=np.clip((np.log(hz[mask]/30)/np.log(20000/30)*32).astype(int),0,31)
    bands=np.bincount(bins,weights=p[mask],minlength=32)
    return bands/max(bands.sum(),1e-20)
def color(a,b,start=.025,seconds=.3):return float(abs(spectral_mass(a,start,seconds)-spectral_mass(b,start,seconds)).sum()/2)
def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def seam(previous,current):
    p=previous[-256:];c=current[-256:]
    return float(np.sum((p-c)**2)/max(np.sum(p*p),1e-20))

initial=json.loads((R/'render-exit.json').read_text());replacement=json.loads((R/'replacement-exit.json').read_text())
check(initial['actualExits']==[0,0],'both initial JVM workers exited successfully',initial['actualExits'])
check(replacement['actualExit']==0,'replacement quarter-law JVM worker exited successfully',replacement['actualExit'])
check(len(replacement['jobs'])==14,'exactly fourteen affected intermediate inputs rerendered')
encode=json.loads((R/'predecessor-exit.json').read_text())
check(encode['actualExit']==0,'exact shared-gain predecessor encoder exited successfully')
stats={}
for j in jobs:
    identity=j['id'];path=Path(j['patch']);a=json.loads((M/(identity+'.json')).read_text());p=json.loads(path.read_text());stats[identity]=a
    samples=load(identity);body=load(identity,'body')
    check(np.isfinite(samples).all() and np.isfinite(body).all(),identity+': finite samples and isolated body')
    check(a['rawStatePeak']<2,identity+': unchanged raw-state peak guard',a['rawStatePeak'])
    check(a['maxConditionedAttack']<=1.001 and a['maxPhaseModulation']<12,identity+': unchanged source/PM budgets')
    check(a['rms']>1e-4,identity+': non-silent')
    check(abs(a['mean'])<max(1e-5,a['rms']*.01),identity+': unchanged DC budget',a['mean'])
    hz=440*2**((p['midi']-69)/12);cents=None if a['pitchHz'] is None else 1200*math.log2(a['pitchHz']/hz);a['pitchErrorCents']=cents
    check(cents is not None and abs(cents)<10,identity+': unchanged ten-cent root guard',cents)
    check(a['genericClassWithoutDurationShortcut'] not in ['KICK','SNARE','CLAP','HAT','TOM'],identity+': actual full-event classifier without duration shortcut',a['genericClassWithoutDurationShortcut'])
    held=p['macros']['HOLD']>=.99
    check(a['declaredClass']==('LOOP' if held else 'TONAL'),identity+': declared class matches HOLD')
    if held:
        before=load(identity,'previous')
        check(len(before)==len(samples) and len(before)>256,identity+': actual preceding cycle retained')
        check(a['prerollCycles']==3,identity+': unchanged three-cycle preroll')
        check(a['stateError']<1e-9,identity+': strict state convergence margin',a['stateError'])
        check(a['seamError']<1e-3,identity+': unchanged actual-predecessor seam bound',a['seamError'])
    if j['native']:
        h,y=decoded(M/(identity+'_native.wav'))
        check(h['channels']==1 and h['rate']==44100 and h['width']==3,identity+': public native format')
        check(len(y)==len(samples) and max(abs(y))<1,identity+': native encoded headroom and frames')

identity_rows=[];factory_ids={'rounded_plug':'factory_02','long_prism':'factory_07','velvet_rise':'factory_23'}
for role in manifest['roles']:
    slug=role['id'];old=original(slug)
    for mode in ['finite','held']:
        ref=f'{slug}_{mode}_reference';center=f'{slug}_{mode}_m050'
        exact=pcm(M/(ref+'_native.wav'))['data']==pcm(M/(center+'_native.wav'))['data']
        exact16=pcm(M/(ref+'.wav'))['data']==pcm(M/(center+'.wav'))['data']
        check(exact and exact16,f'{slug} {mode}: original midpoint native PCM24 and listening PCM16 exact')
        row=dict(roleId=slug,mode=mode,approvedDefaultPcm24Exact=exact,matchedPcm16Exact=exact16,referencePreroll=stats[ref]['prerollCycles'],candidatePreroll=stats[center]['prerollCycles'])
        if mode=='finite':
            published=Path('/workspace/scratch/alloy-factory-release/review/registry-replay-jdk17')/(factory_ids[slug]+'.wav')
            same=pcm(published)['data']==pcm(M/(center+'_native.wav'))['data']
            check(same,slug+': midpoint equals actual previously published MPC sample')
            row.update(frozenFactoryPcm24Exact=same,frozenFactoryFile=str(published),frozenFactorySha256=sha(published))
        identity_rows.append(row)
        for m in [.5,.75,1.]:
            before=f'{slug}_{mode}_old_m{round(m*100):03d}';after=f'{slug}_{mode}_m{round(m*100):03d}'
            same=pcm(M/(before+'_native.wav'))['data']==pcm(M/(after+'_native.wav'))['data']
            check(same,f'{slug} {mode} MATERIAL{m}: upper half public PCM24 exact')
            identity_rows.append(dict(roleId=slug,mode=mode,material=m,upperHalfPcm24Exact=same))
        repeat=f'{slug}_{mode}_repeated_center'
        check(pcm(M/(repeat+'_native.wav'))['data']==pcm(M/(center+'_native.wav'))['data'],f'{slug} {mode}: fresh saved midpoint replay exact')
        for m in [0,.25,.5,.75,1]:
            snapshot=json.loads((O/'patches'/f'{slug}_{mode}_m{round(m*100):03d}.json').read_text())
            cfg=snapshot['config'];baseline=old['config']
            check([x['ratio'] for x in cfg['operators']]==[x['ratio'] for x in baseline['operators']],f'{slug} {mode} {m}: all carrier ratios fixed')
            check(cfg['envelope']==baseline['envelope'] and cfg['held']==baseline['held'],f'{slug} {mode} {m}: original gesture duration and held period unchanged')
            check(cfg['tine']==baseline['tine'] and cfg['breath']==baseline['breath'],f'{slug} {mode} {m}: original tine and breath retained')
            if m>=.5:check(cfg==baseline,f'{slug} {mode} {m}: exact original configuration bypass')

activity=[]
for role in manifest['roles']:
    slug=role['id']
    for mode in ['finite','held']:
        start,end=((.25,.75) if slug=='rounded_plug' else ((1.,2.) if slug=='velvet_rise' else (.30,1.30))) if mode=='finite' else (.05,.60)
        ids=[f'{slug}_{mode}_m{round(m*100):03d}' if m in [0,.25,.5] else f'{slug}_{mode}_dense_m{round(m*1000):04d}' for m in [0,.125,.25,.375,.5]]
        arrays=[load(x) for x in ids];bodies=[load(x,'body') for x in ids]
        dense=[]
        for i in range(4):
            w=difference(bodies[i],bodies[i+1],start,end);c=color(bodies[i],bodies[i+1],start,end-start)
            dense.append(dict(fromMaterial=i*.125,toMaterial=(i+1)*.125,bodyMatchedWave=w,bodySpectralTV=c))
            check(w>.005,f'{slug} {mode}: dense lower step{i} exceeds existing activity floor',w)
        coarse_wave=[difference(bodies[0],bodies[2],start,end),difference(bodies[2],bodies[4],start,end)]
        coarse_color=[color(bodies[0],bodies[2],start,end-start),color(bodies[2],bodies[4],start,end-start)]
        body_wave=difference(bodies[0],bodies[4],start,end);body_color=color(bodies[0],bodies[4],start,end-start)
        full_wave=difference(arrays[0],arrays[4],start,end);full_color=color(arrays[0],arrays[4],start,end-start)
        check(body_wave>=.15 and body_color>=.08,f'{slug} {mode}: stronger isolated sustained-body screening target',dict(wave=body_wave,spectralTV=body_color))
        check(full_wave>=.15 and full_color>=.08,f'{slug} {mode}: stronger full-signal screening target',dict(wave=full_wave,spectralTV=full_color))
        check(min(coarse_wave)>.10 and min(coarse_color)>.003,f'{slug} {mode}: substantial quarter-position movement',dict(wave=coarse_wave,spectralTV=coarse_color))
        row=dict(roleId=slug,mode=mode,windowSeconds=[start,end],denseSteps=dense,
                 coarseBodyWave=coarse_wave,coarseBodySpectralTV=coarse_color,
                 fullEndpointWave=full_wave,fullEndpointSpectralTV=full_color,
                 bodyEndpointWave=body_wave,bodyEndpointSpectralTV=body_color,
                 originalBodyActivityFloor=.005,originalColourFloor=.003,
                 strongerScreeningTargets=dict(bodyMatchedWave=.15,bodySpectralTV=.08),
                 audibilityProven=False)
        activity.append(row);role.setdefault('activity',{})[mode]=row

interactions=[]
for role in manifest['roles']:
    slug=role['id']
    for mode in ['finite','held']:
        low=f'{slug}_{mode}_fuse000_m0000';center=f'{slug}_{mode}_fuse000_m0500'
        delta=difference(load(low,'body'),load(center,'body'),.1,.55)
        check(delta>.05,f'{slug} {mode}: body-linked material remains active at FUSE zero',delta)
        higher=[f'{slug}_{mode}_fuse085_m{m:04d}' for m in [0,500]]
        # Same snapshot shapes, different FUSE: the attack port can still alter
        # the body; it is no longer the sole source of MATERIAL contrast.
        diffs=[difference(load(low,'body'),load(higher[0],'body'),.1,.55),difference(load(center,'body'),load(higher[1],'body'),.1,.55)]
        check(max(diffs)>1e-5,f'{slug} {mode}: FUSE still changes the body',diffs)
        interactions.append(dict(roleId=slug,mode=mode,bodyMaterialDifferenceAtFuseZero=delta,bodyFuseDifferences=diffs))

aliases=[]
for role in manifest['roles']:
    slug=role['id']
    for mode in ['finite','held']:
        for m in [0,.25,.5,1]:
            stem=f'{slug}_{mode}_alias_m{round(m*100):03d}_os'
            a=load(stem+'4','body');b=load(stem+'8','body')
            w=difference(a,b,.02,.47);c=color(a,b,.02,.45)
            p,n=spectrum(load(stem+'4'),.02,.45);hz=np.fft.rfftfreq(n,1/44100);high=float(p[hz>16000].sum()/max(p.sum(),1e-20))
            row=dict(roleId=slug,mode=mode,material=m,midi=84,matchedBodyResidual=w,spectralTV=c,powerAbove16k=high,limits=dict(matchedBodyResidual=.12,spectralTV=.035,powerAbove16k=.02))
            aliases.append(row);check(w<.12 and c<.035 and high<.02,f'{slug} {mode} {m}: unchanged 4x/8x guards',row)

for clip in manifest['clips']:
    identity=clip['id'];a=stats[identity]
    clip.update(frames=a['frames'],durationSeconds=a['frames']/44100,
                metrics=dict(rawPeak=a['rawPeak'],rawSeam=a['seamError'],stateError=a['stateError'],prerollCycles=a['prerollCycles'],pitchErrorCents=a['pitchErrorCents'],probeMs=a['probeMs'],publicRenderMs=a['publicRenderMs']))
    for suffix,url in [('.wav',clip['audioUrl']),('_native.wav',clip['nativeUrl'])]:
        shutil.copy2(M/(identity+suffix),O/url)
    if clip['mode']=='held':
        shutil.copy2(M/(identity+'_previous.wav'),O/clip['previousUrl'])
        _,previous=decoded(O/clip['previousUrl']);_,current=decoded(O/clip['audioUrl'])
        error=seam(previous,current);clip['metrics']['encodedPredecessorSeam']=error
        check(error<1e-3,identity+': actual encoded prior-cycle cut seam',error)
        clip['metrics']['wholeDiscardedCycleRmsDifference']=rms(previous-current)/max(rms(previous),1e-12)

# Edited demonstrations are derived from the actual published listening PCM16,
# not from an alternative renderer or a body-only signal pretending to be native.
comparisons=[]
for role in manifest['roles']:
    slug=role['id']
    for mode in ['finite','held']:
        variants=[('New body',0),('Original tone',50)]
        sources=[]
        for label,m in variants:
            source_id=f'{slug}_{mode}_m{m:03d}';h,y=decoded(O/'audio'/(source_id+'.wav'))
            if mode=='held':first,last=0,len(y)
            elif slug=='rounded_plug':first,last=round(.25*44100),round(.75*44100)
            elif slug=='long_prism':first,last=round(.3*44100),round(1.3*44100)
            else:first,last=44100,88200
            check(last<=len(y) and last>first,f'{source_id}: comparison uses a real post-onset source window')
            sources.append(dict(label=label,m=m,source_id=source_id,first=first,last=last,samples=y[first:last]))
        check(len(sources[0]['samples'])==len(sources[1]['samples']),f'{slug} {mode}: comparison windows align')
        target=.07
        peak_factor=max(float(max(abs(s['samples'])))/max(rms(s['samples']),1e-12) for s in sources)
        target=min(target,.95/max(peak_factor,1e-12))
        fade_frames=round(.004*44100);gap_frames=round(.15*44100)
        parts=[];segments=[];cursor=0
        for index in [0,1,0,1]:
            s=sources[index];gain=target/max(rms(s['samples']),1e-12)
            data=s['samples']*gain
            n=min(fade_frames,len(data)//2);fade=np.linspace(0.,1.,n)
            data=data.copy();data[:n]*=fade;data[-n:]*=fade[::-1]
            quant=np.clip(np.rint(data*32768.),-32768,32767).astype('<i2')
            segment=dict(label=s['label'],variant='new' if index==0 else 'original',sourceClipId=s['source_id'],
                         sourceStartFrame=s['first'],sourceEndFrame=s['last'],frameCount=len(quant),
                         outputStartFrame=cursor,outputEndFrame=cursor+len(quant),destinationStartFrame=cursor,
                         startSeconds=cursor/44100,endSeconds=(cursor+len(quant))/44100,
                         gain=gain,fadeFrames=n)
            segments.append(segment);parts.append(quant);cursor+=len(quant)
            if len(segments)<4:parts.append(np.zeros(gap_frames,dtype='<i2'));cursor+=gap_frames
        data=np.concatenate(parts)
        identity=f'{slug}_{mode}_body_ab';url=f'comparisons/{identity}.wav'
        with wave.open(str(O/url),'wb') as out:out.setnchannels(1);out.setsampwidth(2);out.setframerate(44100);out.writeframes(data.tobytes())
        entry=dict(id=identity,roleId=slug,mode=mode,audioUrl=url,frames=len(data),sampleRate=44100,
                   segments=segments,reference=False,loop=False,edited=True,sequence=['new','original','new','original'],
                   gapFrames=gap_frames,fadeLaw='linear inclusive 0 to 1 for fadeFrames; mirrored inclusive 1 to 0 at each segment end',
                   quantization='decode source PCM16 /32768; Float64 multiply gain and fade; numpy.rint ties-to-even *32768, clipped to signed PCM16',
                   reconstructionToleranceLsb=1,matchedExcerptRms=target,
                   description='Edited, matched sustained excerpts: new body at 0%, original tone at 50%, then repeat. These are listening comparisons, not native one-shots or sampler loops.')
        comparisons.append(entry)
        check(len(data)==4*len(sources[0]['samples'])+3*gap_frames,f'{identity}: exact declared edited segment length')
manifest['comparisons']=comparisons

prior=json.loads(Path('/workspace/scratch/alloy-factory-release/review/jdk17-class-parity.json').read_text());class_rows=[]
for x in prior['rows']:
    p=Path(x['currentFile']);same=p.exists() and sha(p)==x['expectedSha256']
    class_rows.append(dict(className=x['class'],path=str(p),sha256=sha(p) if p.exists() else None,matchingPublishedBuild=same))
check(len(class_rows)==50 and all(x['matchingPublishedBuild'] for x in class_rows),'all fifty compiled dependency classes match approved engine')
old_manifest=json.loads((REPO/'testkit/alloy-factory/manifest.json').read_text());source_rows=[]
for x in old_manifest['provenance']['sourceFiles']:
    p=REPO/x['path'];source_rows.append(dict(path=x['path'],sha256=sha(p),matchingPublishedBuild=sha(p)==x['sha256']))
check(len(source_rows)==8 and all(x['matchingPublishedBuild'] for x in source_rows),'all eight approved engine source files unchanged')
provenance=dict(engine='Actual unchanged Kotlin ALLOY model 1; external candidate config authoring law',sourceCommit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=REPO,text=True).strip(),
                publishedBankSourceCommit=old_manifest['provenance']['gitRevision'],sourceFiles=source_rows,dependencyClasses=class_rows,
                rendererSourceSha256=sha(R/'MaterialProbe.java'),mappingSourceSha256=sha(R/'model.py'),designSourceSha256=sha(R/'final-designs.json'),
                dspArithmeticChanged=False,schemaChanged=False,productionFactoryChanged=False,oldSavedPatchFallbackChanged=False,liveMappingInstalled=False,
                responseLaw=manifest['responseLaw'])
(O/'reports/provenance.json').write_text(json.dumps(provenance,indent=2)+'\n')
report=dict(status='Stronger body-linked prototype; numerical screening is not listening approval',
            actualProcessExits=initial['actualExits']+[replacement['actualExit'],encode['actualExit']],
            uniqueFinalJobs=len(jobs),initialRenderWallSeconds=initial['elapsedSeconds'],replacementJobs=len(replacement['jobs']),
            listeningClips=36,editedComparisons=6,hardChecks=len(checks),hardChecksPassed=len(checks)-len(failures),hardFailures=failures,
            contrastTargetsAreScreeningOnly=True,activity=activity,referenceComparisons=identity_rows,materialFuseInteractions=interactions,highRegister4x8x=aliases,
            extrema=dict(rawStatePeak=max(a['rawStatePeak'] for a in stats.values()),absoluteMean=max(abs(a['mean']) for a in stats.values()),rootErrorCents=max(abs(a['pitchErrorCents']) for a in stats.values() if a['pitchErrorCents'] is not None),heldStateError=max(a['stateError'] for a in stats.values()),heldSeam=max(a['seamError'] for a in stats.values()),aliasMatchedBodyResidual=max(a['matchedBodyResidual'] for a in aliases),aliasSpectralTV=max(a['spectralTV'] for a in aliases),powerAbove16k=max(a['powerAbove16k'] for a in aliases)),
            measured='Actual Kotlin rendering through a scratch Java harness; Python measures saved arrays and WAVs. No new sound has been heard or approved by the owner.',
            rejectedPriorStudy='Owner reported no audible difference on Long Prism/Velvet Rise and barely any on Rounded Plug. Its low numerical thresholds were not perceptual proof.',
            remaining=['Owner listening judgement','Live opt-in MATERIAL-to-config mapping implementation','Physical phone performance and MPC playback'])
(O/'reports/verification.json').write_text(json.dumps(report,indent=2)+'\n')
(R/'all-checks.json').write_text(json.dumps(checks,indent=2)+'\n')
with (O/'reports/metrics.csv').open('w',newline='') as f:
    writer=csv.DictWriter(f,fieldnames=['id','frames','rawPeak','rawStatePeak','mean','rms','pitchHz','pitchConfidence','pitchErrorCents','genericClassWithoutDurationShortcut','declaredClass','prerollCycles','stateError','seamError','probeMs','publicRenderMs']);writer.writeheader()
    for identity,a in stats.items():writer.writerow({key:a.get(key) for key in writer.fieldnames})
manifest['summary']=dict(status=report['status'],reportUrl='reports/verification.json',provenanceUrl='reports/provenance.json',metricsUrl='reports/metrics.csv',originalReferencePcm24Exact=True,upperHalfPcm24Exact=True,hardChecksPassed=len(checks)-len(failures),hardFailures=len(failures),renderedJobs=len(jobs),audibilityProven=False)
manifest['downloads']=[dict(label='Comparison WAVs and snapshots',url='downloads/alloy-material-body-comparison.zip'),dict(label='Snapshot patches',url='downloads/alloy-material-body-patches.zip'),dict(label='Native PCM24 WAVs',url='downloads/alloy-material-body-native-pcm24.zip'),dict(label='Render notes and body-map source',url='downloads/alloy-material-body-source.zip')]
(O/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
if failures:
    print(json.dumps(dict(status='FAILED',failures=failures),indent=2));raise SystemExit(1)
for filename,folders in [('alloy-material-body-comparison.zip',['audio','comparisons','patches','reports']),('alloy-material-body-patches.zip',['patches']),('alloy-material-body-native-pcm24.zip',['native'])]:
    with zipfile.ZipFile(O/'downloads'/filename,'w',zipfile.ZIP_DEFLATED,compresslevel=6) as z:
        for folder in folders:
            for p in sorted((O/folder).rglob('*')):
                if p.is_file():z.write(p,str(p.relative_to(O)))
        z.write(O/'manifest.json','manifest.json')
    check((O/'downloads'/filename).stat().st_size<12*1024*1024,filename+': per-file source budget')
source_files={}
for name in ['model.py','MaterialProbe.java','EncodePredecessors.java','generate.py','run_jobs.py','run_replacements.py','verify.py','README.md','final-designs.json','classpath.txt']:
    source_files['render/'+name]=(R/name).read_bytes()
for slug in DESIGNS:source_files['render/baselines/'+slug+'.json']=(R/'baselines'/(slug+'.json')).read_bytes()
source_files['manifest.json']=(O/'manifest.json').read_bytes()
source_files['ENGINE_PROVENANCE.json']=(O/'reports/provenance.json').read_bytes()
source_files['SOURCE_FILES.json']=(json.dumps({name:hashlib.sha256(data).hexdigest() for name,data in sorted(source_files.items())},indent=2)+'\n').encode()
with zipfile.ZipFile(O/'downloads/alloy-material-body-source.zip','w',zipfile.ZIP_DEFLATED,compresslevel=6) as z:
    for name,data in sorted(source_files.items()):z.writestr(name,data)
check((O/'downloads/alloy-material-body-source.zip').stat().st_size<1024*1024,'source bundle below one MiB')
print(json.dumps(dict(hardChecks=report['hardChecks'],hardFailures=len(failures),activity=activity,extrema=report['extrema'],payloadBytes=sum(p.stat().st_size for p in O.rglob('*') if p.is_file())),indent=2))
