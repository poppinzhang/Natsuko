#!/usr/bin/env python3
"""Exercise the same ExifTool bridge used on Android against container fixtures."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile

ROOT = Path(__file__).resolve().parent.parent
FIXTURES = ROOT / "app/src/androidTest/assets/location"
BRIDGE = ROOT / "app/src/main/assets/metadata/location_bridge.pl"
EXTENSIONS = ("jpg", "png", "webp", "heic", "tif", "arw", "cr2", "cr3", "nef", "raf", "rw2")


def main():
    with tempfile.TemporaryDirectory(prefix="natsuko-metadata-test-") as temporary:
        work = Path(temporary)
        with tarfile.open(ROOT / "third_party/metadata/sources/exiftool-13.53.tar.gz") as archive:
            archive.extractall(work, filter="data")
        upstream = work / "exiftool-13.53"

        def run(*arguments, success=True):
            result = subprocess.run(["perl", "-I", str(upstream / "lib"), str(BRIDGE), *map(str, arguments)], capture_output=True, text=True)
            assert (result.returncode == 0) == success, result.stderr
            return result.stdout

        def inspect(file):
            result = subprocess.run(["perl", str(upstream / "exiftool"), "-j", "-ImageDataHash", "-Make", "-Model", "-Orientation", "-DateTimeOriginal", "-Copyright", str(file)], capture_output=True, text=True, check=True)
            values = json.loads(result.stdout)[0]
            values.pop("SourceFile", None)
            return values

        source = FIXTURES / "source.jpg"
        digest = hashlib.sha256(source.read_bytes()).hexdigest()
        for extension in EXTENSIONS:
            target = work / f"target.{extension}"
            shutil.copyfile(FIXTURES / target.name, target)
            before = inspect(target)
            assert before.get("ImageDataHash"), extension
            output = run("copy", source, target, work / f"temporary.{extension}")
            assert "COORD\t35.6717\t139.6949" in output, extension
            assert "Tokyo".encode().hex() in output, extension
            assert "代々木公園".encode().hex() in output, extension
            assert "Japan".encode().hex() in output, extension
            assert "Kingston".encode().hex() not in output, extension
            assert inspect(target) == before, f"{extension}: image payload or unrelated metadata changed"
            assert hashlib.sha256(source.read_bytes()).hexdigest() == digest
        named_only = run("read", FIXTURES / "named-only.jpg")
        assert "COORD\t" not in named_only and "Tokyo".encode().hex() in named_only
        bookkeeping = work / "bookkeeping.jpg"
        shutil.copyfile(source, bookkeeping)
        subprocess.run(["perl", str(upstream / "exiftool"), "-overwrite_original", "-Location:all=", "-GPS:all=", "-GPSVersionID=2.3.0.0", "-GPSMapDatum=WGS84", str(bookkeeping)], check=True, capture_output=True)
        assert run("read", bookkeeping).strip() == "TYPE\tJPEG"
        webp = work / "structured.webp"
        shutil.copyfile(FIXTURES / "target.webp", webp)
        structured = run("copy", FIXTURES / "structured.jpg", webp, work / "struct-temp.webp")
        assert "COORD\t-33.86\t151.21" in structured
        assert "Sydney Harbour".encode().hex() in structured
        iptc = run("copy", FIXTURES / "iptc-only.jpg", webp, work / "iptc-temp.webp")
        assert "Tokyo".encode().hex() in iptc and "Japan".encode().hex() in iptc
        assert "Sydney Harbour".encode().hex() not in iptc
        legacy = work / "legacy-iptc.jpg"
        shutil.copyfile(FIXTURES / "iptc-only.jpg", legacy)
        subprocess.run(["perl", str(upstream / "exiftool"), "-overwrite_original", "-IPTC:City=München", "-IPTC:Country-PrimaryLocationName=Österreich", str(legacy)], check=True, capture_output=True)
        legacy_result = run("copy", legacy, webp, work / "legacy-temp.webp")
        assert "München".encode().hex() in legacy_result and "Österreich".encode().hex() in legacy_result
        conflicting = work / "conflicting.jpg"
        shutil.copyfile(source, conflicting)
        subprocess.run(["perl", str(upstream / "exiftool"), "-overwrite_original", "-XMP-photoshop:City=Kyoto", str(conflicting)], check=True, capture_output=True)
        jpeg = work / "conflicting-target.jpg"
        shutil.copyfile(FIXTURES / "target.jpg", jpeg)
        conflict_result = run("copy", conflicting, jpeg, work / "conflicting-temp.jpg")
        assert "Kyoto".encode().hex() in conflict_result and "Tokyo".encode().hex() in conflict_result
        previous = webp.read_bytes()
        run("copy", conflicting, webp, work / "conflicting-temp.webp", success=False)
        assert webp.read_bytes() == previous, "Failed name preservation must leave the working copy unchanged"
        invalid = work / "broken.jpg"
        invalid.write_text("invalid image")
        run("read", invalid, success=False)
        wrong = work / "wrong.png"
        shutil.copyfile(source, wrong)
        run("read", wrong, success=False)
        run("copy", FIXTURES / "named-only.jpg", webp, work / "invalid-temp.webp", success=False)
    print("Location metadata: 11 containers preserve image payload and unrelated metadata; structured/Unicode/IPTC names and invalid-input checks passed.")


if __name__ == "__main__":
    main()
