package io.github.sabopura.morphe.patches.youtube.danmaku

import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.MethodImplementation
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import java.lang.reflect.Proxy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoIdMapWriterTest {
    private inline fun <reified T> fixture(vararg properties: Pair<String, Any?>): T {
        val values = properties.toMap()
        return Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            check(values.containsKey(method.name)) { "Unexpected fixture access: ${method.name}" }
            values[method.name]
        } as T
    }

    private fun writer(keyRegister: Int = 5, owner: String = "Lplayer;", operation: String = "put") =
        fixture<Method>(
            "getAccessFlags" to 17, "getReturnType" to "V", "getDefiningClass" to "Lplayer;",
            "getParameterTypes" to listOf("Ljava/lang/String;", "Lmetadata;"),
            "getImplementation" to fixture<MethodImplementation>(
                "getRegisterCount" to 7,
                "getInstructions" to listOf(
                    ImmutableInstruction22c(Opcode.IGET_OBJECT, 4, 4,
                        ImmutableFieldReference(owner, "map", "Ljava/util/Map;")),
                    ImmutableInstruction35c(Opcode.INVOKE_INTERFACE, 3, 4, keyRegister, 6, 0, 0,
                        ImmutableMethodReference("Ljava/util/Map;", operation,
                            listOf("Ljava/lang/Object;", "Ljava/lang/Object;"), "Ljava/lang/Object;")),
                    ImmutableInstruction10x(Opcode.RETURN_VOID),
                ),
            ),
        )

    @Test fun acceptsTheIdArgumentAsMapKey() { assertTrue(isVideoIdMapWriter(writer())) }
    @Test fun rejectsAnotherStringAsMapKey() { assertFalse(isVideoIdMapWriter(writer(keyRegister = 0))) }
    @Test fun rejectsAnotherOwnersMap() { assertFalse(isVideoIdMapWriter(writer(owner = "Lother;"))) }
    @Test fun rejectsOtherMapOperations() { assertFalse(isVideoIdMapWriter(writer(operation = "replace"))) }
}
