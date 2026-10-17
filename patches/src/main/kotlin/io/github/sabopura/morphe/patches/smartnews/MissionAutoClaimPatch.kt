package io.github.sabopura.morphe.patches.smartnews

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.removeInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.booleanOption
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import io.github.sabopura.morphe.patches.shared.Constants.SMARTNEWS_MISSIONS_COMPATIBILITY
import io.github.sabopura.morphe.patches.shared.SmaliTemplates
import java.util.logging.Logger

/*
 * The "My missions" page (tour.smartnews.com/campaign) is a web app inside SmartNews' SNClient
 * bridge WebView. It claims rewards by asking the bridge to `fetch` its missions API, and the
 * app signs those requests. The patch hooks the bridge so the extension can inject a script into
 * that page which makes the same bridge calls itself.
 *
 * The bridge class is obfuscated, so it is found by its contract instead of its name:
 * `@JavascriptInterface postMessageWithId(String, String, String)` (JavaScript interface method
 * names cannot be renamed) plus an attach method that takes the WebView and calls
 * `addJavascriptInterface`. The article reader's older SNClient class has no
 * `postMessageWithId`, so it does not match.
 */

private val logger = Logger.getLogger("SmartNewsMorphePatch")

private const val EXTENSION = "Lio/github/sabopura/morphe/extension/smartnews/MissionAutoClaim;"
private const val BACKGROUND = "Lio/github/sabopura/morphe/extension/smartnews/MissionBackground;"
private const val MAIN_ACTIVITY = "Ljp/gocro/smartnews/android/activity/MainActivity;"
private const val LANDING_COMPANION =
    "Ljp/gocro/smartnews/android/stamprally/ui/MissionsLandingPageFragment\$Companion;"
private const val ACTIVITY = "Landroid/app/Activity;"
private const val REMOVE_ANCHOR = "Cannot remove Fragment attached to a different FragmentManager"
private const val WEB_VIEW = "Landroid/webkit/WebView;"
private const val STRING = "Ljava/lang/String;"
private const val JAVASCRIPT_INTERFACE = "Landroid/webkit/JavascriptInterface;"

private fun Method.isJavascriptInterface() = annotations.any { it.type == JAVASCRIPT_INTERFACE }

private fun Method.parameterTypeNames() = parameterTypes.map { it.toString() }

private fun ClassDef.isSnClientBridge() = methods.any {
    it.name == "postMessageWithId" && it.isJavascriptInterface() &&
        it.parameterTypeNames() == listOf(STRING, STRING, STRING)
}

private fun Method.callsAddJavascriptInterface() = implementation?.instructions?.any { instruction ->
    val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
    reference?.name == "addJavascriptInterface" && reference.definingClass == WEB_VIEW
} == true

private fun MethodReference.toSmali() =
    "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

private fun Method.methodReferences(): List<MethodReference> =
    implementation?.instructions?.mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
        .orEmpty()

private fun MutableMethod.replaceBody(smali: String) {
    removeInstructions(0, instructions.count())
    addInstructions(0, smali)
}

/**
 * Hosts SmartNews' own missions page invisibly from the main activity. The AndroidX fragment
 * API is obfuscated, so the calls are copied from the Points tab, which adds the same fragment:
 * the transaction factory and its add/commit calls around `MissionsLandingPageFragment.newInstance`.
 * The remove call is found by AndroidX's own error message.
 */
