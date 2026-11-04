#!/usr/bin/env python3
"""Verify vendored FFmpeg and optional APK/AAB payloads without an Android NDK."""

import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import struct
from urllib.parse import urlparse
import zipfile


ROOT = Path(__file__).resolve().parents[1] / "third_party" / "ffmpeg"
EXPECTED = {
    f"jniLibs/{abi}/libnatsuko_{program}.so"
    for abi in ("arm64-v8a", "x86_64")
    for program in ("ffmpeg", "ffprobe")
}
REQUIRED_NOTICES = (
    "FFmpeg-LGPL-2.1.txt",
    "FFmpeg-LICENSE.md",
    "FFmpeg-NOTICE.txt",
    "LLVM-NOTICE.txt",
    "dav1d-BSD-2-Clause.txt",
    "FFmpeg-THIRD-PARTY.txt",
    "dav1d-THIRD-PARTY.txt",
    "Android-NDK-SYSROOT-NOTICE.txt",
)


def verify_sources() -> None:
    provenance = json.loads((ROOT / "provenance.json").read_text())
    for component in ("ffmpeg", "dav1d"):
        entry = provenance[component]
        name = f"{component}-{entry['version']}.tar.xz"
        assert Path(urlparse(entry["url"]).path).name == name, f"Unexpected source archive URL: {component}"
        checksum = entry["sha256"]
        assert re.fullmatch(r"[0-9a-f]{64}", checksum), f"Invalid source checksum: {component}"
        path = ROOT / "sources" / name
        assert path.is_file(), f"Missing corresponding source archive: {name}"
        assert hashlib.sha256(path.read_bytes()).hexdigest() == checksum, f"Source checksum mismatch: {name}"
        print(f"Verified corresponding source archive: {name}")


def notice_files() -> dict[str, bytes]:
    directory = ROOT / "notices"
    notices = {path.name: path.read_bytes() for path in sorted(directory.iterdir()) if path.is_file()}
    missing = set(REQUIRED_NOTICES) - notices.keys()
    assert not missing, f"Missing source notices: {', '.join(sorted(missing))}"
    for name, data in notices.items():
        assert data.strip(), f"Empty source notice: {name}"
    return notices


def verify_elf(path: Path) -> None:
    data = path.read_bytes()
    assert data[:6] == b"\x7fELF\x02\x01", f"Not little-endian ELF64: {path}"
    header = struct.unpack_from("<16sHHIQQQIHHHHHH", data)
    expected_machine = 183 if path.parent.name == "arm64-v8a" else 62
    assert header[1] == 3 and header[2] == expected_machine, f"Wrong PIE type/ABI: {path}"
    assert header[4] != 0, f"Missing executable entry point: {path}"
    segments = [
        struct.unpack_from("<IIQQQQQQ", data, header[5] + i * header[9])
        for i in range(header[10])
    ]
    loads = [s for s in segments if s[0] == 1]
    assert loads and all(s[7] >= 16384 for s in loads), f"Not 16 KB aligned: {path}"
    assert all((s[2] - s[3]) % 16384 == 0 for s in loads), f"Unaligned load offsets: {path}"
    interpreters = [data[s[2]:s[2] + s[5]].rstrip(b"\0") for s in segments if s[0] == 3]
    assert interpreters == [b"/system/bin/linker64"], f"Wrong Android linker: {path}"
    stacks = [s for s in segments if s[0] == 0x6474E551]
    assert stacks and all(not s[1] & 1 for s in stacks), f"Executable stack: {path}"

    dynamic = next(s for s in segments if s[0] == 2)
    tags = [struct.unpack_from("<qQ", data, o) for o in range(dynamic[2], dynamic[2] + dynamic[5], 16)]
    string_address = next(value for tag, value in tags if tag == 5)
    string_load = next(s for s in loads if s[3] <= string_address < s[3] + s[5])
    string_offset = string_load[2] + string_address - string_load[3]
    dependencies = set()
    for tag, value in tags:
        if tag == 1:
            start = string_offset + value
            end = data.index(b"\0", start)
            dependencies.add(data[start:end].decode("ascii"))
    assert dependencies == {"libc.so", "libm.so", "libz.so"}, f"Unexpected shared dependency: {path}: {dependencies}"


def verify_artifact(path: Path, sums: dict[str, str]) -> None:
    suffix = path.suffix.lower()
    assert suffix in (".apk", ".aab"), f"Expected an APK or AAB path: {path}"
    prefix = "base/" if suffix == ".aab" else ""
    with zipfile.ZipFile(path) as archive:
        counts = Counter(archive.namelist())
        for name, checksum in sorted(sums.items()):
            member = prefix + "lib/" + name.removeprefix("jniLibs/")
            assert counts[member] == 1, f"Missing or duplicate binary in {path}: {member}"
            assert archive.getinfo(member).file_size == (ROOT / name).stat().st_size, f"Binary size mismatch in {path}: {member}"
            actual = hashlib.sha256(archive.read(member)).hexdigest()
            assert actual == checksum, f"Binary checksum mismatch in {path}: {member}"
        notices = notice_files()
        for name, source in notices.items():
            member = prefix + "assets/" + name
            assert counts[member] == 1, f"Missing or duplicate notice in {path}: {member}"
            assert archive.getinfo(member).file_size == len(source), f"Notice size mismatch in {path}: {member}"
            assert archive.read(member) == source, f"Notice content mismatch in {path}: {member}"
    print(f"Verified {path}: all four embedded executables match SHA256SUMS; all {len(notices)} license notices match")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "artifacts", nargs="*", type=Path, metavar="APK_OR_AAB",
        help="also verify embedded executables and notices in each APK or AAB",
    )
    args = parser.parse_args()
    verify_sources()
    notice_files()
    sums = {}
    for line in (ROOT / "SHA256SUMS").read_text().splitlines():
        checksum, name = line.split(maxsplit=1)
        assert name not in sums, f"Duplicate checksum: {name}"
        sums[name] = checksum
    assert set(sums) == EXPECTED, "Missing or unexpected binary checksum entries"
    for name, checksum in sorted(sums.items()):
        path = ROOT / name
        assert hashlib.sha256(path.read_bytes()).hexdigest() == checksum, f"Checksum mismatch: {name}"
        verify_elf(path)
        print(f"Verified {name}: SHA256, ABI, PIE, 16 KB alignment, system-only shared dependencies")
    for artifact in args.artifacts:
        verify_artifact(artifact, sums)


if __name__ == "__main__":
    main()
