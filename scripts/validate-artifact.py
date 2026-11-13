"""Verify local plugin packaging, isolation and opt-in configuration without loading the host."""

import argparse
import json
import pathlib
import zipfile


def validate(path, experimental):
    metadata = dict(line.split("=", 1) for line in
                    (pathlib.Path(__file__).resolve().parents[1] / "plugin.properties").read_text(encoding="ascii").splitlines()
                    if line and not line.startswith("#"))
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
        if attributes["Plugin-Id"] != metadata["id"]:
            raise ValueError("Unexpected plugin ID")
        if attributes["Plugin-Name"] != metadata["name"]:
            raise ValueError("Unexpected mod display name")
        if attributes["Plugin-Version"] != metadata["version" if experimental else "passiveVersion"]:
            raise ValueError("Unexpected mod version")
        if attributes.get("Plugin-Open-Source-Url") != metadata["openSourceUrl"]:
            raise ValueError("Missing or unexpected open-source URL")
        if experimental:
            if attributes.get("Plugin-Permissions") != "class-transform":
                raise ValueError("Experimental permission/version missing")
        elif "Plugin-Permissions" in attributes:
            raise ValueError("Passive artifact requests permissions")
        configs = json.loads(archive.read("classes/preference_config.json"))
        buttons = [pref["on_click"] for config in configs["configs"] for pref in config["preferences"] if pref["type"] == "button"]
        expected = ["openSidebarSettings", "openAutoSourceSettings", "openQualitySettings", "openProxySettings", "openHomeSettings", "clearCache", "showStatus"] if experimental else ["showStatus"]
        if buttons != ["io.github.yghfv.saltneri.plugin." + entry_class + "." + method for method in expected]:
            raise ValueError("Wrong opt-in action configuration")
        if experimental:
            main = {p.get("key"): p for c in configs["configs"] for p in c["preferences"] if "key" in p}
            if set(main) != {"cache.limitMiB", "cover.mode", "catalog.pageLimit"} or len(configs["configs"]) != 1 or len(configs["configs"][0]["preferences"]) != 10:
                raise ValueError("Unexpected condensed settings menu")
            pages = json.loads(archive.read("classes/settings-pages.json"))
            expected_pages = {
                "sidebar": {"ui.sidebarOptimization", "ui.hiddenSidebar", "ui.hideSidebarToggle"},
                "auto-source": {"netease.autoSourceSwitch", "qqmusic.autoSourceSwitch", "kugou.autoSourceSwitch", "applemusic.autoSourceSwitch"},
                "quality": {"bili.quality", "netease.quality", "qqmusic.quality", "kugou.quality", "video.resolution"},
                "home": {"ui.home", "bili.home", "netease.home", "qqmusic.home", "applemusic.home", "kugou.home"},
                "proxy": {"bili.proxy", "netease.proxy", "qqmusic.proxy", "applemusic.proxy", "kugou.proxy"},
            }
            if set(pages) != set(expected_pages):
                raise ValueError("Missing secondary settings pages")
            for page, keys in expected_pages.items():
                if {p["key"] for p in pages[page] if "key" in p} != keys:
                    raise ValueError("Secondary settings keys mismatch: " + page)
            prefs = dict(main)
            prefs.update({p["key"]: p for items in pages.values() for p in items if "key" in p})
            if prefs["video.resolution"]["entry_values"] != ["2160", "1440", "1080", "720", "480", "360"] or prefs["video.resolution"]["default_value"] != "1080":
                raise ValueError("Wrong preferred video resolution options")
            for key in expected_pages["proxy"]:
                if prefs[key]["entry_values"] != ["direct", "system"] or prefs[key]["default_value"] != "direct":
                    raise ValueError("Source proxy routing must default to direct")
            if any(prefs[key]["type"] != "switch" or prefs[key]["default_value"] is not False for key in ("netease.autoSourceSwitch", "qqmusic.autoSourceSwitch", "kugou.autoSourceSwitch", "applemusic.autoSourceSwitch", "ui.hideSidebarToggle", "ui.sidebarOptimization")):
                raise ValueError("Automatic source switching and toggle hiding must be opt-in")
            if prefs["ui.hiddenSidebar"]["default_value"] is not False:
                raise ValueError("Sidebar hiding must be opt-in")
            if prefs["ui.home"]["entry_values"] != ["restore", "Track", "BILIBILI", "NETEASE", "QQMUSIC", "APPLEMUSIC", "KUGOU"] or prefs["ui.home"]["default_value"] != "restore":
                raise ValueError("Wrong Salt startup home options")
            for key in ("bili.home", "netease.home", "qqmusic.home", "applemusic.home", "kugou.home"):
                if prefs[key]["entry_values"] != ["DISCOVER", "MINE", "SAVED"] or prefs[key]["default_value"] != "DISCOVER":
                    raise ValueError("Wrong platform startup home options")
            if prefs["ui.sidebarOptimization"]["type"] != "switch" or prefs["ui.sidebarOptimization"]["default_value"] is not False:
                raise ValueError("Sidebar optimization must be opt-in")
            if prefs["cover.mode"]["entry_values"] != ["fit", "center", "left", "right"] or prefs["cover.mode"]["default_value"] != "fit":
                raise ValueError("Wrong cover presentation options/default")
            if prefs["catalog.pageLimit"]["entry_values"] != ["20", "30", "50", "60", "100", "0"] or prefs["catalog.pageLimit"]["default_value"] != "30":
                raise ValueError("Wrong catalog page limits/default")
            if prefs["bili.quality"]["entry_values"] != ["dolby", "hires", "lossless", "high", "medium", "low"]:
                raise ValueError("Wrong Bilibili quality order")
            if prefs["netease.quality"]["entry_values"] != ["standard", "higher", "exhigh", "lossless", "hires", "jyeffect", "sky", "jymaster"] or prefs["netease.quality"]["default_value"] != "standard":
                raise ValueError("Wrong NetEase quality options")
            if prefs["qqmusic.quality"]["entry_values"] != ["standard", "higher", "exhigh", "lossless", "hires", "atmos51", "atmos71", "dolby", "dtsx", "master"] or prefs["qqmusic.quality"]["default_value"] != "standard":
                raise ValueError("Wrong QQ Music quality options")
            if prefs["cache.limitMiB"]["entry_values"] != ["0", "512", "1024", "2048", "4096", "8192"] or prefs["cache.limitMiB"]["default_value"] != "2048":
                raise ValueError("Wrong audio cache limits/default")
        profile = archive.read("classes/host-profiles/windows-steam-1.19.3.properties").decode("ascii")
        if sum(line.startswith("class.") for line in profile.splitlines()) != 20:
            raise ValueError("Missing extended engine fingerprints")
        if "classes/preference_config_experimental.json" in names:
            raise ValueError("Alternate configuration bundled")
        if any(name in names for name in ("classes/library.html", "classes/login.html")):
            raise ValueError("Browser UI must not be bundled")
        ui_profile = archive.read("classes/host-profiles/windows-steam-1.19.3-ui.properties").decode("ascii")
        if sum(not line.startswith("#") and "=" in line for line in ui_profile.splitlines()) != 21:
            raise ValueError("Missing native UI fingerprints")
        if "classes/io/github/yghfv/saltneri/plugin/ui/SaltNativeContent.class" not in names:
            raise ValueError("Missing compiled native Compose content")
        if "classes/auth/netease-web-login.ps1" not in names:
            raise ValueError("Missing official NetEase web-login helper")
        for required in ("auth/NativeWindowsLogin", "auth/WindowsEdgeDiscovery", "auth/WindowsEdgeProcess",
                         "auth/WindowsEdgeWebLogin", "auth/WindowsNativeDpapi", "host/PlaybackCoverRefresh",
                         "host/SavedTrackSession"):
            if "classes/io/github/yghfv/saltneri/plugin/" + required + ".class" not in names:
                raise ValueError("Missing native login/artwork implementation: " + required)
        if any(name.startswith("classes/applemusic/") or name.endswith("/AppleMusicPlayback.class") for name in names):
            raise ValueError("Removed Apple Music external playback controller bundled")
        for required in ("classes/musickit/pcm.js",
                         "classes/io/github/yghfv/saltneri/plugin/applemusic/AppleMusicPcmSource.class",
                         "classes/io/github/yghfv/saltneri/plugin/auth/WindowsMusicKitDecoder.class"):
            if required not in names:
                raise ValueError("Missing Apple Music native PCM playback: " + required)
        for required in ("ui/SettingsCatalog", "library/MusicAutoSourceResolver",
                         "library/PlatformPlaylistController", "library/PlatformPlaylistGateway"):
            if "classes/io/github/yghfv/saltneri/plugin/" + required + ".class" not in names:
                raise ValueError("Missing secondary settings/platform playlists: " + required)
        extension = "io.github.yghfv.saltneri.plugin.OnlineLyricsExtension"
        if archive.read("classes/META-INF/extensions.idx").decode("ascii").splitlines() != [extension]:
            raise ValueError("Missing lyric extension registration")
        if "classes/" + extension.replace(".", "/") + ".class" not in names:
            raise ValueError("Missing lyric extension class")
        if any("LibraryPage" in name or "LoginQrPage" in name for name in names):
            raise ValueError("Obsolete browser server bundled")
        for license_name in ("LICENSE", "THIRD_PARTY_NOTICES.md", "QR-Code-generator-MIT.txt", "Kotlin-Compose-Apache-2.0.txt", "Simple-Icons-CC0.txt", "Mygo-Qyin-AGPL-3.0.txt", "Go-DES-BSD-3-Clause.txt", "MineRadio-GPL-3.0.txt"):
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
