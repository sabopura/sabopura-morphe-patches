package io.github.sabopura.morphe.patches.smartnews

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.removeInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import io.github.sabopura.morphe.patches.shared.Constants.SMARTNEWS_COMPATIBILITY
import io.github.sabopura.morphe.patches.shared.SmaliTemplates
import java.util.logging.Logger

private val logger = Logger.getLogger("SmartNewsMorphePatch")

/**
 * Running tally of what the patch actually neutralised, logged at the end so the
 * effect can be audited from Morphe/logcat output instead of assuming success.
 */
private class PatchSummary {
    var booleanFlags = 0
    var globalEnableFlags = 0
    var objectConfigs = 0
    val objectConfigKeys = mutableSetOf<String>()
    var thirdPartyDisableOverrides = 0
    var channelGates = 0
    var readerWebViewMethods = 0
    var viewsPatched = 0
    var viewMethods = 0
    var viewsMissing = 0
    var legacyBooleanFlags = 0
}

@Suppress("unused")
val smartNewsAdsPatch = bytecodePatch(
    name = "SmartNews cleanup",
    description = "Disables SmartNews ad config, native layouts, and reader ad nodes via stable anchors.",
    default = true,
) {
    compatibleWith(SMARTNEWS_COMPATIBILITY)
    dependsOn(smartNewsResourcePatch)

    execute {
        val summary = PatchSummary()
        patchLegacyAdRelatedAttributes(summary)
        patchAdConfigFlags(summary)
        patchThirdPartyDisableCallback(summary)
        val hasServerAdGates = patchServerAdGates(summary)
        patchReaderWebAds(summary)
        patchAdViews(summary)

        logger.info(
            "SmartNews cleanup summary: " +
                "legacyBooleanFlags=${summary.legacyBooleanFlags} " +
                "booleanFlags=${summary.booleanFlags} " +
                "globalEnableFlags=${summary.globalEnableFlags} " +
                "objectConfigs=${summary.objectConfigs} " +
                "objectConfigKeys=${summary.objectConfigKeys.sorted()} " +
                "thirdPartyDisableOverrides=${summary.thirdPartyDisableOverrides} " +
                "channelGates=${summary.channelGates} " +
                "readerWebViewMethods=${summary.readerWebViewMethods} " +
                "viewsPatched=${summary.viewsPatched} " +
                "viewMethods=${summary.viewMethods} " +
                "viewsMissing=${summary.viewsMissing}",
        )

        val neutralised = summary.legacyBooleanFlags + summary.booleanFlags +
            summary.objectConfigs + summary.thirdPartyDisableOverrides + summary.channelGates +
            summary.readerWebViewMethods + summary.viewMethods
        if (neutralised == 0) {
            throw PatchException(
                "SmartNews cleanup matched no critical ad anchors. The app likely changed " +
                    "its config keys or view descriptors; re-inspect the target before release.",
            )
        }

        if (hasServerAdGates) {
            val missing = buildList {
                if (summary.channelGates != 2) add("both Channel ad gates")
                if (summary.globalEnableFlags == 0) add("global ad insertion gate")
                if (summary.readerWebViewMethods == 0) add("reader WebView ad suppression")
                val requiredConfigs = Fingerprints.NULLABLE_AD_CONFIG_KEYS +
                    Fingerprints.EMPTY_LIST_AD_CONFIG_KEYS + Fingerprints.WEATHER_AD_CONFIG_KEY
                val missingConfigs = requiredConfigs - summary.objectConfigKeys
                if (missingConfigs.isNotEmpty()) add("typed configs: ${missingConfigs.sorted()}")
            }
            if (missing.isNotEmpty()) {
                throw PatchException(
                    "SmartNews 26.8+ ad pipeline was detected, but critical anchors were missing: " +
                        missing.joinToString() + ". Refusing a partial patch.",
                )
            }
        }
    }
}

/**
 * Pre-26.7 path: the ad config lived in an exact-named class whose boolean getters took
 * an AttributeProvider. Kept so older targets keep working unchanged.
 */
