package com.poppinzhang.natsuko.data.update

/** A published `major.minor.patch` version; releases never use any other version format. */
data class ReleaseVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<ReleaseVersion> {
    override fun compareTo(other: ReleaseVersion): Int =
        compareValuesBy(this, other, ReleaseVersion::major, ReleaseVersion::minor, ReleaseVersion::patch)

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        // Nine digits per part always fit in an Int.
        private val numbers = Regex("(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})")

        /** Release tags are exactly `v<major.minor.patch>`; anything else is not an installable release. */
        fun fromTag(tag: String): ReleaseVersion? = if (tag.startsWith("v")) parse(tag.substring(1)) else null

        /** Installed version names may carry a build suffix such as `-debug`. */
        fun fromVersionName(name: String): ReleaseVersion? = parse(name.substringBefore('-'))

        private fun parse(text: String): ReleaseVersion? = numbers.matchEntire(text)?.destructured
            ?.let { (major, minor, patch) -> ReleaseVersion(major.toInt(), minor.toInt(), patch.toInt()) }
    }
}
