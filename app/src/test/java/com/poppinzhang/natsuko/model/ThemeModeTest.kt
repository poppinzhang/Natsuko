package com.poppinzhang.natsuko.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeModeTest {
    @Test
    fun missingOrUnknownPreferenceFollowsSystem() {
        for (value in listOf(null, "", "unsupported", "DARK")) {
            assertEquals(ThemeMode.SYSTEM, ThemeMode.fromPreference(value))
        }
    }

    @Test
    fun everyModeRestoresFromItsStablePreferenceValue() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromPreference("system"))
        assertEquals(ThemeMode.LIGHT, ThemeMode.fromPreference("light"))
        assertEquals(ThemeMode.DARK, ThemeMode.fromPreference("dark"))
        for (mode in ThemeMode.entries) {
            assertEquals(mode, ThemeMode.fromPreference(mode.preferenceValue))
        }
    }

    @Test
    fun systemModeTracksBothSystemAppearances() {
        assertFalse(ThemeMode.SYSTEM.usesDarkTheme(false))
        assertTrue(ThemeMode.SYSTEM.usesDarkTheme(true))
    }

    @Test
    fun explicitModesOverrideEitherSystemAppearance() {
        for (systemInDarkTheme in listOf(false, true)) {
            assertFalse(ThemeMode.LIGHT.usesDarkTheme(systemInDarkTheme))
            assertTrue(ThemeMode.DARK.usesDarkTheme(systemInDarkTheme))
        }
    }
}
