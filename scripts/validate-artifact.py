"""Verify local plugin packaging, isolation and opt-in configuration without loading the host."""

import argparse
import json
import pathlib
import zipfile


def validate(path, experimental):
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        if len(set(names)) != len(names):
            raise ValueError("Duplicate ZIP entries")
        for name in names:
            if not name.startswith("classes/") or ".." in pathlib.PurePosixPath(name).parts or "\\" in name:
                raise ValueError("Unexpected plugin entry: " + name)
            if name.endswith(".class"):
                vendor = name.startswith("classes/io/nayuki/qrcodegen/") and name.rsplit("/", 1)[1].split("$")[0].split(".")[0] in {"QrCode", "QrSegment", "BitBuffer", "DataTooLongException"}
                if not name.startswith("classes/io/github/yghfv/saltneri/") and not vendor:
                    raise ValueError("Third-party implementation bundled: " + name)
                if any(test in name for test in ("ContractTest", "SmokeTest", "IntegrationTest", "TransformTest", "RenderTest", "HostRangeReader")):
                    raise ValueError("Test implementation bundled: " + name)
        text = archive.read("classes/META-INF/MANIFEST.MF").decode("ascii")
        if not text.endswith("\r\n\r\n") or any(len(line) > 72 for line in text.splitlines()):
            raise ValueError("Invalid manifest line folding")
        unfolded = text.replace("\r\n ", "")
        pairs = [line.split(": ", 1) for line in unfolded.splitlines() if line]
        attributes = dict(pairs)
        if len(pairs) != len(attributes):
            raise ValueError("Duplicate manifest attributes")
        entry_class = "ExperimentalPlugin" if experimental else "MainPlugin"
        if attributes["Plugin-Class"] != "io.github.yghfv.saltneri.plugin." + entry_class:
            raise ValueError("Wrong plugin entry point")
        if attributes["Plugin-Id"] != "io.github.yghfv.saltneri.online":
            raise ValueError("Unexpected plugin ID")
        if attributes["Plugin-Name"] != "Salt Extend Online":
            raise ValueError("Unexpected mod display name")
        if experimental:
            if attributes.get("Plugin-Permissions") != "class-transform" or attributes["Plugin-Version"] != "0.8.6-web-verification":
                raise ValueError("Experimental permission/version missing")
        elif "Plugin-Permissions" in attributes:
            raise ValueError("Passive artifact requests permissions")
        configs = json.loads(archive.read("classes/preference_config.json"))
        buttons = [pref["on_click"] for config in configs["configs"] for pref in config["preferences"] if pref["type"] == "button"]
        expected = ["showStatus", "installNativeUi", "openLibrary", "loginBilibili", "loginNetease", "restoreBilibili", "restoreNetease", "cancelLogins", "logoutBilibili", "logoutNetease", "resolveBilibili", "resolveNetease", "prepareBilibili", "prepareNetease", "armAdapter", "playPrepared", "playTestTone", "stopAdapter"] if experimental else ["showStatus"]
        if buttons != ["io.github.yghfv.saltneri.plugin." + entry_class + "." + method for method in expected]:
            raise ValueError("Wrong opt-in action configuration")
        if experimental:
            prefs = {p.get("key"): p for c in configs["configs"] for p in c["preferences"] if "key" in p}
            if prefs["bili.target"]["type"] != "edittext" or prefs["bili.target"]["default_value"] != "":
                raise ValueError("Resolver must have an empty explicit target")
            if prefs["bili.quality"]["entry_values"] != ["dolby", "hires", "lossless", "high", "medium", "low"]:
                raise ValueError("Wrong Bilibili quality order")
            if prefs["auth.remember"]["type"] != "switch" or prefs["auth.remember"]["default_value"] is not True:
                raise ValueError("Remembered login must default on")
            if prefs["netease.target"]["type"] != "edittext" or prefs["netease.target"]["default_value"] != "":
                raise ValueError("NetEase resolver must have an empty explicit target")
            if prefs["netease.quality"]["entry_values"] != ["standard", "exhigh"] or prefs["netease.quality"]["default_value"] != "standard":
                raise ValueError("Wrong free-track NetEase quality options")
        profile = archive.read("classes/host-profiles/windows-steam-1.19.3.properties").decode("ascii")
        if sum(line.startswith("class.") for line in profile.splitlines()) != 17:
            raise ValueError("Missing extended engine fingerprints")
        if "classes/preference_config_experimental.json" in names:
            raise ValueError("Alternate configuration bundled")
        if any(name in names for name in ("classes/library.html", "classes/login.html")):
            raise ValueError("Browser UI must not be bundled")
        ui_profile = archive.read("classes/host-profiles/windows-steam-1.19.3-ui.properties").decode("ascii")
        if sum(not line.startswith("#") and "=" in line for line in ui_profile.splitlines()) != 20:
            raise ValueError("Missing native UI fingerprints")
        if "classes/io/github/yghfv/saltneri/plugin/ui/SaltNativeContent.class" not in names:
            raise ValueError("Missing compiled native Compose content")
        if "classes/auth/netease-web-login.ps1" not in names:
            raise ValueError("Missing official NetEase web-login helper")
        if any("LibraryPage" in name or "LoginQrPage" in name for name in names):
            raise ValueError("Obsolete browser server bundled")
        for license_name in ("LICENSE", "THIRD_PARTY_NOTICES.md", "QR-Code-generator-MIT.txt", "Kotlin-Compose-Apache-2.0.txt", "Simple-Icons-CC0.txt"):
            if "classes/META-INF/licenses/" + license_name not in names:
                raise ValueError("Missing license: " + license_name)
        print(f"PASS: {'experimental' if experimental else 'passive'} artifact isolation/configuration; {len(names)} entries / {path.stat().st_size} bytes")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("artifact", type=pathlib.Path)
    parser.add_argument("--experimental", action="store_true")
    args = parser.parse_args()
    validate(args.artifact, args.experimental)


if __name__ == "__main__":
    main()
