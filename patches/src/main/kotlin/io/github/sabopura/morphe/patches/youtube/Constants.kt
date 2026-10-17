package io.github.sabopura.morphe.patches.youtube

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

internal object Constants {
    val YOUTUBE_21_04_223 = Compatibility(
        name = "YouTube",
        packageName = "com.google.android.youtube",
        description = "Official YouTube Android app verified for API-free danmaku comments and ambient light.",
        apkFileType = ApkFileType.APK_REQUIRED,
        appIconColor = 0xFF0033,
        signatures = setOf(
            "5aad2bee6db95d17e05a08d7d1e64c10a1511879154483916b6ae6c7fd9cb0c6",
            "3d7a1223019aa39d9ea0e3436ab7c0896bfb4fb679f4de5fe7c23f326c8f994a",
        ),
        targets = listOf(
            AppTarget(
                version = "21.04.223",
                minSdk = 28,
                description = "Patched, rebuilt, installed, launched, and visually verified on API 36.",
            ),
            AppTarget(
                version = "21.34.248",
                minSdk = 29,
                description = "Automatically verified on the dedicated Android API 36 emulator.",
            ),
            AppTarget(
                version = "21.35.443",
                minSdk = 29,
                description = "Automatically verified on the dedicated Android API 36 emulator.",
            ),
            AppTarget(
                version = "21.35.448",
                minSdk = 29,
                description = "Automatically verified on the dedicated Android API 36 emulator.",
            ),
            AppTarget(
                version = "21.40.161",
                minSdk = 29,
                description = "Danmaku and ambient light verified on the dedicated Android API 36 emulator.",
            ),
            AppTarget(
                version = "21.40.165",
                minSdk = 29,
                description = "Danmaku fetched and drawn over a public replay on the dedicated Android API 36 emulator.",
            ),
            AppTarget(
                version = "21.40.166",
                minSdk = 29,
                description = "Danmaku fetched and drawn over a public video and the see-through watch page shown on the dedicated Android API 36 emulator.",
            ),
            AppTarget(
                version = "21.41.526",
                minSdk = 29,
                description = "Danmaku fetched and drawn over a public replay and the see-through watch page shown on the dedicated Android API 36 emulator.",
            ),
            AppTarget(
                version = "21.41.529",
                minSdk = 29,
                description = "Danmaku fetched and drawn over a public replay and the see-through watch page shown on the dedicated Android API 36 emulator.",
            ),
        ),
    )
}
