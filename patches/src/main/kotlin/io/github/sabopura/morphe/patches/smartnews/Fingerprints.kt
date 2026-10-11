package io.github.sabopura.morphe.patches.smartnews

/**
 * String anchors and descriptors used to locate SmartNews ad surfaces.
 *
 * These are keyed on remote-config JSON keys (stable across app versions) and on the
 * few ad view descriptors SmartNews still ships un-obfuscated, so they survive the
 * R8 renaming that breaks any patch relying on obfuscated class/method names.
 *
 * Evidence (jp.gocro.smartnews.android):
 *  - 26.6.50: ad config lived in `Ljp/gocro/.../ad/config/AdRelatedAttributes;`, read via
 *    getters taking `AttributeProvider`. That class/interface is gone in 26.7.10.
 *  - 26.7.10 and 26.8.50: the same JSON keys are read by obfuscated static helpers. The
 *    key strings themselves are unchanged, so we match on them instead of class names.
 */
object Fingerprints {
    /**
     * Boolean config getters that gate ad display/insertion. Forced to `false`.
     * `isAdsInsertionEnabled` is the primary feed-ad gate in 26.7.10 (obs. `Lyi;->n`).
     */
    val AD_ENABLE_BOOLEAN_KEYS = setOf(
        "isAdsInsertionEnabled",
    )

    /**
     * Boolean config getters that, when `true`, turn ads OFF. Forced to `true`.
     */
    val AD_DISABLE_BOOLEAN_KEYS = setOf(
        "disableThirdPartyAds",
    )

    /** Object configs whose callers explicitly accept `null` as "not configured". */
    val NULLABLE_AD_CONFIG_KEYS = setOf(
        "smartViewStickyBannerAd",
        "adNetworkInterstitialConfigV2",
    )

    val EMPTY_LIST_AD_CONFIG_KEYS = setOf(
        "multiplePremiumAdsChannels",
    )

    const val WEATHER_AD_CONFIG_KEY = "adsWeatherPage"
    const val THIRD_PARTY_DISABLE_KEY = "disableThirdPartyAds"
    const val CHANNEL_DESCRIPTOR = "Ljp/gocro/smartnews/android/channel/contract/Channel;"

    /**
     * Un-obfuscated ad view descriptors that still exist in 26.7.10. Neutralised as a
     * visual safety net by rewriting only stable, framework/kept method names.
     */
    val AD_VIEW_DESCRIPTORS = listOf(
        "Ljp/gocro/smartnews/android/ad/view/SmartViewNativeAdViewContainer;",
        "Ljp/gocro/smartnews/android/ad/view/SmartViewStickyBannerAdView;",
        "Ljp/gocro/smartnews/android/ad/view/sticky/AdFirstPartyStickyBannerView;",
        "Ljp/gocro/smartnews/android/ad/view/carousel/CarouselAdContainerRecyclerView;",
    )

    /**
     * Legacy exact descriptor for the pre-26.7 ad config class, tried first so older
     * targets keep working via the original path.
     */
    const val LEGACY_AD_RELATED_ATTRIBUTES = "Ljp/gocro/smartnews/android/ad/config/AdRelatedAttributes;"
}
