# Metadata fixtures

These small metadata-focused files derive from the unmodified ExifTool 13.53
`t/images` fixtures included in `third_party/metadata/sources/exiftool-13.53.tar.gz`.
They are tests of metadata containers and copying, not full-resolution camera
decoding examples. Upstream terms: the same terms as Perl (Artistic/GPL), see
`third_party/metadata/notices/ExifTool-NOTICE.txt` and `Perl-Artistic.txt`.

- `source.jpg`: upstream `GPS.jpg`, edited to coordinates 35.6717,139.6949 and
  Tokyo / Japan / 代々木公園 location tags.
- `named-only.jpg`: source with GPS removed; location names remain.
- `structured.jpg`: named-only plus IPTC Extension LocationCreated coordinates
  -33.86,151.21 and the name Sydney Harbour.
- `iptc-only.jpg`: source with XMP removed, retaining EXIF GPS and IPTC names.
- `target.jpg`, `.png`, `.webp`, `.heic`, `.tif`, `.cr2`, `.cr3`, `.raf`, `.rw2`:
  upstream ExifTool.jpg, PNG.png, RIFF.webp, QuickTime.heic, ExifTool.tif,
  CanonRaw.cr2, CanonRaw.cr3, FujiFilm.raf and Panasonic.rw2 respectively.
- `target.nef`: upstream Nikon.nef, with IFD0 dimensions normalized to 6 × 1
  to match its deliberately tiny 18-byte strip. Generation used ExifTool's `-m`
  only to repair the upstream fixture; application processing never ignores errors.
- `target.arw`: structural Sony TIFF/ARW fixture derived from ExifTool.tif with
  Make=SONY and Model=DSLR-A100. A separate 30 MB Sony camera ARW was also verified
  during implementation; it is not redistributed in these compact test assets.

The Android tests run the actual packaged Perl/ExifTool backend for all eleven
container families, Unicode place names, structured XMP coordinates, IPTC-to-WebP
mapping, stale place removal, missing metadata, corrupt content, and format mismatch.
