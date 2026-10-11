package io.github.sabopura.morphe.patches.twitch.danmaku

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.resourcePatch
import io.github.sabopura.morphe.patches.twitch.Constants.TWITCH_SUPPORTED
import org.w3c.dom.Element

private const val OVERLAY_VIEW = "io.github.sabopura.morphe.extension.twitch.danmaku.DanmakuOverlayView"

private fun Element.androidId() = getAttribute("android:id").substringAfter('/')

private fun Element.descendants(): List<Element> {
    val result = mutableListOf<Element>()
    val nodes = getElementsByTagName("*")
    for (index in 0 until nodes.length) result += nodes.item(index) as Element
    return result
}

/**
 * Puts the comment layer into every Twitch player directly above the video surface (below
 * the controls, ads and captions). The on/off switch lives in the shared player menu.
 */
internal val twitchDanmakuResourcePatch = resourcePatch(
    description = "Adds the danmaku layer to Twitch's player layouts.",
) {
    compatibleWith(TWITCH_SUPPORTED)

    execute {
        document("res/layout/player_view_delegate.xml").use { document ->
            val root = document.documentElement
            val video = root.descendants().firstOrNull { it.androidId() == "playback_view_container" }
                ?: throw PatchException("Twitch player layout lost playback_view_container")
            val overlay = document.createElement(OVERLAY_VIEW)
            overlay.setAttribute("android:id", "@+id/sabopura_danmaku_overlay")
            overlay.setAttribute("android:layout_width", "match_parent")
            overlay.setAttribute("android:layout_height", "match_parent")
            video.parentNode.insertBefore(overlay, video.nextSibling)
        }
    }
}
