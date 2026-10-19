"""Optional NumPy/SciPy regression checks for the curated Confluence fixtures."""

import importlib.util
import json
from pathlib import Path
import unittest

import numpy as np


SCRIPTS = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("confluence_signal_review", SCRIPTS / "review-confluence-audio.py")
review = importlib.util.module_from_spec(spec)
spec.loader.exec_module(review)
FIXTURES = SCRIPTS.parent / "reference/fixtures/confluence"
MANIFEST = json.loads((FIXTURES / "manifest.json").read_text())


class FixtureBoundaryRegression(unittest.TestCase):
    def test_current_selected_vowels_pass(self):
        self.assertTrue(review.review_fixture_boundaries(FIXTURES)["passed"])

    def test_rejected_male1_crop_fails_amplitude_and_pitch_checks(self):
        source = dict(MANIFEST["retiredVowelMale"]["previousSource"])
        source["boundaryGuard"] = MANIFEST["sources"][0]["boundaryGuard"]
        rate, original = review.read(FIXTURES / source["originalPath"])
        original = original[source["originalCropStartFrame"]:source["originalCropEndFrame"]]
        gain = 10 ** (-3 / 20) / np.max(np.abs(original))
        canonical = np.rint(original * gain * 32767) / 32768
        result = review.fixture_boundary(source, rate, canonical)
        self.assertFalse(result["passed"])
        self.assertGreater(result["tailToSustainRmsRatio"], .8)
        self.assertGreater(result["maxHighEnergyTailPitchExcursionCents"], 1000)
        self.assertEqual(len(result["failures"]), 2)

    def test_stale_selected_end_fails_even_when_canonical_file_has_quiet_tail(self):
        source = dict(MANIFEST["sources"][0])
        rate, canonical = review.read(FIXTURES / source["path"])
        source["phraseEndFrame"] = MANIFEST["retiredVowelMale"]["previousSource"]["phraseEndFrame"]
        result = review.fixture_boundary(source, rate, canonical)
        self.assertFalse(result["passed"])
        self.assertGreater(result["tailToSustainRmsRatio"], .15)


if __name__ == "__main__":
    unittest.main()
