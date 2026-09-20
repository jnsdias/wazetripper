#!/usr/bin/env python3
"""Normalize apkeep output into apk/base.apk + apk/split_config.*.apk.

Runs INSIDE the toolchain container (cwd = repo root). apkeep may hand us an APKPure XAPK/APKM
(a zip of base + config splits) or already-separate .apk files; this flattens either into the
fixed layout the build/install scripts expect.

Base vs split is decided by content: the base apk contains classes.dex; config splits do not.
Split names come from the source filename (APKPure uses config.<name>.apk).
"""
import glob
import os
import re
import shutil
import sys
import zipfile

APK_DIR = "apk"
DL_DIR = os.path.join(APK_DIR, "_dl")
UNPACK = os.path.join(DL_DIR, "unpacked")


def unpack_containers():
    os.makedirs(UNPACK, exist_ok=True)
    for pattern in ("*.xapk", "*.apkm", "*.zip"):
        for arc in glob.glob(os.path.join(DL_DIR, pattern)):
            with zipfile.ZipFile(arc) as z:
                z.extractall(UNPACK)


def collect_apks():
    return [p for p in glob.glob(os.path.join(DL_DIR, "**", "*.apk"), recursive=True)]


def is_base(apk_path):
    try:
        with zipfile.ZipFile(apk_path) as z:
            return "classes.dex" in z.namelist()
    except zipfile.BadZipFile:
        return False


def split_name(apk_path):
    bn = os.path.basename(apk_path)
    name = re.sub(r"\.apk$", "", bn)
    name = re.sub(r"^split_", "", name)
    name = re.sub(r"^com\.waze\.", "", name)
    # APKPure names splits "config.arm64_v8a"; normalize to a "config.*" token
    if not name.startswith("config."):
        m = re.search(r"(config\.[A-Za-z0-9_]+)", bn)
        if m:
            name = m.group(1)
    return name


def main():
    unpack_containers()
    apks = collect_apks()
    if not apks:
        print("no apks found under apk/_dl — did apkeep fail?", file=sys.stderr)
        return 1

    # clean prior normalized outputs
    for old in glob.glob(os.path.join(APK_DIR, "base.apk")) + glob.glob(os.path.join(APK_DIR, "split_*.apk")):
        os.remove(old)

    base_written = False
    splits = []
    for apk in apks:
        if is_base(apk) and not base_written:
            shutil.copy(apk, os.path.join(APK_DIR, "base.apk"))
            base_written = True
        elif is_base(apk):
            continue  # only one base
        else:
            name = split_name(apk)
            dst = os.path.join(APK_DIR, "split_{}.apk".format(name))
            shutil.copy(apk, dst)
            splits.append(os.path.basename(dst))

    if not base_written:
        print("could not identify a base apk (none contained classes.dex)", file=sys.stderr)
        return 1

    shutil.rmtree(DL_DIR, ignore_errors=True)
    print("base.apk + " + ", ".join(sorted(splits)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
