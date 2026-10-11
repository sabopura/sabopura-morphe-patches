package io.github.sabopura.morphe.patches.twitch.support

import app.morphe.patcher.patch.PatchException
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.MethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.immutable.instruction.*
import com.android.tools.smali.dexlib2.immutable.reference.*
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class BytecodeContractsTest {
    private inline fun <reified T> fixture(vararg values: Pair<String, Any?>): T {
        val map = values.toMap()
        return Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            check(map.containsKey(method.name)) { "Unexpected ${method.name}" }; map[method.name]
        } as T
    }
    private val add = ImmutableMethodReference("Ljava/net/URLConnection;", "addRequestProperty",
        listOf("Ljava/lang/String;", "Ljava/lang/String;"), "V")
    private fun method(vararg code: Instruction) = fixture<Method>("getImplementation" to
        fixture<MethodImplementation>("getInstructions" to code.toList(), "getTryBlocks" to emptyList<Any>()))
    private fun key(register: Int) = ImmutableInstruction21c(Opcode.CONST_STRING, register, ImmutableStringReference("X-Android-Cert"))

    @Test fun followsActualKeyPastUnrelatedHeaderWrites() {
        val target = method(key(4),
            ImmutableInstruction21c(Opcode.CONST_STRING, 5, ImmutableStringReference("Other")),
            ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 3, 0, 5, 6, 0, 0, add),
            ImmutableInstruction22x(Opcode.MOVE_OBJECT_FROM16, 2, 4),
            ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 3, 0, 2, 6, 0, 0, add))
        val write = certificateHeaderWrites(target).single()
        assertEquals(4, write.index)
        assertEquals(listOf(0, 2, 6), write.registers)
    }
    @Test fun supportsJumboStringsAndHighRegisterRangeCalls() {
        val target = method(ImmutableInstruction31c(Opcode.CONST_STRING_JUMBO, 21, ImmutableStringReference("X-Android-Cert")),
            ImmutableInstruction3rc(Opcode.INVOKE_VIRTUAL_RANGE, 20, 3, add))
        val write = certificateHeaderWrites(target).single()
        assertTrue(staticHeaderCall(write).startsWith("invoke-static/range {v20 .. v22}"))
    }
    @Test fun rejectsReusedKeyRegisters() {
        val target = method(key(1), ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 1),
            ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 3, 0, 1, 2, 0, 0, add))
        assertTrue(certificateHeaderWrites(target).isEmpty())
    }
    @Test fun rejectsKeysThatAreNotKnownOnEveryIncomingBranch() {
        val target = method(ImmutableInstruction21t(Opcode.IF_EQZ, 3, 4), key(1),
            ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 3, 0, 1, 2, 0, 0, add))
        assertTrue(certificateHeaderWrites(target).isEmpty())
    }
    @Test fun rejectsAmbiguousMatchesWithActionableError() {
        try { listOf(1, 2).uniqueContract("fixture") ; fail() }
        catch (error: PatchException) { assertTrue(error.message!!.contains("found 2")) }
    }
    @Test fun rejectsUnencodableHighRegisterCombinations() {
        try { staticHeaderCall(HeaderWrite(0, listOf(20, 2, 21), false)); fail() }
        catch (expected: PatchException) { assertTrue(expected.message!!.contains("non-contiguous")) }
    }
}
