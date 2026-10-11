package io.github.sabopura.morphe.patches.googlenews

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.patch.Option
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.patch.stringOption
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21c
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction21c
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import io.github.sabopura.morphe.patches.googlenews.Constants.GOOGLE_NEWS
import io.github.sabopura.morphe.patches.googlenews.Constants.GOOGLE_NEWS_PACKAGE
import org.w3c.dom.Element
import java.util.logging.Logger

/*
 * Rewrite of "GmsCore support" and its "Change package name" dependency from
 * docbt/patched-up 1.10.0 (GPLv3, https://github.com/docbt/patched-up), aligned with
 * Morphe's shared GmsCore support. Fixes over the original:
 * - The package rename, provider authorities, the `content_authority` string the content
 *   provider matches URIs against, and the app-defined permissions are renamed together, so the
 *   patched app installs next to the stock app and its content provider keeps working.
 * - The `.SuggestionProvider`/`.fileprovider` rewrite (copied from YouTube, absent in Google News,
 *   and pointing at a package the rename never produced) is dropped.
 * - c2dm permissions/actions in the manifest, MicroG package visibility and the GNP
 *   registration package are handled as in Morphe's GmsCore support.
 * - The GMS availability checks are required matches instead of being skipped silently.
 */

private val logger = Logger.getLogger("Morphe.GoogleNews")

private const val GMS_CORE_VENDOR_GROUP_ID = "app.revanced"
private const val DEFAULT_PACKAGE_NAME = "$GOOGLE_NEWS_PACKAGE.morphe"
private const val UPSTREAM_SPOOFED_SIGNATURE = "24bb24c05e47e0aefa68a58a766179d9b613a600"
private const val GOOGLE_NEWS_CERT_SHA1 = "38918a453d07199354f8b19af05ec6562ced5788"

private lateinit var packageNameOption: Option<String>
private lateinit var spoofedSignatureOption: Option<String>

private val packageNamePattern = Regex("^[a-z]\\w*(\\.[a-z]\\w*)+$")
private val sha1Pattern = Regex("^[0-9a-f]{40}$")

private object GooglePlayUtilityFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "I",
    parameters = listOf("L", "I"),
    strings = listOf("This should never happen.", "MetadataValueReader", "com.google.android.gms"),
)

private object ServiceCheckFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "V",
    parameters = listOf("L", "I"),
    strings = listOf("Google Play Services not available"),
)

/** GNP (Google Notifications Platform) registers with the package name it reports. */
private object GnpRegistrationTargetFingerprint : Fingerprint(
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            smali = "Landroid/content/Context;->getPackageName()Ljava/lang/String;",
        ),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
        string("Failed to get android ID."),
        string("Exception reading GServices key."),
    ),
)

private val googleNewsGmsCoreResourcePatch = resourcePatch {
    execute {
        val toPackageName = packageNameOption.value!!
        val spoofedSignature = spoofedSignatureOption.value!!

        val manifestFile = get("AndroidManifest.xml")
        val original = manifestFile.readText()
        if ("package=\"$GOOGLE_NEWS_PACKAGE\"" !in original) {
            throw PatchException("Manifest package is not $GOOGLE_NEWS_PACKAGE; is another package rename applied?")
        }
        val transformations = listOf(
            "package=\"$GOOGLE_NEWS_PACKAGE\"" to "package=\"$toPackageName\"",
            // NSContentProvider and the GNP chime_sdk_file_provider.
            "android:authorities=\"$GOOGLE_NEWS_PACKAGE" to "android:authorities=\"$toPackageName",
            "$GOOGLE_NEWS_PACKAGE.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" to
                "$toPackageName.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
            // App-defined permissions (START_AUDIO_PLAYBACK, C2D_MESSAGE); keeping the stock
            // names makes installation fail with DUPLICATE_PERMISSION next to the stock app.
            "\"$GOOGLE_NEWS_PACKAGE.permission." to "\"$toPackageName.permission.",
            "com.google.android.c2dm" to "$GMS_CORE_VENDOR_GROUP_ID.android.c2dm",
            // MicroG is a regular app, so it must be declared to be visible on Android 11+.
            "</queries>" to "<package android:name=\"$GMS_CORE_VENDOR_GROUP_ID.android.gms\"/></queries>",
        )
        manifestFile.writeText(
            transformations.fold(original) { text, (from, to) -> text.replace(from, to) }
        )

        document("AndroidManifest.xml").use { document ->
            val application = document.getElementsByTagName("application").item(0)
            fun addMetaData(name: String, value: String) {
                application.appendChild(
                    document.createElement("meta-data").apply {
                        setAttribute("android:name", name)
                        setAttribute("android:value", value)
                    }
                )
            }
            addMetaData("$GMS_CORE_VENDOR_GROUP_ID.android.gms.SPOOFED_PACKAGE_NAME", GOOGLE_NEWS_PACKAGE)
            addMetaData("$GMS_CORE_VENDOR_GROUP_ID.android.gms.SPOOFED_PACKAGE_SIGNATURE", spoofedSignature)
            addMetaData("app.revanced.MICROG_PACKAGE_NAME", "$GMS_CORE_VENDOR_GROUP_ID.android.gms")
        }

        // NSContentProvider matches URIs against this string, not against the manifest.
        document("res/values/strings.xml").use { document ->
            val strings = document.getElementsByTagName("string")
            val contentAuthority = (0 until strings.length)
                .map { strings.item(it) as Element }
                .singleOrNull { it.getAttribute("name") == "content_authority" }
                ?: throw PatchException("String resource content_authority not found")
            if (contentAuthority.textContent.trim() != GOOGLE_NEWS_PACKAGE) {
                throw PatchException("Unexpected content_authority: ${contentAuthority.textContent}")
            }
            contentAuthority.textContent = toPackageName
        }
    }
}

