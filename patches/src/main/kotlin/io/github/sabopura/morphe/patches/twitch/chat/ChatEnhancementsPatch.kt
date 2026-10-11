package io.github.sabopura.morphe.patches.twitch.chat

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.removeInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import io.github.sabopura.morphe.patches.twitch.Constants.TWITCH_SUPPORTED
import io.github.sabopura.morphe.patches.twitch.support.classesMatching
import io.github.sabopura.morphe.patches.twitch.support.uniqueContract
import io.github.sabopura.morphe.patches.twitch.ui.enableTwitchFeature
import io.github.sabopura.morphe.patches.twitch.ui.playerMenuResourcePatch
import java.util.logging.Logger

private const val BRIDGE = "Lio/github/sabopura/morphe/extension/twitch/chat/RowAccess;"
private const val SUPPORT = "Lio/github/sabopura/morphe/extension/twitch/chat/ChatEnhancements;"
private const val SET_TEXT = "Landroid/widget/TextView;->setText(Ljava/lang/CharSequence;Landroid/widget/TextView\$BufferType;)V"

private object MessageItem : Fingerprint(strings = listOf("MessageRecyclerItem(messageId=", ", sourceChannelId="))
private object ChannelKey : Fingerprint(
    classFingerprint = Fingerprint(custom = { _, c -> c.type ==
        "Ltv/twitch/android/shared/chat/pub/messages/data/ChannelChatConnectionKey;" }),
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    returnType = "V", custom = { method, _ ->
        method.parameterTypes.map { it.toString() }.let { parameters ->
            parameters.size == 2 && parameters[0] in setOf("Ljava/lang/String;", "Ltv/twitch/android/models/Tuid;") &&
                parameters[1] == "Ljava/lang/String;"
        }
    },
)

