package io.github.sabopura.morphe.extension.twitch.ui;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import io.github.sabopura.morphe.extension.twitch.chat.ChatHistory;
import io.github.sabopura.morphe.extension.twitch.chat.ChatSettings;
import io.github.sabopura.morphe.extension.twitch.chat.FeedFilters;
import io.github.sabopura.morphe.extension.twitch.chat.UserSearch;
import io.github.sabopura.morphe.extension.twitch.chat.ViewCustomization;
import io.github.sabopura.morphe.extension.twitch.danmaku.DanmakuSettings;
import io.github.sabopura.morphe.extension.twitch.danmaku.TwitchDanmaku;
import io.github.sabopura.morphe.extension.twitch.ads.AdBlock;
import io.github.sabopura.morphe.extension.twitch.player.LiveProxy;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static io.github.sabopura.morphe.extension.twitch.ui.Text.label;

/** Every setting, grouped by category. The screen, search and reset all read this list. */
final class Catalog {
    static final String DANMAKU = "danmaku";
    static final String ABOUT = "about";
    private static final String PROJECT_URL = "https://github.com/sabopura/sabopura-morphe-patches";

    enum Store { CHAT, DANMAKU, ADS }

    static final class Category {
        final String id;
        final String title;
        final String summary;
        final List<Group> groups = new ArrayList<>();

        Category(String id, String title, String summary) {
            this.id = id;
            this.title = title;
            this.summary = summary;
        }

        Group group(String title) {
            Group group = new Group(this, title);
            groups.add(group);
            return group;
        }
    }

    static final class Group {
        final Category category;
        final String title;
        final List<Item> items = new ArrayList<>();
        String note;

        Group(Category category, String title) {
            this.category = category;
            this.title = title;
        }

        Group note(String value) {
            note = value;
            return this;
        }

        Group add(Item item) {
            item.group = this;
            items.add(item);
            return this;
        }
    }

    /** What an item needs while it is on screen. */
    interface Env {
        Context context();

        Ui ui();

        SharedPreferences prefs(Store store);

        /** Saves are already applied; this reloads caches and notifies the features. */
        void changed(Store store);

        /** Runs after every change so dependent rows can enable or disable themselves. */
        void onRefresh(Runnable refresher);

        /** Rebuilds the visible page, for example after a reset. */
        void rebuild();
    }

    abstract static class Item {
        final String title;
        String summary;
        Store store = Store.CHAT;
        String key;
        String dependsOn;
        Group group;

        Item(String title) {
            this.title = title;
        }

        Item summary(String value) {
            summary = value;
            return this;
        }

        Item store(Store value) {
            store = value;
            return this;
        }

        Item dependsOn(String switchKey) {
            dependsOn = switchKey;
            return this;
        }

        boolean matches(String query) {
            String haystack = (title + " " + (summary == null ? "" : summary) + " "
                    + group.title + " " + group.category.title).toLowerCase(Locale.ROOT);
            return haystack.contains(query);
        }

        abstract View build(Env env);

        /** Removes this item's stored value; returns false for items that store nothing. */
        boolean reset(SharedPreferences.Editor editor) {
            if (key == null) return false;
            editor.remove(key);
            return true;
        }
    }

    static final class SwitchItem extends Item {
        final boolean fallback;

        SwitchItem(String key, String title, boolean fallback) {
            super(title);
            this.key = key;
            this.fallback = fallback;
        }

        @Override
        View build(Env env) {
            SharedPreferences prefs = env.prefs(store);
            return env.ui().switchRow(env.context(), title, summary,
                    prefs.getBoolean(key, fallback), checked -> {
                        prefs.edit().putBoolean(key, checked).apply();
                        env.changed(store);
                    });
        }
    }

    static final class SliderItem extends Item {
        final int min;
        final int max;
        final int fallback;
        final Ui.Format format;

        SliderItem(String key, String title, int min, int max, int fallback, Ui.Format format) {
            super(title);
            this.key = key;
            this.min = min;
            this.max = max;
            this.fallback = fallback;
            this.format = format;
        }

