"""Numerical checks for the audition-only Apsis voice study."""
import unittest
import numpy as np
import apsis_voice_study as a


class VoiceStudyTest(unittest.TestCase):
    def test_pitch_probe_rejects_noise_and_tracks_an_independent_harmonic_fixture(self):
        t = np.arange(a.RATE) / a.RATE
        for hz in (65.4064, 261.6256, 1046.5023):
            x = np.sin(2 * np.pi * hz * t) + .6 * np.sin(6 * np.pi * hz * t)
            found, confidence = a.pitch_probe(x, .2)
            self.assertLess(abs(1200 * np.log2(found / hz)), 1.)
            self.assertGreater(confidence, .99)
        noise = np.random.default_rng(42).uniform(-1, 1, a.RATE)
        _, confidence = a.pitch_probe(noise, .2)
        self.assertLess(confidence, .1)

    def test_motion_closes_and_cache_does_not_depend_on_audio_parameters(self):
        seconds = 6 * .6 * (2 / .6) ** .4
        a.motion.cache_clear()
        first, residual = a.motion(.85, .4, .55, seconds, a.RATE * 4, True)
        again, _ = a.motion(.85, .4, .55, seconds, a.RATE * 4, True)
        self.assertIs(first, again)
        self.assertFalse(first.flags.writeable)
        self.assertLess(residual, 1e-11)
        np.testing.assert_allclose(first[0], first[-1], atol=1e-12)

    def test_all_free_networks_contract_and_growth_injection_trips_the_probe(self):
        rate = a.RATE * 4
        fields, _ = a.motion(.65, .4, .55, .1, rate)
        for voice in a.VOICES:
            idx, co, si, rho, rad = a.profile(voice, 261.6256, rate)
            state = np.linspace(-1., 1., 72)
            state[54:60] = 100.
            state[60:66] = 0.
            state[66:72] = .5
            before = np.sum(state[:54] ** 2)
            _, rise = a.run_cycle(fields, 8192, rate, idx, 261.6256, co, si, rho, rad,
                                  1., 1., .65, 0., 42, state, True)
            self.assertLessEqual(rise, 1e-12)
            self.assertLess(np.sum(state[:54] ** 2), before)
        rho = rho.copy()
        rho[0] = 1.0001
        state = np.zeros(72)
        state[0] = 1.
        _, rise = a.run_cycle(fields, 1024, rate, idx, 261.6256, co, si, rho, rad,
                              1., 0., .65, 0., 42, state, True)
        self.assertGreater(rise, .0001)

    def test_render_is_deterministic_after_other_voices_and_cache_eviction(self):
        first, _ = a.render('TETHER', midi=48, velocity=.43)
        a.render('CORONA', midi=72)
        a.motion.cache_clear()
        again, _ = a.render('TETHER', midi=48, velocity=.43)
        np.testing.assert_array_equal(first, again)
        self.assertTrue(np.isfinite(first).all())

    def test_macro_settling_handles_stale_nonfinite_and_unknown_controls(self):
        values = a.settled(dict(STRETCH=float('nan'), DISTANCE=2., ALIGN=-1., UNKNOWN=1.))
        self.assertEqual(values['STRETCH'], a.MACROS['STRETCH'])
        self.assertEqual(values['DISTANCE'], 1.)
        self.assertEqual(values['ALIGN'], 0.)
        self.assertNotIn('UNKNOWN', values)

    def test_zero_velocity_is_exact_silence(self):
        for voice in a.VOICES:
            x, _ = a.render(voice, velocity=0.)
            self.assertFalse(np.any(x))

    def test_invalid_note_velocity_and_rate_are_refused(self):
        for arguments in (dict(midi=35), dict(midi=85), dict(velocity=float('nan')),
                          dict(velocity=-.1), dict(oversample=1)):
            with self.assertRaises(ValueError):
                a.render('PASSAGE', **arguments)


if __name__ == '__main__':
    unittest.main()
