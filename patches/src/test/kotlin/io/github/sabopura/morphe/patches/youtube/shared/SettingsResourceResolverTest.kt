package io.github.sabopura.morphe.patches.youtube.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SettingsResourceResolverTest {
    private val legacy = "res/xml/settings_fragment.xml"
    private val cairo = "res/xml/settings_fragment_cairo.xml"

    @Test
    fun preservesBothScreensForPreviouslySupportedReleases() {
        assertEquals(listOf(legacy to false, cairo to true),
            resolveSettingsScreens(setOf(legacy, cairo)::contains))
    }

    @Test
    fun resolvesCairoWhenTheLegacyScreenWasRemoved() {
        assertEquals(listOf(cairo to true), resolveSettingsScreens(setOf(cairo)::contains))
    }

    @Test
    fun preservesLegacyOnlyReleases() {
        assertEquals(listOf(legacy to false), resolveSettingsScreens(setOf(legacy)::contains))
    }

    @Test
    fun rejectsMissingAnchorsEvenWhenOtherSettingsResourcesExist() {
        assertThrows(IllegalStateException::class.java) {
            resolveSettingsScreens(setOf("res/xml/danmaku_preferences.xml")::contains)
        }
    }
}
