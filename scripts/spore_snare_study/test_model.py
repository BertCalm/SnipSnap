"""Focused tests for the Python sound study; these do not establish app integration."""
import json
from pathlib import Path
import unittest
import numpy as np
from model import RATE, SECONDS, render

class SnareStudyTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.model = json.loads(Path(__file__).with_name('model.json').read_text())

    def test_deterministic_distinct_restrikes_and_json_replay(self):
        x = render(self.model, take=0)
        np.testing.assert_array_equal(x, render(json.loads(json.dumps(self.model)), take=0))
        for take in [1, 2]:
            y = render(self.model, take=take)
            self.assertLess(abs(np.corrcoef(x, y)[0, 1]), .95)

    def test_velocity_changes_timbre_and_preserves_order(self):
        for take in range(3):
            hits = [render(self.model, take=take, velocity=v) for v in [.3, .65, 1.]]
            self.assertLess(max(abs(hits[0])), max(abs(hits[1])))
            self.assertLess(max(abs(hits[1])), max(abs(hits[2])))
            f = np.fft.rfftfreq(len(hits[0]), 1 / RATE)
            brightness = []
            for h in hits:
                p = abs(np.fft.rfft(h)) ** 2
                brightness.append(p[f > 3000].sum() / p.sum())
            self.assertGreater(brightness[-1], brightness[0])
        self.assertTrue(np.all(render(self.model, velocity=0) == 0))

    def test_finite_headroom_dc_and_tail_at_control_edges(self):
        cases = [{name: v} for name in ['tune', 'strike', 'body', 'wires'] for v in [0., 1.]]
        cases += [dict(tune=v, strike=v, body=v, wires=v) for v in [0., 1.]]
        cases += [dict(take=i) for i in range(24)]
        for c in cases:
            x = render(self.model, **c)
            self.assertEqual(len(x), round(SECONDS * RATE))
            self.assertTrue(np.isfinite(x).all())
            self.assertLess(max(abs(x)), 1.)
            self.assertLess(abs(x.mean()), .001)
            self.assertEqual(x[-1], 0.)

    def test_stems_recombine_and_controls_change_shape(self):
        x = render(self.model)
        summed = sum(render(self.model, stem=s) for s in ['attack', 'body', 'tail'])
        np.testing.assert_allclose(x, summed, atol=1e-12)
        for name in ['tune', 'strike', 'body', 'wires']:
            lo = render(self.model, **{name: 0.})
            hi = render(self.model, **{name: 1.})
            self.assertGreater(np.linalg.norm(lo / np.linalg.norm(lo) - hi / np.linalg.norm(hi)), .05)
        with self.assertRaises(ValueError):
            render(self.model, velocity=float('nan'))
        with self.assertRaises(ValueError):
            render({**self.model, 'version': 99})

if __name__ == '__main__':
    unittest.main()
