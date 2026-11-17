"""R6: a second authored synthesis kit; preserve the approved Open Road grid."""
from pathlib import Path
import concurrent.futures,copy,json,shutil,sys,zipfile
import numpy as np
from scipy import signal
from scipy.io import wavfile
import acoustic,resynth,expansion
ROOT=Path(__file__).resolve().parent
# pitch ratio, decay time ratio, residual high-band tilt. Design values, not gear measurements.
RECIPE={'kick':(1.08,.64,-.05),'snare':(1.12,.83,.06),'hat':(1.06,.62,.06),'openhat':(1.06,.56,.04),'tom':(1.14,.63,-.04),'floor':(1.12,.65,-.04),'ride':(1.04,.76,.04),'crash':(1.03,.72,-.03)}
def main(approved,out,config=None):
 config=config or {};kit=config.get("kit","tight-pocket");recipe=config.get("recipe",RECIPE)
 previous=config.get("previous",["open-road"]);articulation=config.get("articulation","muted");levels=config.get("levels",[4,9,14])
 base=approved/config.get("base_model","synthesis-model.json")
 out.mkdir(parents=True,exist_ok=True);staging=out/'analysis-source';model=copy.deepcopy(json.loads(base.read_text()))
 data=list(concurrent.futures.ThreadPoolExecutor(4).map(acoustic.fetch,json.loads((ROOT/config.get('sources','pocket-sources.json')).read_text())))
 lookup={Path(s['path']).stem:(s,x,h) for s,x,h in data};hits=[];provenance=[]
 for l in range(3):
  for rr in range(3):
   name=f'snaremic_snare_{articulation}_vl{levels[l]+rr}';s,x,h=lookup[name]
   x=signal.sosfilt(signal.butter(2,22,fs=44100,btype='highpass',output='sos'),x)
   x[:44]*=np.linspace(0,1,44);x[-882:]*=np.linspace(1,0,882)
   hits.append((l,rr,x));provenance.append({'level':l,'take':rr,'url':acoustic.URL+s['path'],'git_blob':s['sha'],'source_sha256':h})
 gain=.75/max(np.max(abs(x)) for l,r,x in hits)
 (staging/'open-road').mkdir(parents=True,exist_ok=True)
 for l,r,x in hits:wavfile.write(staging/'open-road'/f'snare-{l}-{r}.wav',44100,np.rint(x*gain*32767).astype(np.int16))
 resynth.VOICES=['snare'];model['voices']['snare']=resynth.analyze(staging)['voices']['snare'];shutil.rmtree(staging)
 for voice,layers in model['voices'].items():
  pitch,decay,tilt=recipe[voice]
  for layer in layers:
   # Change resonant tuning and decay before synthesis, not a post-render effect preset.
   layer['frequencies']=(np.array(layer['frequencies'])*pitch).tolist()
   layer['times']=(np.array(layer['times'])*decay).tolist();layer['seconds']*=decay
   freq=np.array(layer['frequencies']);layer['magnitude']=(np.array(layer['magnitude'])*(np.maximum(freq,100)/2000)[:,None]**tilt).tolist()
   for mode in layer['metal_modes']:mode['hz']*=pitch
   af=np.linspace(0,22050,len(layer['attack_magnitude']));a=np.array(layer['attack_magnitude'])
   layer['attack_magnitude']=np.array([np.interp(af/pitch,af,col,right=0) for col in a.T]).T.tolist()
   # Match nominal kit energy while preserving three measured dynamic profiles.
   layer['energy']*=.88 if voice in ('kick','tom','floor') else 1.0
 model['version']=4;model['kit']=kit;model['recipe']=recipe
 (out/'synthesis-model.json').write_text(json.dumps(model,separators=(',',':')));model=json.loads((out/'synthesis-model.json').read_text())
 model_files={kit:'synthesis-model.json'}
 prior_manifest=json.loads((approved/'manifest.json').read_text())
 for prev in previous:
  fallback='synthesis-model.json' if prev==prior_manifest['modes'][0] or not config else prev+'-model.json'
  source_model=prior_manifest.get('models',{}).get(prev,fallback)
  target_model=prev+'-model.json';shutil.copy(approved/source_model,out/target_model);model_files[prev]=target_model
 manifest={'revision':config.get('revision','R6 two synthesized genre kits'),'levels':['Soft','Medium','Hard'],'takes':3,'modes':[kit]+previous,'voices':list(RECIPE),'keys':expansion.KEYS,'files':{},'models':model_files}
 banks={m:{} for m in manifest['modes']};timings=[]
 for v in RECIPE:
  for l in range(3):
   for r in range(3):
    x,ms=resynth.render(model,v,l,r);assert np.array_equal(x,resynth.render(model,v,l,r)[0]);timings.append(ms);banks[kit][v,l,r]=x
    for prev in previous:banks[prev][v,l,r]=resynth.read(approved/(prev if config else 'synth')/f'{v}-{l}-{r}.wav')
 calibration={v:min(1,.90/max(np.max(abs(x)) for (w,l,r),x in banks[kit].items() if w==v)) for v in RECIPE}
 def write(p,x):
  assert np.isfinite(x).all() and np.max(abs(x))<.99;p.parent.mkdir(parents=True,exist_ok=True);wavfile.write(p,44100,np.rint(x*32767).astype(np.int16))
 for mode,bank in banks.items():
  folder=out/mode;folder.mkdir(exist_ok=True);regions=[]
  for (v,l,r),x in list(bank.items()):
   rel=f'{mode}/{v}-{l}-{r}.wav'
   if mode in previous:shutil.copyfile(approved/(mode if config else 'synth')/f'{v}-{l}-{r}.wav',out/rel)
   else:bank[v,l,r]=x*calibration[v];write(out/rel,bank[v,l,r])
   manifest['files'][f'{mode}/{v}/{l}/{r}']=rel
   choke=' group=1 off_by=1 off_mode=fast' if v in ('hat','openhat') else ''
   regions.append(f'<region> sample={v}-{l}-{r}.wav key={expansion.KEYS[v]} lovel={[1,51,96][l]} hivel={[50,95,127][l]} seq_length=3 seq_position={r+1} amp_veltrack=0 loop_mode=one_shot{choke}')
  (folder/'kit.sfz').write_text('\n'.join(regions)+'\n')
 # Same phrase and tempo isolate kit character; neither groove is a song transcription.
 for phrase in ('groove','fill'):
  mixes={mode:expansion.mix(bank,phrase=='fill') for mode,bank in banks.items()}
  g=.85/max(np.max(abs(x)) for x in mixes.values())
  length=max(map(len,mixes.values()))
  for mode,x in mixes.items():write(out/mode/f'{phrase}.wav',np.pad(x,(0,length-len(x)))*g)
 (out/'manifest.json').write_text(json.dumps(manifest,indent=2))
 for name in ('CC0.txt','NOTICE.txt'):shutil.copy(approved/name,out/name)
 (out/'NOTICE.txt').write_text((out/'NOTICE.txt').read_text()+f'Adds {kit}: {articulation}-snare source descriptors plus authored tuning/decay changes. Listening kits use synthesis. No source waveforms in runtime models.\n')
 original=json.loads((approved/'provenance.json').read_text());original[f'{kit}_{articulation}_snare']=provenance
 (out/'provenance.json').write_text(json.dumps(original,indent=2))
 report={'new_render_ms_median':float(np.median(timings)),'new_render_ms_max':max(timings),'calibration':calibration,'preserved_kits':previous,'status':'Family listening prototype; native gates still open'}
 (out/'synthesis-report.json').write_text(json.dumps(report,indent=2))
 with zipfile.ZipFile(out/'open-road-acoustic.zip','w',zipfile.ZIP_DEFLATED) as z:
  for p in sorted(out.rglob('*')):
   if p.is_file() and p.suffix in ('.wav','.sfz','.json','.txt'):z.write(p,p.relative_to(out))
 print(json.dumps(report,indent=2))
if __name__=='__main__':main(Path(sys.argv[1]),Path(sys.argv[2]))
