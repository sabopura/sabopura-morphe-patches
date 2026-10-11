package io.github.sabopura.morphe.patches.twitch.danmaku

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.intOption
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import io.github.sabopura.morphe.patches.twitch.Constants.TWITCH_SUPPORTED
import io.github.sabopura.morphe.patches.twitch.ui.enableTwitchFeature
import io.github.sabopura.morphe.patches.twitch.ui.playerMenuResourcePatch
import io.github.sabopura.morphe.patches.youtube.shared.replaceExtensionConstant
import java.util.logging.Logger

private const val EXTENSION_CLASS =
    "Lio/github/sabopura/morphe/extension/twitch/danmaku/TwitchDanmaku;"
private val logger = Logger.getLogger("Morphe.Twitch.Danmaku")

/** Strings only Twitch's live chat parser uses while classifying a message. */
private val PARSER_STRINGS = setOf("gigantified-emote-message", "animated-message")

/** The parsed live chat message is a Kotlin data class; its toString() is the stable anchor. */
internal object LiveChatMessageToStringFingerprint : Fingerprint(
    name = "toString",
    parameters = listOf(),
    returnType = "Ljava/lang/String;",
    strings = listOf("LiveChatMessage(messageId="),
)

@Suppress("unused")
val twitchDanmakuPatch = bytecodePatch(
    name = "Danmaku chat",
    description = "Flows live chat over the stream like NicoNico/bilibili, with emotes and " +
        "NG words. Long-press the player menu button to switch it on or off.",
    default = true,
) {
    dependsOn(twitchDanmakuResourcePatch, playerMenuResourcePatch)
    compatibleWith(TWITCH_SUPPORTED)

    val fontSizeOption = intOption(
        key = "danmaku_font_size",
        default = 22,
        title = "Font size",
        description = "Danmaku text size in sp at full player height (12-40).",
        required = true,
    ) { value -> value != null && value in 12..40 }

    val opacityOption = intOption(
        key = "danmaku_opacity",
        default = 85,
        title = "Opacity",
        description = "Danmaku opacity percentage (20-100).",
        required = true,
    ) { value -> value != null && value in 20..100 }

    val durationOption = intOption(
        key = "danmaku_duration",
        default = 7,
        title = "Travel duration",
        description = "Seconds for a message to cross the player (3-15).",
        required = true,
    ) { value -> value != null && value in 3..15 }

    val coverageOption = intOption(
        key = "danmaku_coverage",
        default = 75,
        title = "Vertical coverage",
        description = "Percentage of the player height used from the top (20-100).",
        required = true,
    ) { value -> value != null && value in 20..100 }

    val maxLengthOption = intOption(
        key = "danmaku_max_length",
        default = 60,
        title = "Maximum characters",
        description = "Longer messages are not shown (10-200).",
        required = true,
    ) { value -> value != null && value in 10..200 }

    extendWith("extensions/twitch.mpe")

    execute {
        val messageType = LiveChatMessageToStringFingerprint.originalClassDef.type

        // The parser returns one LiveChatMessage per incoming IRC/EventSub message. Whispers
        // and other surfaces use different factory methods without these message types.
        val parsers = mutableListOf<Pair<String, Method>>()
        classDefForEach { classDef ->
            classDef.methods.forEach { method ->
                if (method.returnType != messageType) return@forEach
                val strings = method.implementation?.instructions
                    ?.mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }
                    ?.toSet() ?: return@forEach
                if (strings.containsAll(PARSER_STRINGS)) parsers += classDef.type to method
            }
        }
        if (parsers.size != 1) {
            throw PatchException(
                "Expected one Twitch live chat parser returning $messageType, found ${parsers.size}",
            )
        }
        val (parserClass, parser) = parsers.single()

        val method = mutableClassDefBy(parserClass).methods.single {
            it.name == parser.name && it.parameterTypes == parser.parameterTypes &&
                it.returnType == parser.returnType
        }
        val returns = method.instructions
            .withIndex()
            .filter { it.value.opcode == Opcode.RETURN_OBJECT }
            .map { it.index }
        if (returns.isEmpty()) throw PatchException("Twitch chat parser has no return")
        returns.asReversed().forEach { index ->
            val register = method.getInstruction<OneRegisterInstruction>(index).registerA
            method.addInstruction(
                index,
                "invoke-static/range {v$register .. v$register}, " +
                    "$EXTENSION_CLASS->onChatMessage(Ljava/lang/Object;)V",
            )
        }
        logger.info(
            "Hooked ${returns.size} return(s) of $parserClass->${parser.name} " +
                "producing $messageType",
        )
    }

    finalize {
        fun replaceConfig(methodName: String, value: Int) =
            replaceExtensionConstant(EXTENSION_CLASS, methodName, value)

        replaceConfig("configFontSizeSp", fontSizeOption.value ?: 22)
        replaceConfig("configOpacityPercent", opacityOption.value ?: 85)
        replaceConfig("configDurationSeconds", durationOption.value ?: 7)
        replaceConfig("configCoveragePercent", coverageOption.value ?: 75)
        replaceConfig("configMaxLength", maxLengthOption.value ?: 60)
        enableTwitchFeature("danmakuPatch")
    }
}
