"""Numerically refine the supplied symmetric figure-eight seed; not certification.

G=m1=m2=m3=1; fixed positions set scale/orientation. Development needs NumPy and
SciPy. Family: Chenciner & Montgomery (2000), https://annals.math.princeton.edu/articles/12364
"""
import numpy as np
from scipy.integrate import solve_ivp
from scipy.optimize import least_squares

POSITIONS = np.array([[.97000436, -.24308753], [-.97000436, .24308753], [0., 0.]])


def initial(p):
    return np.r_[POSITIONS.ravel(), [p[0], p[1], p[0], p[1], -2*p[0], -2*p[1]]


def derivative(_time, state):
    positions = state[:6].reshape(3, 2)
    acceleration = np.zeros_like(positions)
    for i, j in ((0, 1), (1, 2), (2, 0)):
        d = positions[j] - positions[i]
        force = d / np.linalg.norm(d)**3
        acceleration[i] += force
        acceleration[j] -= force
    return np.r_[state[6:], acceleration.ravel()]


def closure(p):
    state = initial(p)
    s = solve_ivp(derivative, (0, p[2]), state, method="DOP853", rtol=2.3e-14, atol=2e-15)
    assert s.success
    return s.y[:, -1] - state


if __name__ == "__main__":
    s = least_squares(closure, [.466203685, .432365730, 6.325914], xtol=1e-14,
                      ftol=1e-14, gtol=1e-14, diff_step=1e-5, max_nfev=60)
    print("vx, vy, period:", [format(x, ".17g") for x in s.x])
    print("Closure L2:", np.linalg.norm(closure(s.x)))
