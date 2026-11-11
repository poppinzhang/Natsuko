package com.poppinzhang.natsuko.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseVersionTest {
    @Test
    fun releaseTagsRequireTheVPrefixAndThreeNumericParts() {
        assertEquals(ReleaseVersion(1, 3, 1), ReleaseVersion.fromTag("v1.3.1"))
        assertEquals(ReleaseVersion(10, 0, 27), ReleaseVersion.fromTag("v10.0.27"))
        assertEquals(ReleaseVersion(0, 0, 0), ReleaseVersion.fromTag("v0.0.0"))
        for (tag in listOf(
            "", "v", "1.3.1", "V1.3.1", "v1.3", "v1.3.1.0", "v1.3.1-beta", "v1.3.1+8", "v01.3.1", "v1.3.x",
            " v1.3.1", "v1.3.1\n", "v1.-3.1", "v9999999999.0.0", "release-1.3.1", "v1.3.1/../v1.0.0",
        )) {
            assertNull(tag, ReleaseVersion.fromTag(tag))
        }
    }

    @Test
    fun installedVersionNamesMayCarryABuildSuffix() {
        assertEquals(ReleaseVersion(1, 3, 1), ReleaseVersion.fromVersionName("1.3.1"))
        assertEquals(ReleaseVersion(1, 3, 1), ReleaseVersion.fromVersionName("1.3.1-debug"))
        for (name in listOf("", "v1.3.1", "1.3", "debug", "1.3.1 debug")) {
            assertNull(name, ReleaseVersion.fromVersionName(name))
        }
    }

    @Test
    fun versionsCompareNumericallyPartByPart() {
        val ascending = listOf("0.9.9", "1.0.0", "1.2.10", "1.3.0", "1.3.1", "1.3.2", "1.3.10", "1.10.0", "2.0.0")
            .map { checkNotNull(ReleaseVersion.fromVersionName(it)) }
        ascending.zipWithNext { older, newer ->
            assertTrue("$newer must be newer than $older", newer > older)
            assertTrue("$older must be older than $newer", older < newer)
        }
        assertEquals(ascending, ascending.shuffled(java.util.Random(7)).sorted())
        assertEquals(0, ReleaseVersion(1, 3, 1).compareTo(checkNotNull(ReleaseVersion.fromVersionName("1.3.1-debug"))))
        assertEquals("1.3.10", ReleaseVersion(1, 3, 10).toString())
    }
}
