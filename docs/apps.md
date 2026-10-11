# App details

Technical notes for every patch: how each target is found, what is changed, and what has been verified.
The Twitch user guide is in Japanese in [twitch.md](twitch.md); its matching rules are in [twitch-maintenance.md](twitch-maintenance.md).

## SmartNews

`SmartNews cleanup` disables stable ad remote-config and server-insertion flags,
neutralises known ad view surfaces, hides every tokenised ad layout (including
the newer feed/header/premium containers), collapses SmartNews-marked and publisher
sponsored nodes inside reader WebViews, and repairs the settings switch drawable after
resource rebuilding. Typed empty/default values are used where returning null would
leave the feed waiting indefinitely.

The primary anchors are stable configuration strings and un-obfuscated Android
descriptors. Obfuscated class and method names are not used as the main match.
The patch logs a `SmartNews cleanup summary`; a new app version must not be added
to compatibility until the critical match total is non-zero and launch, settings,
feed, weather, and crash-log checks pass.

Weather config resolves the getter's return type against the stable
`AdsInWeatherConfig$Companion` and its public, typed `default()` factory.
The 26.9.11 repair preserves the disabled default from 26.8.50. Emulator smoke
tests require populated article lists before and after scrolling. 26.10.10 applied
without changes: the public feed showed no sponsored or ad markers before or after
scrolling on the API 36 emulator, with no fatal errors in the process log.

## Reddit

`Auto-translate Reddit content` invokes Reddit's existing translation callbacks
for visible posts and comments. The default target language is Japanese (`ja`);
the `target_language` option accepts a BCP-47 language tag.

The patch includes a small runtime extension that rate-limits callbacks and
suppresses repeated translation requests for the same post or comment.

Reddit `2026.28.0[2628060]` and `2026.30.0[2630060]` were verified to patch and
rebuild successfully. The latter was also signed, installed, and cold-launched
on an Android API 36 emulator without an AndroidRuntime fatal crash. End-to-end
translated content after account login was not verified, so this remains the
main device-test item.

Reddit `2026.37.0[2637051]` through `2026.41.0[2641021]`
passed the forced compatibility probe and a
logged-out cold launch on the dedicated API 36 emulator. The public `/r/all`
route led to onboarding and an authentication sheet; no guest route or public
posts were visible. Reviewed screenshots and process logs showed no fatal
crash or callback/request loop symptoms. Patched DEX inspection confirmed
real post and comment translation callbacks. Translated content rendering
remains unverified behind this authentication gate.

Reddit fingerprints use stable translation state/event descriptors. Obfuscated
callback classes, model classes, field names, and synthetic-lambda selector
numbers are derived from each target APK at patch time. Missing UI variants are
allowed, but the patch intentionally fails instead of producing a no-op when no
critical translation callback can be resolved. The target-language header is
best-effort so a header-only refactor does not disable otherwise valid callbacks.

## YouTube

YouTube has two independent patches in the same bundle. Enable either or both; they share
one set of player hooks so using both does not duplicate any work.

### Ambient light

`Ambient light` brings the Ambient light for YouTube browser extension to the Android app.
It samples the current video frame (PixelCopy for SurfaceView, `getBitmap` for TextureView)
at a low resolution, averages it down for stable colours, and builds a blurred glow that is
stretched outward from the picture (spread), darkened towards the screen edge, and smoothed
between frames to avoid flicker. The glow layer is inserted inside the player directly
above the video and below every player control, so it only paints the pillarbox/letterbox
bars and never covers buttons, captions, or the picture. Optional black-bar detection picks
colours from the picture inside letterboxed movies and can also light those burned-in bars.

