package io.github.sabopura.morphe.patches.twitch.login

import app.morphe.patcher.patch.PatchException
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.*
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.reference.*
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class UnitCompletionTest {
    private inline fun <reified T> fixture(vararg values: Pair<String, Any?>): T {
        val map = values.toMap()
        return Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            check(map.containsKey(method.name)) { method.name }; map[method.name]
        } as T
    }
    private fun unit(type: String): ClassDef {
        val description = fixture<Method>("getName" to "toString", "getReturnType" to "Ljava/lang/String;", "getParameterTypes" to emptyList<String>(),
            "getImplementation" to fixture<MethodImplementation>("getInstructions" to listOf(
                ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference("kotlin.Unit")))))
        return fixture<ClassDef>("getAccessFlags" to 17, "getMethods" to listOf(description), "getStaticFields" to listOf(
            fixture<Field>("getName" to "instance", "getType" to type, "getAccessFlags" to 25)))
    }
    private fun setup(vararg types: String) = fixture<Method>("getImplementation" to fixture<MethodImplementation>(
        "getInstructions" to types.map { ImmutableInstruction21c(Opcode.SGET_OBJECT, 0, ImmutableFieldReference(it, "instance", it)) }))
    @Test fun resolvesNativeUnitDespiteShadedCopiesAndRenaming() {
        val types = mapOf("Lrenamed/nativeUnit;" to unit("Lrenamed/nativeUnit;"), "Lextension/Unit;" to unit("Lextension/Unit;"))
        assertEquals("Lrenamed/nativeUnit;", unitCompletionField(setup("Lrenamed/nativeUnit;")) { types.getValue(it) }.type)
    }
    @Test fun rejectsAmbiguousReferencedCopies() {
        try { unitCompletionField(setup("La;", "Lb;")) { unit(it) }; fail() }
        catch (e: PatchException) { assertTrue(e.message!!.contains("found 2")) }
    }
    @Test fun rejectsMissingNativeUnit() {
        try { unitCompletionField(setup()) { unit(it) }; fail() }
        catch (e: PatchException) { assertTrue(e.message!!.contains("found 0")) }
    }
}
