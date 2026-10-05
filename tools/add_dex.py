#!/usr/bin/env python3
"""Copy an aapt2-linked APK and append the dex files from a directory, stored uncompressed."""
import os
import shutil
import sys
import zipfile


def main():
    src, dex_dir, out = sys.argv[1], sys.argv[2], sys.argv[3]

    entries = []  # (name, data) in original order
    with zipfile.ZipFile(src, "r") as zin:
        for info in zin.infolist():
            entries.append((info, zin.read(info.filename)))

    dex_files = sorted(f for f in os.listdir(dex_dir) if f.endswith(".dex"))
    if not dex_files:
        print("error: no .dex files in", dex_dir, file=sys.stderr)
        return 1

    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as zout:
        for info, data in entries:
            ni = zipfile.ZipInfo(info.filename)
            ni.compress_type = info.compress_type
            zout.writestr(ni, data)
        for dex in dex_files:
            with open(os.path.join(dex_dir, dex), "rb") as fh:
                data = fh.read()
            ni = zipfile.ZipInfo(dex)
            ni.compress_type = zipfile.ZIP_STORED  # dex is left uncompressed + zipaligned later
            zout.writestr(ni, data)

    print("wrote", out, "with dex:", ", ".join(dex_files))
    return 0


if __name__ == "__main__":
    sys.exit(main())
