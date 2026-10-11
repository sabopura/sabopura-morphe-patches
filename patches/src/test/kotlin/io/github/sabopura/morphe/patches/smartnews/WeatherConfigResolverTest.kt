package io.github.sabopura.morphe.patches.smartnews

import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Field
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.MethodImplementation
import io.github.sabopura.morphe.patches.shared.SmaliTemplates
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherConfigResolverTest {
    private inline fun <reified T> fixture(vararg properties: Pair<String, Any?>): T {
        val values = properties.toMap()
        return Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            check(values.containsKey(method.name)) { "Unexpected fixture access: ${method.name}" }
            values[method.name]
        } as T
    }

    private fun config(type: String, fieldType: String = WEATHER_CONFIG_COMPANION, flags: Int = 9) =
        fixture<ClassDef>(
            "getType" to type,
            "getStaticFields" to listOf(fixture<Field>(
                "getName" to "Companion", "getType" to fieldType, "getAccessFlags" to flags,
            )),
        )

    private fun companion(type: String, flags: Int = 1, hasBody: Boolean = true, count: Int = 1) =
        fixture<ClassDef>(
            "getType" to WEATHER_CONFIG_COMPANION,
            "getMethods" to List(count) { fixture<Method>(
                "getName" to "default", "getParameterTypes" to emptyList<String>(),
                "getReturnType" to type, "getAccessFlags" to flags,
                "getImplementation" to if (hasBody) fixture<MethodImplementation>() else null,
            ) },
        )

    @Test fun resolvesOldAndRenamedTypesAndEmitsMatchingReferences() {
        // Observed 26.8.50 / 26.9.11 types, plus an unrelated future rename.
        for (type in listOf("Lsp;", "Lup;", "Lrenamed/weather/Config;")) {
            val resolved = resolveWeatherConfigType(type, config(type), companion(type))
            assertEquals(type, resolved)
            val smali = SmaliTemplates.returnDisabledWeatherConfig(resolved)
            assertTrue(smali.contains("$type->Companion:$WEATHER_CONFIG_COMPANION"))
            assertTrue(smali.contains("$WEATHER_CONFIG_COMPANION->default()$type"))
            if (type != "Lsp;") assertFalse(smali.contains("Lsp;"))
        }
    }

    @Test fun rejectsUnrelatedReturnTypeOrMissingClass() {
        assertThrows(IllegalArgumentException::class.java) {
            resolveWeatherConfigType("Ljava/lang/Object;", config("Ljava/lang/Object;"), companion("Lup;"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            resolveWeatherConfigType("Lup;", null, companion("Lup;"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            resolveWeatherConfigType("Lup;", config("Lup;"), null)
        }
    }

    @Test fun rejectsWrongOrInaccessibleCompanionField() {
        for (value in listOf(config("Lup;", "Lother;"), config("Lup;", flags = 1), config("Lup;", flags = 8))) {
            assertThrows(IllegalArgumentException::class.java) {
                resolveWeatherConfigType("Lup;", value, companion("Lup;"))
            }
        }
    }

    @Test fun rejectsMissingAmbiguousStaticOrUnimplementedFactory() {
        for (value in listOf(companion("Lup;", count = 0), companion("Lup;", count = 2),
            companion("Lup;", flags = 9), companion("Lup;", flags = 0), companion("Lup;", hasBody = false))) {
            assertThrows(IllegalArgumentException::class.java) {
                resolveWeatherConfigType("Lup;", config("Lup;"), value)
            }
        }
    }
}