private fun BytecodePatchContext.patchLegacyAdRelatedAttributes(summary: PatchSummary) {
    val klass = mutableClassDefByOrNull(Fingerprints.LEGACY_AD_RELATED_ATTRIBUTES) ?: return
    logger.info("SmartNews ad config: legacy class ${klass.type} present; stubbing boolean getters to false.")
    klass.methods
        .filter { it.returnType == "Z" && it.parameterTypes.size == 1 }
        .forEach { method ->
            method.replaceBody(SmaliTemplates.returnBoolean(false))
            summary.legacyBooleanFlags++
        }
    if (summary.legacyBooleanFlags == 0) {
        logger.warning("SmartNews ad config: legacy class matched but no boolean getters were stubbed.")
    }
}

/** Version-resilient remote-config path with type-safe values for each ad surface. */
private fun BytecodePatchContext.patchAdConfigFlags(summary: PatchSummary) {
    classDefForEach { classDef ->
        classDef.methods.forEach { method ->
            if (!method.isGetterShaped()) return@forEach
            val strings = method.stringReferences()
            if (strings.isEmpty()) return@forEach

            val enableKey = Fingerprints.AD_ENABLE_BOOLEAN_KEYS.firstOrNull { it in strings }
            val disableKey = Fingerprints.AD_DISABLE_BOOLEAN_KEYS.firstOrNull { it in strings }
            val nullableKey = Fingerprints.NULLABLE_AD_CONFIG_KEYS.firstOrNull { it in strings }
            val emptyListKey = Fingerprints.EMPTY_LIST_AD_CONFIG_KEYS.firstOrNull { it in strings }
            val hasWeatherKey = Fingerprints.WEATHER_AD_CONFIG_KEY in strings

            when {
                method.returnType == "Z" && enableKey != null -> {
                    mutableMethodOf(classDef, method)?.let {
                        it.replaceBody(SmaliTemplates.returnBoolean(false))
                        summary.booleanFlags++
                        summary.globalEnableFlags++
                        logger.info("SmartNews ad flag: ${classDef.type}->${method.name} [$enableKey] forced false.")
                    }
                }

                method.returnType == "Z" && disableKey != null -> {
                    mutableMethodOf(classDef, method)?.let {
                        it.replaceBody(SmaliTemplates.returnBoolean(true))
                        summary.booleanFlags++
                        logger.info("SmartNews ad flag: ${classDef.type}->${method.name} [$disableKey] forced true.")
                    }
                }

                method.returnType.startsWith("L") && nullableKey != null -> {
                    mutableMethodOf(classDef, method)?.let {
                        it.prependReturn(SmaliTemplates.returnNull())
                        summary.objectConfigs++
                        summary.objectConfigKeys += nullableKey
                        logger.info("SmartNews ad config: ${classDef.type}->${method.name} [$nullableKey] forced null.")
                    }
                }

                method.returnType == "Ljava/util/List;" && emptyListKey != null -> {
                    mutableMethodOf(classDef, method)?.let {
                        it.prependReturn(SmaliTemplates.returnEmptyList())
                        summary.objectConfigs++
                        summary.objectConfigKeys += emptyListKey
                        logger.info("SmartNews ad config: ${classDef.type}->${method.name} [$emptyListKey] forced empty.")
                    }
                }

                hasWeatherKey -> {
                    val configType = resolveWeatherConfigType(
                        method.returnType,
                        mutableClassDefByOrNull(method.returnType),
                        mutableClassDefByOrNull(WEATHER_CONFIG_COMPANION),
                    )
                    mutableMethodOf(classDef, method)?.let {
                        it.prependReturn(SmaliTemplates.returnDisabledWeatherConfig(configType))
                        summary.objectConfigs++
                        summary.objectConfigKeys += Fingerprints.WEATHER_AD_CONFIG_KEY
                        logger.info(
                            "SmartNews ad config: ${classDef.type}->${method.name} " +
                                "[${Fingerprints.WEATHER_AD_CONFIG_KEY}] forced to disabled default.",
                        )
                    }
                }
            }
        }
    }
}

