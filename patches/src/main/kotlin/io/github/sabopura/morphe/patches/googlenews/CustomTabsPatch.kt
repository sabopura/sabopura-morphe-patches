package io.github.sabopura.morphe.patches.googlenews

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import io.github.sabopura.morphe.patches.googlenews.Constants.GOOGLE_NEWS
import java.util.logging.Logger

/*
 * Rewrite of "Enable CustomTabs" from docbt/patched-up 1.10.0 (GPLv3,
 * https://github.com/docbt/patched-up). The original addresses Google News 5.163's obfuscated
 * names (Ladzz;->i, Laeah, Laeao, Laeal, Lajpv, ...) and therefore has to be rewritten for every
 * release; on 5.168 those names belong to unrelated classes.
 *
 * Google News decides between Custom Tabs and its in-app WebView in one place: the article
 * launcher stores `useCustomTabs = browserPicker.pick() != null`, and
 * CustomTabsTrampolineActivity finishes when the same picker returns null. Both picker
 * implementations only accept browsers from a server-side allowlist. Replacing the picker
 * therefore enables Custom Tabs everywhere, and when no Custom Tabs browser is installed the
 * picker still returns null so the app keeps using its WebView instead of crashing.
 */

private val logger = Logger.getLogger("Morphe.GoogleNews")

private const val TRAMPOLINE_ACTIVITY =
    "Lcom/google/apps/dots/android/modules/reading/customtabs/CustomTabsTrampolineActivity;"
private const val EXTENSION_CLASS =
    "Lio/github/sabopura/morphe/extension/googlenews/CustomTabsBrowser;"
private const val CONTEXT_TYPE = "Landroid/content/Context;"
private const val STRING_TYPE = "Ljava/lang/String;"

private fun Method.isNoArgStringMethod() =
    returnType == STRING_TYPE && parameterTypes.isEmpty()

/** An interface with a single `String pick()` method, i.e. the browser picker. */
private fun ClassDef.isBrowserPickerInterface() =
    AccessFlags.INTERFACE.isSet(accessFlags) &&
        methods.count { !AccessFlags.STATIC.isSet(it.accessFlags) } == 1 &&
        methods.single { !AccessFlags.STATIC.isSet(it.accessFlags) }.isNoArgStringMethod()

@Suppress("unused")
val googleNewsCustomTabsPatch = bytecodePatch(
    name = "Enable CustomTabs",
    description = "Opens articles in your default browser through Custom Tabs instead of the in-app " +
        "WebView, so browser ad blockers and extensions apply.",
    default = true,
) {
    compatibleWith(GOOGLE_NEWS)

    extendWith("extensions/googlenews.mpe")

    execute {
        val trampoline = classDefByOrNull(TRAMPOLINE_ACTIVITY)
            ?: throw PatchException("CustomTabsTrampolineActivity not found")

        val pickerInterfaces = trampoline.fields
            .filter { !AccessFlags.STATIC.isSet(it.accessFlags) }
            .mapNotNull { classDefByOrNull(it.type) }
            .filter { it.isBrowserPickerInterface() }
            .distinctBy { it.type }
        val picker = pickerInterfaces.singleOrNull()
            ?: throw PatchException(
                "Expected one browser picker field in CustomTabsTrampolineActivity, " +
                    "found ${pickerInterfaces.map { it.type }}"
            )
        val pickMethodName = picker.methods.single { !AccessFlags.STATIC.isSet(it.accessFlags) }.name

        val implementations = mutableListOf<ClassDef>()
        classDefForEach { classDef ->
            if (picker.type in classDef.interfaces) implementations += classDef
        }
        if (implementations.isEmpty()) {
            throw PatchException("No implementation of browser picker ${picker.type}")
        }

        implementations.forEach { implementation ->
            val contextField = implementation.fields
                .filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == CONTEXT_TYPE }
                .singleOrNull()
                ?: throw PatchException("Browser picker ${implementation.type} has no single Context field")

            val method = mutableClassDefBy(implementation).methods.singleOrNull {
                it.name == pickMethodName && it.isNoArgStringMethod() &&
                    !AccessFlags.ABSTRACT.isSet(it.accessFlags)
            } ?: throw PatchException("Browser picker ${implementation.type} has no $pickMethodName()")

            // One local register is needed besides `this`.
            if (method.implementation!!.registerCount < 2) {
                throw PatchException("Browser picker ${implementation.type}.$pickMethodName() has no free register")
            }

            method.addInstructions(
                0,
                """
                    iget-object v0, p0, ${implementation.type}->${contextField.name}:$CONTEXT_TYPE
                    invoke-static { v0 }, $EXTENSION_CLASS->getPackageName($CONTEXT_TYPE)$STRING_TYPE
                    move-result-object v0
                    return-object v0
                """,
            )
        }

        logger.info(
            "Google News Custom Tabs: picker ${picker.type}, replaced " +
                implementations.joinToString { it.type }
        )
    }
}
