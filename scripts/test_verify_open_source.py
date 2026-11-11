"""Regression tests for APK/AAB open-source attribution, without Android tools."""

from contextlib import redirect_stdout
import copy
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import warnings
import zipfile


SPEC = importlib.util.spec_from_file_location("verify_open_source", Path(__file__).with_name("verify-open-source.py"))
VERIFY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFY)


class OpenSourceAuditTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        root_patch = patch.object(VERIFY, "ROOT", self.root)
        root_patch.start()
        self.addCleanup(root_patch.stop)
        self.generated = {}
        self.catalogs = {}
        native = self.root / "third_party" / "ffmpeg" / "notices"
        native.mkdir(parents=True)
        for filename in ("FFmpeg-LGPL-2.1.txt", "dav1d-BSD-2-Clause.txt", "LLVM-NOTICE.txt"):
            (native / filename).write_text(f"Original native license: {filename}")
        metadata = self.root / "third_party" / "metadata" / "notices"
        metadata.mkdir(parents=True)
        for filename in ("ExifTool-NOTICE.txt", "Perl-NOTICE.txt"):
            (metadata / filename).write_text(f"Metadata attribution: {filename}")
        for variant in ("debug", "release"):
            directory = self.root / "app" / "build" / "generated" / "openSource" / variant / "assets"
            self.generated[variant] = directory
            for name in ("LICENSE", "NOTICE", "THIRD_PARTY_NOTICES.md", "licenses/Kotlin-LICENSE.txt"):
                file = directory / "open_source" / name
                file.parent.mkdir(parents=True, exist_ok=True)
                file.write_text(f"Original generated attribution: {name}")
            self.catalogs[variant] = {
                "projects": [
                    {"id": "natsuko", "version": "1.0.0" + ("-debug" if variant == "debug" else ""),
                     "licenseFiles": ["open_source/LICENSE", "open_source/NOTICE", "open_source/THIRD_PARTY_NOTICES.md"]},
                    {"id": "ffmpeg", "licenseFiles": ["FFmpeg-LGPL-2.1.txt"]},
                    {"id": "dav1d", "licenseFiles": ["dav1d-BSD-2-Clause.txt"]},
                    {"id": "llvm", "licenseFiles": ["LLVM-NOTICE.txt"]},
                    {"id": "exiftool", "licenseFiles": ["ExifTool-NOTICE.txt"]},
                    {"id": "perl", "licenseFiles": ["Perl-NOTICE.txt"]},
                    {"id": "kotlin", "licenseFiles": ["open_source/licenses/Kotlin-LICENSE.txt"]},
                ],
                "dependencies": ["org.jetbrains.kotlin:kotlin-stdlib:2.2.21"],
            }
            if variant == "debug":
                self.catalogs[variant]["dependencies"].append("androidx.compose.ui:ui-tooling-android:1.11.4")
            self.write_catalog(variant)

    def write_catalog(self, variant):
        (self.generated[variant] / VERIFY.INDEX).write_text(json.dumps(self.catalogs[variant], indent=2))

    def archive(self, variant="release", suffix=".apk", omit=None, change=None, duplicate=None, extra=None):
        # The artifact filename deliberately does not identify the build variant.
        path = self.root / ("renamed-application" + suffix)
        prefix = "base/assets/" if suffix == ".aab" else "assets/"
        payload = {
            file.relative_to(self.generated[variant]).as_posix(): file.read_bytes()
            for file in self.generated[variant].rglob("*") if file.is_file()
        }
        payload.update({file.name: file.read_bytes() for file in (self.root / "third_party" / "ffmpeg" / "notices").iterdir()})
        payload.update({file.name: file.read_bytes() for file in (self.root / "third_party" / "metadata" / "notices").iterdir()})
        with zipfile.ZipFile(path, "w") as archive:
            for name, data in payload.items():
                if name == omit:
                    continue
                if change and name in change:
                    data = change[name]
                archive.writestr(prefix + name, data)
                if name == duplicate:
                    with warnings.catch_warnings():
                        warnings.simplefilter("ignore", UserWarning)
                        archive.writestr(prefix + name, data)
            if extra:
                for name, data in extra.items():
                    archive.writestr(name, data)
        return path

    def test_debug_release_apk_and_aab_pass_after_renaming(self):
        for variant, suffix in (("debug", ".apk"), ("release", ".apk"), ("release", ".aab")):
            with self.subTest(variant=variant, suffix=suffix), redirect_stdout(io.StringIO()):
                VERIFY.verify_artifact(self.archive(variant=variant, suffix=suffix))

    def test_missing_catalog_fails(self):
        with self.assertRaisesRegex(AssertionError, "Missing open-source catalog"):
            VERIFY.verify_artifact(self.archive(omit=VERIFY.INDEX))

    def test_catalog_with_omitted_dependency_fails(self):
        catalog = copy.deepcopy(self.catalogs["debug"])
        catalog["dependencies"].pop()
        with self.assertRaisesRegex(AssertionError, "Catalog does not uniquely match"):
            VERIFY.verify_artifact(self.archive(variant="debug", change={VERIFY.INDEX: json.dumps(catalog).encode()}))

    def test_each_original_license_file_is_required(self):
        for name in ("LICENSE", "NOTICE", "THIRD_PARTY_NOTICES.md"):
            with self.subTest(name=name), self.assertRaisesRegex(AssertionError, "Missing attribution asset"):
                VERIFY.verify_artifact(self.archive(omit="open_source/" + name))

    def test_same_size_altered_license_is_rejected(self):
        name = "open_source/licenses/Kotlin-LICENSE.txt"
        original = (self.generated["release"] / name).read_bytes()
        changed = bytes([original[0] ^ 1]) + original[1:]
        with self.assertRaisesRegex(AssertionError, "Attribution content mismatch"):
            VERIFY.verify_artifact(self.archive(change={name: changed}))

    def test_missing_native_license_fails(self):
        with self.assertRaisesRegex(AssertionError, "Missing attribution asset"):
            VERIFY.verify_artifact(self.archive(omit="FFmpeg-LGPL-2.1.txt"))

    def test_duplicate_catalog_and_license_fail(self):
        for name in (VERIFY.INDEX, "open_source/NOTICE", "LLVM-NOTICE.txt"):
            with self.subTest(name=name), self.assertRaisesRegex(AssertionError, "Duplicate ZIP entries"):
                VERIFY.verify_artifact(self.archive(duplicate=name))

    def test_duplicate_unrelated_zip_entry_also_fails(self):
        artifact = self.archive(extra={"classes.dex": b"fixture"})
        with warnings.catch_warnings(), zipfile.ZipFile(artifact, "a") as archive:
            warnings.simplefilter("ignore", UserWarning)
            archive.writestr("classes.dex", b"fixture")
        with self.assertRaisesRegex(AssertionError, "Duplicate ZIP entries"):
            VERIFY.verify_artifact(artifact)

    def test_missing_generated_build_output_fails(self):
        artifact = self.archive()
        for directory in self.generated.values():
            (directory / VERIFY.INDEX).unlink()
        with self.assertRaisesRegex(AssertionError, "No generated open-source catalogs"):
            VERIFY.verify_artifact(artifact)

    def test_required_projects_cannot_disappear_from_generated_catalog(self):
        self.catalogs["release"]["projects"] = [
            project for project in self.catalogs["release"]["projects"] if project["id"] != "ffmpeg"
        ]
        self.write_catalog("release")
        with self.assertRaisesRegex(AssertionError, "missing required original/native projects"):
            VERIFY.verify_artifact(self.archive())

    def test_license_reference_without_source_file_fails(self):
        self.catalogs["release"]["projects"][0]["licenseFiles"].append("open_source/missing.txt")
        self.write_catalog("release")
        with self.assertRaisesRegex(AssertionError, "No generated license asset"):
            VERIFY.verify_artifact(self.archive())

    def test_empty_license_fails_even_if_artifact_matches_generated_file(self):
        (self.generated["release"] / "open_source/NOTICE").write_text(" \n")
        with self.assertRaisesRegex(AssertionError, "Empty generated/source attribution asset"):
            VERIFY.verify_artifact(self.archive())

    def test_path_traversal_in_generated_catalog_fails(self):
        self.catalogs["release"]["projects"][0]["licenseFiles"].append("../NOTICE")
        self.write_catalog("release")
        with self.assertRaisesRegex(AssertionError, "Unsafe catalog license path"):
            VERIFY.verify_artifact(self.archive())

    def test_unexpected_stale_attribution_file_fails(self):
        with self.assertRaisesRegex(AssertionError, "Unexpected open-source assets"):
            VERIFY.verify_artifact(self.archive(extra={"assets/open_source/old-license.txt": b"old attribution"}))


if __name__ == "__main__":
    unittest.main()
