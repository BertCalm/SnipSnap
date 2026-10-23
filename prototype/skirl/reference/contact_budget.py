"""Finite acoustic-input budgets for overlapping Skirl contact pulses.

This is an acoustic proxy, in squared input amplitude times seconds. It does
not calibrate radiated sound in joules and does not constrain modal output.

One event enters two source routes (disk/support). The pulse and routing
direction together have unit L2 energy before truncation. If x is the
already-accumulated source and p is the proposed contact, the extra source
energy from amplitude a is q*a*a + 2*c*a, where q=<p,p>, c=<x,p>.
Choosing the positive quadratic root accounts for coherent pulse overlap.
The coefficient is also capped at sqrt(budget): contact truncation or
destructive overlap must never increase an event's nominal amplitude.

The supported accumulation domain is nonnegative finite contact pulses and
nonnegative source routes. Filters run after accumulation. Their separate
stability/energy properties are not established merely by this accumulator.
"""

from __future__ import annotations

import json
import math
from pathlib import Path

import numpy as np
from scipy import signal


INTERNAL_RATE = 176_400


def unit_energy_pulse(width_seconds: float, rate: int = INTERNAL_RATE) -> np.ndarray:
    """A finite raised-cosine contact with integral(p**2) equal to one.

    These smooth samples still require explicit input band limiting before
    acoustic rendering. Oversampling alone is not an anti-alias argument.
    """
    if not math.isfinite(width_seconds) or width_seconds <= 0:
        raise ValueError("pulse width must be finite and positive")
    if not isinstance(rate, (int, np.integer)) or rate <= 0:
        raise ValueError("rate must be a positive integer")
    count = max(4, round(width_seconds * rate))
    pulse = np.sin(np.pi * np.arange(count) / (count - 1)) ** 2
    pulse[0] = pulse[-1] = 0.0
    return pulse / math.sqrt(float(np.dot(pulse, pulse)) / rate)


def _empty_result(budget: float) -> dict:
    return {
        "amplitude": 0.0,
        "nominal_amplitude": math.sqrt(budget),
        "attenuation": 0.0,
        "incremental_energy": 0.0,
        "incremental_energy_quadratic": 0.0,
        "assigned_budget": budget,
        "unused_budget": budget,
        "pulse_energy_present": 0.0,
        "cross_energy": 0.0,
        "frames_added": 0,
        "truncated": False,
    }


def add_budgeted_pulse(
    routes: np.ndarray,
    pulse_unit: np.ndarray,
    start_frame: int,
    budget: float,
    direction: tuple[float, float] | np.ndarray = (1.0, 0.0),
    rate: int = INTERNAL_RATE,
) -> dict:
    """Add one contact in place without exceeding its allocated source energy.

    routes must be a writable float64 ndarray of shape (2, frames).
    pulse_unit must be a nonnegative one-dimensional pulse with full energy
    one at `rate`. Direction is two bounded nonnegative weights; their norm
    is removed so routing changes do not silently multiply the budget.

    Pulses clipped by either buffer boundary retain their original full
    normalization. Any unspent energy is reported rather than added back.
    A zero budget performs no write. The energy accounting includes BOTH
    routes, and an event receives one shared budget, not one per route.
    """
    budget = float(budget)
    if not math.isfinite(budget) or budget < 0:
        raise ValueError("budget must be finite and nonnegative")
    if not isinstance(routes, np.ndarray) or routes.ndim != 2 or routes.shape[0] != 2:
        raise ValueError("routes must be an ndarray with shape (2, frames)")
    if routes.dtype != np.dtype("float64") or not routes.flags.writeable:
        raise ValueError("reference routes must be writable float64 storage")
    if not isinstance(rate, (int, np.integer)) or rate <= 0:
        raise ValueError("rate must be a positive integer")
    if not isinstance(start_frame, (int, np.integer)):
        raise ValueError("start_frame must be an integer")
    result = _empty_result(budget)
    if budget == 0:
        return result
    pulse = np.asarray(pulse_unit, dtype=float)
    if pulse.ndim != 1 or not len(pulse) or not np.all(np.isfinite(pulse)) or np.any(pulse < 0):
        raise ValueError("pulse must be one-dimensional, finite and nonnegative")
    full_energy = float(np.dot(pulse, pulse)) / rate
    if not math.isclose(full_energy, 1.0, rel_tol=2e-7, abs_tol=2e-10):
        raise ValueError("pulse must have unit energy at the supplied rate")
    weights = np.asarray(direction, dtype=float)
    if weights.shape != (2,) or not np.all(np.isfinite(weights)) or np.any(weights < 0) or np.any(weights > 1):
        raise ValueError("direction must be two finite weights in 0..1")
    norm = float(np.linalg.norm(weights))
    if norm == 0:
        raise ValueError("direction cannot be zero")
    weights = weights / norm
    left = max(0, int(start_frame))
    right = min(routes.shape[1], int(start_frame) + len(pulse))
    result["truncated"] = bool(left != start_frame or right != start_frame + len(pulse))
    result["direction"] = weights.tolist()
    if right <= left:
        return result
    pleft = left - int(start_frame)
    local_pulse = pulse[pleft:pleft + right - left]
    proposed = weights[:, None] * local_pulse[None, :]
    existing = routes[:, left:right]
    if not np.all(np.isfinite(existing)) or np.any(existing < 0):
        raise ValueError("unfiltered accumulation must be finite and nonnegative")
    q = float(np.sum(proposed * proposed)) / rate
    c = float(np.sum(existing * proposed)) / rate
    if q == 0:
        return result
    nominal = math.sqrt(budget)
    # The rationalized quadratic root avoids cancellation at large overlap.
    root = budget / (math.sqrt(c * c + q * budget) + c)
    amplitude = min(nominal, root)
    before = float(np.sum(existing.astype(float) ** 2)) / rate
    existing += amplitude * proposed
    after = float(np.sum(existing.astype(float) ** 2)) / rate
    incremental = after - before
    quadratic = q * amplitude * amplitude + 2 * c * amplitude
    result.update(
        amplitude=amplitude,
        attenuation=amplitude / nominal,
        incremental_energy=incremental,
        incremental_energy_quadratic=quadratic,
        unused_budget=max(0.0, budget - quadratic),
        pulse_energy_present=q,
        cross_energy=c,
        frames_added=right - left,
    )
    return result