/** Anchors follow Hooman's GPLv3 Twitch emote patch; no obfuscated identifier is hardcoded. */
@Suppress("unused")
val chatEnhancementsPatch = bytecodePatch(
    name = "Chat enhancements",
    description = "7TV, BTTV and FFZ emotes, local word/user filters, repeat filtering, " +
        "highlights, timestamps and font size. Configure them from the player menu button.",
    default = true,
) {
    compatibleWith(TWITCH_SUPPORTED)
    dependsOn(chatEnhancementsResources, vodChatPatch, playerMenuResourcePatch)
    extendWith("extensions/twitch.mpe")
    val fields = linkedMapOf<String, FieldReference>()

    execute {
        val model = MessageItem.originalClassDef
        val description = dataClassFields(model.methods.single { it.name == "toString" }) { reference ->
            classDefBy(reference.definingClass).methods.single {
                it.name == reference.name && it.parameterTypes == reference.parameterTypes && it.returnType == reference.returnType
            }
        }
        fun fieldAfter(label: String, type: String): FieldReference {
            return description[label]?.takeIf { it.definingClass == model.type && it.type == type }
                ?: throw PatchException("Chat enhancements: unresolved model field $label")
        }
        fields["messageId"] = fieldAfter("MessageRecyclerItem(messageId=", "Ljava/lang/String;")
        fields["channelId"] = fieldAfter(", sourceChannelId=", "Ljava/lang/String;")
        fields["username"] = fieldAfter(", username=", "Ljava/lang/String;")
        fields["userId"] = description[", authorUserId="]?.takeIf {
            it.definingClass == model.type && it.type in setOf("Ljava/lang/String;", "Ltv/twitch/android/models/Tuid;")
        } ?: throw PatchException("Chat enhancements: unsupported author ID field type")
        if (fields.getValue("userId").type == "Ltv/twitch/android/models/Tuid;") {
            classDefBy("Ltv/twitch/android/models/Tuid;").methods.filter {
                it.name == "toString" && it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/String;" &&
                    AccessFlags.PUBLIC.isSet(it.accessFlags)
            }.uniqueContract("Tuid string conversion")
        }
        fields["timestamp"] = fieldAfter(", messageTimestamp=", "Ljava/lang/Long;")

        fun isBinder(method: Method): Boolean = !AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" &&
            method.parameterTypes.map { it.toString() } == listOf(model.type, "Z") &&
            method.implementation?.instructions?.count {
                ((it as? ReferenceInstruction)?.reference as? MethodReference)?.toString() == SET_TEXT
            } == 1
        val holder = classesMatching { it.methods.any(::isBinder) }.uniqueContract("live message holder")
        val binder = mutableClassDefBy(holder).methods.single(::isBinder)
        val code = binder.instructions.toList()
        val setterIndex = code.indexOfFirst {
            ((it as? ReferenceInstruction)?.reference as? MethodReference)?.toString() == SET_TEXT
        }
        val setter = code[setterIndex] as? FiveRegisterInstruction
            ?: throw PatchException("Chat enhancements: unexpected text setter instruction")
        fields["textView"] = code.take(setterIndex).asReversed().firstNotNullOfOrNull {
            val field = (it as? ReferenceInstruction)?.reference as? FieldReference
            if (it.opcode == Opcode.IGET_OBJECT &&
                (it as? TwoRegisterInstruction)?.registerA == setter.registerC &&
                field?.type == "Landroid/widget/TextView;") field else null
        } ?: throw PatchException("Chat enhancements: text view field not found")

        // Resolve the RecyclerView ViewHolder's itemView by type and constructor contract.
        var ancestor = holder
        while (!ancestor.type.startsWith("Landroidx/recyclerview/widget/")) {
            ancestor = classDefBy(ancestor.superclass
                ?: throw PatchException("Chat enhancements: missing holder superclass"))
        }
        fields["itemView"] = ancestor.instanceFields.singleOrNull {
            it.type == "Landroid/view/View;" && AccessFlags.PUBLIC.isSet(it.accessFlags) &&
                AccessFlags.FINAL.isSet(it.accessFlags)
        } ?: throw PatchException("Chat enhancements: ambiguous RecyclerView itemView")

        fields.values.forEach { reference ->
            val field = classDefBy(reference.definingClass).fields.single { it.name == reference.name }
            if (!AccessFlags.PUBLIC.isSet(field.accessFlags)) {
                throw PatchException("Chat enhancements: inaccessible field ${reference.name}")
            }
        }
        val returns = code.withIndex().filter { it.value.opcode == Opcode.RETURN_VOID }.map { it.index }
        if (returns.isEmpty()) throw PatchException("Chat enhancements: no chat binder returns")
        val firstParameter = binder.implementation!!.registerCount - 3
        if (code.any { instruction -> instruction.opcode.setsRegister() &&
                (instruction as? OneRegisterInstruction)?.registerA?.let { register ->
                    register >= firstParameter || instruction.opcode.setsWideRegister() && register + 1 >= firstParameter
                } == true }) throw PatchException("Chat enhancements: binder overwrites callback parameters")
        // At a terminal return, no live scratch register is overwritten. All params are contiguous.
        returns.asReversed().forEach {
            binder.addInstructions(it, "invoke-static/range {p0 .. p2}, $SUPPORT->bind(Ljava/lang/Object;Ljava/lang/Object;Z)V")
        }
        val key = ChannelKey.method
        for ((name, type) in listOf("getChannelId" to key.parameterTypes[0].toString(), "getChannelName" to "Ljava/lang/String;")) {
            classDefBy(key.definingClass).methods.filter {
                it.name == name && it.parameterTypes.isEmpty() && it.returnType == type && AccessFlags.PUBLIC.isSet(it.accessFlags)
            }.uniqueContract("channel key $name")
        }
        key.addInstructions(key.instructions.lastIndex, channelConstructorHook(key))
        Logger.getLogger("Sabopura.ChatEnhancements").info(
            "Chat enhancements: ${returns.size} row hook(s), 1 channel hook, ${fields.size} resolved fields")
    }
    finalize {
        val bridge = mutableClassDefBy(BRIDGE)
        fields.forEach { (name, field) ->
            val method = bridge.methods.single { it.name == name }
            method.removeInstructions(0, method.instructions.count())
            // ART does not narrow an object register to null on an if-eqz edge. Returning
            // from a shared label would merge Tuid and String into Object and reject this
            // entire bridge class when the first chat row calls it. Keep the paths distinct
            // and explicitly materialize null for the declared String return type.
            val convert = if (name == "userId" && field.type == "Ltv/twitch/android/models/Tuid;") """
                if-eqz p0, :id_null
                invoke-virtual {p0}, Ltv/twitch/android/models/Tuid;->toString()Ljava/lang/String;
                move-result-object p0
                return-object p0
                :id_null
                const/4 p0, 0x0
            """ else ""
            method.addInstructions(0, """
                check-cast p0, ${field.definingClass}
                iget-object p0, p0, $field
                $convert
                return-object p0
            """)
        }
        enableTwitchFeature("chatPatch")
    }
}
