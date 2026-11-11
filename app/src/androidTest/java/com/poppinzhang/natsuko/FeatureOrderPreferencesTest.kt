package com.poppinzhang.natsuko

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.poppinzhang.natsuko.data.FeatureOrderPreferences
import com.poppinzhang.natsuko.model.Feature
import com.poppinzhang.natsuko.model.FeatureOrder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FeatureOrderPreferencesTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val rawPreferences = context.getSharedPreferences("home_features", Context.MODE_PRIVATE)
    private val preferences = FeatureOrderPreferences(context)
    private var originalOrder: List<Feature> = emptyList()

    @Before
    fun saveOriginalOrder() {
        originalOrder = preferences.read()
    }

    @After
    fun restoreOriginalOrder() {
        preferences.write(originalOrder)
        instrumentation.waitForIdleSync()
    }

    @Test
    fun missingAndInvalidPreferencesUseDefault() {
        assertTrue(rawPreferences.edit().remove("order").commit())
        assertEquals(FeatureOrder.default, preferences.read())
        assertTrue(rawPreferences.edit().putInt("order", 42).commit())
        assertEquals(FeatureOrder.default, preferences.read())
    }

    @Test
    fun newPreferencesInstanceReadsSavedOrder() {
        val custom = FeatureOrder.default.reversed()
        preferences.write(custom)
        assertEquals(custom, FeatureOrderPreferences(context).read())
        assertEquals(custom.map(Feature::name), rawPreferences.getString("order", null)?.split(','))
    }

    @Test
    fun upgradingRetainsKnownSavedPositionsAndAddsMissingFeatures() {
        assertTrue(rawPreferences.edit().putString("order", "VIDEO_TO_GIF,deleted,VIDEO_TO_GIF,IMAGE_MERGE").commit())
        assertEquals(
            listOf(Feature.VIDEO_TO_GIF, Feature.IMAGE_MERGE) +
                FeatureOrder.default.filterNot { it in setOf(Feature.VIDEO_TO_GIF, Feature.IMAGE_MERGE) },
            FeatureOrderPreferences(context).read(),
        )
    }

    @Test
    fun observerTracksAnotherInstanceUntilUnsubscribed() {
        val observed = mutableListOf<List<Feature>>()
        lateinit var unsubscribe: () -> Unit
        val custom = FeatureOrder.default.reversed()
        instrumentation.runOnMainSync {
            preferences.write(FeatureOrder.default)
            unsubscribe = preferences.observe(observed::add)
            FeatureOrderPreferences(context).write(custom)
        }
        try {
            instrumentation.waitForIdleSync()
            assertEquals(custom, observed.last())
            instrumentation.runOnMainSync { unsubscribe() }
            val notifications = observed.size
            instrumentation.runOnMainSync { preferences.write(FeatureOrder.default) }
            instrumentation.waitForIdleSync()
            assertEquals(notifications, observed.size)
        } finally {
            instrumentation.runOnMainSync { unsubscribe() }
        }
    }
}
