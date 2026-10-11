package io.github.sabopura.morphe.patches.reddit.autotranslate

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.Opcode

internal const val POST_INDICATOR_STATE =
    "Lcom/reddit/feeds/model/PostTranslationIndicatorState;"
internal const val POST_EVENT =
    "Lcom/reddit/feeds/ui/events/translation/OnTranslateButtonClicked;"
internal const val POST_EVENT_SOURCE =
    "Lcom/reddit/feeds/model/translation/TranslateButtonEventSource;"
internal const val COMMENT_INDICATOR_STATE =
    "Lcom/reddit/localization/translations/TranslationIndicatorState;"
internal const val COMMENT_EVENT_SOURCE =
    "Lcom/reddit/comments/events/translation/TranslationCommentEventSource;"

/**
 * Matches every post callback that builds Reddit's stable translation event.
 *
 * The callback, view-model and field names are deliberately not included: R8 changes all of
 * them between releases. The patch derives those references from each match instead.
 */
internal object PostTranslateCallbackFingerprint : Fingerprint(
    name = "invoke",
    returnType = "Ljava/lang/Object;",
    parameters = listOf(),
    filters = listOf(
        fieldAccess(opcode = Opcode.IGET_OBJECT, type = POST_INDICATOR_STATE),
        fieldAccess(
            opcode = Opcode.SGET_OBJECT,
            definingClass = POST_EVENT_SOURCE,
            name = "INDICATOR",
            type = POST_EVENT_SOURCE,
        ),
        methodCall(
            definingClass = POST_EVENT,
            name = "<init>",
            returnType = "V",
            parameters = listOf(
                "Ljava/lang/String;",
                "Ljava/lang/String;",
                "Z",
                "L",
                POST_EVENT_SOURCE,
            ),
        ),
    ),
)

/** Matches the state-aware comment callback while ignoring all obfuscated owners and names. */
internal object CommentTranslateCallbackFingerprint : Fingerprint(
    name = "invoke",
    returnType = "Ljava/lang/Object;",
    parameters = listOf(),
    filters = listOf(
        fieldAccess(opcode = Opcode.IGET_OBJECT, type = COMMENT_INDICATOR_STATE),
        fieldAccess(
            opcode = Opcode.SGET_OBJECT,
            definingClass = COMMENT_EVENT_SOURCE,
            name = "INDICATOR",
            type = COMMENT_EVENT_SOURCE,
        ),
        methodCall(
            returnType = "V",
            parameters = listOf("Ljava/lang/String;", COMMENT_EVENT_SOURCE),
        ),
    ),
)

/** Matches the accessibility callback that captures a comment id directly. */
internal object AccessibilityCommentTranslateCallbackFingerprint : Fingerprint(
    name = "invoke",
    returnType = "Ljava/lang/Object;",
    parameters = listOf(),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = "this",
            type = "Ljava/lang/String;",
        ),
        fieldAccess(
            opcode = Opcode.SGET_OBJECT,
            definingClass = COMMENT_EVENT_SOURCE,
            name = "INDICATOR",
            type = COMMENT_EVENT_SOURCE,
        ),
        methodCall(
            returnType = "V",
            parameters = listOf("Ljava/lang/String;", COMMENT_EVENT_SOURCE),
        ),
    ),
)

internal object TranslationHeaderFingerprint : Fingerprint(
    accessFlags = listOf(
        com.android.tools.smali.dexlib2.AccessFlags.PUBLIC,
        com.android.tools.smali.dexlib2.AccessFlags.STATIC,
    ),
    returnType = "Lokhttp3/Request;",
    parameters = listOf("Lokhttp3/Request;", "L"),
    filters = listOf(
        string("enabled, initiated"),
        string("enabled"),
        string("disabled"),
        string("enabled, seo, "),
        string("X-Reddit-Translations"),
        methodCall(
            definingClass = "Lokhttp3/Request\$Builder;",
            name = "header",
            returnType = "Lokhttp3/Request\$Builder;",
            parameters = listOf("Ljava/lang/String;", "Ljava/lang/String;"),
        ),
    ),
)
