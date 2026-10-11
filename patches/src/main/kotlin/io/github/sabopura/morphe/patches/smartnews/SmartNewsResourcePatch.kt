package io.github.sabopura.morphe.patches.smartnews

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.resourcePatch
import io.github.sabopura.morphe.patches.shared.Constants.SMARTNEWS_COMPATIBILITY
import java.io.File
import java.util.logging.Logger

private val logger = Logger.getLogger("SmartNewsMorphePatch")

@Suppress("unused")
val smartNewsResourcePatch = resourcePatch(
    name = null,
    description = "Internal SmartNews resource cleanup (hides ad layout roots, fixes settings switch).",
    default = false,
) {
    compatibleWith(SMARTNEWS_COMPATIBILITY)

    execute {
        val resDir = this["res"]
        hideAdLayouts(resDir)
        fixSwitchCompatDrawable(resDir)
    }
}

private fun hideAdLayouts(resDir: File) {
    val layoutDirs = resDir.listFiles { file -> file.isDirectory && file.name.startsWith("layout") }.orEmpty()
    var changed = 0

    layoutDirs.forEach { dir ->
        dir.walkTopDown()
            .filter {
                it.isFile && it.extension == "xml" &&
                    it.nameWithoutExtension.isAdLayoutName()
            }
            .forEach { file ->
                val before = file.readText()
                val after = before
                    .upsertRootAndroidAttribute("visibility", "gone")
                    .upsertRootAndroidAttribute("layout_width", "0dp")
                    .upsertRootAndroidAttribute("layout_height", "0dp")

                if (after != before) {
                    file.writeText(after)
                    changed++
                }
            }
    }

    // Never fail the whole patch on a layout-naming change: a future version could move
    // or rename ad layouts, and the bytecode config layer is the primary ad kill anyway.
    if (changed == 0) {
        logger.warning("SmartNews resource: no ad layout roots were hidden (naming may have changed).")
    } else {
        logger.info("SmartNews resource: hid $changed ad layout root(s).")
    }
}

private fun String.isAdLayoutName(): Boolean =
    this == "ad" || startsWith("ad_") || endsWith("_ad") || contains("_ad_")

private fun fixSwitchCompatDrawable(resDir: File) {
    val file = resDir.resolve("drawable/abc_switch_thumb_material.xml")
    if (!file.exists()) {
        logger.warning("SmartNews resource: abc_switch_thumb_material.xml missing; skipping settings switch fix.")
        return
    }
    val transparentName = "abc_switch_thumb_material_transparent"

    val fixed = file.readText()
        .replace("android:drawable=\"@null\"", "android:drawable=\"@drawable/$transparentName\"")
    file.writeText(fixed)

    val transparent = requiredFile(resDir, "drawable/$transparentName.xml", mustExist = false)
    transparent.parentFile.mkdirs()
    transparent.writeText(
        """
        <?xml version="1.0" encoding="utf-8"?>
        <shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
            <size android:width="1dp" android:height="1dp" />
            <solid android:color="@android:color/transparent" />
        </shape>
        """.trimIndent(),
    )
}

private fun requiredFile(resDir: File, path: String, mustExist: Boolean = true): File {
    val file = resDir.resolve(path)
    if (mustExist && !file.exists()) {
        throw PatchException("Required resource file is missing: $path")
    }
    return file
}

private fun String.upsertRootAndroidAttribute(name: String, value: String): String {
    val rootStart = indexOf('<', startIndex = indexOf("?>").let { if (it >= 0) it + 2 else 0 })
    if (rootStart < 0) return this

    val rootEnd = indexOf('>', rootStart)
    if (rootEnd < 0) return this

    val rootTag = substring(rootStart, rootEnd + 1)
    val attr = "android:$name"
    val updatedRootTag = if (rootTag.contains("$attr=")) {
        rootTag.replace(Regex("""$attr="[^"]*""""), "$attr=\"$value\"")
    } else {
        val insertAt = if (rootTag.endsWith("/>")) rootTag.length - 2 else rootTag.length - 1
        rootTag.substring(0, insertAt) + " $attr=\"$value\"" + rootTag.substring(insertAt)
    }

    return replaceRange(rootStart, rootEnd + 1, updatedRootTag)
}
