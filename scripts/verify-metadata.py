#!/usr/bin/env python3
"""Verify the offline metadata runtime and its exact APK/AAB payloads."""

import argparse
from collections import Counter
import hashlib
from pathlib import Path
import re
import struct
import zipfile

ROOT = Path(__file__).resolve().parents[1]
VENDOR = ROOT / "third_party" / "metadata"
EXPECTED = {
    "jniLibs/arm64-v8a/libnatsuko_perl.so",
    "jniLibs/x86_64/libnatsuko_perl.so",
    "assets/metadata/perl-5.40.2-arm64-v8a.zip",
    "assets/metadata/perl-5.40.2-x86_64.zip",
    "assets/metadata/exiftool-13.53.zip",
}


def verify_elf(path):
    data = path.read_bytes()
    assert data[:6] == b"\x7fELF\x02\x01", f"Not ELF64: {path}"
    header = struct.unpack_from("<16sHHIQQQIHHHHHH", data)
    assert header[1] == 3 and header[2] == (183 if path.parent.name == "arm64-v8a" else 62), f"Wrong PIE/ABI: {path}"
    assert header[4], f"Missing entry point: {path}"
    segments = [struct.unpack_from("<IIQQQQQQ", data, header[5] + i * header[9]) for i in range(header[10])]
    loads = [s for s in segments if s[0] == 1]
    assert loads and all(s[7] >= 16384 and (s[2] - s[3]) % 16384 == 0 for s in loads), f"Not 16 KB aligned: {path}"
    assert [data[s[2]:s[2] + s[5]].rstrip(b"\0") for s in segments if s[0] == 3] == [b"/system/bin/linker64"]
    stacks = [s for s in segments if s[0] == 0x6474E551]
    assert stacks and all(not s[1] & 1 for s in stacks), f"Executable stack: {path}"
    dynamic = next(s for s in segments if s[0] == 2)
    tags = [struct.unpack_from("<qQ", data, offset) for offset in range(dynamic[2], dynamic[2] + dynamic[5], 16)]
    address = next(value for tag, value in tags if tag == 5)
    segment = next(s for s in loads if s[3] <= address < s[3] + s[5])
    strings = segment[2] + address - segment[3]
    dependencies = set()
    for tag, value in tags:
        if tag == 1:
            start = strings + value
            dependencies.add(data[start:data.index(b"\0", start)].decode("ascii"))
    assert dependencies <= {"libc.so", "libm.so", "libdl.so", "libz.so"}, f"Unexpected dependency: {dependencies}"


def verify_sources():
    checksums = {}
    for line in (VENDOR / "SHA256SUMS").read_text().splitlines():
        if not line.strip():
            continue
        checksum, name = line.split(maxsplit=1)
        assert re.fullmatch("[0-9a-f]{64}", checksum) and name not in checksums, f"Invalid checksum: {line}"
        assert not Path(name).is_absolute() and ".." not in Path(name).parts
        checksums[name] = checksum
    assert EXPECTED <= checksums.keys(), "Missing embedded metadata payload checksums"
    for name, expected in checksums.items():
        path = VENDOR / name
        assert hashlib.sha256(path.read_bytes()).hexdigest() == expected, f"Checksum mismatch: {name}"
        if name.startswith("jniLibs/"):
            verify_elf(path)
    return checksums


def verify_artifact(path, checksums):
    assert path.suffix in (".apk", ".aab")
    prefix = "base/" if path.suffix == ".aab" else ""
    payload = {
        ("lib/" + name.removeprefix("jniLibs/")) if name.startswith("jniLibs/") else name: VENDOR / name
        for name in checksums if name.startswith(("jniLibs/", "assets/"))
    }
    payload["assets/metadata/location_bridge.pl"] = ROOT / "app/src/main/assets/metadata/location_bridge.pl"
    payload.update({"assets/" + file.name: file for file in (VENDOR / "notices").iterdir() if file.is_file()})
    with zipfile.ZipFile(path) as archive:
        counts = Counter(archive.namelist())
        for name, source in payload.items():
            member = prefix + name
            assert counts[member] == 1, f"Missing/duplicate metadata payload: {member}"
            assert archive.read(member) == source.read_bytes(), f"Metadata payload differs: {member}"
    print(f"Verified {path}: metadata executables, modules, bridge and notices")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("artifacts", nargs="*", type=Path)
    args = parser.parse_args()
    checksums = verify_sources()
    print("Verified metadata runtime checksums, both ABIs and 16 KB alignment")
    for path in args.artifacts:
        verify_artifact(path, checksums)


if __name__ == "__main__":
    main()