def _source_energy(routes: np.ndarray, rate: int = INTERNAL_RATE) -> float:
    return float(np.sum(np.asarray(routes, dtype=float) ** 2)) / rate


def _rate_fixture(width: float, hz: float) -> dict:
    rate = INTERNAL_RATE
    duration, power = 2.0, 0.3
    frames = round(duration * rate)
    pulse = unit_energy_pulse(width, rate)
    safe = np.zeros((2, frames))
    naive = np.zeros_like(safe)
    weights = np.array([0.8, 0.6])
    allocated = 0.0
    increments = []
    coefficients = []
    for k in range(round(duration * hz)):
        start = round(k / hz * rate)
        budget = power / hz
        item = add_budgeted_pulse(safe, pulse, start, budget, weights, rate)
        assert item["amplitude"] <= math.sqrt(budget) * (1 + 1e-14)
        assert item["incremental_energy"] <= budget * (1 + 1e-12) + 1e-14
        assert item["incremental_energy_quadratic"] <= budget * (1 + 1e-12) + 1e-14
        increments.append(item["incremental_energy"])
        coefficients.append(item["attenuation"])
        right = min(frames, start + len(pulse))
        naive[:, start:right] += math.sqrt(budget) * weights[:, None] * pulse[:right-start]
        allocated += budget
    total = _source_energy(safe)
    naive_total = _source_energy(naive)
    assert total <= allocated * (1 + 1e-12) + 1e-12
    assert math.isclose(sum(increments), total, rel_tol=2e-12, abs_tol=2e-12)
    # A zero-state Butterworth LP is contractive in L2, measured here on the
    # finite observation. This is separate from modal response/normalization.
    sos = signal.butter(6, 8000, fs=rate, output="sos")
    filtered = signal.sosfilt(sos, safe, axis=1)
    filtered_total = _source_energy(filtered)
    assert filtered_total <= total * (1 + 1e-10) + 1e-12
    return {
        "contact_hz": hz,
        "pulse_width_seconds": width,
        "schedule_seconds": duration,
        "events": len(increments),
        "assigned_total_energy": allocated,
        "budgeted_measured_total_energy": total,
        "naive_measured_total_energy": naive_total,
        "intended_mean_power": power,
        "budgeted_mean_power": total / duration,
        "naive_mean_power": naive_total / duration,
        "naive_to_intended_ratio": naive_total / allocated,
        "filtered_measured_total_energy": filtered_total,
        "minimum_attenuation": min(coefficients),
        "maximum_attenuation": max(coefficients),
        "increment_energy_sum_error": abs(sum(increments) - total),
    }


