package io.github.sabopura.morphe.patches.twitch.points

import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.immutable.*
import com.android.tools.smali.dexlib2.immutable.instruction.*
import org.junit.Assert.*
import org.junit.Test

class PointsRendererWrapperTest {
    @Test fun preservesCallerWhenNativeCodeOverwritesItsReceiverRegister() {
        val original = ImmutableMethod("Lfixture/Owner;", "renamedRenderer",
            listOf(ImmutableMethodParameter("Lfixture/View;", emptySet(), null)), "Lfixture/View;",
            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, emptySet(), emptySet(),
            ImmutableMethodImplementation(4, listOf(
                ImmutableInstruction11n(Opcode.CONST_4, 2, 0), // p0 is reused by optimized native code.
                ImmutableInstruction11x(Opcode.RETURN_OBJECT, 3)), emptyList(), emptyList()))
        val (wrapper, native) = wrapPointsRenderer(original)
        assertEquals(original.name, wrapper.name)
        assertEquals(original.accessFlags, wrapper.accessFlags)
        assertTrue(AccessFlags.PRIVATE.isSet(native.accessFlags))
        assertEquals(4, native.implementation!!.registerCount)
        assertEquals(listOf(Opcode.CONST_4, Opcode.RETURN_OBJECT), native.implementation!!.instructions.map { it.opcode })
        assertEquals(2, (native.implementation!!.instructions[0] as OneRegisterInstruction).registerA)
        val code = wrapper.implementation!!.instructions
        val render = code[0] as FiveRegisterInstruction
        assertEquals(2, render.registerC)
        assertEquals(3, render.registerD)
        val callback = code[2] as FiveRegisterInstruction
        assertEquals(0, callback.registerC) // Actual native result, including disabled/banned states.
        assertEquals(2, callback.registerD) // Original receiver remains live in this stack frame.
        assertEquals(0, (code.last() as OneRegisterInstruction).registerA)
    }
}