private fun BytecodePatchContext.hookBackgroundPage() {
    fun superChain(type: String): List<ClassDef> =
        generateSequence(classDefByOrNull(type)) { it.superclass?.let(::classDefByOrNull) }.toList()

    val mainActivity = mutableClassDefByOrNull(MAIN_ACTIVITY)
        ?: throw PatchException("MainActivity not found")
    val chain = superChain(MAIN_ACTIVITY)

    mainActivity.methods.singleOrNull { it.name == "onResume" && it.parameterTypes.isEmpty() && it.returnType == "V" }
        ?.addInstructions(0, "invoke-static { p0 }, $BACKGROUND->onMainResumed($ACTIVITY)V")
        ?: throw PatchException("MainActivity.onResume not found")

    val stopOwner = chain.firstOrNull { classDef ->
        classDef.methods.any { it.name == "onStop" && it.parameterTypes.isEmpty() && it.implementation != null }
    } ?: throw PatchException("No onStop in MainActivity's class chain")
    mutableClassDefBy(stopOwner).methods
        .single { it.name == "onStop" && it.parameterTypes.isEmpty() && it.implementation != null }
        .addInstructions(0, "invoke-static { p0 }, $BACKGROUND->onActivityStopped($ACTIVITY)V")

    val (fragmentActivity, getManager) = chain.firstNotNullOfOrNull { classDef ->
        classDef.methods.firstOrNull {
            it.name == "getSupportFragmentManager" && it.parameterTypes.isEmpty() &&
                !AccessFlags.STATIC.isSet(it.accessFlags)
        }?.let { classDef.type to it }
    } ?: throw PatchException("getSupportFragmentManager not found in MainActivity's class chain")
    val managerType = getManager.returnType

    var template: List<MethodReference>? = null
    classDefForEach { classDef ->
        if (template != null) return@classDefForEach
        classDef.methods.forEach { method ->
            val refs = method.methodReferences()
            val at = refs.indexOfFirst { it.definingClass == LANDING_COMPANION && it.name == "newInstance" }
            if (at >= 0) template = refs.subList(maxOf(0, at - 6), minOf(refs.size, at + 6))
        }
    }
    val refs = template ?: throw PatchException("Points tab fragment transaction not found")
    val begin = refs.firstOrNull {
        it.parameterTypes.map { type -> type.toString() } == listOf(managerType, managerType)
    } ?: throw PatchException("Fragment transaction factory not found near newInstance: ${refs.map { it.toSmali() }}")
    val add = refs.firstOrNull {
        it.parameterTypes.size == 3 && it.parameterTypes[0].toString() == "I" &&
            it.parameterTypes[2].toString() == "Ljava/lang/String;"
    } ?: throw PatchException("Fragment add call not found near newInstance")
    val commit = refs.firstOrNull { it.parameterTypes.map { type -> type.toString() } == listOf("Z", "Z") }
        ?: throw PatchException("Fragment commit call not found near newInstance")
    val fragmentType = add.parameterTypes[1].toString()
    val transaction = classDefByOrNull(begin.returnType)
        ?: throw PatchException("Fragment transaction class ${begin.returnType} not found")
    val remove = transaction.methods.singleOrNull { method ->
        method.parameterTypes.map { it.toString() } == listOf(fragmentType) &&
            method.implementation?.instructions?.any {
                ((it as? ReferenceInstruction)?.reference as? StringReference)?.string?.startsWith(REMOVE_ANCHOR) == true
            } == true
    } ?: throw PatchException("Fragment remove method not found in ${transaction.type}")
    val removeRef = "${transaction.type}->${remove.name}($fragmentType)${remove.returnType}"

    val background = mutableClassDefBy(BACKGROUND)
    // The stubs may have no local registers, so the bodies only reuse parameter registers.
    fun stub(name: String) = background.methods.single { it.name == name }
    stub("fragmentManager").replaceBody(
        """
            check-cast p0, $fragmentActivity
            invoke-virtual { p0 }, $fragmentActivity->getSupportFragmentManager()$managerType
            move-result-object p0
            return-object p0
        """,
    )
    stub("addFragment").replaceBody(
        """
            check-cast p0, $managerType
            invoke-static { p0, p0 }, ${begin.toSmali()}
            move-result-object p0
            check-cast p2, $fragmentType
            invoke-virtual { p0, p1, p2, p3 }, ${add.toSmali()}
            const/4 p1, 0x1
            invoke-virtual { p0, p1, p1 }, ${commit.toSmali()}
            return p1
        """,
    )
    stub("removeFragment").replaceBody(
        """
            check-cast p0, $managerType
            invoke-static { p0, p0 }, ${begin.toSmali()}
            move-result-object p0
            check-cast p1, $fragmentType
            invoke-virtual { p0, p1 }, $removeRef
            const/4 p1, 0x1
            invoke-virtual { p0, p1, p1 }, ${commit.toSmali()}
            return p1
        """,
    )

    logger.info(
        "SmartNews missions background: activity $fragmentActivity, stop hook ${stopOwner.type}, " +
            "begin ${begin.toSmali()}, add ${add.toSmali()}, commit ${commit.toSmali()}, remove $removeRef",
    )
}

