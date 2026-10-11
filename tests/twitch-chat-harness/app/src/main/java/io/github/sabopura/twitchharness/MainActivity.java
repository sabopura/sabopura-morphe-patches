package io.github.sabopura.twitchharness;

import android.app.Activity;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ClickableSpan;
import android.text.style.ReplacementSpan;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import io.github.sabopura.morphe.extension.twitch.chat.*;
import io.github.sabopura.morphe.extension.twitch.chat.emotes.EmoteHarnessChecks;
import io.github.sabopura.morphe.extension.twitch.player.*;
import io.github.sabopura.morphe.extension.twitch.ui.*;

import org.json.*;

public class MainActivity extends Activity {
    private int checks;
    private LinearLayout screen;

    private void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
        checks++;
        Log.i("TwitchHarness", "PASS " + name);
    }

    private TextView row(String value) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(18);
        text.setPadding(12, 12, 12, 12);
        screen.addView(text, new LinearLayout.LayoutParams(-1, -2));
        return text;
    }

    private void bind(TextView view, String id, String user, long time) {
        ChatEnhancements.bindRow(view, view, id, "1234", user, user, time);
    }

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setPadding(20, 64, 20, 20);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(screen);
        setContentView(scroll);
        screen.post(
                () -> {
                    try {
                        runChecks();
                    } catch (Throwable t) {
                        fail(t);
                    }
                });
    }

    private void runChecks() throws Exception {
        ChatSettings.preferences(this)
                .edit()
                .clear()
                .putBoolean("7tv", false)
                .putBoolean("bttv", false)
                .putBoolean("ffz", false)
                .apply();
        ChatSettings.reload(this);
        TextView view = row("Alice: hello world");
        float originalSize = view.getTextSize();
        SpannableString original = new SpannableString(view.getText());
        ClickableSpan click =
                new ClickableSpan() {
                    public void onClick(View v) {}
                };
        original.setSpan(click, 0, 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        view.setText(original);
        bind(view, "a", "Alice", 1000);
        ChatSettings.preferences(this).edit().putString("words", "HELLO").apply();
        ChatSettings.reload(this);
        check(
                view.getVisibility() == View.GONE && view.getLayoutParams().height == 0,
                "blocked row collapsed");
        view.setText("Bob: welcome");
        view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, originalSize);
        bind(view, "b", "Bob", 2000);
        check(
                view.getVisibility() == View.VISIBLE && view.getLayoutParams().height == -2,
                "recycled row restored");
        ChatSettings.preferences(this)
                .edit()
                .putString("words", "")
                .putString("users", "bob")
                .apply();
        ChatSettings.reload(this);
        check(view.getVisibility() == View.GONE, "exact username filter");
        ChatSettings.preferences(this)
                .edit()
                .putString("users", "")
                .putBoolean("timestamps", true)
                .putInt("font_percent", 140)
                .putString("highlights", "hello")
                .apply();
        ChatSettings.reload(this);
        view.setText(original);
        view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, originalSize);
        bind(view, "a", "Alice", 1000);
        String once = view.getText().toString();
        ChatEnhancements.refresh();
        ChatEnhancements.refresh();
        check(once.equals(view.getText().toString()), "timestamps do not accumulate on refresh");
        check(
                Math.abs(view.getTextSize() - originalSize * 1.4f) < .1f,
                "font scale does not accumulate");
        check(
                ((Spanned) view.getText()).getSpans(0, view.length(), ClickableSpan.class).length
                        == 1,
                "Twitch clickable spans preserved");
        check(
                ((Spanned) view.getText())
                                .getSpans(
                                        0,
                                        view.length(),
                                        android.text.style.BackgroundColorSpan.class)
                                .length
                        == 1,
                "highlight applied");
        ChatSettings.preferences(this).edit().putBoolean("enabled", false).apply();
        ChatSettings.reload(this);
        check(
                view.getText().toString().equals(original.toString())
                        && Math.abs(view.getTextSize() - originalSize) < .1f,
                "disable restores stock text and size");
        check(view.getVisibility() == View.VISIBLE, "disable restores visibility");
        ChatSettings.preferences(this)
                .edit()
                .clear()
                .putBoolean("7tv", false)
                .putBoolean("bttv", false)
                .putBoolean("ffz", false)
                .putBoolean("repeats", true)
                .apply();
        ChatSettings.reload(this);
        TextView first = row("Test: repeat");
        bind(first, "r1", "Test", 5000);
        TextView second = row("Test: repeat");
        bind(second, "r2", "Test", 6000);
        check(
                first.getVisibility() == View.VISIBLE && second.getVisibility() == View.GONE,
                "same-user repetition hidden");
        ChatEnhancements.refresh();
        check(
                first.getVisibility() == View.VISIBLE,
                "refresh keeps original repeated message visible");
        EmoteHarnessChecks.run(this);
        check(true, "FFZ parsing and seeded provider catalogs");
        ChatSettings.preferences(this).edit().clear().apply();
        ChatSettings.reload(this);
        TextView emotes = row("SevenTest BttvTest FfzTest");
        bind(emotes, "emotes", "Test", 8000);
        TextView layered = row("SevenTest OverlayTest");
        bind(layered, "layered", "Test", 9000);
        TextView separate = row("SevenTest\nOverlayTest");
        bind(separate, "separate", "Test", 9000);
        TextView nativeOverlay = row("Native OverlayTest");
        SpannableString nativeText = new SpannableString(nativeOverlay.getText());
        android.graphics.drawable.ColorDrawable nativeImage =
                new android.graphics.drawable.ColorDrawable(Color.RED);
        nativeImage.setBounds(0, 0, 24, 24);
        nativeText.setSpan(
                new android.text.style.ImageSpan(nativeImage),
                0,
                6,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        nativeOverlay.setText(nativeText);
        bind(nativeOverlay, "native", "Test", 9000);
        screen.postDelayed(
                () -> {
                    try {
                        check(
                                ((Spanned) emotes.getText())
                                                .getSpans(0, emotes.length(), ReplacementSpan.class)
                                                .length
                                        == 3,
                                "7TV BTTV FFZ images render after asynchronous catalog load");
                        ChatSettings.preferences(this).edit().putBoolean("7tv", false).apply();
                        ChatSettings.reload(this);
                        check(
                                ((Spanned) emotes.getText())
                                                .getSpans(0, emotes.length(), ReplacementSpan.class)
                                                .length
                                        == 2,
                                "provider switch removes only its own images");
                        ChatSettings.preferences(this).edit().putBoolean("7tv", true).apply();
                        ChatSettings.reload(this);
                        Spanned layers = (Spanned) layered.getText();
                        ReplacementSpan[] spans =
                                layers.getSpans(0, layers.length(), ReplacementSpan.class);
                        check(
                                spans.length == 1
                                        && layers.getSpanStart(spans[0]) == 0
                                        && layers.getSpanEnd(spans[0]) == layers.length(),
                                "zero-width overlay shares base span and wraps atomically");
                        check(
                                ((Spanned) separate.getText())
                                                .getSpans(
                                                        0, separate.length(), ReplacementSpan.class)
                                                .length
                                        == 2,
                                "zero-width overlay does not cross a newline");
                        Spanned nativeSpans = (Spanned) nativeOverlay.getText();
                        ReplacementSpan[] nativeLayers =
                                nativeSpans.getSpans(
                                        0, nativeSpans.length(), ReplacementSpan.class);
                        check(
                                nativeLayers.length == 1
                                        && nativeSpans.getSpanEnd(nativeLayers[0])
                                                == nativeSpans.length(),
                                "zero-width overlay works on a native Twitch image span");
                        expandedChecks();
                    } catch (Throwable t) {
                        fail(t);
                    }
                },
                2500);
    }

    private void expandedChecks() throws Exception {
        EmoteHarnessChecks.checkSvg();
        check(true, "SVG badges decode and external entities are rejected");
        EmoteHarnessChecks.checkInsertion(this);
        check(true, "emote insertion respects selections and completion token boundaries");
        ChatMetadata.Badge badge =
                ChatMetadata.parseSevenTvBadge(
                        new JSONObject(
                                "{\"data\":{\"users\":{\"userByConnection\":{\"style\":{\"activeBadge\":{\"name\":\"Tester\",\"images\":[{\"url\":\"https://cdn.7tv.app/badge/test/4x.avif\",\"width\":72,\"mime\":\"image/avif\"},{\"url\":\"https://cdn.7tv.app/badge/test/2x.webp\",\"width\":36,\"mime\":\"image/webp\"}]}}}}}}"));
        check(
                badge.url.endsWith("2x.webp")
                        && ChatMetadata.parseSevenTvBadge(
                                        new JSONObject(
                                                "{\"data\":{\"users\":{\"userByConnection\":null}}}"))
                                == null,
                "7TV active badge selects compatible 36px image and handles absent profiles");
        java.util.Map<String, java.util.List<ChatMetadata.Badge>> ffz =
                ChatMetadata.parseBadges(
                        "ffz",
                        new JSONObject(
                                "{\"badges\":[{\"id\":3,\"title\":\"Supporter\",\"image\":\"//cdn.frankerfacez.com/badge/3/1\"}],\"users\":{\"3\":[12,34]}}"));
        check(
                ffz.get("12").get(0).url.equals("https://cdn.frankerfacez.com/badge/3/1")
                        && ffz.size() == 2,
                "FFZ badge IDs and HTTPS images");
        check(
                ChatMetadata.parseBadges(
                                "chatterino",
                                new JSONObject(
                                        "{\"badges\":[{\"tooltip\":\"Supporter\",\"image2\":\"https://fourtf.com/chatterino/badges/test.png\",\"users\":[\"12\"]}]}"))
                        .containsKey("12"),
                "Chatterino badge assignment");
        check(
                ChatMetadata.parsePronouns(
                                new JSONArray(
                                        "[{\"name\":\"theythem\",\"display\":\"they/them\"}]"))
                        .get("theythem")
                        .equals("they/them"),
                "pronouns remain provider-declared");
        check(
                VodChapters.parse(
                                        new JSONObject(
                                                "{\"data\":{\"video\":{\"moments\":{\"edges\":[{\"node\":{\"positionMilliseconds\":9000,\"description\":\"Later\"}},{\"node\":{\"positionMilliseconds\":0,\"description\":\"Start\"}}]}}}}"))
                                .get(0)
                                .millis
                        == 0,
                "VOD chapters sorted by playback position");
        PlayerTools.initialize(this);
        PlayerFixture player = new PlayerFixture();
        PlayerTools.onPlay(player);
        PlayerTools.onVod(new PlayerFixture.Vod(), new PlayerFixture.Channel());
        check(
                ChatEnhancements.channelId().equals("1234")
                        && ChatEnhancements.channelName().equals("test"),
                "VOD sets the channel for emote picker and history");
        PlayerTools.playbackRate(1.5f);
        check(player.rate == 1.5f, "playback speed uses public player interface");
        PlayerTools.seek(999999);
        check(player.position == 100000, "seek clamped to duration");
        PlayerTools.seek(-5);
        check(player.position == 0, "seek clamped at start");
        ChatSettings.preferences(this).edit().putInt("vod_sync_123456", -15).apply();
        check(
                PlayerTools.commentTime(10) == 0 && PlayerTools.commentTime(30) == 15,
                "per-VOD comment offset and negative clamp");
        String original = player.path;
        ChatSettings.preferences(this)
                .edit()
                .putString("live_proxy", "https://lb-as.cdn-perfprod.com/live/")
                .apply();
        Uri routed = LiveProxy.rewrite(Uri.parse(original));
        check(PlayerTools.vodId().isEmpty(), "live playback clears stale VOD controls");
        check(
                routed.getHost().equals("lb-as.cdn-perfprod.com")
                        && routed.getLastPathSegment().equals("test")
                        && !routed.toString().contains("synthetic")
                        && !routed.getQueryParameterNames().contains("token"),
                "proxy contains only public channel and playback options");
        check(
                LiveProxy.rewrite(Uri.parse("https://usher.ttvnw.net/vod/1.m3u8?token=synthetic"))
                        .getHost()
                        .equals("usher.ttvnw.net"),
                "proxy leaves VOD authorization path unchanged");
        ChatSettings.preferences(this).edit().putString("live_proxy", "").apply();
        check(
                LiveProxy.forReload(routed.toString()).toString().equals(original),
                "disabling proxy restores original request locally");
        TextView cast = row("Synthetic cast button");
        cast.setId(io.github.sabopura.twitchharness.R.id.cast_button);
        cast.setVisibility(View.INVISIBLE);
        ViewCustomization.observe(screen);
        ChatSettings.preferences(this).edit().putBoolean("hide_cast", true).apply();
        ChatSettings.reload(this);
        check(
                cast.getVisibility() == View.GONE,
                "custom visibility hides a matching native resource ID");
        ChatSettings.preferences(this).edit().putBoolean("hide_cast", false).apply();
        ChatSettings.reload(this);
        check(
                cast.getVisibility() == View.INVISIBLE,
                "custom visibility restores original invisible state");
        layoutChecks();
        PlayerTools.setSleepDuration(20);
        PlayerTools.setSleepDuration(0);
        screen.postDelayed(
                () -> {
                    try {
                        check(
                                !player.paused && PlayerTools.sleepRemaining() == 0,
                                "cancelled sleep timer does not pause playback");
                        PlayerTools.setSleepDuration(50);
                        screen.postDelayed(
                                () -> {
                                    try {
                                        check(
                                                player.paused && PlayerTools.sleepRemaining() == 0,
                                                "sleep timer expiry pauses active player");
                                        PlayerTools.onRelease(player);
                                        boolean missing = false;
                                        try {
                                            PlayerTools.seek(1);
                                        } catch (Exception e) {
                                            missing = true;
                                        }
                                        check(missing, "released player is not retained or called");
                                        row("PASS — " + checks + " Android checks")
                                                .setTextColor(Color.GREEN);
                                        addUiPreview();
                                        Log.i("TwitchHarness", "ALL_PASS checks=" + checks);
                                    } catch (Throwable t) {
                                        fail(t);
                                    }
                                },
                                150);
                    } catch (Throwable t) {
                        fail(t);
                    }
                },
                100);
    }

    /** The shared player button over a fake player bar, plus direct entry points. */
    private void addUiPreview() {
        Features.enableAllForPreview();
        // A playing VOD so the menu shows speed, VOD and chat tools.
        PlayerTools.onPlay(new PlayerFixture());
        PlayerTools.onVod(new PlayerFixture.Vod(), new PlayerFixture.Channel());
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(android.view.Gravity.END | android.view.Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xFF000000);
        int size = Math.round(48 * getResources().getDisplayMetrics().density);
        PlayerMenuButton button = new PlayerMenuButton(this);
        button.setPadding(size / 4, size / 4, size / 4, size / 4);
        bar.addView(button, new LinearLayout.LayoutParams(size, size));
        screen.addView(bar, new LinearLayout.LayoutParams(-1, size + 24));
        for (String[] entry : new String[][] {
                {"Player menu", "menu"}, {"All settings", "settings"}, {"Danmaku settings", "danmaku"}}) {
            Button open = new Button(this);
            open.setText(entry[0]);
            open.setOnClickListener(v -> openUi(entry[1]));
            screen.addView(open);
        }
        String requested = getIntent().getStringExtra("ui");
        if (requested != null) {
            // Screenshot mode: a plain backdrop instead of the check results.
            screen.setVisibility(View.INVISIBLE);
            getWindow().getDecorView().setBackground(new android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                    new int[] {0xFF2B1D4A, 0xFF0E0E10, 0xFF1B2A44}));
            screen.postDelayed(() -> openUi(requested), 300);
        }
    }

    private void openUi(String which) {
        if ("menu".equals(which)) PlayerMenu.show(this);
        else if ("danmaku".equals(which)) SettingsScreen.show(this, "danmaku");
        else if (which.startsWith("search:")) SettingsScreen.showSearch(this, which.substring(7));
        else if (which.startsWith("category:")) SettingsScreen.show(this, which.substring(9));
        else SettingsScreen.show(this);
    }

    private void fail(Throwable t) {
        Log.e("TwitchHarness", "FAIL", t);
        row("FAIL: " + t).setTextColor(Color.RED);
    }

    private void layoutChecks() {
        android.content.SharedPreferences p = ChatSettings.preferences(this);
        android.widget.FrameLayout box = new android.widget.FrameLayout(this);
        View chat = new View(this);
        chat.setId(R.id.landscape_chat_container);
        box.addView(chat);
        p.edit()
                .putBoolean("landscape_width_enabled", true)
                .putInt("landscape_width", 40)
                .putInt("landscape_split_width", 65)
                .apply();
        check(
                LayoutTools.chatWidth(chat, 30) == 40
                        && LayoutTools.chatWidth(chat, 50) == 65
                        && LayoutTools.chatWidth(box, 30) == 30,
                "landscape width separates regular and split layout without affecting other views");
        p.edit().putBoolean("landscape_width_enabled", false).apply();
        check(
                LayoutTools.chatWidth(chat, 20) == 20,
                "disabling landscape width restores native tablet value");
        box.layout(0, 0, 1000, 800);
        p.edit()
                .putBoolean("mini_player_scale_enabled", true)
                .putInt("mini_player_scale", 150)
                .apply();
        android.util.Size original = new android.util.Size(200, 100),
                scaled = LayoutTools.miniSize(box, original);
        check(
                scaled.equals(new android.util.Size(300, 150))
                        && LayoutTools.miniSize(box, scaled).equals(scaled),
                "mini-player scale does not accumulate on re-entry");
        PlayerFixture.NativeSize nativeSize =
                (PlayerFixture.NativeSize)
                        LayoutTools.miniNativeSize(box, new PlayerFixture.NativeSize(200, 100));
        check(
                nativeSize.getWidth() == 300 && nativeSize.getHeight() == 150,
                "native Twitch Size bridge constructs the correct runtime type");
        p.edit().putBoolean("mini_player_scale_enabled", false).apply();
        check(
                LayoutTools.miniSize(box, scaled).equals(original),
                "mini-player scale restores baseline when disabled");
        android.content.res.Configuration config =
                new android.content.res.Configuration(getResources().getConfiguration());
        config.orientation = android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        android.content.Context landscape = createConfigurationContext(config);
        android.widget.FrameLayout landscapeRoot = new android.widget.FrameLayout(landscape);
        landscapeRoot.setId(R.id.landscape_chat_container);
        landscapeRoot.setBackgroundColor(Color.RED);
        View chatRoot = new View(landscape);
        chatRoot.setId(R.id.chat_view_delegate_root);
        chatRoot.setBackgroundColor(Color.BLUE);
        landscapeRoot.addView(chatRoot);
        p.edit()
                .putBoolean("landscape_opacity_enabled", true)
                .putInt("landscape_opacity", 40)
                .apply();
        LayoutTools.applyBackgrounds(landscapeRoot);
        check(
                ((android.graphics.drawable.ColorDrawable) chatRoot.getBackground()).getColor()
                        == Color.argb(102, 0, 0, 0),
                "landscape opacity changes background without fading message text");
        p.edit().putBoolean("landscape_opacity_enabled", false).apply();
        LayoutTools.applyBackgrounds(landscapeRoot);
        check(
                ((android.graphics.drawable.ColorDrawable) landscapeRoot.getBackground()).getColor()
                                == Color.RED
                        && ((android.graphics.drawable.ColorDrawable) chatRoot.getBackground())
                                        .getColor()
                                == Color.BLUE,
                "opacity disable restores both original backgrounds");
        p.edit()
                .putBoolean("hide_followed_games", true)
                .putBoolean("hide_offline_channels", true)
                .apply();
        java.util.List<String> rows =
                java.util.Arrays.asList(
                        "prefix",
                        "FollowedCategories",
                        "game",
                        "FollowedLiveChannels",
                        "live",
                        "FollowedOfflineChannels",
                        "offline",
                        "ContinueWatching",
                        "vod");
        java.util.List<String> filtered =
                FeedFilters.filterSections(
                        rows, p, row -> Character.isUpperCase(row.charAt(0)) ? row : null);
        check(
                filtered.equals(
                                java.util.Arrays.asList(
                                        "prefix",
                                        "FollowedLiveChannels",
                                        "live",
                                        "ContinueWatching",
                                        "vod"))
                        && rows.size() == 9,
                "following filter removes entire selected sections without mutating incoming rows");
        p.edit()
                .putBoolean("hide_followed_games", false)
                .putBoolean("hide_offline_channels", false)
                .apply();
        check(
                FeedFilters.filterSections(rows, p, row -> null) == rows,
                "disabled following filter returns original list");
        View nav = new View(this), body = new View(this), rating = new View(this);
        nav.setId(R.id.viewer_landing_bottom_navigation_group);
        body.setId(R.id.fragment_container);
        rating.setId(R.id.rating_banner_container);
        box.addView(body);
        box.addView(rating);
        box.addView(nav);
        nav.layout(0, 700, 1000, 800);
        body.layout(0, 0, 1000, 650);
        rating.layout(0, 650, 1000, 700);
        p.edit().putString("navigation_position", "top").apply();
        LayoutTools.applyNavigation(box);
        check(
                nav.getTranslationY() == -700
                        && body.getTranslationY() == 100
                        && rating.getTranslationY() == 100,
                "top navigation moves controls and content consistently");
        p.edit().putString("navigation_position", "hidden").apply();
        LayoutTools.applyNavigation(box);
        check(
                nav.getVisibility() == View.GONE && body.getTranslationY() == 0,
                "hidden navigation clears top translation");
        p.edit().putString("navigation_position", "default").apply();
        LayoutTools.applyNavigation(box);
        check(
                nav.getVisibility() == View.VISIBLE
                        && nav.getTranslationY() == 0
                        && body.getTranslationY() == 0,
                "default navigation restores original state");
    }
}
