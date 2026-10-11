package io.github.sabopura.morphe.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.SupportedAbi

object Constants {
    const val PACKAGE_NAME = "jp.gocro.smartnews.android"

    val SMARTNEWS_COMPATIBILITY = Compatibility(
        name = "SmartNews",
        packageName = PACKAGE_NAME,
        description = "SmartNews ad-removal patch set (26.6.50 and later)",
        apkFileType = ApkFileType.APK,
        appIconColor = 0x0C73C7,
        signatures = setOf(
            "40f82012f7b7512d011eb9678a59c194892c863657f43e7c171d181b6bf5c700",
        ),
        targets = listOf(
            AppTarget(
                version = "26.6.50",
                versionCodes = SupportedAbi.entries.associateWith { 1113 },
                description = "Initial SmartNews APKS target verified against jp.gocro.smartnews.android 26.6.50[1113].",
            ),
            AppTarget(
                version = "26.7.10",
                versionCodes = SupportedAbi.entries.associateWith { 1115 },
                description = "SmartNews APKS target 26.7.10[1115]. Ads gated by version-resilient config-key " +
                    "anchors after the 26.7 ad config/view refactor (AdRelatedAttributes/AttributeProvider removed).",
            ),
            AppTarget(
                version = "26.8.50",
                versionCodes = SupportedAbi.entries.associateWith { 1001135 },
                description = "Verified universal APK target. Disables server/unified insertion gates and typed " +
                    "third-party, sticky-banner, interstitial, and weather ad configs; hides native and reader ads.",
            ),
            AppTarget(
                version = "26.9.11",
                versionCodes = SupportedAbi.entries.associateWith { 1001138 },
                description = "Automatically verified on the dedicated Android API 36 emulator.",
            ),
            AppTarget(
                version = "26.9.20",
                versionCodes = SupportedAbi.entries.associateWith { 1001139 },
                description = "Automatically verified on the dedicated Android API 36 emulator.",
            ),
            AppTarget(
                version = "26.10.10",
                versionCodes = SupportedAbi.entries.associateWith { 1001144 },
                description = "Ad-free public feed and scrolling verified on the dedicated Android API 36 emulator.",
            ),
        ),
    )
}
