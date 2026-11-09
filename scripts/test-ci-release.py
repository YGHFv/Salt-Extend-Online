"""Host-free publication boundary tests; no real repository or network calls."""

import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("publish_release", Path(__file__).with_name("publish-release.py"))
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)
COMMIT = "a" * 40
ENV = {"GITHUB_REPOSITORY": "YGHFv/Salt-Extend-Online", "GITHUB_REF": "refs/heads/main", "GITHUB_SHA": COMMIT}


class PublicationTest(unittest.TestCase):
    def test_invalid_context(self):
        for changed in ({"GITHUB_REPOSITORY": "foreign/repo"}, {"GITHUB_REF": "refs/pull/1/merge"}, {"GITHUB_SHA": "bad"}):
            with patch.dict(os.environ, ENV | changed), patch.object(release, "gh") as network:
                with self.assertRaises(ValueError):
                    release.publish(Path("."), Path("."))
                network.assert_not_called()

    def test_publish_and_existing_version(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            artifact, checksum = root / "fixture.spmod", root / "fixture.spmod.sha256"
            artifact.write_bytes(b"fixture")
            checksum.write_bytes(b"checksum")
            verified = ({"version": "0.8.46-netease-device"}, artifact, checksum, "b" * 64)
            for existing in (True, False):
                calls = []

                def github(*args, **kwargs):
                    calls.append(args)
                    if args[:2] == ("api", "repos/YGHFv/Salt-Extend-Online/releases/tags/v0.8.46-netease-device"):
                        if existing:
                            return json.dumps({"draft": False})
                        if len(calls) == 1:
                            return None
                        return json.dumps({"draft": True, "assets": [
                            {"name": artifact.name, "size": artifact.stat().st_size},
                            {"name": checksum.name, "size": checksum.stat().st_size}]})
                    if args[0] == "api" and "/git/ref/tags/" in args[1]:
                        return None
                    return ""

                with patch.dict(os.environ, ENV), patch.object(release, "verify", return_value=verified), patch.object(release, "gh", side_effect=github):
                    release.publish(root, root)
                if existing:
                    self.assertEqual(len(calls), 1)
                else:
                    self.assertTrue(any(c[:2] == ("release", "create") and "--draft" in c for c in calls))
                    self.assertEqual(calls[-1][:2], ("release", "edit"))
                    self.assertIn("--draft=false", calls[-1])

    def test_tag_conflict_and_incomplete_upload(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            artifact, checksum = root / "fixture.spmod", root / "fixture.sha256"
            artifact.write_bytes(b"x")
            checksum.write_bytes(b"y")
            verified = ({"version": "1.2.3"}, artifact, checksum, "b" * 64)
            for conflict in (True, False):
                calls = []

                def github(*args, **kwargs):
                    calls.append(args)
                    if args[0] == "api" and "/releases/tags/" in args[1]:
                        return json.dumps({"draft": True, "assets": []})
                    if args[0] == "api" and "/git/ref/tags/" in args[1]:
                        return json.dumps({"object": {"type": "commit", "sha": "c" * 40 if conflict else COMMIT}})
                    return ""

                with patch.dict(os.environ, ENV), patch.object(release, "verify", return_value=verified), patch.object(release, "gh", side_effect=github):
                    with self.assertRaises(ValueError):
                        release.publish(root, root)
                self.assertFalse(any(c[:2] == ("release", "edit") for c in calls))

    def test_checksum_and_extra_files(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "plugin.properties").write_text("version=1.2.3\nid=fixture\n", encoding="ascii")
            directory = root / "assets"
            directory.mkdir()
            artifact = directory / "plugin-fixture-1.2.3.spmod"
            artifact.write_bytes(b"fixture")
            checksum = directory / (artifact.name + ".sha256")
            checksum.write_text("wrong", encoding="ascii")
            with self.assertRaisesRegex(ValueError, "checksum"):
                release.verify(root, directory)
            (directory / "unexpected").write_bytes(b"x")
            with self.assertRaisesRegex(ValueError, "exactly"):
                release.verify(root, directory)


if __name__ == "__main__":
    unittest.main()
