"""Prepare complete replayable snapshots for a proposed body-linked MATERIAL law."""
import json
from model import *

for folder in [R/'inputs', R/'measurements', O/'audio', O/'native', O/'patches',
               O/'diagnostics', O/'reports', O/'downloads', O/'comparisons']:
    folder.mkdir(parents=True, exist_ok=True)
jobs=[]; clips=[]; roles=[]

def emit(identity,p,group='diagnostic',oversample=4,seconds='full',native=False):
    dest=(O/'patches' if group=='listening' else R/'inputs')/(identity+'.json')
    dest.write_text(json.dumps(p,indent=2)+'\n')
    jobs.append(dict(id=identity,patch=str(dest),oversample=oversample,seconds=seconds,native=native,group=group))

for slug,d in DESIGNS.items():
    old=original(slug)
    roles.append(dict(id=slug,name=d['name'],voice=d['voice'],description=d['description'],
                      originalMaterial=old['macros']['MATERIAL'],proposedMaterial=.5,
                      originalWeightPower=2 if slug=='rounded_plug' else 1,
                      bodyLinkFields=['operators.gain','operators.decay','operators.sustain','routing.radians','cutoffRatio','wood.losses','wood.gains']))
    for mode in ['finite','held']:
        held=mode=='held'
        for value in ['reference',0.,.25,.5,.75,1.]:
            ref=value=='reference';m=.5 if ref else value
            p=patch(slug,m,held,reference=ref)
            identity=f'{slug}_{mode}_'+('reference' if ref else f'm{round(m*100):03d}')
            emit(identity,p,'listening',native=True)
            clips.append(dict(id=identity,roleId=slug,voice=d['voice'],preset=d['name'],mode=mode,
                              material=p['macros']['MATERIAL'],reference=ref,
                              audioUrl=f'audio/{identity}.wav',nativeUrl=f'native/{identity}.wav',
                              patchUrl=f'patches/{identity}.json',previousUrl=f'diagnostics/{identity}_previous.wav' if held else None,
                              sampleRate=44100,loop=held,snapshot=True))
        for m in [.5,.75,1.]:
            p=patch(slug,m,held,reference=True);p['macros']['MATERIAL']=m
            emit(f'{slug}_{mode}_old_m{round(m*100):03d}',p,native=True)
        for m in [.125,.375]:
            emit(f'{slug}_{mode}_dense_m{round(m*1000):04d}',patch(slug,m,held))
        # FUSE remains the attack/body coupling control. With this proposed law,
        # MATERIAL also changes the body configuration even when FUSE is zero.
        for fuse in [0.,.85]:
            for m in [0.,.25,.5]:
                p=patch(slug,m,held);p['macros']['FUSE']=fuse
                emit(f'{slug}_{mode}_fuse{round(fuse*100):03d}_m{round(m*1000):04d}',p)
        for midi in [36,60,84]:
            for velocity in [.35,1.]:
                for m in [0.,.5,1.]:
                    emit(f'{slug}_{mode}_midi{midi}_vel{round(velocity*100):03d}_m{round(m*100):03d}',patch(slug,m,held,midi,velocity))
        for m in [0.,.25,.5,1.]:
            for os in [4,8]:
                emit(f'{slug}_{mode}_alias_m{round(m*100):03d}_os{os}',patch(slug,m,held,midi=84),oversample=os,seconds='full' if held else '.6')
        emit(f'{slug}_{mode}_repeated_center',patch(slug,.5,held),native=True)

manifest=dict(title='Alloy MATERIAL body comparison',engine='ALLOY',version=1,
              stage='material-body-prototype',approval=False,bankChanged=False,
              mechanism='Proposed pre-render MATERIAL-to-body mapping, demonstrated with complete model-1 snapshots.',
              liveMappingInstalled=False,
              installedInEngine=False,snapshotsOnly=True,
              snapshotNotice='Each position is a complete snapshot patch. The live MATERIAL knob in SnipSnap does not yet apply this body-linked mapping.',
              responseLaw=dict(domain=[0,.5],originalConfigBypassAtOrAbove=.5,
                               originalWeight='(2 * MATERIAL) ** originalWeightPower',
                               numericFields='leftEndpoint + (original - leftEndpoint) * originalWeight',
                               routeLaw='Union of left/original route edges, absent depth=0; original exact route list bypasses interpolation at .5+',
                               fixedFields=['operators.ratio','wood.ratios','tine','breath','envelope','held','fuseRadians'],
                               endpoints=[dict(roleId=slug,power=2 if slug=='rounded_plug' else 1,
                                               leftSnapshot=f'patches/{slug}_finite_m000.json',
                                               originalSnapshot=f'patches/{slug}_finite_reference.json') for slug in DESIGNS]),
              roles=roles,clips=clips,steps=[0,.25,.5,.75,1],comparisons=[],
              summary=dict(status='Render verification in progress',reportUrl='reports/verification.json'),downloads=[])
(O/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
(R/'jobs.json').write_text(json.dumps(jobs,indent=2)+'\n')
(R/'final-designs.json').write_text(json.dumps(serial_designs(),indent=2)+'\n')
for index in range(2):
    (R/f'jobs{index}.tsv').write_text('\n'.join('\t'.join([j['id'],j['patch'],str(j['oversample']),j['seconds'],str(j['native']).lower()]) for j in jobs[index::2])+'\n')
print(json.dumps(dict(listening=len(clips),jobs=len(jobs))))
