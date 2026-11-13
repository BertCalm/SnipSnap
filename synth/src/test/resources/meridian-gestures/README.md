# Accepted MERIDIAN baseline

This genuine native capture was made before the DRAW/LIFT candidate classes were
compiled, using the pristine copied classes from source
`0f7d0e26cfdf875a995e6c8a5aec8225e3fd1fe9`. The seven MERIDIAN DSP/motion/material/
patch/preset sources at that pin match published source
`c2d94337a80580e64df26041010c8af3b5b112ca` exactly. The copied compiled dependency
manifest identity is bound inside the capture; this was not a new full dependency
build. The only source delta from 33df at that baseline is an unrelated OSTERIA
patch decoder repair.

All fourteen complete recipe JSON strings and eight first-finite/first-held
renders of the four accepted voices are retained. Audio hashes use raw IEEE754
Float bits in little-endian order. Frame counts and actual held boundaries are
part of the baseline. No new sound or acceptance verdict was fabricated.

The immutable capture's SHA-256 is in `accepted-models-1-3.sha256`. The capture
harness and its pristine copied classes did not come back with the recovery port.
The normal test reads this tracked resource; an explicit
`snipsnap.meridian.baseline` property selects another actual capture.
