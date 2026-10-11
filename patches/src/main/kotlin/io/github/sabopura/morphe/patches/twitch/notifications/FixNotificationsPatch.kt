package io.github.sabopura.morphe.patches.twitch.notifications

import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import io.github.sabopura.morphe.patches.twitch.Constants.TWITCH_SUPPORTED
import io.github.sabopura.morphe.patches.twitch.support.*
import java.util.logging.Logger

@Suppress("unused")
val fixNotificationsPatch = bytecodePatch(
    name = "Fix notifications",
    description = "Repair push registration after re-signing. Adjusts the certificate header only for HTTPS Firebase Installations requests.",
    default = true,
) {
    compatibleWith(TWITCH_SUPPORTED)
    extendWith("extensions/twitch.mpe")
    execute {
        val candidates = classesWithStrings("X-Android-Cert", "x-goog-api-key").flatMap { c ->
            c.methods.filter { method ->
                method.returnType == "Ljava/net/HttpURLConnection;" &&
                    method.parameterTypes.map { it.toString() } == listOf("Ljava/net/URL;", "Ljava/lang/String;")
            }.map { method -> method to certificateHeaderWrites(method) }
                .filter { it.second.isNotEmpty() }
        }
        val (original, writes) = candidates.uniqueContract("Firebase connection builder")
        val write = writes.uniqueContract("Firebase certificate header write")
        val method = mutableClassDefBy(original.definingClass).methods.filter {
            it.name == original.name && it.parameterTypes == original.parameterTypes && it.returnType == original.returnType
        }.uniqueContract("mutable Firebase connection builder")
        method.replaceInstruction(write.index, staticHeaderCall(write))
        Logger.getLogger("Sabopura.Notifications").info("Firebase certificate header: 1 scoped call in $original")
    }
}
