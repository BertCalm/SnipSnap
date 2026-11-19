"""Negative control: a duplicated soft take must fail even with distinct hard hits."""
import numpy as np
from study import closest_takes
rng=np.random.default_rng(731)
grid=[rng.normal(size=2048) for _ in range(9)]
assert closest_takes(grid)<.99995
grid[1]=grid[0]*.4
assert closest_takes(grid)>.99995, 'Soft gain-only take escaped the detector'
# A time-shifted copy must also fail: start jitter is not a fresh strike.
grid[1]=np.r_[np.zeros(30),grid[0][:-30]]
assert closest_takes(grid)>.99995, 'Time-shifted copy escaped the detector'
print('All-velocity copy and time-shift negative controls passed')
