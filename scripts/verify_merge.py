#!/usr/bin/env python3
"""Check that the bundled apk preserves the golden rule (see DEVELOPMENT.md §3, §11).

The golden rule's real hazard is aapt2 RE-ENCODING resource XML, not a modified resources.arsc as such.
APKEditor's binary merge rebuilds the resource TABLE but copies every res/* FILE verbatim. This gate
asserts exactly that: in the final bundled apk, no pristine-base res/* file has changed except the ones
the graft itself intentionally patched (the launcher icon), and the only dropped entry is the obsolete
split descriptor. It also checks the native .so are STORED (extractNativeLibs=false) and that every dex in
the grafted base survived (patched hook dexes, the package dex, and the pristine passthroughs alike). Exit
non-zero on any violation so scripts/build.sh fails loudly.

Args: <pristine base.apk> <grafted build/gen/base.apk> <final ./wazeology.apk>
"""
import sys
import zipfile

ALLOWED_MISSING = {"res/xml/splits0.xml"}  # the bundle split descriptor, correctly dropped when de-split


def main():
    if len(sys.argv) != 4:
        print("usage: verify_merge.py <pristine.apk> <grafted.apk> <final.apk>", file=sys.stderr)
        return 2
    pristine = zipfile.ZipFile(sys.argv[1])
    grafted = zipfile.ZipFile(sys.argv[2])
    final = zipfile.ZipFile(sys.argv[3])
    pn, gn, fn = set(pristine.namelist()), set(grafted.namelist()), set(final.namelist())

    # The only res/* changes the merge may carry are the ones the graft already made vs the pristine base.
    allowed_changed = {n for n in pn & gn if n.startswith("res/") and pristine.read(n) != grafted.read(n)}
    res = [n for n in pn if n.startswith("res/")]

    bad_changed, bad_missing = [], []
    for n in res:
        if n not in fn:
            if n not in ALLOWED_MISSING:
                bad_missing.append(n)
            continue
        if pristine.read(n) != final.read(n) and n not in allowed_changed:
            bad_changed.append(n)

    so = [i for i in final.infolist() if i.filename.startswith("lib/") and i.filename.endswith(".so")]
    compressed_so = [i.filename for i in so if i.compress_type != 0]
    # Every dex in the grafted base (patched hook dexes, the package dex, and the pristine passthroughs)
    # must survive the APKEditor merge; a dropped one silently loses whatever it carried.
    base_dexes = {n for n in gn if n.endswith(".dex")}
    missing_dex = sorted(d for d in base_dexes if d not in fn)

    ok = True
    if bad_changed:
        print("FATAL: merge changed base resources beyond the graft: " + str(bad_changed[:10]), file=sys.stderr)
        ok = False
    if bad_missing:
        print("FATAL: merge dropped base resources: " + str(bad_missing[:10]), file=sys.stderr)
        ok = False
    if compressed_so:
        print("FATAL: native .so compressed (extractNativeLibs=false needs STORED): "
              + str(compressed_so[:3]), file=sys.stderr)
        ok = False
    if missing_dex:
        print("FATAL: missing patched dex: " + str(missing_dex), file=sys.stderr)
        ok = False
    if not ok:
        return 1

    print("OK: {} base res/ files kept verbatim; graft-only change: {}; libs STORED: {}; hooks+pkg dex present"
          .format(len(res), sorted(allowed_changed), len(so)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
