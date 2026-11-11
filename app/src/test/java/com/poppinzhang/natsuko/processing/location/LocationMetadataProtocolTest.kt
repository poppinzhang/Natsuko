package com.poppinzhang.natsuko.processing.location

import org.junit.Assert.*
import org.junit.Test

class LocationMetadataProtocolTest {
    @Test fun emptyImageHasNoLocation() {
        assertNull(LocationMetadataProtocol.parse("TYPE\tJPEG\n"))
    }

    @Test fun namedOnlyLocationStillRequiresConflictDecision() {
        val location = LocationMetadataProtocol.parse("TYPE\tJPEG\n" + field("XMP-photoshop:City", "東京"))!!
        assertFalse(location.hasCoordinates)
        assertEquals("東京", location.displayText)
    }

    @Test fun coordinatesAcceptZeroAndNegativeValuesWithUnicodePlaceNames() {
        val location = LocationMetadataProtocol.parse("TYPE\tHEIC\nCOORD\t0\t-139.7\n" +
            field("XMP-iptcCore:Location", "代々木公園") + field("IPTC:Sub-location", "代々木公園"))!!
        assertTrue(location.hasCoordinates)
        assertEquals(0.0, location.latitude!!, 0.0)
        assertEquals(-139.7, location.longitude!!, 0.0)
        assertEquals("代々木公園\n0.0000000, -139.7000000", location.displayText)
    }

    @Test fun invalidCoordinatesAndMalformedProtocolAreRejected() {
        for (response in listOf("", "TYPE\tJPEG\nCOORD\t91\t10", "TYPE\tJPEG\nCOORD\tNaN\t10", "TYPE\tJPEG\nFIELD\tzz\t11")) {
            assertTrue(runCatching { LocationMetadataProtocol.parse(response) }.isFailure)
        }
    }

    @Test fun formatsIncludeEveryDocumentedExtensionAndRejectOthers() {
        assertEquals(setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "tif", "tiff", "arw", "cr3", "cr2", "nef", "raf", "rw2"), LocationFormat.supportedExtensions)
        assertEquals("image/x-sony-arw", LocationFormat.mimeType("写真.ARW"))
        assertEquals("image/heif", LocationFormat.mimeType("photo.HEIF"))
        assertNull(LocationFormat.mimeType("image.gif"))
        assertNull(LocationFormat.mimeType("jpg"))
    }

    private fun field(name: String, value: String): String = "FIELD\t${hex(name)}\t${hex(value)}\n"
    private fun hex(value: String) = value.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }
}
