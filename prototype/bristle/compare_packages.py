#!/usr/bin/env python3
"""Check that all four original single-take packages remain byte-identical."""
from pathlib import Path
import hashlib
import json
import sys

a, b = map(Path, sys.argv[1:3])
count = 0
for name in ['Bristle A', 'Bristle B', 'Bristle A Held', 'Bristle B Held']:
    def hashes(root):
        folder = root / name
        assert folder.is_dir(), folder
        return {str(p.relative_to(folder)): hashlib.sha256(p.read_bytes()).hexdigest()
                for p in folder.rglob('*') if p.is_file()}
    before, after = hashes(a), hashes(b)
    assert before and before == after, name
    count += len(before)
print(json.dumps({'single_take_files_byte_identical': count, 'packages': 4}))
