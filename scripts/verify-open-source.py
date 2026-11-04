#!/usr/bin/env python3
"""Audit generated open-source catalogs and notices in APK/AAB files using only Python."""

import argparse
from collections import Counter
import json
from pathlib import Path, PurePosixPath
import zipfile


ROOT = Path(__file__).resolve().parents[1]
INDEX = "open_source/index.json"
REQUIRED_PROJECTS = {"natsuko", "ffmpeg", "dav1d", "llvm"}


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def license_paths(catalog: dict) -> set[str]:
    projects = catalog.get("projects")
    require(isinstance(projects, list) and bool(projects), "Catalog projects must be a nonempty list")
    require(all(isinstance(project, dict) for project in projects), "Invalid catalog project")
    ids = [project.get("id") for project in projects]
    require(all(isinstance(value, str) and value.strip() for value in ids), "Missing catalog project id")
    require(len(ids) == len(set(ids)), "Duplicate catalog project id")
    require(REQUIRED_PROJECTS <= set(ids), "Catalog is missing required original/native projects")
    dependencies = catalog.get("dependencies")
    require(isinstance(dependencies, list) and bool(dependencies), "Catalog dependencies must be a nonempty list")
    require(
        all(isinstance(value, str) and len(value.split(":")) == 3
            and all(part.strip() for part in value.split(":")) for value in dependencies),
        "Catalog dependencies must contain exact Maven coordinates",
    )
    require(len(dependencies) == len(set(dependencies)), "Duplicate catalog dependency coordinate")

    paths = set()

    def collect(value):
        if isinstance(value, dict):
            if "licenseFiles" in value:
                files = value["licenseFiles"]
                require(isinstance(files, list) and bool(files), "Empty catalog licenseFiles")
                for name in files:
                    require(isinstance(name, str) and bool(name.strip()), "Invalid catalog license path")
                    path = PurePosixPath(name)
                    require(
                        not path.is_absolute() and ".." not in path.parts and "\\" not in name
                        and path.as_posix() == name and name != ".",
                        f"Unsafe catalog license path: {name}",
                    )
                    paths.add(name)
            for nested in value.values():
                collect(nested)
        elif isinstance(value, list):
            for nested in value:
                collect(nested)

    for project in projects:
        require("licenseFiles" in project, f"Missing licenseFiles for project: {project['id']}")
    collect(catalog)
    return paths


def generated_catalogs() -> dict[str, Path]:
    catalogs = {}
    for variant in ("debug", "release"):
        directory = ROOT / "app" / "build" / "generated" / "openSource" / variant / "assets"
        if (directory / INDEX).is_file():
            catalogs[variant] = directory
    require(bool(catalogs), "No generated open-source catalogs; build the APK/AAB before auditing")
    return catalogs


def verify_artifact(path: Path) -> None:
    require(path.suffix.lower() in (".apk", ".aab"), f"Expected an APK or AAB: {path}")
    prefix = "base/assets/" if path.suffix.lower() == ".aab" else "assets/"
    with zipfile.ZipFile(path) as archive:
        counts = Counter(archive.namelist())
        duplicates = sorted(name for name, count in counts.items() if count != 1)
        require(not duplicates, f"Duplicate ZIP entries in {path}: {', '.join(duplicates)}")
        member = prefix + INDEX
        require(counts[member] == 1, f"Missing open-source catalog in {path}: {member}")
        actual_index = archive.read(member)
        matches = [
            (variant, directory) for variant, directory in generated_catalogs().items()
            if (directory / INDEX).read_bytes() == actual_index
        ]
        require(len(matches) == 1, f"Catalog does not uniquely match a generated build variant: {path}")
        variant, directory = matches[0]
        catalog = json.loads(actual_index)
        require(isinstance(catalog, dict), "Invalid open-source catalog object")
        licenses = license_paths(catalog)
        expected_assets = {
            file.relative_to(directory).as_posix(): file.read_bytes()
            for file in sorted(directory.rglob("*")) if file.is_file()
        }
        for name in licenses:
            if name not in expected_assets:
                # Native notices are supplied as a separate Android asset source set.
                require(PurePosixPath(name).name == name, f"No generated license asset: {name}")
                source = ROOT / "third_party" / "ffmpeg" / "notices" / name
                require(source.is_file(), f"Missing source license notice: {name}")
                expected_assets[name] = source.read_bytes()
        for name, data in sorted(expected_assets.items()):
            require(bool(data.strip()), f"Empty generated/source attribution asset: {name}")
            member = prefix + name
            require(counts[member] == 1, f"Missing attribution asset in {path}: {member}")
            require(archive.getinfo(member).file_size == len(data), f"Attribution size mismatch in {path}: {member}")
            require(archive.read(member) == data, f"Attribution content mismatch in {path}: {member}")
        unexpected = {
            name for name in counts if name.startswith(prefix + "open_source/") and not name.endswith("/")
        } - {prefix + name for name in expected_assets}
        require(not unexpected, f"Unexpected open-source assets in {path}: {', '.join(sorted(unexpected))}")
    print(f"Verified {path}: {variant} catalog and all {len(expected_assets)} attribution assets match")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("artifacts", nargs="+", type=Path, metavar="APK_OR_AAB")
    args = parser.parse_args()
    for path in args.artifacts:
        verify_artifact(path)


if __name__ == "__main__":
    main()
