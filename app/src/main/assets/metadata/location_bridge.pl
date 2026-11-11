# Natsuko's narrowly scoped bridge to the bundled ExifTool library.
# Arguments are passed directly by ProcessBuilder; no shell or user config is used.
use strict;
BEGIN { $Image::ExifTool::noConfig = 1; }
use Image::ExifTool;

my %types = (
    jpg => 'JPEG', jpeg => 'JPEG', png => 'PNG', webp => 'WEBP',
    heic => 'HEIC', heif => 'HEIC', tif => 'TIFF', tiff => 'TIFF',
    arw => 'ARW', cr3 => 'CR3', cr2 => 'CR2', nef => 'NEF',
    raf => 'RAF', rw2 => 'RW2',
);

sub fail { print STDERR $_[0], "\n"; exit($_[1] || 2); }
sub encode { my $s = defined $_[0] ? "$_[0]" : ''; utf8::encode($s) if utf8::is_utf8($s); return unpack('H*', $s); }
sub flattened {
    my ($value) = @_;
    return join('; ', map { flattened($_) } @$value) if ref($value) eq 'ARRAY';
    return join('; ', map { $_ . ': ' . flattened($value->{$_}) } sort keys %$value) if ref($value) eq 'HASH';
    return ref($value) ? '' : defined($value) ? "$value" : '';
}
sub place_names {
    my ($value, $name) = @_;
    return () if $name =~ /^GPS/ && $name !~ /^GPS(?:AreaInformation|ProcessingMethod)$/;
    return map { place_names($_, $name) } @$value if ref($value) eq 'ARRAY';
    return map { place_names($value->{$_}, $_) } sort keys %$value if ref($value) eq 'HASH';
    return () if ref($value) || !defined($value) || $value !~ /\S/;
    return "$value";
}
sub nested_coordinates {
    my ($value) = @_;
    return map { nested_coordinates($_) } @$value if ref($value) eq 'ARRAY';
    return () unless ref($value) eq 'HASH';
    return ($value) if defined($value->{GPSLatitude}) && defined($value->{GPSLongitude});
    return map { nested_coordinates($value->{$_}) } sort keys %$value;
}
sub read_location {
    my ($file) = @_;
    -f $file && -s $file or fail('Invalid image');
    my ($ext) = $file =~ /\.([^.]+)$/;
    defined($ext) && exists($types{lc $ext}) or fail('Unsupported image format', 3);
    my $tool = Image::ExifTool->new;
    $tool->Options(Duplicates => 1, Struct => 1, Charset => 'UTF8');
    $tool->ExtractInfo($file) or fail('Unable to read image');
    my $info = $tool->GetInfo;
    $info->{Error} and fail('Invalid image metadata');
    my $type = $tool->GetValue('FileType', 'ValueConv') || '';
    $type = 'HEIC' if $type eq 'HEIF';
    $type = 'WEBP' if $type eq 'Extended WEBP';
    $type eq $types{lc $ext} or fail('Unsupported image format', 3);
    my (@fields, @names, %coordinates);
    for my $key ($tool->GetFoundTags('Tag')) {
        my $name = Image::ExifTool::GetTagName($key);
        my $group = $tool->GetGroup($key, 1);
        my $category = $tool->GetGroup($key, 2);
        next unless $category eq 'Location' || $group eq 'GPS' || $name =~ /^GPS/;
        # Schema/version markers alone do not identify a place and should not
        # make an otherwise unlocated target require an overwrite decision.
        next if $name eq 'GPSVersionID' || $name eq 'GPSMapDatum';
        my $value = flattened($tool->GetValue($key, 'PrintConv'));
        next unless length $value;
        push @fields, [$group . ':' . $name, $value];
        push @names, place_names($tool->GetValue($key, 'PrintConv'), $name);
        if ($name eq 'LocationCreated' || $name eq 'LocationShown') {
            my @nested = nested_coordinates($tool->GetValue($key, 'ValueConv'));
            for my $index (0 .. $#nested) { $coordinates{$name . $index} = $nested[$index]; }
        }
        if ($name =~ /^GPS(Latitude|Longitude)(Ref)?$/) {
            $coordinates{$group}{$name} = $tool->GetValue($key, 'ValueConv');
        }
    }
    my ($latitude, $longitude);
    for my $group (sort { ($a eq 'Composite' ? 0 : 1) <=> ($b eq 'Composite' ? 0 : 1) || $a cmp $b } keys %coordinates) {
        my $c = $coordinates{$group};
        my ($lat, $lon) = @{$c}{'GPSLatitude', 'GPSLongitude'};
        next unless defined $lat && defined $lon && "$lat" =~ /^[-+]?\d+(?:\.\d+)?(?:e[-+]?\d+)?$/i && "$lon" =~ /^[-+]?\d+(?:\.\d+)?(?:e[-+]?\d+)?$/i;
        $lat = -abs($lat) if ($c->{GPSLatitudeRef} || '') =~ /^S/i;
        $lon = -abs($lon) if ($c->{GPSLongitudeRef} || '') =~ /^W/i;
        next unless abs($lat) <= 90 && abs($lon) <= 180;
        ($latitude, $longitude) = ($lat, $lon);
        last;
    }
    return { type => $type, fields => \@fields, names => \@names, latitude => $latitude, longitude => $longitude };
}
sub emit_location {
    my ($location) = @_;
    print "TYPE\t", $location->{type}, "\n";
    if (defined $location->{latitude}) {
        print "COORD\t", $location->{latitude}, "\t", $location->{longitude}, "\n";
    }
    for my $field (@{$location->{fields}}) {
        print "FIELD\t", encode($field->[0]), "\t", encode($field->[1]), "\n";
    }
}

my ($operation, $source, $target, $temporary) = @ARGV;
defined($operation) && defined($source) or fail('Missing operation');
my $source_location = read_location($source);
if ($operation eq 'read') {
    @ARGV == 2 or fail('Invalid arguments');
    emit_location($source_location);
} elsif ($operation eq 'copy') {
    @ARGV == 4 && defined($target) && defined($temporary) && !-e $temporary or fail('Invalid copy arguments');
    defined $source_location->{latitude} or fail('Source has no valid coordinates');
    read_location($target);
    my $writer = Image::ExifTool->new;
    $writer->Options(Charset => 'UTF8');
    # Delete only location tags before replacing them: old city names/altitude must
    # not survive when the selected source has only coordinates. All other EXIF,
    # camera maker notes, XMP, IPTC, image data, and color profiles remain in place.
    $writer->SetNewValue('Location:*');
    $writer->SetNewValue('GPS:*');
    $writer->SetNewValue('XMP:GPS*');
    $writer->SetNewValue('XMP-iptcExt:LocationCreated');
    $writer->SetNewValue('XMP-iptcExt:LocationShown');
    $writer->SetNewValuesFromFile($source, '*:*<Location:*', '*:*<GPS:*', '*:*<XMP:GPS*');
    # A family-2 delete expands to flattened structure fields. Cancel those
    # pending field deletes when replacing the complete source structure.
    for my $structure ('LocationCreated', 'LocationShown') {
        if (grep { $_->[0] eq 'XMP-iptcExt:' . $structure } @{$source_location->{fields}}) {
            $writer->SetNewValue('XMP-iptcExt:' . $structure . '*', undef, Replace => 2);
        }
    }
    $writer->SetNewValuesFromFile($source, 'XMP-iptcExt:LocationCreated', 'XMP-iptcExt:LocationShown');
    # WebP cannot store IPTC-IIM. Its standard XMP equivalents preserve place
    # names across all supported formats while retaining source blocks wherever
    # the target container supports them.
    my %source_fields = map { $_->[0] => 1 } @{$source_location->{fields}};
    for my $mapping (
        ['XMP-photoshop:City', 'IPTC:City'],
        ['XMP-photoshop:State', 'IPTC:Province-State'],
        ['XMP-photoshop:Country', 'IPTC:Country-PrimaryLocationName'],
        ['XMP-iptcCore:CountryCode', 'IPTC:Country-PrimaryLocationCode'],
        ['XMP-iptcCore:Location', 'IPTC:Sub-location']) {
        # Preserve an explicitly different XMP value. If the target cannot
        # represent both conflicting names, readback fails without publishing a
        # lossy result instead of silently replacing one of the source values.
        $writer->SetNewValuesFromFile($source, $mapping->[0] . '<' . $mapping->[1])
            unless $source_fields{$mapping->[0]};
    }
    # Normalize coordinates too, so read-only maker-note or QuickTime positions
    # have a portable EXIF representation in the output.
    $writer->SetNewValue('EXIF:GPSLatitude', abs($source_location->{latitude}), Type => 'ValueConv');
    $writer->SetNewValue('EXIF:GPSLongitude', abs($source_location->{longitude}), Type => 'ValueConv');
    $writer->SetNewValue('EXIF:GPSLatitudeRef', $source_location->{latitude} < 0 ? 'S' : 'N');
    $writer->SetNewValue('EXIF:GPSLongitudeRef', $source_location->{longitude} < 0 ? 'W' : 'E');
    my $result = $writer->WriteInfo($target, $temporary);
    $result or fail('Unable to write image metadata');
    # Keep the original extension on the temporary path for content/type checks.
    my $written = read_location($temporary);
    defined($written->{latitude}) && abs($written->{latitude} - $source_location->{latitude}) < 0.0000001 &&
        abs($written->{longitude} - $source_location->{longitude}) < 0.0000001 or fail('Location verification failed', 4);
    my %written_names = map { $_ => 1 } @{$written->{names}};
    for my $name (@{$source_location->{names}}) {
        $written_names{$name} or fail('Place-name verification failed', 4);
    }
    rename($temporary, $target) or fail('Unable to replace working copy');
    emit_location($written);
} else {
    fail('Unknown operation');
}
