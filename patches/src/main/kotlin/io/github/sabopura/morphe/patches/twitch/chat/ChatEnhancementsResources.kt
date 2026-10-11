package io.github.sabopura.morphe.patches.twitch.chat

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.resourcePatch
import org.w3c.dom.Element

internal val chatEnhancementsResources = resourcePatch {
    execute {
        document("res/layout/player_view_delegate.xml").use { doc ->
            val root=doc.documentElement
            if(root.tagName!="FrameLayout")throw PatchException("Chat enhancements: unexpected player root")
            doc.renameNode(root,null,"io.github.sabopura.morphe.extension.twitch.player.PlayerGestureFrame")
        }
        document("res/layout/message_input.xml").use { doc ->
            val nodes=doc.getElementsByTagName("*")
            val picker=(0 until nodes.length).map { nodes.item(it) as Element }.single {
                it.getAttribute("android:id").substringAfter('/')=="emoticon_picker"
            }
            val extra=doc.createElement("io.github.sabopura.morphe.extension.twitch.chat.EmotePickerButton")
            extra.setAttribute("android:layout_width","@dimen/message_input_widget_button_size")
            extra.setAttribute("android:layout_height","match_parent")
            extra.setAttribute("android:layout_gravity","center_vertical")
            picker.parentNode.insertBefore(extra,picker)
        }
    }
}
