# Twitch APK integration verification

Keep official inputs, patched APKs and logs outside the repository. Run with JDK 21
and Morphe Desktop's all-in-one JAR (the local validation used 1.14.0 / patcher 1.15.1).

```powershell
& $java -Xmx3g -cp $morpheCli tests/twitch-compatibility/VerifyTwitchDex.java $patchedApk normal $officialBaseApk
& $java -Xmx3g -cp $morpheCli tests/twitch-compatibility/VerifyTwitchDex.java $loginPatchedApk login $officialBaseApk
```

Both APKs must be built from the official input and the same final Android MPP.
Use `--exclusive --bytecode-mode=FULL` and explicitly select the seven Twitch
patches; additionally select `Fix login` for the second build. Do not use
`--force` or `--continue-on-error` for release validation. Check the CLI result
JSON has zero failed patches and all steps succeeded; process exit code alone
does not establish patch success.

Run this pair for every declared target (currently 30.7.2 / 3007026 and
31.5.2 / 3105026). This verifier checks actual DEX wiring, field accessibility,
native claim submission, original chat-return coverage, intact chat arguments,
nullable author ID conversion, channel ID conversion, width argument ordering,
optional login selection, and the exact native eligibility body retained in the
points helper. Its branch comparison uses instruction indices, so a string
changing between ordinary and jumbo encoding does not invalidate offsets.
Unknown payload instructions are rejected rather than silently ignored.

Then verify signatures with Android SDK apksigner, install on the dedicated
API 36 test emulator (Google APIs image), run `cmd package compile -m verify -f tv.twitch.android.app`,
and check launch and process-scoped AndroidRuntime errors. Keep user data.
The bytecode checks and an unauthenticated launch do not test signed-in claims,
push delivery, live/VOD playback, or successful authentication; record these
separately when a test account is available.

## Execute the native chat path on ART

`LayoutProbe.java` is an Android instrumentation test fixture for the official
31.5.2 / 3105026 input. Its obfuscated names are deliberately fixture-specific;
production patches still resolve targets structurally. Compile it against the
Android SDK, run D8, and package it as `io.github.sabopura.playbackprobe`, with instrumentation
`io.github.sabopura.playbackprobe.LayoutProbe` targeting `tv.twitch.android.app`. Sign the
test APK with the same local signer used for the patched target. Keep APKs,
keystores and device output outside Git. Install only on a dedicated test emulator.

```powershell
adb -s emulator-5554 shell am instrument -w io.github.sabopura.playbackprobe/io.github.sabopura.playbackprobe.LayoutProbe
```

Require `RESULT failed=0`, all eight `PASS` lines, and no `FAIL`. Run for both
login selections. The fixture inflates all three modified native layouts, invokes
the native channel constructor, and creates a real native chat holder/model with
synthetic text. It invokes the patched binder and draws the row in all three
native styles, selected and unselected, with both a Tuid author and a null author.
It also invokes the deleted-message formatter twice and draws the rebuilt span.
It does not use real chat/account data or perform authenticated playback.

The original 31.5.2 patch failed all three native chat styles with a lazy
`VerifyError` in `RowAccess.userId`: the null branch merged Tuid and String into
Object at a shared return. Reflection-based class loading, package compilation,
and application startup had passed. These are therefore insufficient regression
checks. `VerifyTwitchDex.java` now also checks the null branch targets an explicit
zero and that the converted String has a separate return path.

## Player menu and settings screen

`MenuProbe.java` is version-independent and runs on every supported Twitch version. Package
it in the same instrumentation APK as `LayoutProbe` and run:

```powershell
adb -s emulator-5554 shell am instrument -w -e expect all io.github.sabopura.playbackprobe/io.github.sabopura.playbackprobe.MenuProbe
```

It requires that the feature flags of the applied patches are on, that `player_control_overlay`
contains exactly one `sabopura_player_menu` button and none of the older buttons, and that the
player menu, the settings screen, the danmaku category and the settings search open over a
real Twitch activity. Screenshots are written to the app's external files directory
(`menu-probe/`). Use `-e expect danmaku` or `-e expect chat` for builds with fewer patches.
