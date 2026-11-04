"""Regression tests for the release payload integrity audit (standard library only)."""

from contextlib import redirect_stdout
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import warnings
import zipfile


SPEC = importlib.util.spec_from_file_location("verify_ffmpeg", Path(__file__).with_name("verify-ffmpeg.py"))
VERIFY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFY)


class ArtifactAuditTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        root_patch = patch.object(VERIFY, "ROOT", self.root)
        root_patch.start()
        self.addCleanup(root_patch.stop)
        self.payload = {}
        self.sums = {}
        for name in sorted(VERIFY.EXPECTED):
            data = ("native fixture: " + name).encode()
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
            self.sums[name] = hashlib.sha256(data).hexdigest()
            self.payload["lib/" + name.removeprefix("jniLibs/")] = data
        for name in VERIFY.REQUIRED_NOTICES:
            data = ("license fixture: " + name).encode()
            path = self.root / "notices" / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
            self.payload["assets/" + name] = data
        self.binary = "lib/arm64-v8a/libnatsuko_ffmpeg.so"
        self.notice = "assets/FFmpeg-LGPL-2.1.txt"

    def archive(self, suffix=".apk", omit=None, change=None, duplicate=None, prefix=None):
        path = self.root / ("application" + suffix)
        if prefix is None:
            prefix = "base/" if suffix == ".aab" else ""
        with zipfile.ZipFile(path, "w") as archive:
            for member, data in self.payload.items():
                if member == omit:
                    continue
                if member == change:
                    data = bytes([data[0] ^ 1]) + data[1:]
                archive.writestr(prefix + member, data)
                if member == duplicate:
                    with warnings.catch_warnings():
                        warnings.simplefilter("ignore", UserWarning)
                        archive.writestr(prefix + member, data)
        return path

    def test_complete_apk_and_aab_pass(self):
        for suffix in (".apk", ".aab"):
            with self.subTest(suffix=suffix), redirect_stdout(io.StringIO()):
                VERIFY.verify_artifact(self.archive(suffix=suffix), self.sums)

    def test_missing_binary_fails(self):
        with self.assertRaisesRegex(AssertionError, "Missing or duplicate binary"):
            VERIFY.verify_artifact(self.archive(omit=self.binary), self.sums)

    def test_same_size_modified_binary_fails_hash_check(self):
        with self.assertRaisesRegex(AssertionError, "Binary checksum mismatch"):
            VERIFY.verify_artifact(self.archive(change=self.binary), self.sums)

    def test_missing_notice_fails(self):
        with self.assertRaisesRegex(AssertionError, "Missing or duplicate notice"):
            VERIFY.verify_artifact(self.archive(omit=self.notice), self.sums)

    def test_each_required_native_notice_is_enforced(self):
        for name in VERIFY.REQUIRED_NOTICES:
            with self.subTest(name=name), self.assertRaisesRegex(AssertionError, "Missing or duplicate notice"):
                VERIFY.verify_artifact(self.archive(omit="assets/" + name), self.sums)

    def test_new_source_notice_must_also_be_bundled(self):
        (self.root / "notices" / "new-upstream-notice.txt").write_text("Additional copyright notice")
        with self.assertRaisesRegex(AssertionError, "Missing or duplicate notice"):
            VERIFY.verify_artifact(self.archive(), self.sums)

    def test_missing_source_notice_fails(self):
        (self.root / "notices" / "FFmpeg-THIRD-PARTY.txt").unlink()
        with self.assertRaisesRegex(AssertionError, "Missing source notices"):
            VERIFY.verify_artifact(self.archive(), self.sums)

    def test_empty_source_notice_fails(self):
        (self.root / "notices" / "dav1d-THIRD-PARTY.txt").write_text(" \n")
        with self.assertRaisesRegex(AssertionError, "Empty source notice"):
            VERIFY.verify_artifact(self.archive(), self.sums)

    def test_same_size_modified_notice_fails_content_check(self):
        with self.assertRaisesRegex(AssertionError, "Notice content mismatch"):
            VERIFY.verify_artifact(self.archive(change=self.notice), self.sums)

    def test_duplicate_binary_fails(self):
        with self.assertRaisesRegex(AssertionError, "Missing or duplicate binary"):
            VERIFY.verify_artifact(self.archive(duplicate=self.binary), self.sums)

    def test_duplicate_notice_fails(self):
        with self.assertRaisesRegex(AssertionError, "Missing or duplicate notice"):
            VERIFY.verify_artifact(self.archive(duplicate=self.notice), self.sums)

    def test_wrong_archive_layout_fails(self):
        with self.assertRaisesRegex(AssertionError, "Missing or duplicate binary"):
            VERIFY.verify_artifact(self.archive(suffix=".aab", prefix=""), self.sums)


class SourceAuditTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        root_patch = patch.object(VERIFY, "ROOT", self.root)
        root_patch.start()
        self.addCleanup(root_patch.stop)
        (self.root / "sources").mkdir()
        self.provenance = {}
        for component in ("ffmpeg", "dav1d"):
            name = f"{component}-1.0.tar.xz"
            data = f"original {component} source archive".encode()
            (self.root / "sources" / name).write_bytes(data)
            self.provenance[component] = {
                "version": "1.0",
                "url": f"https://example.org/releases/{name}",
                "sha256": hashlib.sha256(data).hexdigest(),
            }
        self.write_provenance()

    def write_provenance(self):
        (self.root / "provenance.json").write_text(json.dumps(self.provenance))

    def test_matching_source_archives_pass(self):
        with redirect_stdout(io.StringIO()):
            VERIFY.verify_sources()

    def test_missing_corresponding_source_fails(self):
        (self.root / "sources" / "ffmpeg-1.0.tar.xz").unlink()
        with self.assertRaisesRegex(AssertionError, "Missing corresponding source archive"):
            VERIFY.verify_sources()

    def test_modified_source_archive_fails(self):
        (self.root / "sources" / "ffmpeg-1.0.tar.xz").write_bytes(b"modified archive")
        with self.assertRaisesRegex(AssertionError, "Source checksum mismatch"):
            VERIFY.verify_sources()

    def test_dav1d_checksum_is_also_verified(self):
        self.provenance["dav1d"]["sha256"] = "0" * 64
        self.write_provenance()
        with redirect_stdout(io.StringIO()), self.assertRaisesRegex(AssertionError, "Source checksum mismatch: dav1d"):
            VERIFY.verify_sources()


if __name__ == "__main__":
    unittest.main()
