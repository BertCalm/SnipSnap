"""Focused signal tests. Run: python3 -m unittest discover -s experiments/obliterate-core -v.

These are Python-host measurements, not Kotlin/phone timings or listening judgements.
The spectral test reports both high-rate-reference residual and off-harmonic
energy: a waveform residual alone also includes filter discretization error.
"""

from dataclasses import replace
import json
import math
import unittest

import numpy as np

from prototype import (MAX_GRAINS, MAX_INPUT_SECONDS, PEAK_CEILING, SAMPLE_RATE,
                       SHAPE_CONSTANTS, TAIL_FRAMES, GrainEvent, Settings,
                       _chop_gains, _fragments, _fuzz, _schedule, render_core)


def rms(audio):
    return float(np.sqrt(np.mean(np.asarray(audio)**2)))


def tone(hz, seconds=0.5, amplitude=0.15):
    return (amplitude * np.sin(2 * np.pi * hz * np.arange(round(seconds * SAMPLE_RATE)) / SAMPLE_RATE))[:, None]


class CoreContractTest(unittest.TestCase):
    def test_zero_is_exact_same_object_bypass_for_both_channel_shapes(self):
        rng = np.random.default_rng(13)
        for dtype in (np.float32, np.float64):
            for channels in (1, 2):
                source = rng.uniform(-0.5, 0.5, (441, channels)).astype(dtype)
                for stages in (1, 2, 3):
                    result = render_core(source, Settings(amount=0), stages)
                    self.assertIs(result.audio, source)
                    self.assertEqual(result.audio.dtype, dtype)
                    np.testing.assert_array_equal(result.audio, source)
                    self.assertEqual(result.diagnostics["tail_frames"], 0)
                    self.assertEqual(result.diagnostics["guard_gain"], 1)

    def test_settings_and_pcm_are_validated(self):
        for kwargs in ({"amount": float("nan")}, {"amount": -0.1}, {"amount": 1.1},
                       {"bpm": 0}, {"bpm": float("inf")}, {"division": 3},
                       {"seed": -1}, {"seed": 2**32}, {"amount": True}):
            with self.subTest(kwargs=kwargs), self.assertRaises(ValueError):
                Settings(**kwargs)
        for source in (np.zeros((0, 2)), np.zeros((20, 3)), np.zeros(20),
                       np.ones((20, 1)) * 1.01, np.full((20, 1), np.nan),
                       np.zeros((20, 1), dtype=np.int16),
                       np.zeros((round(MAX_INPUT_SECONDS * SAMPLE_RATE) + 1, 1))):
            with self.assertRaises(ValueError):
                render_core(source)
        with self.assertRaises(ValueError):
            render_core(tone(220), stages=4)

    def test_silence_finite_fixed_tail_and_no_invented_energy(self):
        for channels in (1, 2):
            for amount in (0.35, 0.7, 1):
                for stages in (1, 2, 3):
                    source = np.zeros((1000, channels))
                    result = render_core(source, Settings(amount), stages)
                    self.assertEqual(result.audio.shape, (1000 + TAIL_FRAMES, 2))
                    self.assertTrue(np.isfinite(result.audio).all())
                    self.assertEqual(np.max(np.abs(result.audio)), 0)
                    self.assertEqual(result.diagnostics["guard_gain"], 1)
                    self.assertFalse(result.diagnostics["feedback"])

    def test_fresh_state_audio_and_inventory_are_deterministic(self):
        source = tone(330)
        a = render_core(source)
        render_core(tone(900), Settings(1, 143, 32, 8))
        b = render_core(source)
        np.testing.assert_array_equal(a.audio, b.audio)
        self.assertEqual(a.diagnostics["grain_events"], b.diagnostics["grain_events"])
        self.assertEqual(a.diagnostics["event_schedule_sha256"], b.diagnostics["event_schedule_sha256"])
        changed = render_core(source, replace(Settings(), seed=20))
        self.assertNotEqual(a.diagnostics["event_schedule_sha256"], changed.diagnostics["event_schedule_sha256"])
        self.assertGreater(rms(a.audio - changed.audio), 0.001)
        json.dumps(a.diagnostics, allow_nan=False)
        json.dumps(SHAPE_CONSTANTS, allow_nan=False)

    def test_all_amounts_move_the_signal_and_keep_bounded_stereo(self):
        source = tone(164.81, 0.7, 0.4) * np.exp(-np.arange(round(0.7 * SAMPLE_RATE))[:, None] / 9000)
        results = [render_core(source, Settings(amount)).audio for amount in (0.35, 0.7, 1)]
        for audio in results:
            self.assertTrue(np.isfinite(audio).all())
            self.assertLessEqual(np.max(np.abs(audio)), PEAK_CEILING + 1e-12)
            self.assertGreater(rms(audio[:, 0] - audio[:, 1]), 0.001)
            self.assertEqual(audio[-1, 0], 0)
            self.assertEqual(audio[-1, 1], 0)
            self.assertLess(np.max(np.abs(audio[-4410:])), 1e-7)
            self.assertLess(np.max(np.abs(np.mean(audio, axis=0))), 0.005)
        self.assertGreater(rms(results[0] - results[1]), 0.005)
        self.assertGreater(rms(results[1] - results[2]), 0.005)

    def test_guard_only_attenuates_and_is_linked(self):
        source = np.random.default_rng(3).uniform(-1, 1, (4000, 2))
        result = render_core(source, Settings(1))
        gain = result.diagnostics["guard_gain"]
        expected = min(1.0, PEAK_CEILING / result.diagnostics["pre_guard_peak"])
        self.assertEqual(gain, expected)
        self.assertGreater(gain, 0)
        self.assertLessEqual(gain, 1)
        quiet = render_core(source * 1e-3, Settings(0.35))
        self.assertEqual(quiet.diagnostics["guard_gain"], 1)


