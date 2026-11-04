package com.poppinzhang.natsuko.processing

import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BlackBorderCropperTest {
    @Test
    fun removesAllFourAsymmetricBorders() {
        assertEquals(
            BlackBorderBounds(2, 1, 7, 5),
            bounds(10, 8) { x, y -> if (x in 2..6 && y in 1..4) WHITE else BLACK },
        )
    }

    @Test
    fun removesHorizontalAndVerticalBarsIndependently() {
        assertEquals(
            BlackBorderBounds(0, 2, 10, 7),
            bounds(10, 9) { _, y -> if (y in 2..6) WHITE else BLACK },
        )
        assertEquals(
            BlackBorderBounds(3, 0, 8, 9),
            bounds(10, 9) { x, _ -> if (x in 3..7) WHITE else BLACK },
        )
    }

    @Test
    fun canTrimOnlyOneEdge() {
        assertEquals(
            BlackBorderBounds(0, 3, 10, 9),
            bounds(10, 9) { _, y -> if (y >= 3) WHITE else BLACK },
        )
    }

    @Test
    fun retainsDarkContentEvenOneLevelAboveThreshold() {
        assertEquals(
            BlackBorderBounds(2, 1, 7, 5),
            bounds(10, 8) { x, y -> if (x in 2..6 && y in 1..4) 0xff090909.toInt() else BLACK },
        )
    }

    @Test
    fun requiresEveryColorChannelToBeNearBlack() {
        for (content in listOf(0xff090000.toInt(), 0xff000900.toInt(), 0xff000009.toInt())) {
            assertEquals(
                BlackBorderBounds(1, 1, 4, 4),
                bounds(5, 5) { x, y -> if (x in 1..3 && y in 1..3) content else 0xff080808.toInt() },
            )
        }
    }

    @Test
    fun evenOneNonblackEdgePixelIsPreserved() {
        assertEquals(
            BlackBorderBounds(0, 0, 10, 8),
            bounds(10, 8) { x, y ->
                when {
                    x == 0 && y == 0 -> 0xff090909.toInt()
                    x == 9 && y == 7 -> WHITE
                    else -> BLACK
                }
            },
        )
    }

    @Test
    fun retainsInteriorBlackRegionsWithoutSplittingContent() {
        assertEquals(
            BlackBorderBounds(1, 1, 9, 7),
            bounds(10, 8) { x, y -> if ((x == 1 && y == 1) || (x == 8 && y == 6)) WHITE else BLACK },
        )
    }

    @Test
    fun transparentAndPartlyTransparentEdgesAreNotBlackBorders() {
        for (transparent in listOf(0x00000000, 0xfe000000.toInt())) {
            assertEquals(
                BlackBorderBounds(0, 0, 5, 5),
                bounds(5, 5) { x, y -> if (x in 1..3 && y in 1..3) WHITE else transparent },
            )
        }
    }

    @Test
    fun retainsNoBorderAndEntirelyBlackImages() {
        for (color in listOf(WHITE, BLACK, 0xff080808.toInt())) {
            assertEquals(BlackBorderBounds(0, 0, 5, 6), bounds(5, 6) { _, _ -> color })
        }
    }

    @Test
    fun onePixelContentAndSinglePixelInputsNeverProduceEmptyBounds() {
        assertEquals(
            BlackBorderBounds(3, 2, 4, 3),
            bounds(7, 5) { x, y -> if (x == 3 && y == 2) WHITE else BLACK },
        )
        assertEquals(BlackBorderBounds(0, 0, 1, 1), bounds(1, 1) { _, _ -> BLACK })
        assertEquals(BlackBorderBounds(0, 0, 1, 1), bounds(1, 1) { _, _ -> WHITE })
    }

    @Test
    fun reusesOneRowBufferAndChecksCancellationThroughoutScan() {
        var buffer: IntArray? = null
        var readCount = 0
        var checkpoints = 0
        BlackBorderCropper.findBounds(
            100,
            80,
            readRow = { _, row ->
                buffer?.let { assertSame(it, row) }
                buffer = row
                row.fill(BLACK)
                readCount++
            },
            checkCancellation = { checkpoints++ },
        )
        assertEquals(100, buffer?.size)
        assertEquals(80, readCount)
        assertEquals(readCount, checkpoints)
    }

    @Test
    fun cancellationStopsReadingImmediately() {
        val cancellation = CancellationException("test cancellation")
        var readCount = 0
        val failure = runCatching {
            BlackBorderCropper.findBounds(
                100,
                80,
                readRow = { _, row -> row.fill(BLACK); readCount++ },
                checkCancellation = { if (readCount == 3) throw cancellation },
            )
        }.exceptionOrNull()
        assertSame(cancellation, failure)
        assertEquals(3, readCount)
    }

    @Test
    fun rejectsEmptyDimensions() {
        for ((width, height) in listOf(0 to 4, 4 to 0, -1 to 4)) {
            assertTrue(runCatching { bounds(width, height) { _, _ -> BLACK } }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    private fun bounds(width: Int, height: Int, pixel: (Int, Int) -> Int): BlackBorderBounds =
        BlackBorderCropper.findBounds(width, height, readRow = { y, row ->
            for (x in row.indices) row[x] = pixel(x, y)
        })

    private companion object {
        const val BLACK = 0xff000000.toInt()
        const val WHITE = 0xffffffff.toInt()
    }
}
