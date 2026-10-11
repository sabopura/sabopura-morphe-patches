package io.github.sabopura.morphe.patches.twitch.chat

import app.morphe.patcher.patch.PatchException
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import io.github.sabopura.morphe.patches.twitch.support.callRegisters

internal fun isVodBinder(method: Method): Boolean = !AccessFlags.STATIC.isSet(method.accessFlags) &&
    method.returnType == "V" && method.parameterTypes.size == 1 && method.parameterTypes[0].startsWith("L") &&
    method.implementation?.instructions?.any {
        val ref = (it as? ReferenceInstruction)?.reference as? MethodReference
        ref?.definingClass == "Landroid/widget/TextView;" && ref.name == "setText" &&
            ref.parameterTypes.map { p -> p.toString() } == listOf("Ljava/lang/CharSequence;")
    } == true

internal fun isVodRow(type: ClassDef, model: String): Boolean =
    type.instanceFields.count { it.type == model } == 1 &&
        type.methods.any { it.name == "getItemId" && it.returnType == "Ljava/lang/String;" && it.parameterTypes.isEmpty() } &&
        type.methods.any(::isVodBinder)

internal fun isFollowingCardsBuilder(method: Method): Boolean = !AccessFlags.STATIC.isSet(method.accessFlags) &&
    method.parameterTypes.map { it.toString() } == listOf("Ljava/util/List;", "Z", "Ljava/util/Map;", "Ljava/util/Map;", "Ljava/util/Map;") &&
    method.returnType in setOf("Ljava/util/ArrayList;", "Ljava/util/List;") &&
    method.implementation?.instructions?.any {
        val ref = (it as? ReferenceInstruction)?.reference as? MethodReference
        ref?.definingClass == "Ltv/twitch/android/models/streams/StreamModelBase;" && ref.name == "getChannelId"
    } == true

/** Parameter order can change with R8 argument propagation, even when the behavior is unchanged. */
internal fun widthHook(method: Method): String {
    if (!AccessFlags.STATIC.isSet(method.accessFlags) || method.returnType != "V")
        throw PatchException("Layout: width helper must be static and void")
    val parameters = method.parameterTypes.map { it.toString() }
    val view = parameters.indexOf("Landroid/view/View;")
    val percent = parameters.indexOf("I")
    if (parameters.size != 2 || view < 0 || percent < 0)
        throw PatchException("Layout: width helper parameter contract changed")
    val registers = method.implementation?.registerCount ?: throw PatchException("Layout: width helper has no code")
    val target = "Lio/github/sabopura/morphe/extension/twitch/player/LayoutTools;->chatWidth(Landroid/view/View;I)I"
    val call = if (view == 0) "invoke-static/range {p0 .. p1}, $target"
        else if (registers <= 16) "invoke-static {p$view, p$percent}, $target"
        else throw PatchException("Layout: reordered width arguments exceed compact-call registers")
    return "$call\nmove-result p$percent"
}

/** Read the completed key via its getters; constructor parameters may be scratch registers. */
internal fun channelConstructorHook(method: Method): String {
    val code = method.implementation ?: throw PatchException("Chat: channel constructor has no code")
    val parameters = method.parameterTypes.map { it.toString() }
    if (!AccessFlags.CONSTRUCTOR.isSet(method.accessFlags) || AccessFlags.STATIC.isSet(method.accessFlags) ||
        parameters.size != 2 || parameters[0] !in setOf("Ljava/lang/String;", "Ltv/twitch/android/models/Tuid;") ||
        parameters[1] != "Ljava/lang/String;" || code.instructions.count { it.opcode == Opcode.RETURN_VOID } != 1 ||
        code.instructions.last().opcode != Opcode.RETURN_VOID)
        throw PatchException("Chat: unsupported channel constructor")
    val receiver = code.registerCount - 3
    if (code.instructions.any { it.opcode.setsRegister() && (it as? OneRegisterInstruction)?.registerA == receiver })
        throw PatchException("Chat: channel constructor overwrites its receiver")
    val convert = if (parameters[0] == "Ltv/twitch/android/models/Tuid;") """
        invoke-virtual/range {p1 .. p1}, Ltv/twitch/android/models/Tuid;->toString()Ljava/lang/String;
        move-result-object p1
    """ else ""
    return """
        invoke-virtual/range {p0 .. p0}, ${method.definingClass}->getChannelId()${parameters[0]}
        move-result-object p1
        $convert
        invoke-virtual/range {p0 .. p0}, ${method.definingClass}->getChannelName()Ljava/lang/String;
        move-result-object p2
        invoke-static/range {p1 .. p2}, Lio/github/sabopura/morphe/extension/twitch/chat/ChatEnhancements;->onChannelChanged(Ljava/lang/String;Ljava/lang/String;)V
    """
}

internal data class FollowingIndexHook(val index: Int, val listRegister: Int)

/** Match the completed-row iterator that builds section -> adapter-index, not another list loop. */
internal fun followingIndexLoop(method: Method, section: FieldReference): FollowingIndexHook? {
    val code = method.implementation?.instructions?.toList() ?: return null
    val read = code.indices.filter {
        code[it].opcode == Opcode.IGET_OBJECT && (code[it] as? ReferenceInstruction)?.reference == section
    }.singleOrNull() ?: return null
    val iterator = (0 until read).lastOrNull {
        (code[it] as? ReferenceInstruction)?.reference?.toString() ==
            "Ljava/util/ArrayList;->iterator()Ljava/util/Iterator;"
    } ?: return null
    val span = code.subList(iterator + 1, read)
    if (span.none { (it as? ReferenceInstruction)?.reference?.toString() == "Ljava/util/Iterator;->next()Ljava/lang/Object;" } ||
        span.none { it.opcode == Opcode.INSTANCE_OF && (it as? ReferenceInstruction)?.reference?.toString() == section.definingClass }) return null
    // Exact adjacent valueOf/move-result/put sequence ties the section field to the index map.
    if (code.getOrNull(read + 1).let { (it as? ReferenceInstruction)?.reference?.toString() } != "Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;" ||
        code.getOrNull(read + 2)?.opcode != Opcode.MOVE_RESULT_OBJECT ||
        code.getOrNull(read + 3).let { (it as? ReferenceInstruction)?.reference?.toString() } != "Ljava/util/Map;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;") return null
    val list = code[iterator].callRegisters().singleOrNull() ?: return null
    return FollowingIndexHook(iterator, list)
}