        @Override
        View build(Env env) {
            SharedPreferences prefs = env.prefs(store);
            int value = Math.max(min, Math.min(max, prefs.getInt(key, fallback)));
            return env.ui().sliderRow(env.context(), title, summary, min, max, value, format,
                    committed -> {
                        prefs.edit().putInt(key, committed).apply();
                        env.changed(store);
                    });
        }
    }

    /** Choice stored as a string. */
    static final class ChoiceItem extends Item {
        final String[] labels;
        final String[] values;
        final String fallback;

        ChoiceItem(String key, String title, String[] labels, String[] values, String fallback) {
            super(title);
            this.key = key;
            this.labels = labels;
            this.values = values;
            this.fallback = fallback;
        }

        int index(SharedPreferences prefs) {
            String value = prefs.getString(key, fallback);
            for (int i = 0; i < values.length; i++) if (values[i].equals(value)) return i;
            return -1;
        }

        @Override
        View build(Env env) {
            SharedPreferences prefs = env.prefs(store);
            int index = index(prefs);
            return env.ui().linkRow(env.context(), title, summary,
                    index >= 0 ? labels[index] : null, () -> env.ui().choose(env.context(), title,
                            labels, index(prefs), picked -> {
                                prefs.edit().putString(key, values[picked]).apply();
                                env.changed(store);
                                env.rebuild();
                            }));
        }
    }

    /** Choice stored as an int (colours). */
    static final class IntChoiceItem extends Item {
        final String[] labels;
        final int[] values;
        final int fallback;

        IntChoiceItem(String key, String title, String[] labels, int[] values, int fallback) {
            super(title);
            this.key = key;
            this.labels = labels;
            this.values = values;
            this.fallback = fallback;
        }

        int index(SharedPreferences prefs) {
            int value = prefs.getInt(key, fallback);
            for (int i = 0; i < values.length; i++) if (values[i] == value) return i;
            return -1;
        }

        @Override
        View build(Env env) {
            SharedPreferences prefs = env.prefs(store);
            int index = index(prefs);
            return env.ui().linkRow(env.context(), title, summary,
                    index >= 0 ? labels[index] : null, () -> env.ui().choose(env.context(), title,
                            labels, index(prefs), picked -> {
                                prefs.edit().putInt(key, values[picked]).apply();
                                env.changed(store);
                                env.rebuild();
                            }));
        }
    }

    /** One rule per line. */
    static final class LinesItem extends Item {
        final String help;

        LinesItem(String key, String title, String help) {
            super(title);
            this.key = key;
            this.help = help;
        }

        @Override
        View build(Env env) {
            SharedPreferences prefs = env.prefs(store);
            String value = prefs.getString(key, "");
            int count = 0;
            for (String line : value.split("[\\n,]")) if (!line.trim().isEmpty()) count++;
            String shown = count == 0 ? label("なし", "None") : label(count + "件", count + "");
            return env.ui().linkRow(env.context(), title, summary, shown,
                    () -> env.ui().editLines(env.context(), title, help,
                            prefs.getString(key, ""), text -> {
                                prefs.edit().putString(key, text).apply();
                                env.changed(store);
                                env.rebuild();
                            }));
        }
    }

    interface Action {
        void run(Env env);
    }

    static final class ActionItem extends Item {
        final Action action;

        ActionItem(String title, Action action) {
            super(title);
            this.action = action;
        }

        @Override
        View build(Env env) {
            return env.ui().linkRow(env.context(), title, summary, null, () -> action.run(env));
        }
    }

    // ---- the catalog ----

    static List<Category> build() {
        List<Category> categories = new ArrayList<>();
        if (Features.ads()) categories.add(ads());
        if (Features.danmaku()) categories.add(danmaku());
        if (Features.chat()) {
            categories.add(chat());
            categories.add(emotes());
            categories.add(filters());
        }
        if (Features.player()) {
            categories.add(playback());
            categories.add(layout());
            categories.add(home());
        }
        if (Features.chat()) {
            categories.add(hide());
            categories.add(tools());
        }
        categories.add(about(categories));
        return categories;
    }

