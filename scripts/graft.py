#!/usr/bin/env python3
"""Graft patched code onto the PRISTINE base.apk without touching resources.

Takes the pristine base.apk and swaps in ONLY:
  - AndroidManifest.xml  (rebuilt binary, from the apktool build — carries the new <activity>)
  - one or more patched hook dexes: the classesN.dex the apktool build reassembled because a hook
    touched their smali (--rebuilt-dex, repeatable). The nav hooks land in classes6.dex, the
    FreeMapAppActivity startup hook in classes5.dex; each MUST be swapped or its hook silently vanishes.
  - the package dex: a new contiguous classesN.dex holding the Wazeology package (com.waze.wazeology +
    com.waze.debug), freshly compiled from src/ (not reassembled by apktool)
  - zero or more --res-sub overwrites: the BYTES of an already-existing res/* file are replaced in
    place (same zip path), so resources.arsc still resolves the same id -> same path. Used to give
    Wazeology its launcher icon by overwriting the ORPHAN mipmap/launch_icon_round with a compiled,
    self-contained adaptive-icon (see patches/wazeology-icon.xml). This does NOT add a resource id
    and does NOT re-encode the resource table.

resources.arsc stays byte-identical to the original (the golden rule: apktool's resource re-encode
corrupts drawables -> route-card InflateException). Verified by SHA compare. --res-sub only ever
changes the CONTENT of an existing res/* entry, never resources.arsc, so the golden rule holds.
"""
import argparse
import hashlib
import re
import sys
import zipfile


def sha(zf, name):
    with zipfile.ZipFile(zf) as z:
        return hashlib.sha256(z.read(name)).hexdigest()


def next_dex_name(names):
    idx = []
    for n in names:
        if n == "classes.dex":
            idx.append(1)
        else:
            m = re.match(r"^classes(\d+)\.dex$", n)
            if m:
                idx.append(int(m.group(1)))
    nxt = (max(idx) if idx else 1) + 1
    return "classes{}.dex".format(nxt)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pristine", required=True)
    ap.add_argument("--apktool", required=True)
    ap.add_argument("--pkgdex", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--rebuilt-dex", action="append", default=[], required=True, metavar="NAME",
                    help="a patched hook dex: a classesN.dex to take from the apktool build instead of the "
                         "pristine base (every dex whose smali a hook touched); may repeat")
    ap.add_argument("--res-sub", action="append", default=[], metavar="ZIPPATH=FILE",
                    help="overwrite the bytes of an existing res/* entry (may repeat)")
    a = ap.parse_args()

    res_sub = {}
    for spec in a.res_sub:
        if "=" not in spec:
            print("FATAL: --res-sub needs ZIPPATH=FILE, got " + spec, file=sys.stderr)
            return 1
        zippath, localfile = spec.split("=", 1)
        with open(localfile, "rb") as f:
            res_sub[zippath] = f.read()

    with zipfile.ZipFile(a.apktool) as z:
        apktool_names = set(z.namelist())
        manifest = z.read("AndroidManifest.xml")
        rebuilt = {}
        for d in a.rebuilt_dex:
            if d not in apktool_names:
                print("FATAL: --rebuilt-dex not in apktool build: " + d, file=sys.stderr)
                return 1
            rebuilt[d] = z.read(d)
    with open(a.pkgdex, "rb") as f:
        pkgdex = f.read()

    with zipfile.ZipFile(a.pristine) as zin:
        names = zin.namelist()
        dexname = next_dex_name(names)
        with zipfile.ZipFile(a.out, "w") as zout:
            for item in zin.infolist():
                if item.filename == dexname:
                    continue  # avoid dup if a previous run added it
                if item.filename == "AndroidManifest.xml":
                    zi = zipfile.ZipInfo("AndroidManifest.xml", date_time=item.date_time)
                    zi.compress_type = item.compress_type
                    zi.external_attr = item.external_attr
                    zout.writestr(zi, manifest)
                elif item.filename in rebuilt:
                    zi = zipfile.ZipInfo(item.filename, date_time=item.date_time)
                    zi.compress_type = zipfile.ZIP_STORED
                    zi.external_attr = item.external_attr
                    zout.writestr(zi, rebuilt.pop(item.filename))
                elif item.filename in res_sub:
                    zi = zipfile.ZipInfo(item.filename, date_time=item.date_time)
                    zi.compress_type = item.compress_type
                    zi.external_attr = item.external_attr
                    zi.internal_attr = item.internal_attr
                    zi.create_system = item.create_system
                    zout.writestr(zi, res_sub.pop(item.filename))
                else:
                    zi = zipfile.ZipInfo(item.filename, date_time=item.date_time)
                    zi.compress_type = item.compress_type
                    zi.external_attr = item.external_attr
                    zi.internal_attr = item.internal_attr
                    zi.create_system = item.create_system
                    zout.writestr(zi, zin.read(item.filename))
            zi = zipfile.ZipInfo(dexname)
            zi.compress_type = zipfile.ZIP_STORED
            zout.writestr(zi, pkgdex)

    if res_sub:
        print("FATAL: --res-sub path(s) not found in pristine apk: "
              + ", ".join(sorted(res_sub)), file=sys.stderr)
        return 1
    if rebuilt:
        print("FATAL: --rebuilt-dex path(s) not found in pristine apk: "
              + ", ".join(sorted(rebuilt)), file=sys.stderr)
        return 1

    if sha(a.pristine, "resources.arsc") != sha(a.out, "resources.arsc"):
        print("FATAL: resources.arsc changed — graft is not resource-pristine", file=sys.stderr)
        return 1
    print("grafted -> {} (patched hook dexes = {}, package dex = {}, resources.arsc byte-identical)"
          .format(a.out, ",".join(sorted(a.rebuilt_dex)), dexname))
    return 0


if __name__ == "__main__":
    sys.exit(main())
