"""Check native UI call-site fingerprints and every emitted host method/field reference."""

import argparse
import functools
import hashlib
import importlib.util
import json
import pathlib
import struct
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location("salt_inspector", ROOT / "scripts/inspect-salt.py")
inspector = importlib.util.module_from_spec(spec)
spec.loader.exec_module(inspector)


def references(data):
    position = 8

    def read(size):
        nonlocal position
        value = data[position:position + size]
        position += size
        if len(value) != size:
            raise ValueError("Truncated constant pool")
        return value

    def u2():
        return struct.unpack(">H", read(2))[0]

    pool = [None] * u2()
    index = 1
    while index < len(pool):
        tag = read(1)[0]
        if tag == 1:
            pool[index] = read(u2()).decode("utf-8", errors="replace")
        elif tag in (7, 8, 16, 19, 20):
            pool[index] = (tag, u2())
        elif tag in (9, 10, 11, 12, 17, 18):
            pool[index] = (tag, u2(), u2())
        elif tag in (3, 4):
            read(4)
        elif tag in (5, 6):
            read(8)
            index += 1
        elif tag == 15:
            read(3)
        else:
            raise ValueError("Unsupported pool tag")
        index += 1
    for value in pool:
        if isinstance(value, tuple) and value[0] in (9, 10, 11):
            _, owner, member = value
            _, name, descriptor = pool[member]
            yield value[0], pool[pool[owner][1]], pool[name], pool[descriptor]


def verify(host_package, classes):
    profile = json.loads((ROOT / "profiles/windows-steam-1.19.3-ui.json").read_text())
    with zipfile.ZipFile(host_package) as host:
        for pinned in profile["classes"]:
            path = pinned["name"].replace(".", "/") + ".class"
            if hashlib.sha256(host.read(path)).hexdigest() != pinned["sha256"]:
                raise ValueError("Host native UI fingerprint mismatch")

        @functools.cache
        def metadata(owner):
            return inspector.class_metadata(host.read(owner + ".class"))

        def present(owner, name, descriptor, field):
            info = metadata(owner)
            members = info["fields" if field else "methods"]
            if any(m["name"] == name and m["descriptor"] == descriptor for m in members):
                return True
            if name == "<init>":
                return False
            ancestors = [info["superclass"]] + info["interfaces"]
            for parent in filter(None, ancestors):
                path = parent.replace(".", "/")
                if path + ".class" in host.namelist() and present(path, name, descriptor, field):
                    return True
            return False

        checked = set()
        for file in (classes / "io/github/yghfv/saltneri/plugin/ui").glob("*.class"):
            for tag, owner, name, descriptor in references(file.read_bytes()):
                if not owner.startswith(("androidx/", "com/xuncorp/", "kotlin/", "kotlinx/", "org/jetbrains/skia/")):
                    continue
                key = (tag, owner, name, descriptor)
                if key in checked:
                    continue
                if owner + ".class" not in host.namelist() or not present(owner, name, descriptor, tag == 9):
                    raise ValueError("Native host ABI reference missing: " + repr(key))
                checked.add(key)
        if len(checked) < 30:
            raise ValueError("Native Compose content not compiled")
    print(f"PASS: {len(profile['classes'])} native UI fingerprints; {len(checked)} emitted host ABI references")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("host", type=pathlib.Path)
    parser.add_argument("classes", type=pathlib.Path)
    args = parser.parse_args()
    verify(args.host, args.classes)
