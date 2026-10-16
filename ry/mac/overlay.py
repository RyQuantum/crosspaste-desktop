#!/usr/bin/env python3
"""Layer this branch's patched classes onto the official app-desktop jar.

    overlay.py <official.jar> <built.jar> <patterns-file> <out.jar>

The built jar is compiled from this branch with the same JDK as the official release, so it
must equal the official jar except for the patched classes. Every entry is compared; the
script fails if anything outside the patterns differs (wrong base tag, wrong JDK, a stray
change) or if an exact (non-wildcard) pattern is missing (the patch was not compiled in).

crosspaste-version.properties is always taken from the official jar: upstream CI appends the
revision line to it at release time.
"""
import fnmatch
import sys
import zipfile

KEEP_OFFICIAL = {"crosspaste-version.properties"}


def norm(name: str, data: bytes) -> bytes:
    # Line endings only matter for text resources built on Windows
    return data if name.endswith(".class") else data.replace(b"\r\n", b"\n")


def class_major(data: bytes) -> int:
    return int.from_bytes(data[6:8], "big")


def main() -> int:
    official_path, built_path, patterns_path, out_path = sys.argv[1:5]
    patterns = [
        line.strip()
        for line in open(patterns_path, encoding="utf-8")
        if line.strip() and not line.startswith("#")
    ]

    official = zipfile.ZipFile(official_path)
    built = zipfile.ZipFile(built_path)
    o_names = {i.filename for i in official.infolist() if not i.is_dir()}
    b_names = {i.filename for i in built.infolist() if not i.is_dir()}

    def patched(name: str) -> bool:
        return any(fnmatch.fnmatchcase(name, p) for p in patterns)

    errors = []
    replace = {}
    for name in sorted(o_names | b_names):
        if name in KEEP_OFFICIAL:
            continue
        in_o, in_b = name in o_names, name in b_names
        if in_o and in_b:
            od, bd = official.read(name), built.read(name)
            if name.endswith(".class") and class_major(od) != class_major(bd):
                errors.append(f"class version {class_major(bd)} != official {class_major(od)}: {name} (build with the release JDK)")
                continue
            if norm(name, od) == norm(name, bd):
                continue
        if patched(name):
            if in_b:
                replace[name] = built.read(name)
            # an inner class that exists only in the official jar is dropped
        else:
            where = "differs" if in_o and in_b else ("only in built" if in_b else "only in official")
            errors.append(f"unexpected {where}: {name}")

    # Exact names must exist; wildcard lines (inner classes) may match nothing
    for p in patterns:
        if "*" not in p and p not in b_names:
            errors.append(f"pattern matches nothing in the built jar: {p}")

    if errors:
        print("overlay refused:", *errors, sep="\n  ")
        return 1

    dropped = sorted(n for n in o_names if patched(n) and n not in b_names)
    changed = sorted(replace)
    with zipfile.ZipFile(out_path, "w", zipfile.ZIP_DEFLATED) as out:
        for info in official.infolist():
            if info.filename in dropped:
                continue
            data = replace.pop(info.filename, None)
            out.writestr(info, data if data is not None else official.read(info.filename))
        for name, data in sorted(replace.items()):
            info = zipfile.ZipInfo(name, date_time=(1980, 2, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            out.writestr(info, data)

    print(f"overlay ok: {len(o_names)} official entries; replaced or added:")
    for name in changed:
        print(f"  {name}")
    for name in dropped:
        print(f"  (dropped) {name}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
