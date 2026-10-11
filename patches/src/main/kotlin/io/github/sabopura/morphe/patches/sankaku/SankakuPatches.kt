package io.github.sabopura.morphe.patches.sankaku

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.rawResourcePatch
import app.morphe.patcher.string
import io.github.sabopura.morphe.patches.sankaku.Constants.SANKAKU
import java.util.logging.Logger

/*
 * Rewrite of "Disable Infinite Upgrade Popup" from HellveticaPatches 1.1.3 (GPLv3,
 * https://github.com/HellveticaStandard/HellveticaPatches). The original replaces fixed byte
 * sequences per release (4.23-4.26) and leaves the bundle's SHA-1 footer stale. Its OTA guard
 * only stops new update checks: a bundle HotUpdater already downloaded is still loaded by the
 * native side instead of the patched one, which brings the popup back.
 *
 * This version reads the Hermes bundle, finds the functions by name and rewrites their entry
 * to return a constant, then recomputes the footer. The native bundle resolver is pinned to
 * the APK's bundle so OTA bundles, old or new, are never loaded.
 */

private val logger = Logger.getLogger("Morphe.Sankaku")

private const val BUNDLE_PATH = "assets/index.android.bundle"
private const val ASSET_BUNDLE_URL = "assets://index.android.bundle"

private fun HermesBundle.singleFunction(name: String, filter: (HermesBundle.Function) -> Boolean = { true }) =
    functionsNamed(name).filter(filter).singleOrNull()
        ?: throw PatchException("Expected exactly one Hermes function '$name'")

private object JsBundleFileFingerprint : Fingerprint(
    name = "getJSBundleFile",
    returnType = "Ljava/lang/String;",
    parameters = listOf(),
    filters = listOf(string("prepareLaunch: bundleId=")),
)

private val disableHotUpdaterCheckPatch = rawResourcePatch {
    execute {
        val file = get(BUNDLE_PATH)
        val bundle = HermesBundle(file.readBytes())

        // HotUpdater's public checkForUpdate() throws this message when not configured.
        val hotUpdaterMessage = bundle.stringIds(
            " requires HotUpdater.wrap() or HotUpdater.init() to be used.\n\n",
        )
        val checkForUpdate = bundle.singleFunction("checkForUpdate") {
            bundle.loadsString(it, hotUpdaterMessage)
        }
        // The wrap() HOC awaits the result; null means "no update" and it finishes normally.
        bundle.returnConstant(checkForUpdate, HermesBundle.OP_LOAD_CONST_NULL)

        bundle.updateFooter()
        file.writeBytes(bundle.toByteArray())
        logger.info("Sankaku: HotUpdater checkForUpdate #${checkForUpdate.index} returns null")
    }
}

@Suppress("unused")
val sankakuDisableOtaUpdatesPatch = bytecodePatch(
    name = "Disable OTA bundle updates",
    description = "Always runs the JavaScript bundle shipped in the APK and stops HotUpdater from " +
        "checking for updates, so over-the-air bundles cannot undo the other patches.",
    default = true,
) {
    compatibleWith(SANKAKU)

    dependsOn(disableHotUpdaterCheckPatch)

    execute {
        JsBundleFileFingerprint.method.apply {
            if (implementation!!.registerCount < 2) throw PatchException("getJSBundleFile has no free register")
            addInstructions(
                0,
                """
                    const-string v0, "$ASSET_BUNDLE_URL"
                    return-object v0
                """,
            )
        }
    }
}

@Suppress("unused")
val sankakuDisableInfinitePopupPatch = rawResourcePatch(
    name = "Disable Infinite Upgrade Popup",
    description = "Disables the \"Want unlimited access? Get Sankaku Infinite!\" upsell popup that " +
        "periodically appears while browsing the app.",
    default = true,
) {
    compatibleWith(SANKAKU)

    // Without it, a downloaded OTA bundle replaces the patched one.
    dependsOn(sankakuDisableOtaUpdatesPatch)

    execute {
        val file = get(BUNDLE_PATH)
        val bundle = HermesBundle(file.readBytes())

        val showPopup = bundle.singleFunction("showPopupUpsell")
        val checkShowPopup = bundle.singleFunction("checkShowPopupUpsellModal")
        bundle.returnConstant(showPopup, HermesBundle.OP_LOAD_CONST_UNDEFINED)
        bundle.returnConstant(checkShowPopup, HermesBundle.OP_LOAD_CONST_FALSE)

        bundle.updateFooter()
        file.writeBytes(bundle.toByteArray())
        logger.info(
            "Sankaku: Hermes v${bundle.version}, showPopupUpsell #${showPopup.index} and " +
                "checkShowPopupUpsellModal #${checkShowPopup.index} disabled",
        )
    }
}
