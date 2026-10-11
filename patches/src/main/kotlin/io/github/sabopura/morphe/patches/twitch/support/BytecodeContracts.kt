package io.github.sabopura.morphe.patches.twitch.support

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.BytecodePatchContext
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.*
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

internal fun Instruction.callRegisters(): List<Int> = when (this) {
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    else -> emptyList()
}

internal fun <T> Iterable<T>.uniqueContract(label: String): T {
    val found = toList()
    return found.singleOrNull() ?: throw PatchException("Twitch: $label: expected 1 match, found ${found.size}; input layout changed")
}

internal fun BytecodePatchContext.classesWithStrings(vararg anchors: String): List<ClassDef> {
    require(anchors.isNotEmpty())
    val candidates = classDefByStrings(anchors.first())
    val otherTypes = anchors.drop(1).map { text -> classDefByStrings(text).map { it.type }.toSet() }
    return candidates.filter { c -> otherTypes.all { c.type in it } }
}

/** R8 may erase Kotlin parameter-check strings. Resolve behavior against anchored model types. */
internal fun BytecodePatchContext.classesMatching(predicate: (ClassDef) -> Boolean): List<ClassDef> {
    val matches = mutableListOf<ClassDef>()
    classDefForEach { if (predicate(it)) matches += it }
    return matches
}

internal data class HeaderWrite(val index: Int, val registers: List<Int>, val replace: Boolean)

/** Follow the actual key argument, not the next arbitrary call after a string constant. */
internal fun certificateHeaderWrites(method: Method): List<HeaderWrite> {
    val implementation = method.implementation ?: return emptyList()
    val code = implementation.instructions.toList()
    val offsets = code.runningFold(0) { offset, instruction -> offset + instruction.codeUnits }.dropLast(1)
    val joins = implementation.tryBlocks.flatMap { it.exceptionHandlers }.map { it.handlerCodeAddress }.toMutableSet()
    code.forEachIndexed { i, instruction ->
        if (instruction is OffsetInstruction) joins += offsets[i] + instruction.codeOffset
    }
    code.forEachIndexed { i, instruction ->
        if (instruction is SwitchPayload) {
            code.forEachIndexed { j, branch ->
                if (branch is OffsetInstruction && offsets[j] + branch.codeOffset == offsets[i])
                    instruction.switchElements.forEach { joins += offsets[j] + it.offset }
            }
        }
    }
    val strings = mutableMapOf<Int, String>()
    val found = mutableListOf<HeaderWrite>()
    code.forEachIndexed { i, instruction ->
        if (offsets[i] in joins) strings.clear()
        val reference = (instruction as? ReferenceInstruction)?.reference
        val registers = instruction.callRegisters()
        if (instruction.opcode in setOf(Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE) &&
            reference is MethodReference && reference.definingClass in setOf(
                "Ljava/net/URLConnection;", "Ljava/net/HttpURLConnection;", "Ljavax/net/ssl/HttpsURLConnection;"
            ) && reference.name in setOf("addRequestProperty", "setRequestProperty") &&
            reference.parameterTypes.map { it.toString() } == listOf("Ljava/lang/String;", "Ljava/lang/String;") &&
            reference.returnType == "V" && registers.size == 3 && strings[registers[1]] == "X-Android-Cert") {
            found += HeaderWrite(i, registers, reference.name == "setRequestProperty")
        }
        val a = (instruction as? OneRegisterInstruction)?.registerA
        val moved = if (instruction is TwoRegisterInstruction) strings[instruction.registerB] else null
        if (a != null && instruction.opcode.setsRegister()) {
            strings.remove(a)
            if (instruction.opcode.setsWideRegister()) strings.remove(a + 1)
        }
        when (instruction.opcode) {
            Opcode.CONST_STRING, Opcode.CONST_STRING_JUMBO -> strings[a!!] = (reference as StringReference).string
            Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16 -> if (moved != null) strings[a!!] = moved
            else -> Unit
        }
        if (instruction is OffsetInstruction || instruction.opcode.name.startsWith("RETURN") ||
            instruction.opcode == Opcode.THROW || instruction.opcode.name.endsWith("PAYLOAD")) strings.clear()
    }
    return found
}

internal fun staticHeaderCall(write: HeaderWrite): String {
    val target = "Lio/github/sabopura/morphe/extension/twitch/notifications/FirebaseHeaders;->" +
        (if (write.replace) "set" else "add") + "(Ljava/net/URLConnection;Ljava/lang/String;Ljava/lang/String;)V"
    val registers = write.registers
    return if (registers.all { it <= 15 }) {
        "invoke-static {${registers.joinToString { "v$it" }}}, $target"
    } else {
        if (registers.zipWithNext().any { (a, b) -> b != a + 1 })
            throw PatchException("Twitch notifications: non-contiguous high registers; input layout changed")
        "invoke-static/range {v${registers.first()} .. v${registers.last()}}, $target"
    }
}
