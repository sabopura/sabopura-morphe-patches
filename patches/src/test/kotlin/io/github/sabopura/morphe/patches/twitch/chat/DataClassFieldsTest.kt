package io.github.sabopura.morphe.patches.twitch.chat

import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.MethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.immutable.instruction.*
import com.android.tools.smali.dexlib2.immutable.reference.*
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class DataClassFieldsTest {
    private inline fun <reified T> fixture(vararg p: Pair<String, Any?>): T {
        val values = p.toMap()
        return Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, m, _ ->
            check(values.containsKey(m.name)) { "Unexpected access ${m.name}" }; values[m.name]
        } as T
    }
    private fun method(regs: Int, flags: Int, params: List<String>, code: List<Instruction>) = fixture<Method>(
        "getParameterTypes" to params, "getAccessFlags" to flags,
        "getImplementation" to fixture<MethodImplementation>("getRegisterCount" to regs, "getInstructions" to code),
    )
    @Test fun resolvesPreloadedLabelsThroughR8AppendHelperAndRegisterReuse() {
        val sb = "Ljava/lang/StringBuilder;"
        val str = "Ljava/lang/String;"
        val author = ImmutableFieldReference("Lrenamed;", "x", str)
        val username = ImmutableFieldReference("Lrenamed;", "y", str)
        val append = ImmutableMethodReference(sb, "append", listOf(str), sb)
        val helperRef = ImmutableMethodReference("Lhelper;", "q", listOf(sb, str, str, str, str), "V")
        val helper = method(5, 9, helperRef.parameterTypes, (1..4).map { r ->
            ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 0, r, 0, 0, 0, append)
        })
        val entry = method(8, 1, emptyList(), listOf(
            ImmutableInstruction21c(Opcode.NEW_INSTANCE, 2, ImmutableTypeReference(sb)),
            ImmutableInstruction21c(Opcode.CONST_STRING, 3, ImmutableStringReference(", authorUserId=")),
            ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 2, 3, 0, 0, 0, append),
            ImmutableInstruction21c(Opcode.CONST_STRING, 3, ImmutableStringReference(", username=")),
            ImmutableInstruction21c(Opcode.CONST_STRING, 4, ImmutableStringReference(", displayName=")),
            ImmutableInstruction22c(Opcode.IGET_OBJECT, 5, 7, author),
            ImmutableInstruction22c(Opcode.IGET_OBJECT, 6, 7, username),
            ImmutableInstruction35c(Opcode.INVOKE_STATIC, 5, 2, 5, 3, 6, 4, helperRef),
        ))
        val fields = dataClassFields(entry) { helper }
        assertEquals(author, fields[", authorUserId="])
        assertEquals(username, fields[", username="])
        assertFalse(fields.containsKey(", displayName="))
    }
}
