"""Validate the only vendored runtime dependency against the reviewed reference lock."""
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
