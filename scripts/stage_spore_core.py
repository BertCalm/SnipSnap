"""Stage the approved source and kick study on the existing owner-private Site."""
import argparse
import shutil
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--output',type=Path,required=True);p.add_argument('--site',type=Path,required=True);a=p.parse_args()
assert (a.site/'.openai/hosting.json').is_file()
target=a.site/'dist/approved-core';target.mkdir(exist_ok=True)
for f in a.output.iterdir():
    if f.name=='spore-core-offline.zip':continue
    if f.is_dir():shutil.copytree(f,target/f.name,dirs_exist_ok=True)
    else:shutil.copy2(f,target/f.name)
for rel,url in [('dist/index.html','approved-core/'),('dist/snare-reset/index.html','../approved-core/'),('dist/synthesis/index.html','../approved-core/')]:
    p=a.site/rel;s=p.read_text()
    if 'id="approved-core-link"' not in s:
        assert '<body>' in s
        s=s.replace('<body>','<body><section id="approved-core-link" style="padding:20px;border:1px solid #688159;margin:16px 0"><h2>Approved snare port + new kick study</h2><p>The accepted snare is preserved in Kotlin. Compare the new kick and download the native instrument.</p><a href="'+url+'">Open the latest core audition →</a></section>',1)
    if 'snare-reset' in rel:
        s=s.replace('the result still needs your listening verdict.', 'the owner approved its sound on 14 November. The Kotlin port is linked above.')
        s=s.replace('The rebuilt snare has not passed listening acceptance or native instrument integration.', 'This Python source has since received owner approval; the current Kotlin port and workflow evidence are linked above.')
    p.write_text(s)
print('Staged approved-core/; historical auditions and accepted source audio retained.')
