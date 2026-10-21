package io.github.sabopura.morphe.patches.smartnews

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.removeInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.booleanOption
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.stringOption
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import io.github.sabopura.morphe.patches.shared.Constants.SMARTNEWS_BROWSER_COMPATIBILITY
import io.github.sabopura.morphe.patches.shared.SmaliTemplates
import java.util.logging.Logger

/*
 * SmartNews shows web pages in two places of its own: the article screen, for articles whose
 * style is a web page, and WebBrowserActivity, for links, related articles and ad pages.
 *
 * The feed and StandaloneArticleActivity (notifications, linked articles) open articles through
 * one method of the obfuscated article presenter. LiteArticle's public fields are JSON model
 * fields and are not renamed. StandaloneArticleActivity would stay empty, so its own close method
 * runs first; it may start MainActivity, which must end up below the browser.
 *
 * WebBrowserActivity loads its page through one method that calls WebView.loadUrl, either from
 * onCreate or after SmartNews found no SmartView version of the page, so hooking it sends exactly
 * the pages SmartNews would show as web pages. SmartView versions never reach it; when they should
 * leave too, the onCreate hook clears the URL and the activity finishes through its own "no URL"
 * path.
 */

private val logger = Logger.getLogger("SmartNewsMorphePatch")

private const val EXTENSION = "Lio/github/sabopura/morphe/extension/smartnews/ExternalBrowser;"
private const val PRESENTER_COMPANION =
    "Ljp/gocro/smartnews/android/article/LegacyLinkMasterDetailFlowPresenter\$Companion;"
private const val OPEN_ARTICLE_TRACE = "open article_view"
private const val LINK = "Ljp/gocro/smartnews/android/feed/contract/unified/Link;"
private const val LITE_ARTICLE = "Ljp/gocro/smartnews/android/feed/contract/unified/LiteArticle;"
private const val ARTICLE_VIEW_STYLE = "Ljp/gocro/smartnews/android/article/contract/api/domain/ArticleViewStyle;"
private const val WEB_BROWSER_ACTIVITY = "Ljp/gocro/smartnews/android/activity/WebBrowserActivity;"
private const val STANDALONE_ARTICLE_ACTIVITY = "Ljp/gocro/smartnews/android/article/StandaloneArticleActivity;"
private const val ACTIVITY = "Landroid/app/Activity;"
private const val CONTEXT = "Landroid/content/Context;"
private const val BUNDLE = "Landroid/os/Bundle;"
private const val INTENT = "Landroid/content/Intent;"
private const val WEB_VIEW = "Landroid/webkit/WebView;"
private const val STRING = "Ljava/lang/String;"
private const val BOOLEAN_OBJECT = "Ljava/lang/Boolean;"

