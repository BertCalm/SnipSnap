#!/usr/bin/env python3
"""Reproduce the accepted audition core, then add only native host adaptation.

All upstream extraction work executes in a temporary directory. The canonical
parameters.json is verified, never overwritten by the extraction. --check does
not modify repository files.
"""
from pathlib import Path
import argparse,difflib,json,re,shutil,subprocess,sys,tempfile
root=Path(__file__).resolve().parent
parser=argparse.ArgumentParser();parser.add_argument('--check',action='store_true');args=parser.parse_args()
with tempfile.TemporaryDirectory(prefix='snipsnap-aquarium-core-') as directory:
    task=Path(directory)
    shutil.copytree(root/'upstream',task/'upstream')
    shutil.copyfile(root/'extract_audition.py',task/'extract.py')
    (task/'src/DSP/Effects').mkdir(parents=True)
    subprocess.run([sys.executable,str(task/'extract.py')],check=True,stdout=subprocess.DEVNULL)
    upstream_meta=json.loads((task/'parameters.json').read_text())
    canonical=json.loads((root/'parameters.json').read_text())
    assert list(canonical)==list(upstream_meta)
    assert all({k:v for k,v in values.items() if k in ('min','max','default')}==upstream_meta[name] for name,values in canonical.items())
    for source in (task/'src').rglob('*.h'):
        target=root/'private'/source.relative_to(task/'src')
        text=source.read_text().replace('namespace xoceanus','namespace snipsnap::aquarium::detail')
        if source.name=='AquaticFXSuite.h':
            text=text.replace('    void setSeed(uint32_t seed);','    void setSeed(uint32_t seed);\n    void applyParameterValues(const float* values) noexcept;')
            members={name:member for name,member in re.findall(r'if \(id == "([^"]+)"\) \{ params\.(\w+) = ',text)}
            assignment='\ninline void AquaticFXSuite::applyParameterValues(const float* values) noexcept\n{\n'+''.join(f'    params.{members[name]} = values[{i}];\n' for i,name in enumerate(canonical))+'}\n'
            text=text.replace('\n} // namespace snipsnap::aquarium::detail',assignment+'\n} // namespace snipsnap::aquarium::detail')
            text=text.replace('    void setParameter(const std::string& id, float value);\n','')
            start=text.index('inline void AquaticFXSuite::setParameter(')
            end=text.index('inline void AquaticFXSuite::setSeed(',start)
            text=text[:start]+text[end:]
        if args.check:
            if not target.exists() or target.read_text()!=text:raise SystemExit(f'Stale Aquarium core: {target.relative_to(root)}')
        else:
            target.parent.mkdir(parents=True,exist_ok=True);target.write_text(text)
    # Human-reviewable diff includes upstream→audition corrections and tiny host adaptation.
    diff=''
    for path in ['DSP/Effects/AquaticFXSuite.h','DSP/Effects/MultibandCompressor.h','DSP/FastMath.h']:
        original=(root/'upstream/Source'/path).read_text().splitlines(keepends=True)
        adapted=(root/'private'/path).read_text().splitlines(keepends=True)
        diff+=''.join(difflib.unified_diff(original,adapted,fromfile='upstream/Source/'+path,tofile='private/'+path))
    patch=root/'UPSTREAM_CHANGES.diff'
    if args.check:
        if not patch.exists() or patch.read_text()!=diff:raise SystemExit('Stale Aquarium source diff')
    else:patch.write_text(diff)
