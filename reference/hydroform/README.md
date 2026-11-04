# HYDROFORM reference

Frozen Python R5 source and a compact recipe manifest copied from the accepted audition.
The full original source SHA-256 is 05987dc201ffd5cd1c1bdc76ebb0b59d1edcf922456053f8ca88a8f92b8ef2a9.
`python-report.json` contains Python measurements, not Kotlin results. The compact manifest
retains all native main-path studies except the optional all-pass bypass, which is not an engine macro.
The Python fixed comparison is retained as an audition reference, not a saved production patch.

Regenerate the Python source with NumPy/SciPy from requirements.txt if needed. Its output is
under this directory's dist/. Library WAVs and generated native assets belong under testkit/,
not the source tree. `python3 prototype.py` produces its own report and ZIP.

Why it is built this way: preserve the exact source and recipes for raw cross-language
comparison; compare output stages separately because the Python resampler and weighted RMS
proxy differ from the real house chain; keep all claims of listening with the owner.