    private static Ui.Format suffix(String unit) {
        return value -> value + unit;
    }

    private static Category ads() {
        Category c = new Category("ads", label("広告ブロック", "Ad blocking"),
                label("動画広告・配信の広告・表示広告・配信プロキシ", "Video, stream and display ads, stream proxy"));
        c.group(label("広告ブロック", "Ad blocking"))
                .note(label("広告が配信に入ったときだけ、Twitch から広告のない再生リストを取り直して差し替えます。下の配信プロキシを設定しない限り、外部のサーバーは使いません。",
                        "When an ad is stitched into a stream, the same stream is fetched again from Twitch without it. No third-party server is used unless you set the stream proxy below."))
                .add(new SwitchItem(AdBlock.KEY_ENABLED, label("広告をブロック", "Block ads"), true).store(Store.ADS)
                        .summary(label("再生前・途中・VOD の広告、配信に入る広告、バナーなどの表示広告を止めます。新しく開いた画面から反映します",
                                "Pre-rolls, mid-rolls, VOD ads, stream ads and banners. Applies to newly opened screens")))
                .add(new ChoiceItem(AdBlock.KEY_FALLBACK, label("差し替えられなかった広告", "Ads that cannot be replaced"),
                        new String[]{label("黒い画面で隠して消音", "Cover and mute"), label("そのまま表示", "Show them")},
                        new String[]{AdBlock.FALLBACK_COVER, AdBlock.FALLBACK_SHOW}, AdBlock.FALLBACK_COVER)
                        .store(Store.ADS).dependsOn(AdBlock.KEY_ENABLED)
                        .summary(label("サブスク限定の配信などで、広告のない再生リストを取得できなかったとき",
                                "For example on subscriber-only streams, when no ad-free playlist is available")));
        c.group(label("配信プロキシ", "Stream proxy"))
                .note(Features.player()
                        ? label("ライブ配信のプレイリスト取得先を変えます。広告のない再生リストを Twitch から取得できなかったときの予備にも使います。プロキシには視聴チャンネルとIPアドレスが伝わります。再読み込み後に反映します。",
                                "Changes where live playlists are fetched, and is the backup when no ad-free playlist is available from Twitch. The proxy sees the channel and your IP address. Applies after reloading.")
                        : label("広告のない再生リストを Twitch から取得できなかったときの予備に使います。プロキシには視聴チャンネルとIPアドレスが伝わります。",
                                "The backup when no ad-free playlist is available from Twitch. The proxy sees the channel and your IP address."))
                .add(new ProxyItem());
        String issue = AdBlock.lastIssue();
        c.group(label("この起動中の記録", "Since the app started"))
                .note(label("止めた広告の要求 " + AdBlock.count(AdBlock.REQUESTS, true) + " 件・差し替え " + AdBlock.count(AdBlock.REPLACED, true)
                                + " 回・隠した広告 " + AdBlock.count(AdBlock.COVERED, true) + " 回"
                                + (issue.isEmpty() ? "" : "\n最後に差し替えられなかった理由: " + issue),
                        "Ad requests stopped: " + AdBlock.count(AdBlock.REQUESTS, true) + " · replaced: " + AdBlock.count(AdBlock.REPLACED, true)
                                + " · covered: " + AdBlock.count(AdBlock.COVERED, true)
                                + (issue.isEmpty() ? "" : "\n" + label("最後に差し替えられなかった理由: ", "Last reason an ad was not replaced: ") + issue)));
        return c;
    }

