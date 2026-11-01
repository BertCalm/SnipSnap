"""Publish the attack revision while retaining preferred voices and before takes."""
import argparse
import copy
import hashlib
import json
from pathlib import Path
import shutil
from zipfile import ZipFile, ZIP_DEFLATED


def main(output, site):
    output, site = Path(output), Path(site)
    repo=Path(__file__).resolve().parents[1]
    dist=site/'dist'
    current=json.loads((dist/'data/manifest.json').read_text())
    new=json.loads((output/'manifest.json').read_text())
    old_rows=[e for e in current['clips'] if e.get('group')!='attack-revision']
    assert len(old_rows)==143 and len(new['clips'])==22
    originals={e['id']:copy.deepcopy(e) for e in old_rows}
    preserved=[e for e in old_rows if e.get('voice') not in ('VESSEL','CORONA')]
    preserved_files={}
    for e in preserved:
        for key in ('rawFile','matchedFile'):
            if e.get(key):
                path=dist/e[key]
                preserved_files[e[key]]=hashlib.sha256(path.read_bytes()).hexdigest()
    (dist/'attacks/reports').mkdir(parents=True,exist_ok=True)
    (dist/'attacks/recipes').mkdir(parents=True,exist_ok=True)
    before_path=dist/'attacks/before.json'
    if before_path.exists():
        before=json.loads(before_path.read_text())
    else:
        before=[copy.deepcopy(originals[v+'_identity_C4']) for v in ('vessel','corona')]
        before_path.write_text(json.dumps(before,indent=2)+'\n')
    revised={}
    for e in new['clips']:
        ident=e['id']
        for treatment in ('raw','matched'):
            name=f'{ident}_attack2_{treatment}.wav'
            dest=dist/'voices/audio'/name
            shutil.copy2(output/'audio'/f'{ident}_{treatment}.wav',dest)
            assert hashlib.sha256(dest.read_bytes()).hexdigest()==e[treatment+'Sha256']
            prior=dist/originals[ident][treatment+'File']
            if '_identity_C4' not in ident and prior!=dest:
                prior.unlink(missing_ok=True)
            e[treatment+'File']='voices/audio/'+name
        e['recipeFile']=f'attacks/recipes/{ident}.json'
        (dist/e['recipeFile']).write_text(json.dumps(e,indent=2)+'\n')
        revised[ident]=e
    pairs=[]
    for old in before:
        after=copy.deepcopy(revised[old['id']])
        original=copy.deepcopy(old)
        for row,label in ((original,'before'),(after,'after')):
            row['id']=row['voice'].lower()+'_attack_'+label
            row['group']='attack-revision'
            row['title']=row['voice'].title()+' / '+('Before' if label=='before' else 'Varied attacks + overlap')
            row['comparisonGroup']=row['voice'].lower()+'_attack_comparison'
            row['description']=('Original voice study: repeated contacts at the original strength and decay.' if label=='before' else after['description'])
            pairs.append(row)
    current['clips']=pairs+[revised.get(e['id'],e) for e in old_rows]
    current['clipCount']=147
    current['attackRevision']=new['report']
    (dist/'data/manifest.json').write_text(json.dumps(current,separators=(',',':'))+'\n')
    (dist/'attacks/manifest.json').write_text(json.dumps(dict(clips=list(revised.values()),report=new['report']),separators=(',',':'))+'\n')
    shutil.copy2(output/'reports/attack-validation.json',dist/'attacks/reports/attack-validation.json')
    # Preserve the historical JSON values while removing whitespace to fit hosting.
    history=dist/'data/history-model2.json'
    parsed=json.loads(history.read_text())
    history.write_text(json.dumps(parsed,separators=(',',':'))+'\n')
    assert json.loads(history.read_text())==parsed
    shutil.copy2(repo/'docs/APSIS-ATTACK-MEMORY.md',dist/'downloads/APSIS-ATTACK-MEMORY.md')
    with ZipFile(dist/'downloads/Apsis-Attack-Study-Source.zip','w',ZIP_DEFLATED) as z:
        for name in ('apsis_voice_study.py','apsis_attack_study.py','generate_apsis_attack_study.py','test_apsis_voice_study.py',
                     'test_apsis_attack_study.py','test_apsis_attack_site.py','prepare_apsis_attack_site.py'):
            z.write(repo/'scripts'/name,'scripts/'+name)
        for name in ('snipkit.py','probes.py'):
            z.write(repo/'.claude/skills/luthier/scripts'/name,'.claude/skills/luthier/scripts/'+name)
        z.write(repo/'docs/APSIS-ATTACK-MEMORY.md','docs/APSIS-ATTACK-MEMORY.md')
        z.write(output/'reports/attack-validation.json','reports/attack-validation.json')
        z.writestr('requirements.txt','numpy\nscipy\nnumba\n')
    html=(dist/'index.html').read_text()
    if 'data-filter="attack-revision"' not in html:
        html=html.replace('data-filter="identities" aria-pressed="true"','data-filter="identities" aria-pressed="false"')
        html=html.replace('<nav class="group-filters" aria-label="Listening sections" id="group-filters">',
                          '<nav class="group-filters" aria-label="Listening sections" id="group-filters"><button type="button" data-filter="attack-revision" aria-pressed="true"><span>↳</span> Attacks &amp; overlap</button>')
    html=html.replace('VOICE IDENTITIES · STUDY 04','ATTACKS & OVERLAP · STUDY 05')
    html=html.replace('STUDY<br><strong>04</strong>','STUDY<br><strong>05</strong>')
    html=html.replace('Compare the voice identities','Compare the revised attacks')
    html=html.replace('Choose the identities worth developing.','Let each encounter develop.')
    html=html.replace('Compare six Apsis voice identities on the same notes and phrase. Explore touch, register and held returns alongside the existing PASSAGE MPC bank.',
                      'Compare original and revised Vessel and Corona: varied contacts and resonance that carries into the next attack. Grain and Anchor remain preferred references.')
    html=html.replace('Start with Same note, then Same phrase. These are Python/Numba voice prototypes, not additional Android voices. Their PASSAGE reference uses the same study harness. The existing Kotlin PASSAGE examples and native bank are retained in the later sections.',
                      'Start with Attacks & overlap: compare the original and revised VESSEL and CORONA. Their contacts now vary in strength and hardness while resonance continues under the next attack. GRAIN and ANCHOR remain your preferred references; TETHER is retained. These are Python prototypes, not new Android voices.')
    html=html.replace('Voice measurements ↓','Original voice measurements ↓')
    if 'Apsis-Attack-Study-Source.zip' not in html:
        html=html.replace('<nav class="footer-evidence" aria-label="Source and audition evidence">',
                          '<nav class="footer-evidence" aria-label="Source and audition evidence"><a href="downloads/Apsis-Attack-Study-Source.zip" download>Attack revision source ↓</a><a href="downloads/APSIS-ATTACK-MEMORY.md" download>Attacks &amp; memory notes ↓</a>')
    (dist/'index.html').write_text(html)
    js=(dist/'app.js').read_text()
    if '"attack-revision":' not in js:
        declaration='"attack-revision": '+json.dumps(dict(number='↳',name='ATTACKS & OVERLAP',title='An attack with a memory.',description='Before and after at the same note, velocity and orbital settings. Each complete take is matched once; individual attacks retain their relative strength. Raw uses the original shared monitor gain.'))+','
        js=js.replace('const GROUPS = {','const GROUPS = {\n  '+declaration)
    js=js.replace("let selectedGroup = 'identities';","let selectedGroup = 'attack-revision';")
    js=js.replace("renderGroup(clips.some(clip => clip.normalizedGroup === 'identities') ? 'identities' :", "renderGroup(clips.some(clip => clip.normalizedGroup === 'attack-revision') ? 'attack-revision' : clips.some(clip => clip.normalizedGroup === 'identities') ? 'identities' :")
    (dist/'app.js').write_text(js)
    by_id={e['id']:e for e in current['clips']}
    assert all(by_id[e['id']]==e for e in preserved)
    assert all(hashlib.sha256((dist/n).read_bytes()).hexdigest()==h for n,h in preserved_files.items())
    size=sum(p.stat().st_size for p in dist.rglob('*') if p.is_file())+(site/'.openai/hosting.json').stat().st_size
    assert size<256*1024**2, size
    print(json.dumps(dict(clips=147,revised=22,preservedClipRows=len(preserved),preservedAudioFiles=len(preserved_files),expandedBytes=size)))


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('output');p.add_argument('site')
    args=p.parse_args();main(args.output,args.site)
