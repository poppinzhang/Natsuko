package com.poppinzhang.natsuko.processing.location

import java.util.Locale

/** Coordinate and embedded place-name summary; no online reverse geocoding. */
data class LocationMetadata(
    val displayText: String,
    val hasCoordinates: Boolean,
    val latitude: Double? = null,
    val longitude: Double? = null,
)

object LocationFormat {
    private val mimeTypes = linkedMapOf(
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg",
        "png" to "image/png", "webp" to "image/webp",
        "heic" to "image/heic", "heif" to "image/heif",
        "tif" to "image/tiff", "tiff" to "image/tiff",
        "arw" to "image/x-sony-arw", "cr3" to "image/x-canon-cr3",
        "cr2" to "image/x-canon-cr2", "nef" to "image/x-nikon-nef",
        "raf" to "image/x-fuji-raf", "rw2" to "image/x-panasonic-rw2",
    )
    val supportedExtensions: Set<String> = mimeTypes.keys.toSet()
    fun mimeType(fileName: String): String? = mimeTypes[fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)]
}

/** A bounded, hex-escaped line protocol avoids parsing JSON in the native runtime. */
internal object LocationMetadataProtocol {
    fun parse(output: String): LocationMetadata? {
        val lines = output.lineSequence().filter(String::isNotBlank).map { it.split('\t') }.toList()
        require(lines.firstOrNull()?.let { it.size == 2 && it[0] == "TYPE" } == true) { "Invalid metadata response" }
        val coordinates = lines.singleOrNull { it.first() == "COORD" }?.let {
            require(it.size == 3)
            val latitude = it[1].toDouble()
            val longitude = it[2].toDouble()
            require(latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0)
            latitude to longitude
        }
        val fields = lines.drop(1).mapNotNull {
            if (it[0] == "COORD") return@mapNotNull null
            require(it.size == 3 && it[0] == "FIELD")
            decode(it[1]) to decode(it[2]).trim()
        }.filter { it.second.isNotBlank() }
        if (coordinates == null && fields.isEmpty()) return null
        val placeNames = fields.filter { (name, _) ->
            !name.substringAfter(':').startsWith("GPS") ||
                name.endsWith(":GPSAreaInformation") || name.endsWith(":GPSProcessingMethod")
        }.map { it.second }.distinct()
        val summary = buildList {
            addAll(placeNames)
            coordinates?.let { (latitude, longitude) -> add(String.format(Locale.ROOT, "%.7f, %.7f", latitude, longitude)) }
            if (isEmpty()) addAll(fields.map { it.second }.distinct())
        }.joinToString("\n").take(4096)
        return LocationMetadata(summary, coordinates != null, coordinates?.first, coordinates?.second)
    }

    private fun decode(hex: String): String {
        require(hex.length % 2 == 0 && hex.length <= 128 * 1024)
        return ByteArray(hex.length / 2) { index ->
            hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }.toString(Charsets.UTF_8)
    }
}