class FuzzTest(unittest.TestCase):
    def test_full_wave_stage_produces_octave_not_just_odd_clipping(self):
        source = tone(440, 1, 0.2)
        audio = _fuzz(source, 1)[4410:-4410, 0]
        spectrum = np.abs(np.fft.rfft(audio))
        frequencies = np.fft.rfftfreq(len(audio), 1 / SAMPLE_RATE)
        fundamental = spectrum[np.argmin(np.abs(frequencies - 440))]
        octave = spectrum[np.argmin(np.abs(frequencies - 880))]
        self.assertGreater(octave, fundamental * 2)

    def test_dc_is_removed_before_capture_and_filters_release_in_budget(self):
        source = np.full((SAMPLE_RATE, 2), 0.1)
        for stages in (1, 2, 3):
            result = render_core(source, Settings(1), stages)
            # Probe settled input, not a whole-file mean that cancels transients.
            self.assertLess(np.max(np.abs(np.mean(result.audio[SAMPLE_RATE // 2:SAMPLE_RATE], axis=0))), 1e-5)
            self.assertLess(np.max(np.abs(result.audio[-SAMPLE_RATE // 10:])), 1e-7)

    def test_nonlinearity_is_intentionally_level_sensitive_without_makeup(self):
        source = tone(220, 0.5, 0.4)
        loud, quiet = (_fuzz(source, 1), _fuzz(source * 0.1, 1))
        # Absolute drive changes saturation, unlike an input-peak-relative shaper.
        self.assertGreater(rms(quiet - 0.1 * loud), 0.005)
        self.assertLess(rms(quiet), rms(loud))

    def test_oversampling_against_32x_reference_and_alias_bins(self):
        source = tone(6000, 1, 0.15)
        outputs = {factor: _fuzz(source, 1, factor)[4410:-4410, 0] for factor in (1, 8, 32)}
        errors = {factor: rms(outputs[factor] - outputs[32]) for factor in (1, 8)}
        improvement_db = 20 * math.log10(errors[1] / errors[8])
        self.assertGreater(improvement_db, 20, (errors, improvement_db))

        def off_harmonic_ratio(audio):
            spectrum = np.abs(np.fft.rfft(audio))**2
            frequencies = np.fft.rfftfreq(len(audio), 1 / SAMPLE_RATE)
            keep = np.ones(len(spectrum), dtype=bool)
            bin_hz = SAMPLE_RATE / len(audio)
            for harmonic in range(4):
                keep &= np.abs(frequencies - harmonic * 6000) > 2 * bin_hz
            return math.sqrt(float(np.sum(spectrum[keep]) / np.sum(spectrum)))

        alias_1, alias_8 = (off_harmonic_ratio(outputs[factor]) for factor in (1, 8))
        self.assertGreater(alias_1, 0.01)
        self.assertLess(alias_8, alias_1 * 0.1, (alias_1, alias_8))
        # Two-tone reference residual includes intermodulation and filter errors.
        two_tone = tone(3700, 0.6, 0.07) + tone(6100, 0.6, 0.06)
        yy = {factor: _fuzz(two_tone, 1, factor)[4410:-4410] for factor in (1, 8, 32)}
        self.assertLess(rms(yy[8] - yy[32]), rms(yy[1] - yy[32]) * 0.1)
        print("\nPython alias probe:", json.dumps({
            "single_tone_reference_residual_rms": errors,
            "residual_improvement_db": improvement_db,
            "off_harmonic_rms_ratio_1x": alias_1,
            "off_harmonic_rms_ratio_8x": alias_8,
            "two_tone_reference_residual_rms_1x": rms(yy[1] - yy[32]),
            "two_tone_reference_residual_rms_8x": rms(yy[8] - yy[32]),
        }, sort_keys=True))


class CapturedFragmentsTest(unittest.TestCase):
    def test_schedule_reads_only_available_recent_history_and_fits_tail(self):
        frames = round(MAX_INPUT_SECONDS * SAMPLE_RATE)
        for amount in (0.35, 0.7, 1):
            for bpm, division in ((40, 4), (123, 16), (240, 32)):
                events = _schedule(frames, Settings(amount, bpm, division))
                self.assertGreater(len(events), 0)
                self.assertLessEqual(len(events), MAX_GRAINS)
                for event in events:
                    lower = min(event.source_first_frame, event.source_last_frame)
                    upper = max(event.source_first_frame, event.source_last_frame)
                    self.assertGreaterEqual(lower, max(0, event.onset_frame - round(0.320 * SAMPLE_RATE)) - 1e-8)
                    self.assertLess(math.floor(upper) + 1, event.onset_frame)
                    self.assertLess(event.onset_frame, frames)
                    self.assertLessEqual(event.onset_frame + event.length_frames, frames + TAIL_FRAMES)
                    self.assertAlmostEqual(abs(event.source_last_frame - event.source_first_frame),
                                           (event.length_frames - 1) * event.pitch_ratio, places=8)
                    self.assertEqual(event.reverse, event.source_last_frame < event.source_first_frame)

    def test_early_short_inputs_do_not_truncate_outside_allocations(self):
        for frames in (1, 2, 31, 100, 700, 2000):
            result = render_core(np.ones((frames, 1)) * 0.1, Settings(1, 240, 32))
            self.assertEqual(len(result.audio), frames + TAIL_FRAMES)
            self.assertTrue(np.isfinite(result.audio).all())

    def test_linear_fragment_and_chop_stages_preserve_quiet_scaling(self):
        source = np.pad(np.repeat(tone(330, 0.5), 2, axis=1), ((0, TAIL_FRAMES), (0, 0)))
        settings = Settings(0.7)
        events = _schedule(round(0.5 * SAMPLE_RATE), settings)
        a, b = _fragments(source, settings, events), _fragments(source * 0.01, settings, events)
        np.testing.assert_allclose(b, a * 0.01, rtol=1e-11, atol=1e-14)
        gains = _chop_gains(len(a), settings)
        np.testing.assert_allclose(b * gains, a * gains * 0.01, rtol=1e-11, atol=1e-14)

    def test_stereo_is_never_folded_and_antiphase_drives_capture(self):
        mono = tone(330, 0.5)
        left_only = np.pad(np.concatenate((mono, mono * 0), axis=1), ((0, TAIL_FRAMES), (0, 0)))
        settings = Settings(0.7)
        events = _schedule(len(mono), settings)
        result = _fragments(left_only, settings, events)
        self.assertEqual(np.max(np.abs(result[:, 1])), 0)
        antiphase = np.pad(np.concatenate((mono, -mono), axis=1), ((0, TAIL_FRAMES), (0, 0)))
        centered = tuple(replace(event, balance=0) for event in events)
        memory = _fragments(antiphase, settings, centered)
        np.testing.assert_allclose(memory[:, 0], -memory[:, 1], atol=1e-14)
        self.assertGreater(rms(memory), 0.01)
        # Full-wave fuzz intentionally makes an even common component. It must
        # still respond to antiphase audio instead of taking a cancelling fold.
        full = render_core(np.concatenate((mono, -mono), axis=1), settings)
        self.assertGreater(rms(full.audio), 0.005)

    def test_reverse_pitch_is_real_and_window_boundaries_are_soft(self):
        source = np.zeros((30000, 2))
        source[:8000] = np.repeat(tone(440, 8000 / SAMPLE_RATE, 0.2), 2, axis=1)
        event = GrainEvent(15000, 2000, 7000, 3002, 2, 12, True, 0)
        settings = Settings(1)
        with_grain = _fragments(source, settings, (event,))
        without = _fragments(source, settings, ())
        isolated = (with_grain - without)[15000:17000, 0]
        frequencies = np.fft.rfftfreq(len(isolated), 1 / SAMPLE_RATE)
        strongest = frequencies[np.argmax(np.abs(np.fft.rfft(isolated)))]
        self.assertLess(abs(strongest - 880), 20)
        self.assertLess(abs(isolated[0]), 1e-12)
        self.assertLess(abs(isolated[1]), 1e-6)
        # A constant captured span makes the window edge visible independently
        # of the sine's own adjacent-sample slope.
        constant = np.ones((30000, 2)) * 0.2
        edge = _fragments(constant, settings, (event,)) - _fragments(constant, settings, ())
        self.assertLess(abs(edge[15001, 0] - edge[15000, 0]), 1e-6)


class StereoClockTest(unittest.TestCase):
    def test_120_bpm_sixteenths_are_125ms_and_channels_alternate(self):
        settings = Settings(1, 120, 16)
        gains = _chop_gains(SAMPLE_RATE, settings)
        for cycle in (2, 3, 4, 5):
            left_open = round((cycle * 0.125 + 0.020) * SAMPLE_RATE)
            right_open = round((cycle * 0.125 + 0.085) * SAMPLE_RATE)
            self.assertGreater(gains[left_open, 0], 0.999)
            self.assertEqual(gains[left_open, 1], 0)
            self.assertEqual(gains[right_open, 0], 0)
            self.assertGreater(gains[right_open, 1], 0.999)
        np.testing.assert_array_equal(gains[:round(0.018 * SAMPLE_RATE)], 1)

    def test_noninteger_clock_does_not_accumulate_rounded_period_drift(self):
        settings = Settings(1, 123, 32)
        step = 60 / 123 * 4 / 32
        gains = _chop_gains(12 * SAMPLE_RATE, settings)
        for cycle in (2, 50, 100, 190):
            opened = round((cycle * step + 0.010) * SAMPLE_RATE)
            closed = round((cycle * step + 0.8 * step) * SAMPLE_RATE)
            self.assertGreater(gains[opened, 0], 0.999)
            self.assertEqual(gains[closed, 0], 0)

    def test_edges_are_cosine_ramps_not_single_sample_discontinuities(self):
        settings = Settings(1, 240, 32)
        gains = _chop_gains(SAMPLE_RATE, settings)
        # sin^2 edge max slope pi/(2*edge*rate), with tolerance for engagement.
        delta = np.max(np.abs(np.diff(gains[4410:], axis=0)))
        self.assertLess(delta, 0.013)
        self.assertTrue(np.all(gains >= 0))
        self.assertTrue(np.all(gains <= 1))


if __name__ == "__main__":
    unittest.main(verbosity=2)
