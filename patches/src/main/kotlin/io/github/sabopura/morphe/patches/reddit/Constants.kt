package io.github.sabopura.morphe.patches.reddit

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

internal object Constants {
    val REDDIT_2026_28_0 = Compatibility(
        name = "Reddit",
        packageName = "com.reddit.frontpage",
        description = "Official Reddit Android app, pinned to verified bytecode anchors.",
        apkFileType = ApkFileType.APKS,
        appIconColor = 0xFF4500,
        signatures = setOf(
            "970b91143813b4c9d5f3634f672c9fcaa5621b4efaaedafd6c235cbbb869736f",
        ),
        targets = listOf(
            AppTarget(
                version = "2026.28.0",
                versionCode = 2628060,
                minSdk = 29,
                description = "Verified against the official split-APK archive.",
            ),
            AppTarget(
                version = "2026.30.0",
                versionCode = 2630060,
                minSdk = 29,
                description =
                    "Verified anchors, rebuild, signing, installation, and cold launch on API 36.",
            ),
            AppTarget(
                version = "2026.35.0",
                versionCode = 2635091,
                minSdk = 29,
                description = "Automatically verified on the dedicated Android API 36 emulator.",
            ),
            AppTarget(
                version = "2026.37.0",
                versionCode = 2637051,
                minSdk = 29,
                description = "Automatically verified on the dedicated Android API 36 emulator.",
            ),
            AppTarget(
                version = "2026.38.0",
                versionCode = 2638001,
                minSdk = 32,
                description = "Automatically verified on the dedicated Android API 36 emulator.",
            ),
            AppTarget(
                version = "2026.39.0",
                versionCode = 2639001,
                minSdk = 32,
                description = "Automatically verified on the dedicated Android API 36 emulator.",
            ),
            AppTarget(
                version = "2026.40.0",
                versionCode = 2640041,
                minSdk = 32,
                description = "Automatically verified on the dedicated Android API 36 emulator.",
            ),
            AppTarget(
                version = "2026.41.0",
                versionCode = 2641021,
                minSdk = 32,
                description = "Patched and launched logged out on the dedicated Android API 36 emulator; the sign-in screen blocks public feeds.",
            ),
            AppTarget(
                version = "2026.42.0",
                versionCode = 2642001,
                minSdk = 32,
                description = "Patched and launched logged out on the dedicated Android API 36 emulator; the sign-in screen blocks public feeds.",
            ),
        ),
    )
}
