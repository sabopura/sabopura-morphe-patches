package io.github.sabopura.morphe.patches.pixiv

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val NOVEL_AD_ITEM_VIEW_HOLDER =
    "Ljp/pxv/android/feature/commonlist/recyclerview/baserecycler/NovelAdItemViewHolder;"

private fun Method.hasConstClass(type: String) =
    implementation?.instructions?.any { instruction ->
        instruction.opcode == Opcode.CONST_CLASS &&
                ((instruction as ReferenceInstruction).reference as TypeReference).type == type
    } == true

/**
 * Keeps SDK fingerprints from matching code inside the SDKs or their mediation adapters.
 * R8 moves obfuscated app classes to the default package, so those have no '/' in the type.
 */
private fun ClassDef.isAppCode() = '/' !in type || type.startsWith("Ljp/pxv/")

/**
 * Novel list adapter method that inserts a native ad every 10 items:
 *
 * ```
 * check-cast v1, L<AdVisibility>;
 * invoke-virtual {v1}, L<AdVisibility>;->a()Z   // returns !isPremium
 * move-result v1
 * ...
 * rem-int/lit8 v0, v0, 0xa
 * ...
 * const-class v1, L.../NovelAdItemViewHolder;
 * ```
 *
 * The first instruction match is the call to the shared "should show ads" check.
 */
internal object NovelListAdInsertionFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(
        methodCall(
            returnType = "Z",
            parameters = listOf(),
            opcode = Opcode.INVOKE_VIRTUAL
        ),
        opcode(Opcode.MOVE_RESULT, MatchAfterImmediately()),
        opcode(Opcode.REM_INT_LIT8)
    ),
    custom = { method, _ -> method.hasConstClass(NOVEL_AD_ITEM_VIEW_HOLDER) }
)

/**
 * AppLovin interstitial loader. After the "not initialized" early return it calls the same
 * "should show ads" check before creating the MaxInterstitialAd.
 */
internal object InterstitialAdLoaderFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("L"),
    filters = listOf(
        string("AppLovin SDK is not initialized yet"),
        methodCall(
            returnType = "Z",
            parameters = listOf(),
            opcode = Opcode.INVOKE_VIRTUAL
        ),
        opcode(Opcode.MOVE_RESULT, MatchAfterImmediately()),
        methodCall(
            definingClass = "Lcom/applovin/mediation/ads/MaxInterstitialAd;",
            name = "<init>"
        )
    )
)

internal object AppLovinInitializationFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(
        methodCall(
            definingClass = "Lcom/applovin/sdk/AppLovinSdk;",
            name = "initialize"
        )
    ),
    custom = { _, classDef -> classDef.isAppCode() }
)

internal object GoogleMobileAdsInitializationFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(
        methodCall(
            smali = "Lcom/google/android/gms/ads/MobileAds;->initialize(Landroid/content/Context;" +
                    "Lcom/google/android/gms/ads/initialization/OnInitializationCompleteListener;)V"
        )
    ),
    custom = { _, classDef -> classDef.isAppCode() }
)

internal object AmazonApsInitializationFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Landroid/content/Context;"),
    filters = listOf(
        methodCall(
            smali = "Lcom/amazon/device/ads/AdRegistration;->getInstance(Ljava/lang/String;" +
                    "Landroid/content/Context;)Lcom/amazon/device/ads/AdRegistration;"
        )
    ),
    custom = { _, classDef -> classDef.isAppCode() }
)