    private static Category danmaku() {
        Category c = new Category(DANMAKU, label("弾幕", "Danmaku"),
                label("表示・大きさ・速さ・NGワード", "Display, size, speed and muted words"));
        String sec = label(" 秒", " s");
        c.group(label("表示", "Display"))
                .add(new SwitchItem(DanmakuSettings.KEY_ENABLED, label("弾幕を表示", "Show danmaku"), true)
                        .summary(label("プレイヤーのボタンを長押ししても切り替えられます",
                                "You can also long-press the player button"))
                        .store(Store.DANMAKU))
                .add(new SwitchItem(DanmakuSettings.KEY_SHOW_EMOTES,
                        label("エモートを画像で表示", "Show emote images"), true).store(Store.DANMAKU))
                .add(new SwitchItem(DanmakuSettings.KEY_CHAT_COLORS,
                        label("チャットの名前の色を使う", "Use chat name colours"), false).store(Store.DANMAKU))
                .add(new SwitchItem(DanmakuSettings.KEY_SHOW_NAMES,
                        label("ユーザー名を表示", "Show user names"), false).store(Store.DANMAKU))
                .add(new SwitchItem(DanmakuSettings.KEY_BOLD, label("太字", "Bold text"), true)
                        .store(Store.DANMAKU));
        c.group(label("大きさと動き", "Size and motion"))
                .add(new SliderItem(DanmakuSettings.KEY_FONT_SIZE, label("文字サイズ", "Text size"),
                        DanmakuSettings.MIN_FONT_SIZE, DanmakuSettings.MAX_FONT_SIZE,
                        TwitchDanmaku.configFontSizeSp(), suffix(" sp"))
                        .summary(label("全画面のときの大きさ。小さいプレイヤーでは縮小します",
                                "Size at full screen; smaller players scale it down"))
                        .store(Store.DANMAKU))
                .add(new SliderItem(DanmakuSettings.KEY_OPACITY, label("不透明度", "Opacity"),
                        DanmakuSettings.MIN_OPACITY, 100, TwitchDanmaku.configOpacityPercent(),
                        suffix(" %")).store(Store.DANMAKU))
                .add(new SliderItem(DanmakuSettings.KEY_DURATION, label("画面を横切る時間", "Time to cross the screen"),
                        DanmakuSettings.MIN_DURATION, DanmakuSettings.MAX_DURATION,
                        TwitchDanmaku.configDurationSeconds(), suffix(sec))
                        .summary(label("短いほど速く流れます", "Shorter is faster"))
                        .store(Store.DANMAKU))
                .add(new SliderItem(DanmakuSettings.KEY_COVERAGE, label("表示する範囲（上から）", "Area used (from the top)"),
                        DanmakuSettings.MIN_COVERAGE, 100, TwitchDanmaku.configCoveragePercent(),
                        suffix(" %")).store(Store.DANMAKU))
                .add(new SliderItem(DanmakuSettings.KEY_MAX_ON_SCREEN, label("同時に表示する最大数", "Maximum on screen"),
                        DanmakuSettings.MIN_ON_SCREEN, DanmakuSettings.MAX_ON_SCREEN, 80,
                        String::valueOf).store(Store.DANMAKU));
        c.group(label("除外", "Exclude"))
                .add(new SliderItem(DanmakuSettings.KEY_MAX_LENGTH, label("長いコメントを除外", "Skip long messages"),
                        DanmakuSettings.MIN_MAX_LENGTH, DanmakuSettings.MAX_MAX_LENGTH,
                        TwitchDanmaku.configMaxLength(), suffix(label(" 文字", " chars")))
                        .summary(label("この文字数より長いコメントは流しません",
                                "Messages longer than this are not shown"))
                        .store(Store.DANMAKU))
                .add(new LinesItem(DanmakuSettings.KEY_NG_WORDS, label("弾幕のNGワード", "Danmaku muted words"),
                        label("1行またはカンマ区切りで1件。/正規表現/ も使えます。弾幕だけに適用します。",
                                "One per line or comma. /regex/ is allowed. Applies to danmaku only."))
                        .store(Store.DANMAKU));
        return c;
    }

