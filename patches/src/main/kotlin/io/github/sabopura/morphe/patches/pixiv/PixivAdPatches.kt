package io.github.sabopura.morphe.patches.pixiv

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.removeInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import io.github.sabopura.morphe.patches.pixiv.Constants.PIXIV
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

@Suppress("unused")
val pixivHideAdsPatch = bytecodePatch(
    name = "Hide pixiv ads",
    description = "Removes the overlay, list, detail and interstitial ads. Does not unlock Premium features.",
    default = true
) {
    compatibleWith(PIXIV)

    execute {
        // Every ad slot (viewer overlays, illust/manga/novel lists, illust detail,
        // AppLovin interstitial) is gated by one method that returns !isPremium.
        // Two independent call sites are used to find it so a single changed caller
        // in a future app version does not break the patch.
        val callSites = listOfNotNull(
            NovelListAdInsertionFingerprint.matchOrNull(),
            InterstitialAdLoaderFingerprint.matchOrNull()
        ).map { match ->
            match.instructionMatches.first {
                (it.instruction as? ReferenceInstruction)?.reference is MethodReference
            }
        }

        val adVisibilityChecks = callSites.map {
            it.getInstruction<ReferenceInstruction>().reference as MethodReference
        }.distinctBy { "${it.definingClass}->${it.name}" }

        when (adVisibilityChecks.size) {
            0 -> throw PatchException("Could not find the ad visibility check")
            1 -> Unit
            else -> throw PatchException("Call sites disagree on the ad visibility check: $adVisibilityChecks")
        }

        callSites.first().getMethodCalled().apply {
            val opcodes = implementation!!.instructions.map { it.opcode }
            if (Opcode.IGET_BOOLEAN !in opcodes || Opcode.XOR_INT_LIT8 !in opcodes) {
                throw PatchException("Unexpected ad visibility check body: $opcodes")
            }

            addInstructions(
                0,
                """
                    const/4 v0, 0x0
                    return v0
                """
            )
        }
    }
}

@Suppress("unused")
val pixivDisableAdSdksPatch = bytecodePatch(
    name = "Disable pixiv ad SDKs",
    description = "Skips initialization of AppLovin MAX, Google Mobile Ads and Amazon APS " +
            "to reduce ad-related network traffic.",
    default = true
) {
    compatibleWith(PIXIV)

    // The ad views must already be hidden, otherwise they would try to load ads
    // from SDKs that were never initialized.
    dependsOn(pixivHideAdsPatch)

    execute {
        AppLovinInitializationFingerprint.method.addInstruction(0, "return-void")

        AmazonApsInitializationFingerprint.method.addInstruction(0, "return-void")

        // This call sits in one branch of a switch shared with unrelated initializers,
        // so only the call itself is removed.
        GoogleMobileAdsInitializationFingerprint.let {
            it.method.removeInstruction(it.instructionMatches.first().index)
        }
    }
}
