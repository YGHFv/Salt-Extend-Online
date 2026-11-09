"""Validate vendored QR sources and adapted Apple Music control scripts."""
import hashlib
import json
import pathlib

root = pathlib.Path(__file__).resolve().parent.parent
lock = json.loads((root / "reference-lock.json").read_text(encoding="utf-8"))["qrCodeGenerator"]
directory = root / "plugin/src/main/java/io/nayuki/qrcodegen"
if {path.name for path in directory.glob("*.java")} != set(lock["sha256"]):
    raise SystemExit("Unexpected vendored QR source")
for name, digest in lock["sha256"].items():
    if hashlib.sha256((directory / name).read_bytes()).hexdigest() != digest:
        raise SystemExit("QR source fingerprint mismatch: " + name)
print("PASS: 4 pinned Nayuki MIT QR source fingerprints")

resources = json.loads((root / "reference-lock.json").read_text(encoding="utf-8"))["mineradio"]["resources"]
for name, entry in resources.items():
    source = root / "plugin/src/main/resources/applemusic/lib" / name
    if hashlib.sha256(source.read_bytes()).hexdigest() != entry["adaptedSha256"]:
        raise SystemExit("Apple control source fingerprint mismatch: " + name)
print("PASS: 4 pinned GPL-3.0 Apple Music control resource fingerprints")