    private static Category chat() {
        Category c = new Category("chat", label("チャット", "Chat"),
                label("文字サイズ・時刻・連投・背景", "Text size, time stamps, repeats, backgrounds"));
        c.group(label("全般", "General"))
                .add(new SwitchItem("enabled", label("チャット拡張を使う", "Use chat enhancements"), true)
                        .summary(label("オフにすると、エモート・フィルター・表示の変更をすべて止めます",
                                "Turning this off stops emotes, filters and display changes")));
        c.group(label("表示", "Display"))
                .add(new SliderItem("font_percent", label("文字サイズ", "Text size"), 70, 160, 100,
                        suffix(" %")).dependsOn("enabled"))
                .add(new SwitchItem("timestamps", label("送信時刻を表示", "Show time stamps"), false)
                        .dependsOn("enabled"))
                .add(new SwitchItem("timestamp_seconds", label("時刻に秒を含める", "Include seconds"), false)
                        .dependsOn("timestamps"))
                .add(new SwitchItem("alternating", label("1行ごとに背景を交互に変える", "Alternate row backgrounds"), false)
                        .dependsOn("enabled"))
                .add(new SwitchItem("show_login", label("ログイン名も表示", "Also show login names"), false)
                        .summary(label("表示名とログイン名が違う場合に併記します",
                                "Shown when it differs from the display name"))
                        .dependsOn("enabled"));
        c.group(label("連投", "Repeats"))
                .add(new SwitchItem("repeats", label("同じユーザーの連投を隠す", "Hide repeated messages"), false)
                        .summary(label("同じ人が8秒以内に送った同じ文を隠します",
                                "Same text from the same user within 8 seconds"))
                        .dependsOn("enabled"));
        return c;
    }

    private static Category emotes() {
        Category c = new Category("emotes", label("エモートとバッジ", "Emotes and badges"),
                label("7TV・BTTV・FFZ・バッジ・代名詞", "7TV, BTTV, FFZ, badges, pronouns"));
        c.group(label("エモート", "Emotes"))
                .add(new SwitchItem("7tv", "7TV", true).dependsOn("enabled"))
                .add(new SwitchItem("bttv", "BetterTTV", true).dependsOn("enabled"))
                .add(new SwitchItem("ffz", "FrankerFaceZ", true).dependsOn("enabled"))
                .add(new SwitchItem("emote_completion", label("「:」で入力を補完", "Complete after ':'"), true)
                        .summary(label("入力欄で :名前 と打つと候補を表示します",
                                "Type :name in the message box to see suggestions"))
                        .dependsOn("enabled"));
        Group badges = c.group(label("バッジ", "Badges"))
                .add(new SwitchItem("badges", label("追加バッジを表示", "Show third-party badges"), false)
                        .dependsOn("enabled"));
        String[][] providers = {{"7tv", "7TV"}, {"ffz", "FrankerFaceZ"}, {"chatterino", "Chatterino"},
                {"bttv", "BetterTTV"}, {"dankchat", "DankChat"}, {"chatsen", "Chatsen"}};
        for (String[] p : providers)
            badges.add(new SwitchItem("badge_" + p[0], p[1], true).dependsOn("badges"));
        c.group(label("代名詞", "Pronouns"))
                .add(new SwitchItem("pronouns", label("代名詞を表示", "Show pronouns"), false)
                        .summary(label("Alejo の公開プロフィールに登録された値だけを表示します",
                                "Only values registered on Alejo's public profiles"))
                        .dependsOn("enabled"));
        return c;
    }

