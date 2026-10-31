"""PANE's body for SCOUR R1.1: the completely free square plate, solved, checked and heard at one point.

Run: python3 docs/superpowers/specs/2026-10-26-scour-engine-square-plate.py   (numpy and scipy)

1. A Rayleigh-Ritz solve of the free square plate with products of orthonormal Legendre polynomials
   on [-1, 1]^2 (side a = 2), so lambda = omega a^2 sqrt(rho h / D) = 4 omega with D = rho h = 1.
   The strain energy is D/2 * integral of w_xx^2 + w_yy^2 + 2 nu w_xx w_yy + 2 (1 - nu) w_xy^2; the
   edges are free, so no constraint is imposed and the first three eigenvalues are the rigid modes.
2. The check against Leissa, Vibration of Plates (NASA SP-160, 1969): eight entries of Table 4.65
   (nu = 0.3, the complete table) and the nu = 0.225 bounds on the fundamental (section 4.3.15).
3. The modes symmetric about the diagonal x = y (every other mode is exactly still along it).
4. The point on the diagonal where the modes at 4.347 and 5.489 have their nodes, and each kept
   mode's motion there as a fraction of the root's: Scour.kt's SQUARE and SQUARE_HEARD.
"""
import numpy as np
from numpy.polynomial import legendre as L
from scipy.linalg import eigh
from scipy.optimize import brentq


def plate(nu, n):
    """Eigenvalues lambda (rigid modes dropped), eigenvectors, mass matrix and basis for N x N Legendre products."""
    xg, wg = L.leggauss(n + 8)
    basis = [np.eye(n)[i] * np.sqrt((2 * i + 1) / 2) for i in range(n)]
    p0 = np.array([L.legval(xg, c) for c in basis])
    p1 = np.array([L.legval(xg, L.legder(c)) for c in basis])
    p2 = np.array([L.legval(xg, L.legder(c, 2)) for c in basis])
    inner = lambda a, b: (a * wg) @ b.T
    a0, a1, a2, a20 = inner(p0, p0), inner(p1, p1), inner(p2, p2), inner(p2, p0)
    k = (np.kron(a2, a0) + np.kron(a0, a2) + nu * (np.kron(a20, a20.T) + np.kron(a20.T, a20))
         + 2 * (1 - nu) * np.kron(a1, a1))
    m = np.kron(a0, a0)
    w2, v = eigh(k, m)
    assert np.all(np.abs(w2[:3]) < 1e-6 * w2[3]), "the three rigid modes should come first"
    return 4 * np.sqrt(w2[3:]), v[:, 3:], m, basis


# 1-2. Convergence and Leissa.
lam14 = plate(0.225, 14)[0]
lam, vecs, mass, basis = plate(0.225, 20)
print(f"nu 0.225: N 14 against N 20, largest change over the first 12 modes {np.max(np.abs(lam14[:12] - lam[:12]) / lam[:12]):.1e}")
leissa = [19.5961, 24.2702, 34.8011, 61.0932, 63.6870, 105.4634, 117.1093, 131.4697]
lam3 = plate(0.3, 20)[0]
worst = max(abs(lam3[np.argmin(abs(lam3 - x))] - x) / x for x in leissa)
print(f"nu 0.3 against Leissa Table 4.65 ({len(leissa)} entries): largest relative difference {worst:.1e}")
print(f"nu 0.225 fundamental {lam[0]:.4f}; Leissa's bounds 14.1028 to 14.1165")

# 3. Diagonal symmetry: swapping x and y maps coefficient (i, j) to (j, i).
n = len(basis)
swap = np.zeros((n * n, n * n))
for i in range(n):
    for j in range(n):
        swap[j * n + i, i * n + j] = 1
grid = np.linspace(-1, 1, 121)
pg = np.array([L.legval(grid, c) for c in basis])
kept, i = [], 0
while len(kept) < 12:
    ks = [i] + ([i + 1] if abs(lam[i + 1] - lam[i]) < 1e-6 * lam[i] else [])
    b = vecs[:, ks]
    sub = b.T @ mass @ swap @ b
    e, u = np.linalg.eigh((sub + sub.T) / 2)
    if e[-1] > .5:  # +1 symmetric about the diagonal, -1 antisymmetric (still along it)
        c = (b @ u[:, -1]).reshape(n, n)
        kept.append((lam[i], c / np.max(np.abs(pg.T @ c @ pg))))
    i += len(ks)
print("diagonal-symmetric modes:", " ".join(f"{x:.3f}" for x, _ in kept))


# 4. The listening point: t of the half-diagonal, centre 0, corner 1.
def phi(c, t):
    p = np.array([L.legval(t, cc) for cc in basis])
    return p @ c @ p


z3 = brentq(lambda t: phi(kept[3][1], t), .65, .78)
z5 = brentq(lambda t: phi(kept[5][1], t), .65, .78)
t = (z3 + z5) / 2
root = phi(kept[0][1], t)
print(f"nodes of {kept[3][0] / kept[0][0]:.3f} at t = {z3:.4f} and of {kept[5][0] / kept[0][0]:.3f} at t = {z5:.4f}; listening at {t:.4f}")
heard = [(x, abs(phi(c, t) / root)) for x, c in kept[:9]]
for x, h in heard:
    print(f"  {x:8.3f}  ratio {x / kept[0][0]:6.3f}  motion {h:.3f}" + ("" if h >= .1 else "  (left out: under a tenth)"))
body = [(x, h) for x, h in heard if h >= .1]
print("SQUARE       =", ", ".join(f"{x:.3f}" for x, _ in body))
print("SQUARE_HEARD =", ", ".join(f"{h:.3f}" for _, h in body))
