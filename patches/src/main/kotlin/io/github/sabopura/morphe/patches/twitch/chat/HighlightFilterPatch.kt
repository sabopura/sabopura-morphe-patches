package io.github.sabopura.morphe.patches.twitch.chat

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import io.github.sabopura.morphe.patches.twitch.support.classesMatching
import io.github.sabopura.morphe.patches.twitch.support.uniqueContract
import java.util.logging.Logger

private object AddHighlightRequested : Fingerprint(strings = listOf("AddHighlightRequested(highlightId="))
private object AddCommunityHighlight : Fingerprint(strings = listOf("AddCommunityHighlight(model="))
private object AddCommunityPill : Fingerprint(strings = listOf("AddCommunityPill(eventType="))

private fun Instruction.type() = ((this as? ReferenceInstruction)?.reference as? TypeReference)?.type
private fun Instruction.call() = ((this as? ReferenceInstruction)?.reference as? MethodReference)?.toString()

private fun Instruction.registers(): List<Int> = when (this) {
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    is ThreeRegisterInstruction -> listOf(registerA, registerB, registerC)
    is TwoRegisterInstruction -> listOf(registerA, registerB)
    is OneRegisterInstruction -> listOf(registerA)
    else -> emptyList()
}

/** The register is overwritten before anything reads it, on the straight path from [from]. */
private fun deadAt(code: List<Instruction>, from: Int, register: Int): Boolean {
    for (instruction in code.drop(from)) {
        val used = instruction.registers()
        if (register in used) {
            return instruction is OneRegisterInstruction && instruction.registerA == register &&
                instruction.opcode.setsRegister() && used.count { it == register } == 1 &&
                (instruction !is TwoRegisterInstruction || instruction.registerB != register)
        }
        if (instruction.opcode.name.startsWith("IF_") || instruction.opcode.name.startsWith("GOTO") ||
            instruction.opcode.name.contains("SWITCH") || instruction.opcode.name.startsWith("RETURN")) return false
    }
    return false
}

/**
 * Twitch filters every community highlight event (hype trains, pinned messages, polls, …) before
 * its chat shows it. The filter is the case of a merged lambda that starts by casting the event
 * and returns Boolean.valueOf; right after the cast it gets a first check of its own, so the hide
 * options can drop additions of a highlight type. Only the two addition events (a highlight, a
 * hype train pill) are asked about; removals and everything else go straight on, so nothing
 * stays stuck on screen.
 */
internal val highlightFilterPatch = bytecodePatch {
    extendWith("extensions/twitch.mpe")
    execute {
        val request = AddHighlightRequested.originalClassDef.type
        val added = AddCommunityHighlight.originalClassDef
        val event = added.superclass ?: throw PatchException("Highlights: event base type missing")
        val pill = AddCommunityPill.originalClassDef
        if (pill.superclass != event) throw PatchException("Highlights: the pill event has another base type")
        fun isFilter(m: Method) = m.name == "invoke" && m.parameterTypes.map { it.toString() } == listOf("Ljava/lang/Object;") &&
            m.implementation?.instructions?.let { code ->
                code.any { it.opcode == Opcode.NEW_INSTANCE && it.type() == request } &&
                    code.any { it.opcode == Opcode.INSTANCE_OF && it.type() == added.type }
            } == true
        val owner = classesMatching { c -> c.methods.any(::isFilter) }.uniqueContract("community highlight event filter")
        val method = mutableClassDefBy(owner).methods.filter(::isFilter).uniqueContract("community highlight filter method")
        val code = method.instructions.toList()
        val event1 = method.implementation!!.registerCount - 1
        val start = code.indices.filter { i ->
            code[i].opcode == Opcode.CHECK_CAST && (code[i] as OneRegisterInstruction).registerA == event1 &&
                code[i].type() == event && run {
                    val end = (i + 1 until code.size).firstOrNull { code[it].opcode == Opcode.RETURN_OBJECT } ?: code.size
                    (i until end).any { code[it].call() == "Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;" }
                }
        }.uniqueContract("community highlight filter case")
        val selector = code.filter { it.opcode == Opcode.PACKED_SWITCH || it.opcode == Opcode.SPARSE_SWITCH }
            .uniqueContract("community highlight lambda switch") as OneRegisterInstruction
        val scratch = selector.registerA
        if (scratch > 15 || event1 > 15 || !deadAt(code, start + 1, scratch))
            throw PatchException("Highlights: no free register at the filter (selector v$scratch, event v$event1)")
        // After the cast, so the case still starts with it. "keep" is Twitch's own next
        // instruction: the label must name an instruction that occurs once in the method.
        val next = method.getInstruction(start + 1)
        method.addInstructionsWithLabels(start + 1, """
            instance-of v$scratch, v$event1, ${added.type}
            if-nez v$scratch, :ask
            instance-of v$scratch, v$event1, ${pill.type}
            if-eqz v$scratch, :keep
            :ask
            invoke-static {v$event1}, Lio/github/sabopura/morphe/extension/twitch/chat/ViewCustomization;->keepHighlight(Ljava/lang/Object;)Z
            move-result v$scratch
            if-nez v$scratch, :keep
            sget-object v$scratch, Ljava/lang/Boolean;->FALSE:Ljava/lang/Boolean;
            return-object v$scratch
        """, ExternalLabel("keep", next))
        // Every way out of the hook must land on Twitch's next instruction, never back in it.
        val hook = method.instructions.toList()
        val ask = hook.indexOfFirst { it.call()?.contains("->keepHighlight(") == true }
        val resume = hook.indexOfFirst { it === next }
        fun target(i: Int) = (hook.getOrNull(i) as? BuilderOffsetInstruction)?.target?.location?.index
        if (ask < 4 || resume != ask + 5 || target(ask - 3) != ask || target(ask - 1) != resume || target(ask + 2) != resume)
            throw PatchException("Highlights: the hook does not continue with Twitch's code (at $ask, resume $resume)")
        Logger.getLogger("Sabopura.Chat").info("Highlights: filter hook in $owner after $start (event v$event1, scratch v$scratch)")
    }
}