    private static Category filters() {
        Category c = new Category("filters", label("フィルターとハイライト", "Filters and highlights"),
                label("NGワード・非表示ユーザー・強調", "Blocked words, hidden users, highlights"));
        String rule = label("1行に1件。英字の大小と全角・半角は区別しません。正規表現は使えません。",
                "One rule per line. Case and character width are ignored. No regex.");
        c.group(label("非表示", "Hide"))
                .add(new LinesItem("words", label("NGワード", "Blocked words"), rule).dependsOn("enabled"))
                .add(new LinesItem("users", label("非表示にするユーザー", "Hidden users"),
                        label("ログイン名または数値IDを1行に1件。", "One login name or numeric ID per line."))
                        .dependsOn("enabled"));
        c.group(label("ハイライト", "Highlights"))
                .add(new LinesItem("highlights", label("ハイライトする語句", "Highlighted phrases"), rule)
                        .dependsOn("enabled"))
                .add(new IntChoiceItem("highlight_color", label("ハイライトの色", "Highlight colour"),
                        new String[]{label("紫", "Violet"), label("黄", "Yellow"), label("緑", "Green"),
                                label("青", "Blue"), label("赤", "Red")},
                        new int[]{0x649146ff, 0x64efc94c, 0x645cc98b, 0x644c9bef, 0x64ee5c5c},
                        0x649146ff).dependsOn("enabled"))
                .add(new SwitchItem("vibrate_highlight", label("ハイライトで振動", "Vibrate on highlights"), false)
                        .dependsOn("enabled"))
                .add(new SliderItem("vibration_ms", label("振動の長さ", "Vibration length"), 10, 1000, 100,
                        suffix(label(" ミリ秒", " ms"))).dependsOn("vibrate_highlight"));
        return c;
    }

    private static Category playback() {
        Category c = new Category("playback", label("再生", "Playback"),
                Features.ads() ? label("シーク幅・ジェスチャー", "Seek steps, gestures")
                        : label("シーク幅・ジェスチャー・配信プロキシ", "Seek steps, gestures, stream proxy"));
        String sec = label(" 秒", " s");
        c.group(label("シーク", "Seek"))
                .add(new SliderItem("forward_seek", label("進む秒数", "Seek forward"), 5, 120, 30, suffix(sec)))
                .add(new SliderItem("rewind_seek", label("戻る秒数", "Seek back"), 5, 120, 10, suffix(sec)));
        c.group(label("ジェスチャー", "Gestures"))
                .add(new SwitchItem("brightness_gesture", label("左端の上下スワイプで明るさ", "Swipe left edge for brightness"), false))
                .add(new SwitchItem("volume_gesture", label("右端の上下スワイプで音量", "Swipe right edge for volume"), false));
        if (!Features.ads()) {
            // With Block ads applied, the proxy lives in the ad blocking settings.
            c.group(label("配信プロキシ", "Stream proxy"))
                    .note(label("ライブ配信のプレイリスト取得先を変えます。広告が必ず消えるとは限りません。プロキシには視聴チャンネルとIPアドレスが伝わります。再読み込み後に反映します。",
                            "Changes where live playlists are fetched. Ads are not always removed. The proxy sees the channel and your IP address. Applies after reloading."))
                    .add(new ProxyItem());
        }
        return c;
    }

    /** Proxy presets plus a validated custom HTTPS address. */
    static final class ProxyItem extends Item {
        private static final String[] URLS = {
                "",
                "https://lb-as.cdn-perfprod.com/live/",
                "https://lb-eu.cdn-perfprod.com/live/",
                "https://lb-na.cdn-perfprod.com/live/",
                "https://as.luminous.dev/live/",
                "https://eu.luminous.dev/live/",
        };

        ProxyItem() {
            super(label("プロキシ", "Proxy"));
            key = "live_proxy";
        }

        private static String[] labels() {
            return new String[]{label("使わない（Twitchへ直接接続）", "Off (connect directly)"),
                    "PerfProd Asia", "PerfProd Europe", "PerfProd North America",
                    "Luminous Asia", "Luminous Europe", label("カスタム URL…", "Custom URL…")};
        }

        @Override
        View build(Env env) {
            SharedPreferences prefs = env.prefs(Store.CHAT);
            String current = prefs.getString(key, "");
            String[] labels = labels();
            int index = labels.length - 1;
            for (int i = 0; i < URLS.length; i++) if (URLS[i].equals(current)) index = i;
            String shown = index == labels.length - 1 ? current : labels[index];
            final int selected = index;
            return env.ui().linkRow(env.context(), title, null, shown, () ->
                    env.ui().choose(env.context(), title, labels, selected, picked -> {
                        if (picked < URLS.length) {
                            prefs.edit().putString(key, URLS[picked]).apply();
                            env.changed(Store.CHAT);
                            env.rebuild();
                        } else {
                            custom(env, prefs);
                        }
                    }));
        }

