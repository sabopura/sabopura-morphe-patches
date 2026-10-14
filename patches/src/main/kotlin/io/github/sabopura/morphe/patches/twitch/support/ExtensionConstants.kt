package io.github.sabopura.morphe.patches.twitch.support

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

/** Replaces the first int constant in an extension method, used for patch-time feature flags. */
internal fun BytecodePatchContext.replaceExtensionConstant(
    definingClass: String,
    methodName: String,
    value: Int,
) {
    Fingerprint(definingClass = definingClass, name = methodName).method.apply {
        val constantIndex = implementation!!.instructions.toList().indexOfFirst { instruction ->
            instruction.opcode == Opcode.CONST ||
                instruction.opcode == Opcode.CONST_4 ||
                instruction.opcode == Opcode.CONST_16
        }
        check(constantIndex >= 0) { "Extension config constant missing: $methodName" }
        val register = getInstruction<OneRegisterInstruction>(constantIndex).registerA
        replaceInstruction(constantIndex, "const v$register, $value")
    }
}