private const val DEFAULT_BROWSER = "default"
private val packageNamesPattern =
    Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+(\\s*,\\s*[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+)*$")

private val browsers = linkedMapOf<String, String?>(
    "Default browser (Android setting)" to DEFAULT_BROWSER,
    "Chrome" to "com.android.chrome",
    "Firefox" to "org.mozilla.firefox",
    "Brave" to "com.brave.browser",
    "Microsoft Edge" to "com.microsoft.emmx",
    "Samsung Internet" to "com.sec.android.app.sbrowser",
    "Vivaldi" to "com.vivaldi.browser",
    "Opera" to "com.opera.browser",
    "DuckDuckGo" to "com.duckduckgo.mobile.android",
)

private fun Method.parameterTypeNames() = parameterTypes.map { it.toString() }

private fun Method.containsString(value: String) = implementation?.instructions?.any {
    ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == value
} == true

private fun Method.calls(definingClass: String, name: String) = implementation?.instructions?.any {
    val reference = (it as? ReferenceInstruction)?.reference as? MethodReference
    reference?.definingClass == definingClass && reference.name == name
} == true

private fun Method.isStatic() = AccessFlags.STATIC.isSet(accessFlags)

private fun Method.parameterRegisterCount() =
    parameterTypeNames().sumOf { if (it == "J" || it == "D") 2 else 1 } +
        if (AccessFlags.STATIC.isSet(accessFlags)) 0 else 1

private fun MutableMethod.requireLocals(count: Int, what: String) {
    if (implementation!!.registerCount - parameterRegisterCount() < count) {
        throw PatchException("$what has fewer than $count local registers")
    }
}

private fun MutableMethod.replaceBody(smali: String) {
    removeInstructions(0, instructions.count())
    addInstructions(0, smali)
}

private fun BytecodePatchContext.hookArticleOpen(): String {
    val presenters = mutableListOf<ClassDef>()
    classDefForEach { classDef ->
        if (classDef.fields.any { AccessFlags.STATIC.isSet(it.accessFlags) && it.type == PRESENTER_COMPANION }) {
            presenters += classDef
        }
    }
    val presenter = presenters.singleOrNull()
        ?: throw PatchException("Expected one article presenter, found ${presenters.map { it.type }}")

    val opens = presenter.methods.filter {
        !it.isStatic() && it.returnType == "V" && it.parameterTypeNames().let { types ->
            types.size == 3 && types[0] == CONTEXT && types[2] == "Z"
        } && it.containsString(OPEN_ARTICLE_TRACE)
    }
    val open = opens.singleOrNull()
        ?: throw PatchException("Expected one article open method in ${presenter.type}, found ${opens.map { it.name }}")
    val requestType = open.parameterTypeNames()[1]
    val linkField = classDefByOrNull(requestType)?.fields
        ?.singleOrNull { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == LINK }
        ?: throw PatchException("Article request $requestType has no single Link field")
    val activityField = presenter.fields.singleOrNull { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == ACTIVITY }
        ?: throw PatchException("Article presenter ${presenter.type} has no single Activity field")

    val standaloneClose = classDefByOrNull(STANDALONE_ARTICLE_ACTIVITY)?.methods?.singleOrNull {
        !it.isStatic() && it.returnType == "V" && it.parameterTypes.isEmpty() &&
            it.calls(ACTIVITY, "isTaskRoot") && it.calls(ACTIVITY, "finish")
    } ?: throw PatchException("StandaloneArticleActivity close method not found")

    val article = classDefByOrNull(LITE_ARTICLE) ?: throw PatchException("LiteArticle not found")
    val articleFields = mapOf(
        "url" to STRING,
        "internalUrl" to STRING,
        "articleViewStyle" to ARTICLE_VIEW_STYLE,
        "premium" to BOOLEAN_OBJECT,
    )
    articleFields.forEach { (name, type) ->
        if (article.fields.none { it.name == name && it.type == type && !AccessFlags.STATIC.isSet(it.accessFlags) }) {
            throw PatchException("LiteArticle.$name:$type not found")
        }
    }

    val method = mutableClassDefBy(presenter).methods.single {
        it.name == open.name && it.parameterTypeNames() == open.parameterTypeNames() && it.returnType == "V"
    }
    method.requireLocals(5, "${presenter.type}->${open.name}")
    method.addInstructionsWithLabels(
        0,
        """
            move-object/from16 v0, p2
            iget-object v0, v0, $requestType->${linkField.name}:$LINK
            if-eqz v0, :sabopura_in_app
            iget-object v1, v0, $LITE_ARTICLE->url:$STRING
            iget-object v2, v0, $LITE_ARTICLE->internalUrl:$STRING
            iget-object v3, v0, $LITE_ARTICLE->articleViewStyle:$ARTICLE_VIEW_STYLE
            iget-object v4, v0, $LITE_ARTICLE->premium:$BOOLEAN_OBJECT
            move-object/from16 v0, p1
            invoke-static { v0, v1, v2, v3, v4 }, $EXTENSION->articleTarget($CONTEXT$STRING${STRING}Ljava/lang/Enum;$BOOLEAN_OBJECT)$STRING
            move-result-object v1
            if-eqz v1, :sabopura_in_app
            move-object/from16 v2, p0
            iget-object v2, v2, ${presenter.type}->${activityField.name}:$ACTIVITY
            instance-of v3, v2, $STANDALONE_ARTICLE_ACTIVITY
            if-eqz v3, :sabopura_open
            check-cast v2, $STANDALONE_ARTICLE_ACTIVITY
            invoke-virtual { v2 }, $STANDALONE_ARTICLE_ACTIVITY->${standaloneClose.name}()V
            :sabopura_open
            invoke-static { v0, v1 }, $EXTENSION->open($CONTEXT$STRING)Z
            return-void
        """,
        ExternalLabel("sabopura_in_app", method.getInstruction(0)),
    )
    return "${presenter.type}->${open.name}($requestType.${linkField.name})"
}

private fun BytecodePatchContext.hookWebBrowserActivity(): String {
    val activity = mutableClassDefByOrNull(WEB_BROWSER_ACTIVITY)
        ?: throw PatchException("WebBrowserActivity not found")

    val loads = activity.methods.filter {
        !it.isStatic() && it.returnType == "V" &&
            it.parameterTypeNames() == listOf(STRING) && it.calls(WEB_VIEW, "loadUrl")
    }
    val load = loads.singleOrNull()
        ?: throw PatchException("Expected one page load method in WebBrowserActivity, found ${loads.map { it.name }}")
    load.requireLocals(1, "WebBrowserActivity->${load.name}")
    load.addInstructionsWithLabels(
        0,
        """
            invoke-static/range { p0 .. p1 }, $EXTENSION->onWebPageLoad(Landroid/app/Activity;$STRING)Z
            move-result v0
            if-eqz v0, :sabopura_in_app
            return-void
        """,
        ExternalLabel("sabopura_in_app", load.getInstruction(0)),
    )

    val onCreate = activity.methods.singleOrNull {
        it.name == "onCreate" && it.parameterTypeNames() == listOf(BUNDLE) && it.returnType == "V"
    } ?: throw PatchException("WebBrowserActivity.onCreate not found")

    val instructions = onCreate.instructions.toList()
    val superOnCreate = instructions.indexOfFirst {
        it.opcode == Opcode.INVOKE_SUPER && ((it as ReferenceInstruction).reference as MethodReference).name == "onCreate"
    }
    val dataString = instructions.indexOfFirst {
        val reference = (it as? ReferenceInstruction)?.reference as? MethodReference
        reference?.definingClass == INTENT && reference.name == "getDataString"
    }
    val finishesWithoutUrl = dataString >= 0 && instructions.drop(dataString + 1).take(3).let { next ->
        next.any { it.opcode == Opcode.IF_NEZ } && next.any {
            ((it as? ReferenceInstruction)?.reference as? MethodReference)?.name == "finish"
        }
    }
    if (!finishesWithoutUrl || superOnCreate !in 0 until dataString) {
        throw PatchException("WebBrowserActivity.onCreate no longer finishes when it has no URL")
    }

    onCreate.addInstruction(
        superOnCreate + 1,
        "invoke-static/range { p0 .. p1 }, $EXTENSION->onWebBrowserCreate(Landroid/app/Activity;$BUNDLE)V",
    )
    return load.name
}

@Suppress("unused")
val smartNewsExternalBrowserPatch = bytecodePatch(
    name = "Open in external browser",
    description = "Opens the web pages that SmartNews shows in its in-app browser in another installed " +
        "browser instead: as a Custom Tab (the browser's own in-app browser) or in the browser app.",
    default = false,
) {
    compatibleWith(SMARTNEWS_BROWSER_COMPATIBILITY)

    val browserOption = stringOption(
        key = "browser",
        default = DEFAULT_BROWSER,
        values = browsers,
        title = "Browser",
        description = "Package name of the browser, or several separated by commas (the first installed one " +
            "is used). \"default\" uses the default browser app set in Android settings, which is also used " +
            "when none of the chosen browsers is installed (without a default, the first installed browser).",
        required = true,
    ) { value -> value == DEFAULT_BROWSER || (value != null && packageNamesPattern.matches(value)) }

    val customTabsOption = booleanOption(
        key = "custom_tabs",
        default = true,
        title = "Use Custom Tabs",
        description = "Show pages in the browser's in-app browser (Custom Tabs) on top of SmartNews. " +
            "Turn off to open them in the browser app itself.",
    )

    val smartViewOption = booleanOption(
        key = "smart_view_articles",
        default = false,
        title = "Also open SmartView articles",
        description = "Also open articles that SmartNews shows in its SmartView reader, as their original page.",
    )

    extendWith("extensions/smartnews.mpe")

    execute {
        val browser = (browserOption.value ?: DEFAULT_BROWSER).split(",").joinToString(",") { it.trim() }
        val customTabs = customTabsOption.value != false
        val smartView = smartViewOption.value == true

        val articleHook = hookArticleOpen()
        val pageLoad = hookWebBrowserActivity()

        val extension = mutableClassDefBy(EXTENSION)
        fun stub(name: String) = extension.methods.single { it.name == name && it.parameterTypes.isEmpty() }
        stub("browserPackage").replaceBody(
            """
                const-string v0, "$browser"
                return-object v0
            """,
        )
        stub("customTabs").replaceBody(SmaliTemplates.returnBoolean(customTabs))
        stub("smartViewArticles").replaceBody(SmaliTemplates.returnBoolean(smartView))

        logger.info(
            "SmartNews external browser: article hook $articleHook, WebBrowserActivity page load $pageLoad, " +
                "browser=$browser customTabs=$customTabs smartViewArticles=$smartView",
        )
    }
}
