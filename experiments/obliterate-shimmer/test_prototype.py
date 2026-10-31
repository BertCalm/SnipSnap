"""Signal probes for the shimmer pre-stage; independent of its artifact exporter."""
import math
import unittest
import numpy as np
import prototype as p

class ShimmerTest(unittest.TestCase):
    def test_zero_is_exact_same_object_and_shape(self):
        for channels in [1,2]:
            x=np.array([[-0.,.2],[-.7,0.]])[:,:channels]
            r=p.render(x,0.)
            self.assertIs(r.audio,x)
            np.testing.assert_array_equal(r.audio.view(np.uint64),x.view(np.uint64))
            self.assertEqual(r.diagnostics['tailFrames'],0)

    def test_invalid_pcm_and_controls_are_refused(self):
        for mix in [True,float('nan'),float('inf'),-.01,.501]:
            with self.assertRaises(ValueError):p.render(np.zeros((8,1)),mix)
        for x in [np.zeros((0,1)),np.zeros((8,3)),np.ones((8,1),dtype=np.int32),np.array([[np.nan]]),np.array([[1.01]]),np.zeros((396901,1))]:
            with self.assertRaises(ValueError):p.render(x,.3)
        with self.assertRaises(ValueError):p.render(np.zeros((8,1)),.3,pitched='yes')

    def test_silence_finite_tail_and_no_hidden_state(self):
        x=np.zeros((500,1));a=p.render(x,.3);b=p.render(x,.3)
        self.assertEqual(a.audio.shape,(500+p.TAIL_FRAMES,2))
        self.assertFalse(np.any(a.audio));np.testing.assert_array_equal(a.audio,b.audio)
        self.assertEqual(a.diagnostics['statePeak'],0.)

    def test_octave_peaks_and_disclosed_grain_tuning_error(self):
        n=np.arange(44100)
        for hz in [220.,440.,233.]:
            y=p.octave_up(.2*np.sin(2*np.pi*hz*n/p.RATE))[10000:,0]
            window=np.hanning(len(y));freq=np.fft.rfftfreq(len(y),1/p.RATE)[np.argmax(np.abs(np.fft.rfft(y*window)))]
            self.assertLess(abs(1200*math.log2(freq/(hz*2))),40.)

    def test_prefilter_reduces_folded_high_tone_without_alias_free_claim(self):
        x=.2*np.sin(2*np.pi*16000*np.arange(44100)/p.RATE)
        raw=p.octave_up(x,False)[10000:];filtered=p.octave_up(x,True)[10000:]
        reduction=20*np.log10(np.sqrt(np.mean(raw*raw))/np.sqrt(np.mean(filtered*filtered)))
        self.assertGreater(reduction,35.)

    def test_dry_attack_is_unchanged_before_first_wet_return(self):
        x=np.zeros((10000,2));x[:200]=np.random.default_rng(4).normal(0,.04,(200,2))
        y=p.render(x,.3)
        np.testing.assert_array_equal(y.audio[:int(min(p.DELAYS))],x[:int(min(p.DELAYS))])
        self.assertEqual(y.diagnostics['guardGain'],1.)

    def test_quiet_scaling_and_wet_send_are_linear(self):
        x=np.random.default_rng(3).normal(0,.03,(8000,2))
        a=p.render(x,.15);b=p.render(x,.3);q=p.render(x*.1,.3)
        np.testing.assert_allclose(b.wet,2*a.wet,atol=1e-14,rtol=1e-12)
        np.testing.assert_allclose(q.audio,.1*b.audio,atol=1e-14,rtol=1e-12)
        np.testing.assert_allclose(a.audio-a.wet,b.audio-b.wet,atol=1e-14)

    def test_stereo_excitation_is_retained_and_room_crossfeed_is_real(self):
        x=np.zeros((6000,2));x[0,0]=.5
        l=p.render(x,.3);r=p.render(x[:,::-1].copy(),.3)
        self.assertGreater(np.max(np.abs(l.wet[:,1])),1e-5)
        self.assertFalse(np.array_equal(l.wet,r.wet))
        self.assertFalse(np.array_equal(l.wet[:,0],l.wet[:,1]))

    def test_room_and_shimmer_are_different_with_same_clock_and_gain(self):
        x=np.sin(2*np.pi*220*np.arange(16000)/p.RATE)[:,None]*.1
        a=p.render(x,.3,False);b=p.render(x,.3,True)
        self.assertGreater(np.sqrt(np.mean((a.wet-b.wet)**2)),.001)
        self.assertGreater(b.diagnostics['shiftedFeedbackPeak'],0.)
        self.assertEqual(a.diagnostics['shiftedFeedbackPeak'],0.)

    def test_feedback_bound_and_unforced_release_before_fade(self):
        x=np.zeros((44100,2));x[0]=.5
        r=p.render(x,.3)
        self.assertLess(np.linalg.norm(p.HADAMARD,np.inf)*p.FEEDBACK,1.)
        early=np.sqrt(np.mean(r.wet[2000:12000]**2));late=np.sqrt(np.mean(r.wet[40000:44000]**2))
        self.assertLess(20*np.log10(max(late,1e-30)/early),-90.)
        self.assertLess(r.diagnostics['last100msStatePeak'],1e-10)
        self.assertFalse(r.diagnostics['limiterInsideTank'])

    def test_block_geometry_matches_smaller_causal_blocks(self):
        x=np.pad(np.random.default_rng(6).normal(0,.03,(5000,2)),((0,12000),(0,0)))
        a=p._room(x,True,256)[0];b=p._room(x,True,64)[0]
        np.testing.assert_allclose(a,b,atol=1e-14,rtol=1e-11)

    def test_dc_releases_and_end_is_zero(self):
        x=np.ones((44100,1))*.2;r=p.render(x,.3)
        self.assertLess(np.max(np.abs(np.mean(r.wet[35000:44000],axis=0))),1e-5)
        self.assertEqual(float(np.max(np.abs(r.audio[-1]))),0.)

    def test_peak_protection_only_attenuates_and_maximum_input_is_bounded(self):
        x=np.ones((round(p.MAX_INPUT_SECONDS*p.RATE),2))*.99
        r=p.render(x,.5)
        self.assertTrue(np.all(np.isfinite(r.audio)))
        self.assertLessEqual(np.max(np.abs(r.audio)),.98000000001)
        self.assertLessEqual(r.diagnostics['guardGain'],1.)
        self.assertEqual(len(r.audio),len(x)+p.TAIL_FRAMES)

if __name__=='__main__':unittest.main()
