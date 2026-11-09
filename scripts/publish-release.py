"""Publish only a verified build; never overwrite a published version."""

import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile


def metadata(root):
    values = dict(line.split("=", 1) for line in (root / "plugin.properties").read_text(encoding="ascii").splitlines()
                  if line and not line.startswith("#"))
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:-[a-zA-Z0-9.-]+)?", values["version"]):
        raise ValueError("Invalid release version")
    if not re.fullmatch(r"[a-zA-Z0-9_.-]+", values["id"]):
        raise ValueError("Invalid plugin ID")
    return values


def verify(root, directory):
    values = metadata(root)
    name = f"plugin-{values['id']}-{values['version']}.spmod"
    directory = directory.resolve()
    artifact, checksum = directory / name, directory / (name + ".sha256")
    if set(p.name for p in directory.iterdir()) != {name, name + ".sha256"}:
        raise ValueError("Expected exactly one versioned mod and its checksum")
    if artifact.is_symlink() or checksum.is_symlink() or not 0 < artifact.stat().st_size <= 16 * 1024 * 1024:
        raise ValueError("Invalid release artifact")
    digest = hashlib.sha256(artifact.read_bytes()).hexdigest()
    if checksum.read_text(encoding="ascii").strip() != f"{digest}  {name}":
        raise ValueError("Artifact checksum mismatch")
    spec = importlib.util.spec_from_file_location("artifact_validator", root / "scripts/validate-artifact.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    module.validate(artifact, True)
    return values, artifact, checksum, digest


def gh(*arguments, allow_missing=False):
    result = subprocess.run(["gh", *arguments], capture_output=True, text=True, encoding="utf-8")
    if result.returncode:
        # Only an explicit GitHub 404 is absence; transport/auth failures must stop publication.
        if allow_missing and ("HTTP 404" in result.stderr or "release not found" in result.stderr.lower()):
            return None
        raise RuntimeError("GitHub release operation failed; publication stopped")
    return result.stdout


def publish(root, directory):
    repo, commit = os.environ.get("GITHUB_REPOSITORY"), os.environ.get("GITHUB_SHA", "")
    if repo != "YGHFv/Salt-Extend-Online" or os.environ.get("GITHUB_REF") != "refs/heads/main":
        raise ValueError("Publication is restricted to the repository's main branch")
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise ValueError("Invalid build commit")
    values, artifact, checksum, digest = verify(root, directory)
    tag = "v" + values["version"]
    # The REST releases/tags endpoint omits drafts; gh release view resolves them by ID.
    release = gh("release", "view", tag, "--repo", repo, "--json", "isDraft,assets", allow_missing=True)
    if release is not None and not json.loads(release)["isDraft"]:
        print(f"Version {tag} is already published; no assets replaced. Bump plugin.properties to publish new changes.")
        return
    ref = gh("api", f"repos/{repo}/git/ref/tags/{tag}", allow_missing=True)
    if ref is not None:
        target = json.loads(ref)["object"]
        if target["type"] != "commit" or target["sha"] != commit:
            raise ValueError("Existing version tag does not identify this build; refuse to overwrite")
    else:
        gh("api", f"repos/{repo}/git/refs", "-f", f"ref=refs/tags/{tag}", "-f", f"sha={commit}")
    notes = (
        f"Development preview for Windows / Steam Salt 1.19.3.\n\n"
        f"Install the `.spmod` in Salt's mod manager and fully restart Salt after updating. "
        f"Do not enable multiple versions of the same mod. Requires CLASS_TRANSFORM permission.\n\n"
        f"Built from commit `{commit}` after host-free and full native regression. "
        f"No Salt binaries, accounts or credentials are included.\n\n"
        f"SHA-256: `{digest}`\n\n"
        f"See README and versioned docs for changes and verification limits."
    )
    with tempfile.TemporaryDirectory(prefix="salt-release-") as temporary:
        note_file = Path(temporary) / "notes.md"
        note_file.write_text(notes, encoding="utf-8")
        if release is None:
            gh("release", "create", tag, "--repo", repo, "--verify-tag", "--draft", "--prerelease",
               "--title", f"Salt Extend Online {values['version']}", "--notes-file", str(note_file))
        gh("release", "upload", tag, str(artifact), str(checksum), "--repo", repo, "--clobber")
        uploaded = json.loads(gh("release", "view", tag, "--repo", repo, "--json", "isDraft,assets"))
        assets = {a["name"]: a["size"] for a in uploaded["assets"]}
        if assets != {artifact.name: artifact.stat().st_size, checksum.name: checksum.stat().st_size}:
            raise ValueError("Draft release assets are incomplete; left unpublished")
        gh("release", "edit", tag, "--repo", repo, "--draft=false", "--prerelease", "--latest=false",
           "--notes-file", str(note_file))
    print(f"Published {tag}: verified mod and SHA-256; development prerelease")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--artifact-dir", required=True, type=Path)
    args = parser.parse_args()
    publish(Path(__file__).resolve().parents[1], args.artifact_dir)
