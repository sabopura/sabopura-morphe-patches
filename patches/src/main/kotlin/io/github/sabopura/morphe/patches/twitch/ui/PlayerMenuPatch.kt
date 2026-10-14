package io.github.sabopura.morphe.patches.twitch.ui

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.resourcePatch
import io.github.sabopura.morphe.patches.twitch.Constants.TWITCH_SUPPORTED
import io.github.sabopura.morphe.patches.twitch.support.replaceExtensionConstant
import org.w3c.dom.Element

private const val BUTTON = "io.github.sabopura.morphe.extension.twitch.ui.PlayerMenuButton"
private const val BUTTON_ID = "sabopura_player_menu"
private const val FEATURES = "Lio/github/sabopura/morphe/extension/twitch/ui/Features;"

private fun Element.descendants(): List<Element> {
    val nodes = getElementsByTagName("*")
    return (0 until nodes.length).map { nodes.item(it) as Element }
}

private fun Element.androidId() = getAttribute("android:id").substringAfter('/')

/**
 * Adds the single player menu button directly left of the share button. Every feature patch
 * depends on it, so it is added once. The cast button, which appears and disappears with cast
 * devices, now sits left of ours, so ours never moves.
 */
internal val playerMenuResourcePatch = resourcePatch(
    description = "Adds the shared player menu button to Twitch's player controls.",
) {
    compatibleWith(TWITCH_SUPPORTED)
    dependsOn(settingsEntryPatch, nativeTheatrePatch)

    execute {
        document("res/layout/player_control_overlay.xml").use { document ->
            val root = document.documentElement
            val views = root.descendants()
            if (views.any { it.androidId() == BUTTON_ID }) return@use
            val share = views.singleOrNull { it.androidId() == "share_button" }
                ?: throw PatchException("Twitch player controls lost share_button")
            // Everything that sat directly left of the share button (the cast button) now sits left of ours.
            views.filter { it.getAttribute("app:layout_constraintEnd_toStartOf").substringAfter('/') == "share_button" }
                .forEach { it.setAttribute("app:layout_constraintEnd_toStartOf", "@+id/$BUTTON_ID") }
            val button = document.createElement(BUTTON)
            button.setAttribute("android:id", "@+id/$BUTTON_ID")
            button.setAttribute("android:layout_width", "@dimen/player_overlay_button_size")
            button.setAttribute("android:layout_height", "@dimen/player_overlay_button_size")
            button.setAttribute("android:padding", "@dimen/default_margin_large")
            button.setAttribute("app:layout_constraintEnd_toStartOf", "@+id/share_button")
            button.setAttribute("app:layout_constraintTop_toTopOf", "parent")
            root.insertBefore(button, share)
        }
    }
}

/** Tells the shared menu and settings screen that a feature patch was applied. */
internal fun BytecodePatchContext.enableTwitchFeature(flagMethod: String) =
    replaceExtensionConstant(FEATURES, flagMethod, 1)
