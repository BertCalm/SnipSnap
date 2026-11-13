"""Stage the checked study in the existing Site without replacing prior auditions."""
import argparse
import shutil
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--output',type=Path,required=True);p.add_argument('--site',type=Path,required=True);a=p.parse_args()
assert (a.site/'.openai/hosting.json').is_file()
target=a.site/'dist/snare-reset';target.mkdir(exist_ok=True)
shutil.copytree(a.output/'audio',target/'audio',dirs_exist_ok=True)
for name in ['index.html','manifest.json','model.json','report.json','comparison.png','study.md','synthetic-snare-study.zip']:
    shutil.copy2(a.output/name,target/name)
for relative,href in [('dist/index.html','snare-reset/'),('dist/synthesis/index.html','../snare-reset/')]:
    p=a.site/relative;s=p.read_text()
    if 'id="snare-reset-link"' not in s:
        marker='<body>'
        if marker not in s:
            raise ValueError('Cannot find page body: '+str(p))
        s=s.replace(marker,marker+'<section id="snare-reset-link" style="padding:20px;border:1px solid #688159;margin:16px 0"><h2>New snare study: attack, body and tail rebuilt</h2><p>Compare directly with the live-drum clip you supplied.</p><a href="'+href+'">Open the snare reset audition →</a></section>',1)
        p.write_text(s)
print('Staged snare-reset/ and links; previous auditions retained.')
