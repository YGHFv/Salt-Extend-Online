"""Read installed Salt class-file metadata without loading or changing the app."""

import argparse
import hashlib
import io
import json
import pathlib
import re
import struct
import zipfile


CLASSES = (
    "com.xuncorp.pisces.PiscesMediaItem",
    "com.xuncorp.pisces.PiscesPlayer",
    "com.xuncorp.pisces.PiscesPlayer$Properties",
    "com.xuncorp.pisces.\u0528",
    "androidx.compose.ui.\u015b",
    "androidx.compose.ui.\u0454",
    "androidx.compose.ui.\u017a",
    "com.xuncorp.voxzen.service.PlaybackController",
    "com.xuncorp.voxzen.service.PlaybackService",
    "androidx.compose.ui.\u0aae",
    "com.xuncorp.voxzen.cast.HttpRangeBassStream",
    "com.xuncorp.voxzen.cast.HttpRangeSource",
    "com.xuncorp.voxzen.cast.HttpRangeStreamOwner",
    "com.xuncorp.voxzen.tag.TagParser",
    "com.xuncorp.spc.v.Vri",
    "com.xuncorp.spw.workshop.api.WorkshopApi",
    "com.xuncorp.spw.workshop.api.hook.HookRegistrar",
    "com.xuncorp.spc.core.queue.PlaybackQueueState",
    "com.xuncorp.spc.core.queue.PlaybackQueue$Mode",
    "com.xuncorp.spc.core.queue.\u037f",
)


def class_metadata(data):
    stream = io.BytesIO(data)

    def read(size):
        value = stream.read(size)
        if len(value) != size:
            raise ValueError("Truncated class file")
        return value

    def u1():
        return read(1)[0]

    def u2():
        return struct.unpack(">H", read(2))[0]

    def u4():
        return struct.unpack(">I", read(4))[0]

    if read(4) != b"\xca\xfe\xba\xbe":
        raise ValueError("Not a JVM class file")
    minor, major = u2(), u2()
    pool = [None] * u2()
    index = 1
    while index < len(pool):
        tag = u1()
        if tag == 1:
            # Modified UTF-8 nulls do not occur in member names/descriptors.
            pool[index] = read(u2()).decode("utf-8", errors="replace")
        elif tag in (3, 4):
            read(4)
        elif tag in (5, 6):
            read(8)
            index += 1
        elif tag in (7, 8, 16, 19, 20):
            pool[index] = u2()
        elif tag in (9, 10, 11, 12, 17, 18):
            read(4)
        elif tag == 15:
            read(3)
        else:
            raise ValueError(f"Unknown constant-pool tag: {tag}")
        index += 1

    def text(index):
        if not 0 < index < len(pool) or not isinstance(pool[index], str):
            raise ValueError("Invalid UTF-8 constant reference")
        return pool[index]

    def class_name(index):
        if not 0 < index < len(pool) or not isinstance(pool[index], int):
            raise ValueError("Invalid class constant reference")
        return text(pool[index]).replace("/", ".")

    def attributes():
        for _ in range(u2()):
            text(u2())
            read(u4())

    def members():
        result = []
        for _ in range(u2()):
            access, name, descriptor = u2(), text(u2()), text(u2())
            attributes()
            result.append({"name": name, "descriptor": descriptor, "access": access})
        return result

    access, this_class, super_class = u2(), u2(), u2()
    interfaces = [class_name(u2()) for _ in range(u2())]
    fields, methods = members(), members()
    attributes()
    if stream.read(1):
        raise ValueError("Unexpected trailing class data")
    return {
        "name": class_name(this_class),
        "superclass": class_name(super_class) if super_class else None,
        "interfaces": interfaces,
        "majorVersion": major,
        "minorVersion": minor,
        "access": access,
        "sha256": hashlib.sha256(data).hexdigest(),
        "fields": fields,
        "methods": methods,
    }


def property_escape(value):
    result = []
    for char in value:
        if ord(char) > 127:
            result.append(f"\\u{ord(char):04x}")
        elif char in "\\:= #!":
            result.append("\\" + char)
        else:
            result.append(char)
    return "".join(result)


def inspect(salt_home):
    app = salt_home / "app"
    configs = list(app.glob("*.cfg"))
    if len(configs) != 1:
        raise ValueError("Expected one jpackage application configuration")
    config = configs[0].read_text(encoding="utf-8-sig")
    version = re.search(r"(?m)^java-options=-Djpackage\.app-version=(.+)$", config)
    classpath = re.search(r"(?m)^app\.classpath=\$APPDIR[\\/](.+)$", config)
    if not version or not classpath:
        raise ValueError("Missing app version or packaged classpath")
    package = (app / classpath.group(1).strip()).resolve()
    if not package.is_relative_to(app.resolve()):
        raise ValueError("Application package is outside the app directory")
    with package.open("rb") as stream:
        package_hash = hashlib.file_digest(stream, "sha256").hexdigest()
    with zipfile.ZipFile(package) as archive:
        classes = [class_metadata(archive.read(name.replace(".", "/") + ".class")) for name in CLASSES]
    return {
        "schemaVersion": 1,
        "profileId": "windows-steam-" + version.group(1).strip(),
        "os": "windows",
        "channel": "Steam" if (salt_home / "steam_appid.txt").exists() else "unknown",
        "spwVersion": version.group(1).strip(),
        "packageName": package.name,
        "packageSha256": package_hash,
        "verificationLevel": "static-metadata-only",
        "livePlaybackVerified": False,
        "classes": classes,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("salt_home", type=pathlib.Path)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--fingerprints", type=pathlib.Path)
    args = parser.parse_args()
    report = inspect(args.salt_home.resolve())
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, ensure_ascii=True) + "\n", encoding="ascii")
    if args.fingerprints:
        if report["spwVersion"] != "1.19.3" or report["channel"] != "Steam":
            raise ValueError("Only the inspected Steam 1.19.3 profile can be pinned by this script")
        lines = [
            "# Generated fingerprints only; no host implementation is redistributed.",
            "profile.id=" + report["profileId"],
            "profile.version=" + report["spwVersion"],
            "profile.channel=" + report["channel"],
            "profile.packageSha256=" + report["packageSha256"],
        ]
        lines.extend("class." + property_escape(c["name"]) + "=" + c["sha256"] for c in report["classes"])
        args.fingerprints.parent.mkdir(parents=True, exist_ok=True)
        args.fingerprints.write_text("\n".join(lines) + "\n", encoding="ascii")
    print(f"Inspected Salt {report['spwVersion']}: {len(report['classes'])} class fingerprints; no app execution")


if __name__ == "__main__":
    main()