        private void custom(Env env, SharedPreferences prefs) {
            Context context = env.context();
            Ui ui = env.ui();
            EditText input = new EditText(context);
            input.setSingleLine(true);
            input.setHint("https://example.com/live/");
            input.setText(prefs.getString(key, ""));
            input.setTextColor(ui.text);
            input.setHintTextColor(ui.secondary);
            android.app.Dialog dialog = ui.modal(context, label("カスタム URL", "Custom URL"),
                    label("末尾が / の HTTPS URL。Twitchの認証情報はURLに含めません。",
                            "An HTTPS URL ending in /. Twitch credentials are never added."),
                    input, label("保存", "Save"), (Ui.Validate) () -> {
                        String url = input.getText().toString().trim();
                        if (!LiveProxy.validBase(url)) {
                            input.setError(label("有効な HTTPS URL を入力してください",
                                    "Enter a valid HTTPS URL"));
                            return false;
                        }
                        prefs.edit().putString(key, url).apply();
                        env.changed(Store.CHAT);
                        env.rebuild();
                        return true;
                    }, label("キャンセル", "Cancel"));
            Ui.show(dialog, context);
        }
    }

    private static Category layout() {
        Category c = new Category("layout", label("レイアウト", "Layout"),
                label("横画面のチャット・ミニプレイヤー・ナビゲーション", "Landscape chat, mini player, navigation"));
        String pct = " %";
        c.group(label("横画面のチャット", "Landscape chat"))
                .note(label("幅と大きさは動画を開き直すと反映します。", "Reopen the video to apply width and size."))
                .add(new SwitchItem("landscape_width_enabled", label("チャットの幅を変える", "Change chat width"), false))
                .add(new SliderItem("landscape_width", label("通常表示の幅", "Width"), 10, 70, 30, suffix(pct))
                        .dependsOn("landscape_width_enabled"))
                .add(new SliderItem("landscape_split_width", label("分割表示の幅", "Split layout width"), 10, 70, 50,
                        suffix(pct)).dependsOn("landscape_width_enabled"))
                .add(new SwitchItem("landscape_opacity_enabled", label("背景の濃さを変える", "Change background opacity"), false))
                .add(new SliderItem("landscape_opacity", label("背景の濃さ", "Background opacity"), 0, 100, 30,
                        suffix(pct)).dependsOn("landscape_opacity_enabled"));
        c.group(label("ミニプレイヤー", "Mini player"))
                .add(new SwitchItem("mini_player_scale_enabled", label("大きさを変える", "Change size"), false))
                .add(new SliderItem("mini_player_scale", label("大きさ", "Size"), 50, 200, 100, suffix(pct))
                        .summary(label("画面内に収まる範囲に制限します", "Limited to fit on screen"))
                        .dependsOn("mini_player_scale_enabled"));
        c.group(label("ナビゲーション", "Navigation"))
                .add(new ChoiceItem("navigation_position", label("ナビゲーションバーの位置", "Navigation bar position"),
                        new String[]{label("標準", "Default"), label("上部", "Top"), label("非表示", "Hidden")},
                        new String[]{"default", "top", "hidden"}, "default"));
        return c;
    }

    private static Category home() {
        Category c = new Category("home", label("ホーム", "Home"),
                label("フォロー中の欄・カードの大きさ", "Following sections and card size"));
        Group sections = c.group(label("フォロー中に表示する欄", "Following sections"))
                .note(label("フォロー中を更新するか開き直すと反映します。", "Refresh or reopen Following to apply."));
        sections.add(new SwitchItem("full_channel_cards", label("配信を大きなカードで表示", "Large stream cards"), false));
        for (String[] option : FeedFilters.OPTIONS)
            sections.add(new SwitchItem(option[0], label(option[1] + "を隠す", "Hide " + option[2]), false));
        return c;
    }

