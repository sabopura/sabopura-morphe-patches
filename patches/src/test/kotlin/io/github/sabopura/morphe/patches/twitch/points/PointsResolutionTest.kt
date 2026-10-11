package io.github.sabopura.morphe.patches.twitch.points

import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.MethodImplementation
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class PointsResolutionTest {
    private inline fun <reified T> fixture(vararg values: Pair<String, Any?>): T {
        val map = values.toMap()
        return Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            check(map.containsKey(method.name)); map[method.name]
        } as T
    }
    private fun matches(prefix: String, constructed: String, flags: Int = 1): Boolean {
        val enabled = fixture<ClassDef>("getType" to "L$prefix/enabled;", "getSuperclass" to "L$prefix/view;")
        val method = fixture<Method>("getAccessFlags" to flags, "getParameterTypes" to listOf("L$prefix/state;"),
            "getReturnType" to "L$prefix/view;", "getImplementation" to fixture<MethodImplementation>(
                "getInstructions" to listOf(ImmutableInstruction21c(Opcode.NEW_INSTANCE, 0, ImmutableTypeReference(constructed)))))
        return isPointsRenderer(method, "L$prefix/state;", enabled)
    }
    @Test fun survivesRenamingOfClassesAndMethods() {
        assertTrue(matches("old", "Lold/enabled;"))
        assertTrue(matches("com/new/version", "Lcom/new/version/enabled;"))
    }
    @Test fun rejectsSimilarMethodsReturningADifferentStateOrStaticHelpers() {
        assertFalse(matches("new", "Lnew/hidden;"))
        assertFalse(matches("new", "Lnew/enabled;", 9))
    }
}