/**
 * `disableThirdPartyAds` is one branch of a shared synthetic lambda in 26.8.50.
 * Replacing the whole lambda would break unrelated startup work, so only the Boolean
 * conversion result associated with this key is overwritten.
 */
private fun BytecodePatchContext.patchThirdPartyDisableCallback(summary: PatchSummary) {
    classDefForEach { classDef ->
        classDef.methods
            .filter { Fingerprints.THIRD_PARTY_DISABLE_KEY in it.stringReferences() }
            .forEach { method ->
                val mutable = mutableMethodOf(classDef, method)
                    ?: throw PatchException("Unable to make ${classDef.type}->${method.name} mutable.")
                val keyIndex = mutable.instructions.indexOfFirst {
                    ((it as? ReferenceInstruction)?.reference as? StringReference)?.string ==
                        Fingerprints.THIRD_PARTY_DISABLE_KEY
                }
                val booleanCallIndex = ((keyIndex + 1)..minOf(keyIndex + 40, mutable.instructions.lastIndex))
                    .firstOrNull { index ->
                        val reference = (mutable.instructions[index] as? ReferenceInstruction)?.reference as? MethodReference
                        reference?.definingClass == "Ljava/lang/Boolean;" &&
                            reference.name == "booleanValue" &&
                            reference.returnType == "Z"
                    } ?: throw PatchException(
                    "Found ${Fingerprints.THIRD_PARTY_DISABLE_KEY} in ${classDef.type}->${method.name}, " +
                        "but not its Boolean conversion.",
                )
                val resultIndex = booleanCallIndex + 1
                val result = mutable.instructions.getOrNull(resultIndex)
                if (result?.opcode != Opcode.MOVE_RESULT || result !is OneRegisterInstruction) {
                    throw PatchException(
                        "Unexpected ${Fingerprints.THIRD_PARTY_DISABLE_KEY} result in " +
                            "${classDef.type}->${method.name}.",
                    )
                }
                mutable.replaceInstruction(resultIndex, "const/4 v${result.registerA}, 0x1")
                summary.thirdPartyDisableOverrides++
                logger.info(
                    "SmartNews third-party ads: ${classDef.type}->${method.name} " +
                        "[${Fingerprints.THIRD_PARTY_DISABLE_KEY}] forced true in-place.",
                )
            }
    }
}

/** Stable channel-level insertion gates introduced by the 26.8 ad pipeline. */
private fun BytecodePatchContext.patchServerAdGates(summary: PatchSummary): Boolean {
    val channel = mutableClassDefByOrNull(Fingerprints.CHANNEL_DESCRIPTOR)
    val detected = channel != null

    channel?.let { klass ->
        listOf("isServerAdsInsertionEnabled", "isUnifiedAdEndpointEnabled").forEach { name ->
            val method = klass.findMethod(name, "Z")
                ?: throw PatchException("SmartNews Channel method missing: $name()Z")
            method.replaceBody(SmaliTemplates.returnBoolean(false))
            summary.channelGates++
            logger.info("SmartNews channel gate: ${klass.type}->$name forced false.")
        }
    }

    return detected
}

/** Collapse SmartNews-marked ad nodes inside reader WebViews, including late DOM inserts. */
private fun BytecodePatchContext.patchReaderWebAds(summary: PatchSummary) {
    classDefForEach { classDef ->
        if (classDef.superclass != "Landroid/webkit/WebViewClient;" ||
            classDef.fields.none { it.type == "Ljp/gocro/smartnews/android/view/WebViewWrapper;" }
        ) {
            return@classDefForEach
        }

        classDef.methods
            .filter {
                it.name == "onPageFinished" && it.returnType == "V" &&
                    it.parameterTypes.map { type -> type.toString() } ==
                    listOf("Landroid/webkit/WebView;", "Ljava/lang/String;")
            }
            .forEach { method ->
                val mutable = mutableMethodOf(classDef, method)
                    ?: throw PatchException("Unable to patch reader WebView client ${classDef.type}.")
                mutable.addInstructions(0, SmaliTemplates.hideReaderWebAds())
                summary.readerWebViewMethods++
                logger.info("SmartNews reader: ${classDef.type}->onPageFinished hides data-sn-ad nodes.")
            }
    }
}

