package io.github.sabopura.morphe.patches.googlenews

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import io.github.sabopura.morphe.patches.googlenews.Constants.GOOGLE_NEWS
import java.util.logging.Logger

/*
 * GnpSdk (Google Notifications Platform) periodically registers every Google account on the
 * device for push notifications and requests an `oauth2:.../auth/notifications` token for each.
 * Under MicroG an app has no token permission for an account until the user approves it, so each
 * request posts a "Account <name> / Requested by Google News" notification, one per account.
 *
 * The device-account provider (blocking and suspend variants) is the only source of that list;
 * both log "missing permission GET_ACCOUNTS" before calling AccountManager.getAccountsByType.
 * Returning an empty list makes GnpSdk register no device accounts, as it already does when the
 * app lacks GET_ACCOUNTS. The app's own sign-in (AuthHelper) does not use this provider.
 */

private val logger = Logger.getLogger("Morphe.GoogleNews")

private const val ACCOUNTS_LOG =
    "Failed to get accounts using AccountManager, missing permission GET_ACCOUNTS"

private fun Method.isGnpDeviceAccountProvider(): Boolean {
    val instructions = implementation?.instructions ?: return false
    val references = instructions.mapNotNull { (it as? ReferenceInstruction)?.reference }
    return references.any { it is StringReference && it.string == ACCOUNTS_LOG } &&
        references.any { it is MethodReference && it.name == "getAccountsByType" }
}

@Suppress("unused")
val googleNewsGnpAccountsPatch = bytecodePatch(
    name = "Disable device account notification sync",
    description = "Stops the notification SDK from requesting a token for every Google account on the " +
        "device, which under MicroG shows an \"Account ... requested by Google News\" notification per " +
        "account. Account-bound push notifications are no longer registered.",
    default = true,
) {
    compatibleWith(GOOGLE_NEWS)

    execute {
        var patched = 0
        getAllClassesWithStrings().forEach { classDef ->
            if (classDef.methods.none { it.isGnpDeviceAccountProvider() }) return@forEach
            mutableClassDefBy(classDef).methods
                .filter { it.isGnpDeviceAccountProvider() }
                .forEach { method ->
                    if (method.returnType != "Ljava/util/List;" && method.returnType != "Ljava/lang/Object;") {
                        throw PatchException("Unexpected return type ${method.returnType} in ${method.definingClass}")
                    }
                    val implementation = method.implementation!!
                    val parameterRegisters = method.parameterTypes.sumOf { if (it == "J" || it == "D") 2 else 1 } +
                        if (AccessFlags.STATIC.isSet(method.accessFlags)) 0 else 1
                    if (implementation.registerCount - parameterRegisters < 1) {
                        throw PatchException("No free register in ${method.definingClass}->${method.name}")
                    }
                    method.addInstructions(
                        0,
                        """
                            invoke-static {}, Ljava/util/Collections;->emptyList()Ljava/util/List;
                            move-result-object v0
                            return-object v0
                        """,
                    )
                    patched++
                }
        }
        if (patched == 0) throw PatchException("GnpSdk device account provider not found")
        logger.info("Google News GnpSdk: emptied $patched device account provider method(s)")
    }
}
