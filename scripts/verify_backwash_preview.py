"""Verify the built trial APK before copying it into the download workspace."""
from pathlib import Path
import argparse,os,subprocess,zipfile,hashlib,json,re,shutil
repo=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--sdk', type=Path, default=os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT'))
parser.add_argument('--build-tools', default='35.0.0')
parser.add_argument('--output', type=Path, default=repo/'testkit/backwash-preview')
args=parser.parse_args()
if args.sdk is None:parser.error('Set ANDROID_HOME or pass --sdk.')
app=repo/'app/build/outputs/apk/backwashPreview/app-backwashPreview.apk'
test=repo/'app/build/outputs/apk/androidTest/backwashPreview/app-backwashPreview-androidTest.apk'
build=args.sdk/'build-tools'/args.build_tools
def run(*args):return subprocess.check_output([str(a) for a in args],text=True,stderr=subprocess.STDOUT)
signs=[run(build/'apksigner','verify','--verbose','--print-certs',p) for p in [app,test]]
certs=[re.search(r'Signer #1 certificate SHA-256 digest: (\w+)',s).group(1) for s in signs]
assert certs[0]==certs[1]
badging=run(build/'aapt2','dump','badging',app)
assert "name='com.snipsnap.app.backwashpreview'" in badging
assert "minSdkVersion:'29'" in badging
assert "application-label:'SnipSnap Backwash Preview'" in badging
assert "native-code: 'arm64-v8a'" in badging
with zipfile.ZipFile(app) as z:
 assert z.testzip() is None
 assert 'lib/arm64-v8a/libsnipsnap_surface.so' in z.namelist()
 dex=b''.join(z.read(p) for p in z.namelist() if re.fullmatch(r'classes\d*\.dex',p))
 assert b'Lcom/snipsnap/synth/BackwashPresets;' in dex
 fixture=json.loads((repo/'testkit/backwash-pushed-kit/factory-presets.json').read_text())
 names=sorted({x['name'] for x in fixture})
 assert len(names)==36
 assert {x['model'] for x in fixture}=={3,4}
 # New names are composed from a treatment prefix and voice suffix in Kotlin.
 # The exported registry and JVM tests verify the full names; DEX keeps their parts.
 assert all(name.encode() in dex or all(part.encode() in dex for part in name.split()) for name in names)
with zipfile.ZipFile(test) as z:
 dex=b''.join(z.read(p) for p in z.namelist() if re.fullmatch(r'classes\d*\.dex',p))
 assert b'backwashRestoresItsDrumVoiceAndSixControls' in dex
out=args.output;out.mkdir(parents=True,exist_ok=True)
copy=out/'snipsnap-backwash-preview.apk';shutil.copy2(app,copy)
report={'package':'com.snipsnap.app.backwashpreview','label':'SnipSnap Backwash Preview','minAndroid':10,'abi':'arm64-v8a','buildType':'development preview','apkBytes':copy.stat().st_size,'apkSha256':hashlib.sha256(copy.read_bytes()).hexdigest(),'certificateSha256':certs[0],'processingModel':4,'factoryPresets':names,'checks':{'apkSignaturesVerified':True,'testSigningIdentityMatches':True,'nativeAudioLibraryPresent':True,'factoryClassAndNamingPartsPresent':True,'targetedUiTestPackaged':True},'deviceUiTestRun':False,'phoneRenderTimingMeasured':False}
(out/'backwash-preview-verification.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(report,indent=2))