/**
 * Visual safety net. For the ad view descriptors SmartNews still ships un-obfuscated,
 * rewrite only stable method names (framework overrides + the kept `setAd`) so anything
 * that slips past the config layer still measures/renders to nothing. Missing descriptors
 * are logged, never silently skipped.
 */
private fun BytecodePatchContext.patchAdViews(summary: PatchSummary) {
    Fingerprints.AD_VIEW_DESCRIPTORS.forEach { descriptor ->
        val klass = mutableClassDefByOrNull(descriptor)
        if (klass == null) {
            logger.warning("SmartNews ad view: descriptor not found, skipping: $descriptor")
            summary.viewsMissing++
            return@forEach
        }

        var touched = 0
        klass.methodsByName("onMeasure").forEachReturnType("onMeasure", "V") {
            it.replaceBody(SmaliTemplates.zeroMeasureAndReturn()); touched++
        }
        klass.methodsByName("setAd").forEach {
            if (it.returnType == "V") { it.replaceBody(SmaliTemplates.hideViewAndReturn()); touched++ }
        }
        klass.methodsByName("onAttachedToWindow").forEachReturnType("onAttachedToWindow", "V") {
            it.replaceBody(SmaliTemplates.hideViewAndReturn()); touched++
        }
        klass.methodsByName("getVisible").forEachReturnType("getVisible", "Z") {
            it.replaceBody(SmaliTemplates.returnBoolean(false)); touched++
        }

        if (touched == 0) {
            logger.warning("SmartNews ad view: $descriptor found but no stable method matched (config layer must cover it).")
        } else {
            logger.info("SmartNews ad view: $descriptor neutralised $touched method(s).")
            summary.viewMethods += touched
        }
        summary.viewsPatched++
    }
}

private fun Method.isGetterShaped(): Boolean =
    name != "<init>" &&
        name != "<clinit>" &&
        returnType != "V" &&
        parameterTypes.size <= 1

private fun Method.stringReferences(): Set<String> {
    val implementation = implementation ?: return emptySet()
    return implementation.instructions
        .asSequence()
        .mapNotNull { instruction ->
            ((instruction as? ReferenceInstruction)?.reference as? StringReference)?.string
        }
        .toSet()
}

private fun BytecodePatchContext.mutableMethodOf(classDef: ClassDef, method: Method): MutableMethod? {
    val mutable = mutableClassDefBy(classDef)
    return mutable.methods.firstOrNull {
        it.name == method.name &&
            it.returnType == method.returnType &&
            it.parameterTypes.map { type -> type.toString() } ==
                method.parameterTypes.map { type -> type.toString() }
    }
}

private fun MutableClass.findMethod(
    name: String,
    returnType: String? = null,
): MutableMethod? = methods.firstOrNull {
    it.name == name &&
        it.parameterTypes.isEmpty() &&
        (returnType == null || it.returnType == returnType)
}

private fun MutableClass.methodsByName(name: String): List<MutableMethod> =
    methods.filter { it.name == name }

private inline fun List<MutableMethod>.forEachReturnType(
    @Suppress("UNUSED_PARAMETER") methodName: String,
    returnType: String,
    action: (MutableMethod) -> Unit,
) {
    filter { it.returnType == returnType }.forEach(action)
}

private fun MutableMethod.replaceBody(smali: String) {
    val implementation = implementation ?: throw PatchException("Method $name has no implementation.")
    removeInstructions(0, implementation.instructions.count())
    addInstructions(0, smali)
}

/** Keep existing try/catch ranges valid while making the original body unreachable. */
private fun MutableMethod.prependReturn(smali: String) {
    if (implementation == null) throw PatchException("Method $name has no implementation.")
    addInstructions(0, smali)
}