    private static Category hide() {
        Category c = new Category("hide", label("表示の整理", "Declutter"),
                label("使わないボタンや欄を隠す", "Hide buttons and panels you do not use"));
        Group group = c.group(label("隠す項目", "Hide"))
                .note(label("オフに戻すと元の表示に戻ります。", "Turning an item off restores it."));
        for (String[] option : ViewCustomization.OPTIONS) {
            Item item = new SwitchItem(option[0], label(option[1] + "を隠す", "Hide " + option[2]), false);
            if (option[0].equals("hide_unfollow"))
                item.summary(label("フォロー中のチャンネルでは、配信画面のハート（押すとフォロー解除）を出しません。検索結果とストーリーの解除ボタンも隠します",
                        "On followed channels the heart under the player (one tap unfollows) is not shown, nor the unfollow buttons in search and stories"));
            group.add(item);
        }
        return c;
    }

    private static Category tools() {
        Category c = new Category("tools", label("履歴とツール", "History and tools"),
                label("過去チャット・閲覧履歴・ユーザー検索", "Recent chat, viewed history, user lookup"));
        c.group(label("履歴", "History"))
                .add(new ActionItem(label("過去チャットを取得", "Load recent chat"),
                        env -> ChatHistory.show(env.context(), true))
                        .summary(label("robotty.de が収集した公開ログから最大500件", "Up to 500 public messages from robotty.de")))
                .add(new ActionItem(label("表示したメッセージを検索", "Search viewed messages"),
                        env -> ChatHistory.show(env.context(), false)))
                .add(new SwitchItem("save_history", label("表示した履歴を端末に保存", "Save viewed history"), false)
                        .summary(label("最大1,000件。アプリの非公開領域に保存します", "Up to 1,000, stored privately in the app")));
        c.group(label("ツール", "Tools"))
                .add(new ActionItem(label("ユーザー検索", "Find user"), env -> UserSearch.show(env.context()))
                        .summary(label("ログイン名または数値IDで公開プロフィールを開きます",
                                "Open a public profile by login name or numeric ID")));
        return c;
    }

    private static Category about(List<Category> others) {
        Category c = new Category(ABOUT, label("このパッチについて", "About"),
                label("初期化・情報", "Reset and information"));
        c.group(label("設定", "Settings"))
                .add(new ActionItem(label("すべて初期値に戻す", "Reset everything"), env -> confirmReset(env, others))
                        .summary(label("この画面の設定だけを戻します。履歴やVODの同期は残ります",
                                "Only settings on this screen; history and VOD offsets stay")));
        c.group(label("情報", "Information"))
                .add(new ActionItem(label("パッチの公開ページ", "Project page"), env -> {
                    try {
                        env.context().startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(PROJECT_URL))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    } catch (RuntimeException e) {
                        Toast.makeText(env.context(), PROJECT_URL, Toast.LENGTH_LONG).show();
                    }
                }).summary(label("ソースと更新情報（GitHub）。設定はこの端末にだけ保存されます",
                        "Source and updates on GitHub. Settings stay on this device")));
        return c;
    }

    private static void confirmReset(Env env, List<Category> categories) {
        Context context = env.context();
        Ui.show(env.ui().modal(context, label("初期値に戻しますか？", "Reset everything?"),
                label("プレイヤーメニューと設定画面のすべての項目を初期値に戻します。",
                        "Every option in the player menu and this screen returns to its default."),
                null, label("戻す", "Reset"), () -> {
                    SharedPreferences.Editor chat = env.prefs(Store.CHAT).edit();
                    SharedPreferences.Editor danmaku = env.prefs(Store.DANMAKU).edit();
                    SharedPreferences.Editor ads = env.prefs(Store.ADS).edit();
                    for (Category category : categories)
                        for (Group group : category.groups)
                            for (Item item : group.items)
                                item.reset(item.store == Store.DANMAKU ? danmaku : item.store == Store.ADS ? ads : chat);
                    chat.apply();
                    danmaku.apply();
                    ads.apply();
                    env.changed(Store.CHAT);
                    env.changed(Store.DANMAKU);
                    env.rebuild();
                }, label("キャンセル", "Cancel")), context);
    }
}