@Suppress("unused")
val googleNewsGmsCoreSupportPatch = bytecodePatch(
    name = "GmsCore support",
    description = "Renames the app and routes Google sign-in through MicroG (GmsCore) instead of " +
        "Google Play Services, so the patched app installs next to the stock app.",
    default = true,
) {
    compatibleWith(GOOGLE_NEWS)

    packageNameOption = stringOption(
        key = "packageName",
        default = DEFAULT_PACKAGE_NAME,
        values = mapOf("docbt/patched-up default (update-compatible)" to DEFAULT_PACKAGE_NAME),
        title = "Package name",
        description = "Package name of the patched app. Keep the default to update an install " +
            "patched with docbt/patched-up.",
        required = true,
    ) { value -> value != null && value != GOOGLE_NEWS_PACKAGE && packageNamePattern.matches(value) }

    spoofedSignatureOption = stringOption(
        key = "spoofedSignature",
        default = UPSTREAM_SPOOFED_SIGNATURE,
        values = mapOf(
            "docbt/patched-up value (reported working)" to UPSTREAM_SPOOFED_SIGNATURE,
            "Google News signing certificate" to GOOGLE_NEWS_CERT_SHA1,
        ),
        title = "Spoofed signature",
        description = "Certificate SHA-1 that MicroG reports for this app. Try the Google News " +
            "certificate if sign-in fails on a fresh install.",
        required = true,
    ) { value -> value != null && sha1Pattern.matches(value) }

    dependsOn(googleNewsGmsCoreResourcePatch)

    execute {
        // Report "no Play Services" (version 0) and never throw for a missing GMS install.
        GooglePlayUtilityFingerprint.method.addInstructions(
            0,
            """
                const/4 v0, 0x0
                return v0
            """,
        )
        ServiceCheckFingerprint.method.addInstruction(0, "return-void")

        // GNP must keep registering under the original package name.
        GnpRegistrationTargetFingerprint.matchOrNull()?.let { match ->
            val result = match.instructionMatches[1]
            match.method.replaceInstruction(
                result.index,
                BuilderInstruction21c(
                    Opcode.CONST_STRING,
                    (result.instruction as OneRegisterInstruction).registerA,
                    ImmutableStringReference(GOOGLE_NEWS_PACKAGE),
                ),
            )
        } ?: logger.warning("Google News GmsCore: GNP registration target not found")

        val renamedAuthorities = AUTHORITIES.associateWith {
            it.replace("com.google", GMS_CORE_VENDOR_GROUP_ID)
        }

        fun transformString(value: String): String? = when {
            value == "com.google" || value == "com.google.android.gms" ||
                value in PERMISSIONS || value in ACTIONS || value in AUTHORITIES ->
                value.replace("com.google", GMS_CORE_VENDOR_GROUP_ID)

            value == "subscribedfeeds" -> "$GMS_CORE_VENDOR_GROUP_ID.subscribedfeeds"

            value.startsWith("content://subscribedfeeds") ->
                value.replace("content://subscribedfeeds", "content://$GMS_CORE_VENDOR_GROUP_ID.subscribedfeeds")

            value.startsWith("content://") -> renamedAuthorities.entries
                .firstOrNull { (from, _) -> value.startsWith("content://$from") }
                ?.let { (from, to) -> value.replaceFirst("content://$from", "content://$to") }

            else -> null
        }

        fun Instruction21c.transformedString() =
            (reference as? StringReference)?.string?.let(::transformString)

        var replaced = 0
        getAllClassesWithStrings().forEach { classDef ->
            // mutableClassDefBy() is expensive; only call it for classes that need changes.
            val needsPatch = classDef.methods.any { method ->
                method.implementation?.instructions?.any {
                    (it as? Instruction21c)?.transformedString() != null
                } == true
            }
            if (!needsPatch) return@forEach

            mutableClassDefBy(classDef).methods.forEach { method ->
                val implementation = method.implementation ?: return@forEach
                implementation.instructions.mapIndexedNotNull { index, instruction ->
                    val transformed = (instruction as? Instruction21c)?.transformedString()
                        ?: return@mapIndexedNotNull null
                    index to BuilderInstruction21c(
                        instruction.opcode,
                        instruction.registerA,
                        ImmutableStringReference(transformed),
                    )
                }.forEach { (index, instruction) ->
                    method.replaceInstruction(index, instruction)
                    replaced++
                }
            }
        }
        if (replaced == 0) throw PatchException("No GMS identifiers found to rewrite")
        logger.info("Google News GmsCore: rewrote $replaced GMS strings")
    }
}