@Suppress("unused")
val smartNewsMissionAutoClaimPatch = bytecodePatch(
    name = "Auto claim mission rewards",
    description = "Claims completed My missions rewards and draws their lotteries in the background, " +
        "without the reward popup or the lottery animation, and shows a short message with the result.",
    default = true,
) {
    compatibleWith(SMARTNEWS_MISSIONS_COMPATIBILITY)

    val drawLotteriesOption = booleanOption(
        key = "draw_lotteries",
        default = true,
        title = "Draw lotteries",
        description = "Also draw lottery rewards automatically. When off, only point rewards are claimed.",
    )

    val backgroundOption = booleanOption(
        key = "claim_in_background",
        default = true,
        title = "Claim in the background",
        description = "Open the missions page invisibly while the app is open (at most every 5 minutes), " +
            "so rewards are claimed without opening the Points tab. A short message shows what was claimed.",
    )

    extendWith("extensions/smartnews.mpe")

    execute {
        val bridges = mutableListOf<ClassDef>()
        classDefForEach { classDef ->
            if (classDef.isSnClientBridge()) bridges += classDef
        }
        val bridge = bridges.singleOrNull()
            ?: throw PatchException(
                "Expected one SNClient bridge with postMessageWithId, found ${bridges.map { it.type }}",
            )
        val mutableBridge = mutableClassDefBy(bridge)

        val attach = mutableBridge.methods.singleOrNull {
            !AccessFlags.STATIC.isSet(it.accessFlags) &&
                it.parameterTypeNames().firstOrNull() == WEB_VIEW &&
                it.callsAddJavascriptInterface()
        } ?: throw PatchException("SNClient bridge ${bridge.type} has no single WebView attach method")
        attach.addInstructions(
            0,
            "invoke-static { p0, p1 }, $EXTENSION->onBridgeAttached(Ljava/lang/Object;$WEB_VIEW)V",
        )

        val messages = mutableBridge.methods.filter {
            it.isJavascriptInterface() && it.name.startsWith("postMessage") && it.implementation != null
        }
        if (messages.map { it.name }.toSet() != setOf("postMessage", "postMessageWithId")) {
            throw PatchException(
                "SNClient bridge ${bridge.type} message methods changed: ${messages.map { it.name }}",
            )
        }
        messages.forEach {
            it.addInstructions(0, "invoke-static { p0 }, $EXTENSION->onBridgeMessage(Ljava/lang/Object;)V")
        }

        if (backgroundOption.value != false) hookBackgroundPage()

        if (drawLotteriesOption.value == false) {
            val toggle = mutableClassDefBy(EXTENSION).methods.single { it.name == "drawLotteries" }
            toggle.removeInstructions(0, toggle.instructions.count())
            toggle.addInstructions(0, SmaliTemplates.returnBoolean(false))
        }

        logger.info(
            "SmartNews missions: bridge ${bridge.type}, attach ${attach.name}, " +
                "hooked ${messages.joinToString { it.name }}, drawLotteries=${drawLotteriesOption.value != false}",
        )
    }
}
