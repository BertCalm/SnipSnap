# Accepted Python raw-source fixtures

R0 source at `prototype/bristle/bristle_proto.py`, accepted by the owner on 2026-11-14:
“I like both versions.” These four fixtures are C2, hard touch, routes A/B, SLIDE 0 and .65.
Each stores every sixteenth raw 176.4 kHz sample as little-endian float32 (not playable audio).
They pin the source independently of the changed Kotlin output conditioning.

Regenerate only if deliberately revising the accepted baseline:

```python
import sys
sys.path.insert(0, 'prototype/bristle')
import bristle_proto as p
for route in ('A', 'B'):
    for slide in (0.0, .65):
        p.render(route, {'SLIDE': slide}, 36)[::16].astype('<f4').tofile(
            f'synth/src/test/resources/bristle/{route}_{slide}.f32')
```

The continuous-raw comparison in `kotlin_audition.py` complements these sparse regression
fixtures. The Kotlin test tolerates float state arithmetic, not an arbitrary voicing change.
