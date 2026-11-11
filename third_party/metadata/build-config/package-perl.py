#!/usr/bin/env python3
"""Put pure Perl modules at one relocatable -I path, without native build files."""
from pathlib import Path
import sys
import zipfile

source, architecture, destination = Path(sys.argv[1]), sys.argv[2], Path(sys.argv[3])
files = {}
for tree in (source, source / architecture):
    for path in sorted(tree.rglob('*')):
        if not path.is_file() or path.suffix not in ('.pm', '.pl', '.al', '.ix'):
            continue
        relative = path.relative_to(tree)
        if architecture in relative.parts or 'CORE' in relative.parts or 'pod' in relative.parts:
            continue
        files['lib/' + relative.as_posix()] = path.read_bytes()
for required in ('strict.pm', 'warnings.pm', 'Carp.pm', 'Exporter.pm', 'XSLoader.pm',
                 'AutoLoader.pm', 'constant.pm', 'Config.pm', 'Encode.pm', 'IO.pm',
                 'Cwd.pm', 'File/Glob.pm', 'Storable.pm', 'parent.pm',
                 'Compress/Zlib.pm', 'Compress/Raw/Zlib.pm'):
    assert 'lib/' + required in files, f'Missing runtime module: {required}'
destination.parent.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(destination, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
    for name, data in sorted(files.items()):
        entry = zipfile.ZipInfo(name, (2026, 1, 1, 0, 0, 0))
        entry.compress_type = zipfile.ZIP_DEFLATED
        entry.external_attr = 0o100644 << 16
        archive.writestr(entry, data)
print(f'{destination}: {len(files)} modules/data files')