In the regular (non-fullscreen) player the watch page becomes see-through and the video's
light fills the whole screen, like the desktop extension: the page background itself is made
transparent where it is painted (YouTube's own ambient mode is hidden) and the light is drawn
underneath, so YouTube's own text, icons and thumbnails sit directly on the light in either
theme. The light calms down while the user touches or scrolls the page (touches on the player
itself are ignored) and fades back after an idle delay. There is no light in the miniplayer.

Open `Settings > Video and audio > Ambient light` (or the player menu) to configure it:
fullscreen / regular-player display, blur, spread, edge fade, brightness, saturation, frame
rate, colour detail, smoothing, black-bar handling, and energy saving. The player settings
menu has an `Ambient light: On/Off` row; long-press it to open the settings. The light
stops updating while playback is paused or buffering and on
still pictures, and hides itself for protected (DRM) videos whose frames cannot be read.

### Danmaku comments

`Danmaku comments` displays regular comments, live chat, and premiere chat over
the player in a right-to-left NicoNico/bilibili-style overlay. It does not use
YouTube Data API or an API key, and never opens the visible comments or chat
panel. It reads YouTube's keyless internal comment and chat responses in the
background using the app's current in-memory request context.

Open `Settings > Video and audio > Danmaku` to configure the overlay. The screen is
embedded in YouTube's native preference hierarchy and uses its standard switches,
selection dialog, and sliders. Regular videos and live/premiere streams have
independent switches. Regular comments can be limited to timestamped comments;
every timestamp in a multi-timestamp comment becomes a separately timed segment,
while each untimestamped comment receives one stable slot and is not intentionally
repeated. Live messages and chat replay messages use the current playback position
and replay-provided video offsets. For live streams, premieres, and their archives,
the comment source can be switched between live chat preferred (the default), regular
comments only, or live chat plus timestamped regular comments.
Long-comment filtering defaults to 100 characters and can be changed or disabled
in the app. Like counts, handles, timestamps, and other comment metadata are not
rendered as danmaku. YouTube custom emoji and channel stamps are drawn inline from
their attachment images using a bounded memory-only cache. The collision scheduler
measures comment widths and delays or omits entries that cannot use a lane safely (or,
optionally, overlaps them NicoNico-style when the screen is crowded).

Further options: NG words (one per line or comma separated; matching ignores case, full/half
width and hiragana/katakana; `/regex/` rules are supported), merging repeated spam such as
`www` into one `×N` comment, Super Chat and membership colours, bold text, outline / shadow
edges, constant-duration (NicoNico) or constant-speed (bilibili) scrolling, regular-player
text scale or fullscreen-only display, and reset to defaults. Comments keep moving in the
miniplayer and picture-in-picture, with the text scaled to the player. Appearance and filter changes are applied instantly from the comments that
were already received; only a change of comment source triggers a new background fetch.

Rendering records each comment once into a hardware display list (RenderNode) and only moves
it per frame, draws only the comments currently on screen, and stops its frame loop while the
video is paused or buffering and between sparse comments.

Verification: for every added YouTube version a public replay is opened on the API 36
emulator and the danmaku overlay must attach, fetch comments and draw them. 21.40.165
passed this check. The emulator build needs a test-only startup shim that is applied
after patching and never enters the MPP.

## Pinterest

The three Pinterest patches replace Binarymend Patches 1.3.1 (GPLv3). Binarymend
addresses Pinterest 14.11.0's obfuscated names (`qu/c`, `api/model/yd`, `d9/c`,
`g10/n4`); in 14.38.0 those classes were renamed or removed and all three
patches fail to apply. The rewrites use stable anchors instead:

- `Remove Promoted Pins`: the Pin model is the `com/pinterest/api/model` class
  whose Boolean fields carry the JSON names `is_promoted` and `is_active_ad`;
  their getters return `false`. 14.38 no longer reads `is_active_ad`, so only
  `is_promoted` is required. As in Binarymend, pins are not filtered out of the
  feed; Pinterest stops treating them as promoted.
- `Disable Bugsnag Telemetry`: Bugsnag's ImmutableConfig (the class that builds
  the `Bugsnag-Payload-Version`/`Bugsnag-Api-Key` headers) reports
  `shouldDiscardByReleaseStage() = true`, so the SDK discards every error and
  session itself.
- `Disable General Telemetry`: the startup task run method skips the same 14
  analytics, attribution, ads-measurement and tracking task tags
  (`TAG_APPSFLYER_INIT`, `TAG_TRACKING_REQUESTS`, ...), found through the tag enum.

14.11.0, 14.38.0 and 14.39.0 were patched, installed and launched to the sign-in
screen on the API 36 emulator without crashes; for 14.11.0 and 14.38.0 the patched
methods were also checked by decompiling the output. Feed behaviour after login was
not verified.

## pixiv

- `Hide pixiv ads`: every ad slot (viewer overlays, illust/manga/novel list
  ads, the illust detail ad item including self-serve ads, and the AppLovin
  interstitial) is gated by one method that returns `!isPremium`. It is found
  from two independent call sites (the novel list method that references
  `NovelAdItemViewHolder`, and the interstitial loader after the
  `AppLovin SDK is not initialized yet` log string) and made to return `false`.
  The patch fails if the call sites disagree or the body is not the expected
  boolean-field inversion. Premium features are not unlocked.
- `Disable pixiv ad SDKs` (requires `Hide pixiv ads`): skips the app's
  AppLovin MAX, Google Mobile Ads and Amazon APS initialization calls. Only
  app code (R8 default package or `jp/pxv/`) is matched, so calls inside SDKs
  and mediation adapters are not touched.

