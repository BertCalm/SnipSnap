"""Regenerate the independent physics oracle; not needed to run JVM tests.

Requires NumPy and SciPy. The original fixture used NumPy 2.3.5 / SciPy 1.17.0.
Run this file directly; output is dynamics-dop853.csv.gz beside this script.
This is the displayed reciprocal ODE, not a translation of the Kotlin integrator.
It solves the original 140-cycle research horizon before extracting its first 32
cycles, so its adaptive prefix has the same endpoint policy as that experiment.
"""
import gzip
import io
from pathlib import Path

import numpy as np
from scipy.integrate import solve_ivp
from scipy.optimize import brentq

PHASE = np.array([
    .8966527134072003, .01371931073871436, 1.032307611146228, 1.6333219162822996,
    .8954696745107658, 1.7364706805937027, 4.686724916365365, 2.881533644331152,
    .984617281081893, 2.197295625035535, 4.92245680526012, .4100143347613006,
    1.7232504142699445, 4.663003291521934, 6.222979243758299,
])
OFFSET = np.array([
    .2702849749993048, -.2162914734369595, .8923071623543083, -.8332611591935914,
    -.7394243316245537, -1., .7656632647226754, .5644466614602919,
    .21773618789379703, -.3851930196340212, .38047645960622606, -.6053016041020003,
    -.11061557259250432, .4369388347714392, .36223361477558763,
])
W = np.sqrt(.02 / .024)
T = 2 * np.pi / W
J = .02 / (W * (1 + .01 * OFFSET)) ** 2


def derivative(_, state):
    theta, omega, velocity = state[:15], state[15:30], state[31]
    q = .02 * np.cos(theta)
    b = J * .1 * (1 - (theta / .38) ** 2) * omega - .02 * np.sin(theta)
    denominator = .3 + .6 - np.sum(q * q / J)
    base = (np.sum(.02 * np.sin(theta) * omega ** 2) - .05 * velocity - np.sum(q * b / J)) / denominator
    return np.r_[omega, (b - q * base) / J, velocity, base]


def main():
    initial = np.r_[.76 * np.cos(PHASE), -.76 * W * (1 + .01 * OFFSET) * np.sin(PHASE), 0., 0.]
    times = np.linspace(0, 140 * T, 5601)
    solution = solve_ivp(derivative, [0, times[-1]], initial, method="DOP853",
                         rtol=1e-9, atol=1e-11, t_eval=times, dense_output=True)
    if not solution.success:
        raise RuntimeError(solution.message)
    events = []
    for mechanism in range(15):
        theta = solution.y[mechanism]
        for j in np.where(theta[:-1] * theta[1:] < 0)[0]:
            at = brentq(lambda t: solution.sol(t)[mechanism], times[j], times[j + 1], xtol=1e-11)
            if at / T <= 32:
                speed = solution.sol(at)[15 + mechanism]
                events.append((at / T, mechanism, 1 if speed > 0 else -1, abs(speed)))
    events.sort()
    lines = [
        "# Python SciPy DOP853 rtol=1e-9 atol=1e-11; exact REFERENCE_SINE_20261022 vectors; Mbase=.6 cb=.05 kappa=1 spread=.01 mu=.1\n",
        "# S,canonical_cycle,theta[0..14],omega[0..14],x,V; E,canonical_cycle,mechanism,direction,canonical_speed\n",
    ]
    for j in range(32 * 40 + 1):
        lines.append("S," + ",".join(format(float(value), ".17g") for value in np.r_[times[j] / T, solution.y[:, j]]) + "\n")
    for event in events:
        lines.append("E," + ",".join(format(float(value), ".17g") for value in event) + "\n")
    destination = Path(__file__).with_name("dynamics-dop853.csv.gz")
    with destination.open("wb") as raw:
        with gzip.GzipFile(fileobj=raw, mode="wb", filename="", mtime=0) as compressed:
            compressed.write("".join(lines).encode())
    print(destination)


if __name__ == "__main__":
    main()
