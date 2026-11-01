"""Admission/ownership checks only. Actual audio fidelity uses the native fixture suite."""
import contextlib
import io
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from prepare_phone import stage
from verify_phone import native_sources


class PhoneAdmissionTest(unittest.TestCase):
    def setUp(self):
        quiet=contextlib.redirect_stdout(io.StringIO());quiet.__enter__();self.addCleanup(quiet.__exit__,None,None,None)
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name);self.source=self.root/'input';self.source.mkdir();self.target=self.root/'output'
        payload=b'opaque test payload';(self.source/'test.cpcb').write_bytes(payload)
        self.manifest={'format':'crease-playable-pcm-v1','phoneStudy':1,'approximationPassed':True,
            'accuracy':{'total':1628,'passed':1628,'failed':0},'cacheIdentity':{'test':'admission-only'},
            'container':{'file':'test.cpcb','pcmBytes':2,'compressedBytes':len(payload),
                'compressedSha256':hashlib.sha256(payload).hexdigest(),'pcmSha256':'test-pcm'}}
        self.native={'receiptVersion':2,'builtFromCurrentCheckout':True,'result':'passed','cases':1628,
            'output48kCases':24,'pcmSha256':'test-pcm','nativeSources':native_sources()}
        self.save('manifest.json',self.manifest);self.save('report.json',{'approximationPassed':True})
        self.save('reader-verification.json',{'result':'passed','cacheIdentity':self.manifest['cacheIdentity']})
        self.save('native-verification.json',self.native)
    def save(self,name,data):
        (self.source/name).write_text(json.dumps(data))
    def test_stage_and_replace_only_owned_output(self):
        stage(self.source,self.target);stage(self.source,self.target)
        self.assertEqual({'manifest.json','test.cpcb'},{p.name for p in self.target.iterdir()})
        self.assertEqual((self.source/'test.cpcb').read_bytes(),(self.target/'test.cpcb').read_bytes())
    def test_failed_candidate_refuses(self):
        self.manifest['approximationPassed']=False;self.save('manifest.json',self.manifest)
        with self.assertRaisesRegex(ValueError,'Failed cache cannot enter'): stage(self.source,self.target)
    def test_stale_native_code_and_unbuilt_receipts_refuse(self):
        self.native['nativeSources']={};self.save('native-verification.json',self.native)
        with self.assertRaisesRegex(ValueError,'Native sources changed'): stage(self.source,self.target)
        self.native['receiptVersion']=1;self.save('native-verification.json',self.native)
        with self.assertRaisesRegex(ValueError,'must build the current checkout'): stage(self.source,self.target)
    def test_different_cache_receipt_refuses(self):
        self.save('reader-verification.json',{'result':'passed','cacheIdentity':{}})
        with self.assertRaisesRegex(ValueError,'different cache'): stage(self.source,self.target)
    def test_input_overlap_never_deletes_captures(self):
        for target in [self.source,self.source/'nested',self.root]:
            with self.assertRaisesRegex(ValueError,'must not overlap'): stage(self.source,target)
        self.assertTrue((self.source/'test.cpcb').is_file())
    def test_unrelated_output_files_survive(self):
        self.target.mkdir();note=self.target/'notes.txt';note.write_text('keep')
        with self.assertRaisesRegex(ValueError,'not a study output'): stage(self.source,self.target)
        self.assertEqual('keep',note.read_text())
        note.unlink();stage(self.source,self.target);note.write_text('keep')
        with self.assertRaisesRegex(ValueError,'unrelated files'): stage(self.source,self.target)
        self.assertEqual('keep',note.read_text())
    def test_limits_apply_before_reading_payload(self):
        self.manifest['container']['pcmBytes']=32*1024**2+1;self.save('manifest.json',self.manifest)
        with self.assertRaisesRegex(ValueError,'byte limits'): stage(self.source,self.target)


if __name__=='__main__': unittest.main()
