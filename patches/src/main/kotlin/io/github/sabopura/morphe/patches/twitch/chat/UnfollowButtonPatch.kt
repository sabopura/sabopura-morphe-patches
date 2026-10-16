package io.github.sabopura.morphe.patches.twitch.chat

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import io.github.sabopura.morphe.patches.twitch.support.classesWithStrings
import io.github.sabopura.morphe.patches.twitch.support.uniqueContract
import java.util.logging.Logger

/**
 * The buttons under the player (follow, the heart that unfollows, subscribe, notifications …)
 * are one list in a state object. Its constructor hands the list to the extension first, which
 * removes the unfollow heart when the user hides it. The chat header above the landscape chat
 * needs nothing: Twitch puts its follow button there only for channels not followed.
 */
internal val unfollowButtonPatch = bytecodePatch {
    extendWith("extensions/twitch.mpe")
    execute {
        val state = classesWithStrings("Visible(buttons=", ", onNotificationSettingSelected=")
            .uniqueContract("player metadata button state")
        val constructor = mutableClassDefBy(state).methods.filter {
            it.name == "<init>" && it.parameterTypes.firstOrNull()?.toString() in
                setOf("Ljava/util/ArrayList;", "Ljava/util/List;")
        }.uniqueContract("player metadata button state constructor")
        if (constructor.implementation == null) throw PatchException("Unfollow button: constructor has no code")
        constructor.addInstructions(0, """
            invoke-static/range {p1 .. p1}, Lio/github/sabopura/morphe/extension/twitch/chat/ViewCustomization;->metadataButtons(Ljava/util/List;)V
        """)
        Logger.getLogger("Sabopura.Chat").info("Unfollow button: list filter in ${state.type}")
    }
}
