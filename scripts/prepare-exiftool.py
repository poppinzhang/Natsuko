#!/usr/bin/env python3
"""Create the offline ExifTool asset from the pinned, unmodified source archive."""
import hashlib
from pathlib import Path
import tarfile
import zipfile

ROOT = Path(__file__).resolve().parent.parent
VENDOR = ROOT / "third_party/metadata"
SOURCE = VENDOR / "sources/exiftool-13.53.tar.gz"
OUTPUT = VENDOR / "assets/metadata/exiftool-13.53.zip"
SOURCE_SHA256 = "d5224829f4835fd21ccfdde72a407afff29e94bc39b54203bf94e8d884675348"


def main():
    if hashlib.sha256(SOURCE.read_bytes()).hexdigest() != SOURCE_SHA256:
        raise ValueError("Pinned ExifTool source checksum mismatch")
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    with tarfile.open(SOURCE) as source, zipfile.ZipFile(OUTPUT, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as output:
        for member in sorted(source.getmembers(), key=lambda item: item.name):
            parts = Path(member.name).parts
            if not member.isfile() or len(parts) < 3 or parts[1] != "lib":
                continue
            relative = Path(*parts[1:]).as_posix()
            if ".." in Path(relative).parts:
                raise ValueError("Unsafe source archive member")
            info = zipfile.ZipInfo(relative, date_time=(2026, 3, 20, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            output.writestr(info, source.extractfile(member).read(), compresslevel=9)
    print(f"{hashlib.sha256(OUTPUT.read_bytes()).hexdigest()}  {OUTPUT.relative_to(VENDOR)}")


if __name__ == "__main__":
    main()
