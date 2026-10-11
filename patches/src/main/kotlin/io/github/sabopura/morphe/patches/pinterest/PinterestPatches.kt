package io.github.sabopura.morphe.patches.pinterest

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.removeInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Field
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.value.StringEncodedValue
import io.github.sabopura.morphe.patches.pinterest.Constants.PINTEREST
import java.util.logging.Logger

/*
 * Rewrite of the Pinterest patches from Binarymend Patches 1.3.1 (GPLv3,
 * https://github.com/binarymend/morphe-patches). The originals address obfuscated class and
 * method names of Pinterest 14.11.0 (qu/c.d0, api/model/yd.B5/d5, d9/c, g10/n4.c), so they
 * stop applying as soon as R8 renames them. These versions locate the same code through
 * JSON field annotations, SDK header strings and enum constant names.
 */

private val logger = Logger.getLogger("Morphe.Pinterest")

private const val PIN_MODEL_PACKAGE = "Lcom/pinterest/api/model/"

private fun Method.strings(): Set<String> =
    implementation?.instructions
        ?.mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }
        ?.toSet() ?: emptySet()

/** The JSON key of a field annotated with Gson's (renamed) {@code @SerializedName}. */
private fun Field.jsonNames(): Set<String> =
    annotations.flatMap { annotation ->
        annotation.elements.mapNotNull { (it.value as? StringEncodedValue)?.value }
    }.toSet()

private fun BytecodePatchContext.mutableMethod(classDef: ClassDef, method: Method): MutableMethod =
    mutableClassDefBy(classDef.type).methods.single {
        it.name == method.name && it.returnType == method.returnType &&
            it.parameterTypes == method.parameterTypes
    }

private fun MutableMethod.replaceBody(smali: String) {
    removeInstructions(0, instructions.count())
    addInstructions(0, smali)
}

@Suppress("unused")
val pinterestPromotedPinsPatch = bytecodePatch(
    name = "Remove Promoted Pins",
    description = "Makes every pin report itself as not promoted and not an active ad, " +
        "which disables Pinterest's ad handling for pins.",
    default = true,
) {
    compatibleWith(PINTEREST)

    execute {
        // The Pin model is the class carrying both ad flags as JSON fields.
        val pinClasses = mutableListOf<Pair<ClassDef, Map<String, Field>>>()
        classDefForEach { classDef ->
            if (!classDef.type.startsWith(PIN_MODEL_PACKAGE)) return@classDefForEach
            val flags = mutableMapOf<String, Field>()
            classDef.fields.forEach { field ->
                if (AccessFlags.STATIC.isSet(field.accessFlags)) return@forEach
                if (field.type != "Ljava/lang/Boolean;") return@forEach
                val names = field.jsonNames()
                if ("is_promoted" in names) flags["is_promoted"] = field
                if ("is_active_ad" in names) flags["is_active_ad"] = field
            }
            if (flags.size == 2) pinClasses += classDef to flags
        }
        val (pinClass, flags) = pinClasses.singleOrNull()
            ?: throw PatchException("Expected one Pin model with is_promoted/is_active_ad, found ${pinClasses.size}")

        // Getters: public, no arguments, return Boolean and read the flag field.
        flags.forEach { (jsonName, field) ->
            val getters = pinClass.methods.filter { method ->
                !AccessFlags.STATIC.isSet(method.accessFlags) &&
                    method.parameterTypes.isEmpty() &&
                    method.returnType == "Ljava/lang/Boolean;" &&
                    (method.implementation?.instructions?.count() ?: Int.MAX_VALUE) <= 8 &&
                    method.implementation!!.instructions.any { instruction ->
                        instruction.opcode == Opcode.IGET_OBJECT &&
                            ((instruction as ReferenceInstruction).reference as FieldReference).let {
                                it.name == field.name && it.definingClass == pinClass.type
                            }
                    }
            }
            if (getters.isEmpty()) {
                // Newer builds (14.38+) no longer read is_active_ad, and R8 drops its getter.
                if (jsonName == "is_promoted") throw PatchException("No getter found for Pin.$jsonName")
                logger.info("Pin.$jsonName: no getter (the app does not read it in this version)")
                return@forEach
            }
            getters.forEach { getter ->
                mutableMethod(pinClass, getter).replaceBody(
                    """
                        sget-object v0, Ljava/lang/Boolean;->FALSE:Ljava/lang/Boolean;
                        return-object v0
                    """.trimIndent(),
                )
            }
            logger.info("Pin.$jsonName: ${getters.size} getter(s) in ${pinClass.type} return false")
        }
    }
}

@Suppress("unused")
val pinterestBugsnagPatch = bytecodePatch(
    name = "Disable Bugsnag Telemetry",
    description = "Makes the Bugsnag SDK discard every error and session report.",
    default = true,
) {
    compatibleWith(PINTEREST)

    execute {
        // Bugsnag's ImmutableConfig builds the delivery headers.
        val configs = mutableListOf<ClassDef>()
        classDefForEach { classDef ->
            if (classDef.methods.any { method ->
                    method.strings().containsAll(setOf("Bugsnag-Payload-Version", "Bugsnag-Api-Key"))
                } &&
                classDef.methods.any { it.parameterTypes == listOf("Ljava/lang/Throwable;") && it.returnType == "Z" }
            ) {
                configs += classDef
            }
        }
        val config = configs.singleOrNull()
            ?: throw PatchException("Expected one Bugsnag ImmutableConfig, found ${configs.size}")

        // shouldDiscardError(Throwable) starts with shouldDiscardByReleaseStage(); the SDK
        // checks the latter before sending errors and sessions. Forcing it to true is the
        // SDK's own "release stage not enabled" switch.
        val discardError = config.methods.single {
            it.parameterTypes == listOf("Ljava/lang/Throwable;") && it.returnType == "Z"
        }
        val releaseStageCheck = discardError.implementation!!.instructions.firstNotNullOfOrNull { instruction ->
            if (instruction.opcode != Opcode.INVOKE_VIRTUAL) return@firstNotNullOfOrNull null
            val reference = (instruction as ReferenceInstruction).reference as MethodReference
            reference.takeIf {
                it.definingClass == config.type && it.parameterTypes.isEmpty() && it.returnType == "Z"
            }
        } ?: throw PatchException("Bugsnag release stage check not found in ${config.type}")
        val target = config.methods.single {
            it.name == releaseStageCheck.name && it.parameterTypes.isEmpty() && it.returnType == "Z"
        }
        mutableMethod(config, target).replaceBody(
            """
                const/4 v0, 0x1
                return v0
            """.trimIndent(),
        )
        logger.info("Bugsnag: ${config.type}->${target.name}() now always discards")
    }
}

