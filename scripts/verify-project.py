"""Keep metadata and automatic test inventories in sync; no host or third-party dependencies."""

import json
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parents[1]


def verify():
    lines = (ROOT / "plugin.properties").read_text(encoding="ascii").splitlines()
    pairs = [line.split("=", 1) for line in lines if line and not line.startswith("#")]
    metadata = dict(pairs)
    assert len(pairs) == len(metadata), "Duplicate plugin metadata"
    assert set(metadata) == {"id", "name", "provider", "openSourceUrl", "version", "passiveVersion"}
    assert metadata["id"] == "io.github.yghfv.saltneri.online", "Do not change upgrade identity"
    assert metadata["name"] == "Salt Extend Online"
    assert metadata["openSourceUrl"] == "https://github.com/YGHFv/Salt-Extend-Online"
    for key in ("version", "passiveVersion"):
        assert re.fullmatch(r"\d+\.\d+\.\d+(?:-[a-z0-9-]+)?", metadata[key]), key
    matrix = json.loads((ROOT / "scripts/contract-tests.json").read_text(encoding="ascii"))
    assert matrix["schema"] == 1
    entries = matrix["tests"]
    classes = {e["class"] for e in entries}
    assert len(classes) == len(entries), "Duplicate contract test"
    tests = ROOT / "plugin/src/test/java"
    actual = {".".join(p.relative_to(tests).with_suffix("").parts) for p in tests.rglob("*Test.java")
              if re.search(r"public\s+static\s+void\s+main\s*\(", p.read_text(encoding="utf-8"))}
    assert classes == actual, f"Test inventory drift: missing={actual - classes}, stale={classes - actual}"
    for entry in entries:
        assert set(entry) == {"class", "baseOnly", "offline"}
        assert type(entry["baseOnly"]) is bool and type(entry["offline"]) is bool
        assert not entry["offline"] or entry["baseOnly"], "Host-free contracts must run in java.base"
    assert matrix["offlineSources"] and len(set(matrix["offlineSources"])) == len(matrix["offlineSources"])
    for name in matrix["offlineSources"]:
        path = pathlib.PurePosixPath(name)
        assert not path.is_absolute() and ".." not in path.parts and "\\" not in name
        assert (ROOT / "plugin/src/main/java/io/github/yghfv/saltneri/plugin" / path).is_file(), name
    for name in ("preference_config.json", "preference_config_experimental.json"):
        json.loads((ROOT / "plugin/src/main/resources" / name).read_text(encoding="utf-8"))
    pages = json.loads((ROOT / "plugin/src/main/resources/settings-pages.json").read_text(encoding="utf-8"))
    assert set(pages) == {"sidebar", "auto-source", "quality", "home", "proxy"}
    for items in pages.values():
        for item in items:
            if item["type"] == "list":
                assert len(item["entries"]) == len(item["entry_values"]) and item["default_value"] in item["entry_values"]
            elif item["type"] == "switch":
                assert isinstance(item["default_value"], bool)
    assert not any(item.get("key") == "bili.autoSourceSwitch" for items in pages.values() for item in items)
    print(f"PASS: metadata {metadata['version']}; {len(entries)} inventoried contracts, "
          f"{sum(e['offline'] for e in entries)} host-free plugin contracts")


if __name__ == "__main__":
    verify()