6.199.0 was patched, decompiled to check the four modified methods, installed
and launched on the API 36 emulator without crashes. The unpatched app logs
AppLovin, Mintegral, Pangle and Google ads activity at launch; the patched app
logged none. Ad slots after login were not verified.

## Google News

Rewrites of the two Google News patches from docbt/patched-up 1.10.0 (GPLv3).
The upstream CustomTabs patch addresses 5.163's obfuscated names and fails on
5.168 (`LaeahFingerprint` does not match).

- `Enable CustomTabs`: `CustomTabsTrampolineActivity` (not obfuscated) holds the
  browser-picker interface (single `String pick()` method). Every implementation
  is replaced with an extension that returns the default browser if it offers a
  Custom Tabs service, else the first browser that does, else `null`. The app
  derives `useCustomTabs = pick() != null` itself, so the upstream
  `useCustomTabs = true` rewrites are not needed; without a Custom Tabs browser
  the app keeps its WebView instead of binding a null package.
- `GmsCore support`: renames the package (option `packageName`, default
  `com.google.android.apps.magazines.morphe`, the upstream default, so updates
  over upstream installs keep working) together with both provider authorities,
  the `content_authority` string that `NSContentProvider` matches URIs against,
  and the app-defined permissions (`START_AUDIO_PLAYBACK`, receiver permission);
  the stock app and the patched app install side by side. GMS strings, content
  URIs and manifest c2dm names move to `app.revanced`, MicroG is added to
  `<queries>`, GNP keeps registering as the stock package, and the two GMS
  availability checks are required matches. Upstream's
  `.SuggestionProvider`/`.fileprovider` rewrite (absent in Google News) is
  dropped. Option `spoofedSignature` keeps upstream's value by default and
  offers Google News' own certificate SHA-1 (`38918a45...`) for fresh-install
  sign-in problems.

- `Disable device account notification sync`: GnpSdk periodically registers
  every Google account on the device for push notifications and requests an
  `oauth2:.../auth/notifications` token for each. Under MicroG every unapproved
  request posts "Account <name> / requested by Google News", one per account.
  The device-account provider (found through its "missing permission
  GET_ACCOUNTS" log and `getAccountsByType`; one method in 5.163, blocking and
  suspend variants in 5.168) now returns an empty list, as when GET_ACCOUNTS is
  missing. Account-bound push notifications are no longer registered; the app's
  own sign-in (AuthHelper) is not affected.

Both versions were patched and the manifest, resources and modified methods
were checked in the output. On API 36, 5.168 patched with `Enable CustomTabs` and
`Disable device account notification sync` launched to its home screen. The Android
emulator process itself then exits while the feed loads; this also happens with the
stock app and with every GPU mode, so article opening and MicroG sign-in are unverified.

## Sankaku App

Rewrite of "Disable Infinite Upgrade Popup" from HellveticaPatches 1.1.3
(GPLv3). Sankaku is a React Native app whose logic lives in the Hermes bundle
`assets/index.android.bundle`. Upstream replaces fixed byte sequences per
release, lists only 4.23-4.26 (so 4.27 is skipped) and leaves the bundle's
SHA-1 footer stale. Its OTA guard only stops new update checks; a bundle that
HotUpdater already downloaded is still loaded natively, which matches the
upstream report that the popup returns on 4.26.

`HermesBundle` parses bytecode versions 98-99 (header, small/large function
headers, string table) to find functions by name, rewrites their entry to
`load r0, <constant>; ret r0` and recomputes the footer.

- `Disable Infinite Upgrade Popup`: `showPopupUpsell` returns `undefined` and
  `checkShowPopupUpsellModal` returns `false`. Requires the OTA patch.
- `Disable OTA bundle updates`: HotUpdater's `checkForUpdate` (the one that
  loads the "requires HotUpdater.wrap()" message) returns `null`, which the
  `wrap()` HOC treats as "no update"; the native `getJSBundleFile()` (found
  through its `prepareLaunch: bundleId=` log) always returns
  `assets://index.android.bundle`, so previously downloaded OTA bundles are
  ignored too.

4.26 and 4.27 (official APKs from `welcome.sankaku.app/app-download`) were
patched; a byte diff against the original bundle shows only the three function
entries and the footer changed, the hermes-dec disassembly of the patched
functions is as intended, and the patched native method returns the asset URL.
4.27 was installed and launched on the API 36 emulator without fatal errors; the
popup timing itself was not exercised.

