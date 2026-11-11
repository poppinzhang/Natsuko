"""Regression tests for shipped metadata runtime integrity, without Android tools."""

from contextlib import redirect_stdout
import hashlib
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import warnings
import zipfile


SPEC = importlib.util.spec_from_file_location("verify_metadata", Path(__file__).with_name("verify-metadata.py"))
VERIFY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFY)


class MetadataAuditTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.vendor = self.root / "third_party/metadata"
        for name, value in (("ROOT", self.root), ("VENDOR", self.vendor)):
            patched = patch.object(VERIFY, name, value)
            patched.start()
            self.addCleanup(patched.stop)
        # Explicit packaged paths are a fixture contract, independent of the verifier's mapping.
        self.packaged = {
            "lib/arm64-v8a/libnatsuko_perl.so": b"arm64 interpreter fixture",
            "lib/x86_64/libnatsuko_perl.so": b"x86_64 interpreter fixture",
            "assets/metadata/perl-5.40.2-arm64-v8a.zip": b"arm64 modules fixture",
            "assets/metadata/perl-5.40.2-x86_64.zip": b"x86_64 modules fixture",
            "assets/metadata/exiftool-13.53.zip": b"ExifTool modules fixture",
        }
        self.checksums = {}
        for member, content in self.packaged.items():
            relative = member.replace("lib/", "jniLibs/", 1) if member.startswith("lib/") else member
            self.write(self.vendor / relative, content)
            self.checksums[relative] = hashlib.sha256(content).hexdigest()
        for name in ("ExifTool-NOTICE.txt", "Perl-NOTICE.txt"):
            content = f"Original attribution: {name}".encode()
            self.write(self.vendor / "notices" / name, content)
            self.packaged["assets/" + name] = content
        bridge = b"# metadata bridge fixture\n"
        self.write(self.root / "app/src/main/assets/metadata/location_bridge.pl", bridge)
        self.packaged["assets/metadata/location_bridge.pl"] = bridge
        self.write_checksums()

    @staticmethod
    def write(path, content):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(content)

    def write_checksums(self, omit=None):
        (self.vendor / "SHA256SUMS").write_text("".join(
            f"{checksum}  {name}\n" for name, checksum in self.checksums.items() if name != omit
        ))

    def archive(self, suffix=".apk", omit=None, change=None, duplicate=None, misplaced_notice=False):
        artifact = self.root / ("application" + suffix)
        prefix = "base/" if suffix == ".aab" else ""
        with zipfile.ZipFile(artifact, "w") as archive:
            for member, content in self.packaged.items():
                if member == omit:
                    continue
                if change and member in change:
                    content = change[member]
                if misplaced_notice and member == "assets/ExifTool-NOTICE.txt":
                    member = "assets/metadata/ExifTool-NOTICE.txt"
                archive.writestr(prefix + member, content)
                if duplicate == member:
                    with warnings.catch_warnings():
                        warnings.simplefilter("ignore", UserWarning)
                        archive.writestr(prefix + member, content)
        return artifact

    def test_complete_checksums_require_both_native_abis_to_be_inspected(self):
        with patch.object(VERIFY, "verify_elf") as inspect:
            self.assertEqual(self.checksums, VERIFY.verify_sources())
        self.assertEqual({self.vendor / "jniLibs" / abi / "libnatsuko_perl.so" for abi in ("arm64-v8a", "x86_64")},
                         {call.args[0] for call in inspect.call_args_list})

    def test_missing_payload_checksum_is_rejected(self):
        for name in ("jniLibs/x86_64/libnatsuko_perl.so", "assets/metadata/exiftool-13.53.zip"):
            with self.subTest(name=name), patch.object(VERIFY, "verify_elf"):
                self.write_checksums(omit=name)
                with self.assertRaisesRegex(AssertionError, "Missing embedded metadata payload checksums"):
                    VERIFY.verify_sources()

    def test_modified_runtime_module_is_rejected_before_packaging(self):
        name = "assets/metadata/perl-5.40.2-arm64-v8a.zip"
        self.write(self.vendor / name, b"substituted modules")
        with patch.object(VERIFY, "verify_elf"), self.assertRaisesRegex(AssertionError, "Checksum mismatch"):
            VERIFY.verify_sources()

    def test_apk_and_aab_contain_exact_runtime_bridge_and_root_attribution_assets(self):
        for suffix in (".apk", ".aab"):
            with self.subTest(suffix=suffix), redirect_stdout(io.StringIO()):
                VERIFY.verify_artifact(self.archive(suffix=suffix), self.checksums)

    def test_each_native_abi_is_required_in_distributable(self):
        for suffix in (".apk", ".aab"):
            for abi in ("arm64-v8a", "x86_64"):
                with self.subTest(suffix=suffix, abi=abi), self.assertRaisesRegex(AssertionError, "Missing/duplicate metadata payload"):
                    VERIFY.verify_artifact(self.archive(suffix=suffix, omit=f"lib/{abi}/libnatsuko_perl.so"), self.checksums)

    def test_modified_module_bridge_or_notice_is_rejected_in_artifact(self):
        for member in ("assets/metadata/exiftool-13.53.zip", "assets/metadata/location_bridge.pl", "assets/Perl-NOTICE.txt"):
            for suffix in (".apk", ".aab"):
                with self.subTest(member=member, suffix=suffix), self.assertRaisesRegex(AssertionError, "Metadata payload differs"):
                    original = self.packaged[member]
                    changed = bytes([original[0] ^ 1]) + original[1:]
                    VERIFY.verify_artifact(self.archive(suffix=suffix, change={member: changed}), self.checksums)

    def test_notice_in_runtime_subdirectory_does_not_satisfy_about_asset(self):
        with self.assertRaisesRegex(AssertionError, "Missing/duplicate metadata payload: base/assets/ExifTool-NOTICE.txt"):
            VERIFY.verify_artifact(self.archive(suffix=".aab", misplaced_notice=True), self.checksums)

    def test_duplicate_bridge_cannot_shadow_verified_entry(self):
        with self.assertRaisesRegex(AssertionError, "Missing/duplicate metadata payload"):
            VERIFY.verify_artifact(self.archive(duplicate="assets/metadata/location_bridge.pl"), self.checksums)


if __name__ == "__main__":
    unittest.main()
