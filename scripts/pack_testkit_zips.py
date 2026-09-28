#!/usr/bin/env python3
"""Rebuild the download ZIPs in testkit/ from the folders beside them.

Each testkit/*.zip is a packaged copy of one or more testkit folders (a kit
folder, or an MPC 3 file beside its _[TrackData]/_[ProjectData] folder). The
generators rewrite the folders but not the ZIPs, so after regenerating, run:

    python3 scripts/pack_testkit_zips.py          # rebuild any stale ZIP
    python3 scripts/pack_testkit_zips.py --check  # only report; exit 1 if stale

What a ZIP holds is read from the ZIP itself (its top-level entries), so a new
ZIP only needs packing once by hand. A ZIP whose files already match the
folders is left untouched. Entries are written sorted, with a fixed timestamp,
so rebuilding twice gives the same bytes and git sees no churn.
"""
import os
import sys
import zipfile

TESTKIT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "testkit")
# A fixed date: the ZIP should change only when the files in it do.
STAMP = (2026, 1, 1, 0, 0, 0)


def top_level_entries(zip_path):
    """The folders and files a ZIP packages, e.g. ['SnipSnap Thump Kit']."""
    with zipfile.ZipFile(zip_path) as zf:
        return sorted({info.filename.split("/")[0] for info in zf.infolist()})


def wanted_contents(tops):
    """Every directory and file under the given top-level entries, sorted, as ZIP names."""
    dirs, files = [], []
    for top in tops:
        if os.path.isfile(top):
            files.append(top)
            continue
        for root, subdirs, names in os.walk(top):
            subdirs.sort()
            dirs.append(root.replace(os.sep, "/") + "/")
            files.extend(os.path.join(root, n).replace(os.sep, "/") for n in sorted(names))
    return dirs, files


def is_stale(zip_path, files):
    """True when the ZIP's files differ from the folders' files, by name or by content."""
    with zipfile.ZipFile(zip_path) as zf:
        packed = {i.filename: i for i in zf.infolist() if not i.is_dir()}
        if set(packed) != set(files):
            return True
        for name in files:
            with open(name, "rb") as f:
                if zf.read(packed[name]) != f.read():
                    return True
    return False


def write_zip(zip_path, dirs, files):
    """Write the ZIP afresh: directory entries first, then files, deflated, Unix permissions."""
    tmp = zip_path + ".tmp"
    with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as zf:
        for name in dirs:
            info = zipfile.ZipInfo(name, STAMP)
            info.create_system = 3  # Unix, so the permissions below are honoured
            info.external_attr = (0o40755 << 16) | 0x10  # a directory, rwxr-xr-x
            zf.writestr(info, b"")
        for name in files:
            info = zipfile.ZipInfo(name, STAMP)
            info.create_system = 3
            info.external_attr = 0o100644 << 16  # a regular file, rw-r--r--
            info.compress_type = zipfile.ZIP_DEFLATED
            with open(name, "rb") as f:
                zf.writestr(info, f.read())
    os.replace(tmp, zip_path)


def main():
    check_only = "--check" in sys.argv[1:]
    os.chdir(TESTKIT)
    stale = []
    for zip_path in sorted(p for p in os.listdir(".") if p.endswith(".zip")):
        tops = top_level_entries(zip_path)
        missing = [t for t in tops if not os.path.exists(t)]
        if missing:
            print(f"{zip_path}: skipped, no {', '.join(missing)} beside it")
            continue
        dirs, files = wanted_contents(tops)
        if not is_stale(zip_path, files):
            continue
        stale.append(zip_path)
        if check_only:
            print(f"{zip_path}: stale")
        else:
            write_zip(zip_path, dirs, files)
            print(f"{zip_path}: rebuilt ({len(files)} files)")
    if not stale:
        print("every ZIP matches its folders")
    return 1 if check_only and stale else 0


if __name__ == "__main__":
    sys.exit(main())
