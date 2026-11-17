"""Pin the concrete study WAVs used by the native transport proof."""
import argparse,hashlib,json
from pathlib import Path

def main():
 ap=argparse.ArgumentParser();ap.add_argument('--input',type=Path,required=True);ap.add_argument('--revision',type=int,required=True);ap.add_argument('--source-commit',required=True);a=ap.parse_args();kits=[]
 for name in ['second-line','samba-bateria']:
  recipe=json.loads((a.input/name/'recipes.json').read_text());pads=[]
  for i,p in enumerate(recipe['recipes']):
   grid=[]
   for v in recipe['velocities']:
    row=[]
    for t in range(3):
     file=f'{name}/{i+1:02}_{p[0]}_v{float(v)}_t{t}.wav'
     row.append({'file':file,'sha256':hashlib.sha256((a.input/file).read_bytes()).hexdigest()})
    grid.append(row)
   pads.append({'name':p[0],'family':p[1],'players':p[4],'grid':grid})
  kits.append({'id':name,'bpm':recipe['bpm'],'pads':pads,'events':recipe['events']})
 (Path(__file__).parent/'accepted-study.json').write_text(json.dumps({'revision':a.revision,'sourceCommit':a.source_commit,'status':'Audition reference hits retained; transport proof input, not native synthesis','kits':kits},indent=2)+'\n')
if __name__=='__main__':main()