def verification_report() -> dict:
    cases = [_rate_fixture(width, hz) for width in [0.002, 0.050] for hz in [5.0, 20.0, 80.0]]
    assert cases[-1]["naive_mean_power"] > 2.5 * 0.3
    # The final 50 ms contact is clipped by this fixed two-second observation.
    # Its unspent input budget stays unspent instead of boosting the fragment.
    assert .29 < cases[-1]["budgeted_mean_power"] <= 0.3 * (1 + 1e-12)
    rate = INTERNAL_RATE
    pulse = unit_energy_pulse(0.050)
    boundary_cases = []
    for name, start, frames in [
        ("left_clipped", -len(pulse) // 2, len(pulse) * 2),
        ("right_clipped", len(pulse) // 2, len(pulse)),
        ("entirely_before", -len(pulse) * 2, len(pulse)),
        ("entirely_after", len(pulse) * 2, len(pulse)),
    ]:
        routes = np.zeros((2, frames))
        item = add_budgeted_pulse(routes, pulse, start, .04, (0.8, 0.6))
        assert _source_energy(routes) <= .04 * (1 + 1e-12)
        assert item["amplitude"] <= .2
        if item["frames_added"]:
            assert item["unused_budget"] > 0.0
        boundary_cases.append({"name": name, **item})
    routes = np.zeros((2, len(pulse) * 2))
    snapshot = routes.copy()
    zero = add_budgeted_pulse(routes, pulse, 0, 0.0, (0.8, 0.6))
    assert np.array_equal(snapshot, routes)
    assert zero["incremental_energy"] == 0.0
    # A coincident second contact has strong cross energy. It must receive
    # less amplitude even though its allocated energy equals the first.
    first = add_budgeted_pulse(routes, pulse, 0, .04, (0.8, 0.6))
    second = add_budgeted_pulse(routes, pulse, 0, .04, (0.8, 0.6))
    assert second["amplitude"] < first["amplitude"]
    assert math.isclose(_source_energy(routes), .08, rel_tol=2e-12, abs_tol=1e-12)
    # Direction changes consume one total budget even when the output is
    # distributed across the disk/support pair.
    for weights in [(1.0, 0.0), (0.0, 1.0), (1.0, 1.0), (0.01, .5)]:
        routes = np.zeros((2, len(pulse)))
        add_budgeted_pulse(routes, pulse, 0, .04, weights)
        assert math.isclose(_source_energy(routes), .04, rel_tol=2e-12, abs_tol=1e-12)
    # No hidden random state: the same event sequence reproduces the buffer.
    def deterministic_fixture():
        output = np.zeros((2, 10000))
        for index in range(10):
            add_budgeted_pulse(output, pulse, index * 137, .01 / (1 + index), (0.8, 0.6))
        return output
    assert np.array_equal(deterministic_fixture(), deterministic_fixture())
    return {
        "status": "Python two-route contact accumulator, not Kotlin; not heard",
        "units": "squared acoustic-input proxy amplitude times seconds; not calibrated joules",
        "law": "q*a^2+2*c*a <= budget, with a <= sqrt(budget)",
        "rate": rate,
        "rate_cases": cases,
        "boundary_cases": boundary_cases,
        "coincident_contacts": {"first": first, "second": second, "total_energy": .08},
        "zero_budget_unchanged": True,
        "deterministic": True,
        "limits": [
            "Pulse and accumulated unfiltered routes must be nonnegative.",
            "Budgeted energy is the combined energy of the two acoustic input routes, not the radiated waveform or mechanical energy.",
            "Truncated contacts are never amplified to spend their unconsumed budget.",
            "The proxy can lower amplitudes when contacts overlap; its audible effect needs the owner's audition.",
            "Only this input accumulator and a zero-state Butterworth LP were checked here; modal/filter state stability and aliasing require separate probes.",
        ],
    }


if __name__ == "__main__":
    report = verification_report()
    path = Path(__file__).with_name("contact_budget_report.json")
    path.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps({
        "report": str(path),
        "cases": [{"hz": item["contact_hz"], "width_s": item["pulse_width_seconds"],
                   "safe_power": item["budgeted_mean_power"], "naive_power": item["naive_mean_power"]}
                  for item in report["rate_cases"]],
    }, indent=2))