/** Startup tasks that only initialise analytics, ads measurement or tracking uploads. */
private val TELEMETRY_TASKS = listOf(
    "TAG_CRASH_REPORTING",
    "TAG_APPSFLYER_INIT",
    "TAG_FIREBASE_ANALYTICS_INIT",
    "TAG_GOOGLE_ENGAGE_INIT",
    "TAG_ADS_GMA_MANAGER_INIT",
    "TAG_ADS_OPEN_MEASUREMENT_SDK_INIT",
    "TAG_TRACKING_REQUESTS",
    "TAG_RUM_REPORTING",
    "TAG_SCHEDULE_SUBMIT_NETWORK_METRICS",
    "TAG_LANDING_SIGNALS_UPLOAD",
    "TAG_ADS_APP_INSTALL_LOG",
    "TAG_LOG_DEVICE_PROFILE",
    "TAG_LOG_ENTRY_POINT",
    "TAG_LOG_LOCATION_PERMISSIONS",
)

@Suppress("unused")
val pinterestGeneralTelemetryPatch = bytecodePatch(
    name = "Disable General Telemetry",
    description = "Skips Pinterest's analytics, attribution, ads measurement and tracking " +
        "startup tasks.",
    default = true,
) {
    compatibleWith(PINTEREST)

    execute {
        // The startup task tag enum lists every task by name.
        val tagEnums = mutableListOf<ClassDef>()
        classDefForEach { classDef ->
            if (classDef.superclass != "Ljava/lang/Enum;") return@classDefForEach
            val names = classDef.fields.map { it.name }.toSet()
            if (names.containsAll(listOf("TAG_APPSFLYER_INIT", "TAG_CRASH_REPORTING", "TAG_UNDEFINED"))) {
                tagEnums += classDef
            }
        }
        val tagEnum = tagEnums.singleOrNull()
            ?: throw PatchException("Expected one Pinterest startup task tag enum, found ${tagEnums.size}")
        val tags = TELEMETRY_TASKS.filter { name -> tagEnum.fields.any { it.name == name } }
        if (tags.size < TELEMETRY_TASKS.size / 2) {
            throw PatchException("Only ${tags.size} telemetry task tags exist in ${tagEnum.type}")
        }

        // The task base class holds a Runnable and its tag; its run method reads the tag.
        val runMethods = mutableListOf<Triple<ClassDef, Method, FieldReference>>()
        classDefForEach { classDef ->
            if (!AccessFlags.ABSTRACT.isSet(classDef.accessFlags)) return@classDefForEach
            val fields = classDef.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) }
            if (fields.none { it.type == "Ljava/lang/Runnable;" }) return@classDefForEach
            val tagField = fields.singleOrNull { it.type == tagEnum.type } ?: return@classDefForEach
            classDef.methods.forEach { method ->
                if (AccessFlags.STATIC.isSet(method.accessFlags) || method.name == "<init>") return@forEach
                if (method.parameterTypes.isNotEmpty() || method.returnType != "V") return@forEach
                val instructions = method.implementation?.instructions ?: return@forEach
                val readsTag = instructions.firstNotNullOfOrNull { instruction ->
                    if (instruction.opcode != Opcode.IGET_OBJECT) return@firstNotNullOfOrNull null
                    ((instruction as ReferenceInstruction).reference as FieldReference)
                        .takeIf { it.name == tagField.name && it.definingClass == classDef.type }
                }
                val checksDone = instructions.any { instruction ->
                    ((instruction as? ReferenceInstruction)?.reference as? MethodReference)?.let {
                        it.name == "contains" && it.definingClass.endsWith("Set;")
                    } == true
                }
                if (readsTag != null && checksDone) runMethods += Triple(classDef, method, readsTag)
            }
        }
        val (taskClass, run, tagField) = runMethods.singleOrNull()
            ?: throw PatchException("Expected one Pinterest startup task run method, found ${runMethods.size}")

        val method = mutableMethod(taskClass, run)
        val implementation = method.implementation!!
        if (implementation.registerCount - 1 < 2) {
            throw PatchException("Startup task run method has too few registers")
        }
        val checks = tags.mapIndexed { index, tag ->
            """
                sget-object v1, ${tagEnum.type}->$tag:${tagEnum.type}
                if-ne v0, v1, :sabopura_next_$index
                return-void
                :sabopura_next_$index
            """.trimIndent()
        }
        method.addInstructionsWithLabels(
            0,
            "iget-object v0, p0, ${taskClass.type}->${tagField.name}:${tagField.type}\n" +
                checks.joinToString("\n") + "\nnop",
        )
        logger.info("Startup tasks: ${tags.size} telemetry task(s) skipped in ${taskClass.type}->${run.name}()")
    }
}
