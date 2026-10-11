package io.github.sabopura.morphe.extension.twitch.ui;

import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import io.github.sabopura.morphe.extension.twitch.chat.ChatHistory;
import io.github.sabopura.morphe.extension.twitch.chat.ChatSettings;
import io.github.sabopura.morphe.extension.twitch.chat.PublicApi;
import io.github.sabopura.morphe.extension.twitch.chat.UserSearch;
import io.github.sabopura.morphe.extension.twitch.danmaku.DanmakuSettings;
import io.github.sabopura.morphe.extension.twitch.danmaku.TwitchDanmaku;
import io.github.sabopura.morphe.extension.twitch.player.PlayerTools;
import io.github.sabopura.morphe.extension.twitch.player.VodChapters;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static io.github.sabopura.morphe.extension.twitch.ui.Text.label;

/**
 * Quick controls over the video: danmaku on/off, speed, sleep timer and the most used tools.
 * A bottom sheet in portrait, a side panel in landscape and fullscreen.
 */
public final class PlayerMenu {
    private static final float[] RATES = {0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f};
    private static final int[] SLEEP_MINUTES = {0, 15, 30, 45, 60, 90, 120};
    private static int lastSleepMinutes;

    private final Context context;
    private final Ui ui = Ui.overVideo();
    private final Dialog dialog;
    private final LinearLayout panel;
    private TextView sleepStatus;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!dialog.isShowing()) return;
            updateSleepStatus();
            PublicApi.UI.postDelayed(this, 1000);
        }
    };

    private PlayerMenu(Context context) {
        this.context = context;
        boolean side = Ui.landscape(context);
        dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(context, 20);
        panel.setPadding(pad, Ui.dp(context, side ? 20 : 10), pad, Ui.dp(context, 20));

        ScrollView scroll = new ScrollView(context);
        scroll.setBackground(side
                ? Ui.corners(context, ui.surface, 24, 0, 0, 24)
                : Ui.corners(context, ui.surface, 24, 24, 0, 0));
        scroll.addView(panel);
        scroll.setClipToOutline(true);
        dialog.setContentView(scroll);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setDimAmount(0.25f);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            int screenWidth = context.getResources().getDisplayMetrics().widthPixels;
            if (side) {
                window.setGravity(Gravity.END | Gravity.FILL_VERTICAL);
                window.setLayout(Math.min(Ui.dp(context, 400), screenWidth), ViewGroup.LayoutParams.MATCH_PARENT);
            } else {
                window.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
                window.setLayout(Math.min(Ui.dp(context, 640), screenWidth), ViewGroup.LayoutParams.WRAP_CONTENT);
                window.setWindowAnimations(android.R.style.Animation_InputMethod);
            }
        }
        dialog.setOnDismissListener(d -> PublicApi.UI.removeCallbacks(tick));
        fill();
    }

    public static void show(Context context) {
        if (Features.player()) PlayerTools.initialize(context);
        PlayerMenu menu = new PlayerMenu(context);
        Ui.show(menu.dialog, context);
        menu.tick.run();
    }

    // ---- content ----

    private void fill() {
        panel.removeAllViews();
        sleepStatus = null;
        if (!Ui.landscape(context)) {
            View handle = new View(context);
            handle.setBackground(Ui.rounded(context, 0x55FFFFFF, 2));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(context, 36), Ui.dp(context, 4));
            lp.gravity = Gravity.CENTER_HORIZONTAL;
            lp.bottomMargin = Ui.dp(context, 12);
            panel.addView(handle, lp);
        }

        TextView title = ui.text(context, label("プレイヤーメニュー", "Player menu"), 20, ui.text, true);
        panel.addView(title);
        if (Features.danmaku()) {
            TextView hint = ui.text(context, label("ボタンの長押しで弾幕をすぐ切り替えられます",
                    "Long-press the button to switch danmaku instantly"), 13, ui.secondary, false);
            hint.setPadding(0, Ui.dp(context, 2), 0, 0);
            panel.addView(hint);
        }

        if (Features.danmaku()) addDanmaku();
        if (Features.player()) {
            addSpeed();
            addSleep();
            addPlaybackTiles();
        }
        if (Features.chat()) addChatTiles();

        LinearLayout.LayoutParams lp = Ui.matchWidth();
        lp.topMargin = Ui.dp(context, 20);
        panel.addView(ui.button(context, label("すべての設定", "All settings"), true, () -> {
            dialog.dismiss();
            SettingsScreen.show(context);
        }), lp);
    }

    private void section(String text) {
        TextView label = ui.sectionLabel(context, text);
        label.setPadding(0, Ui.dp(context, 20), 0, Ui.dp(context, 8));
        panel.addView(label);
    }

    private void addDanmaku() {
        section(label("弾幕", "Danmaku"));
        DanmakuSettings settings = DanmakuSettings.get(context);
        String summary = settings.enabled
                ? label(settings.fontSizeSp + " sp・不透明度 " + settings.opacityPercent + "%・"
                        + settings.durationSeconds + " 秒で横断",
                settings.fontSizeSp + " sp · " + settings.opacityPercent + "% · "
                        + settings.durationSeconds + " s across")
                : label("オフ", "Off");
        LinearLayout card = ui.card(context);
        card.setBackground(Ui.rounded(context, 0x14FFFFFF, 16));
        card.addView(ui.switchRow(context, label("弾幕を表示", "Show danmaku"), summary,
                settings.enabled, checked -> {
                    DanmakuSettings.setEnabled(context, checked);
                    TwitchDanmaku.settingsChanged();
                    fill();
                }), Ui.matchWidth());
        card.addView(ui.linkRow(context, label("弾幕の設定", "Danmaku settings"),
                label("大きさ・速さ・範囲・NGワード", "Size, speed, area, muted words"), null, () -> {
                    dialog.dismiss();
                    SettingsScreen.show(context, Catalog.DANMAKU);
                }), Ui.matchWidth());
        panel.addView(card, Ui.matchWidth());
    }

    private void addSpeed() {
        float rate = PlayerTools.playbackRate();
        boolean available = !Float.isNaN(rate);
        section(label("再生速度", "Playback speed")
                + (available ? "  ·  " + format(rate) : ""));
        List<View> chips = new ArrayList<>();
        for (float value : RATES) {
            boolean selected = available && Math.abs(value - rate) < 0.01f;
            TextView chip = ui.chip(context, format(value), selected, () -> {
                PlayerTools.run(context, () -> PlayerTools.playbackRate(value));
                fill();
            });
            chip.setEnabled(available);
            chip.setAlpha(available ? 1f : 0.4f);
            chips.add(chip);
        }
        panel.addView(ui.chipStrip(context, chips.toArray(new View[0])), Ui.matchWidth());
        if (!available) note(label("再生中の動画がないため変更できません", "No active video"));
    }

    private void addSleep() {
        section(label("スリープタイマー", "Sleep timer"));
        boolean running = PlayerTools.sleepRemaining() > 0;
        List<View> chips = new ArrayList<>();
        for (int minutes : SLEEP_MINUTES) {
            boolean selected = running ? minutes == lastSleepMinutes : minutes == 0;
            String text = minutes == 0 ? label("オフ", "Off") : label(minutes + "分", minutes + " min");
            chips.add(ui.chip(context, text, selected, () -> {
                PlayerTools.setSleepMinutes(minutes);
                lastSleepMinutes = minutes;
                fill();
            }));
        }
        panel.addView(ui.chipStrip(context, chips.toArray(new View[0])), Ui.matchWidth());
        sleepStatus = note("");
        updateSleepStatus();
    }

    private void updateSleepStatus() {
        if (sleepStatus == null) return;
        long remaining = PlayerTools.sleepRemaining();
        if (remaining <= 0) {
            sleepStatus.setText(label("指定した時間が経つと再生を一時停止します",
                    "Pauses playback after the chosen time"));
            return;
        }
        long seconds = (remaining + 999) / 1000;
        String time = String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
        sleepStatus.setText(label("残り " + time + " で一時停止", "Pauses in " + time));
    }

    private void addPlaybackTiles() {
        section(label("操作", "Controls"));
        SharedPreferences prefs = ChatSettings.preferences(context);
        int back = prefs.getInt("rewind_seek", 10), forward = prefs.getInt("forward_seek", 30);
        List<View> tiles = new ArrayList<>();
        tiles.add(ui.tile(context, label(back + "秒戻る", "Back " + back + " s"),
                () -> PlayerTools.relativeSeek(context, false)));
        tiles.add(ui.tile(context, label(forward + "秒進む", "Forward " + forward + " s"),
                () -> PlayerTools.relativeSeek(context, true)));
        tiles.add(ui.tile(context, label("再読み込み", "Reload"), () -> {
            dialog.dismiss();
            PlayerTools.reload(context);
        }));
        tiles.add(ui.tile(context, label("再生統計", "Statistics"), () -> PlayerTools.stats(context)));
        if (!PlayerTools.vodId().isEmpty()) {
            tiles.add(ui.tile(context, label("チャプター", "Chapters"), () -> VodChapters.show(context)));
            tiles.add(ui.tile(context, label("チャット同期", "Chat offset"), () -> PlayerTools.sync(context)));
        }
        panel.addView(ui.grid(context, columns(), tiles.toArray(new View[0])), Ui.matchWidth());
    }

    private void addChatTiles() {
        section(label("チャット", "Chat"));
        panel.addView(ui.grid(context, columns(),
                ui.tile(context, label("過去チャット", "Recent chat"), () -> ChatHistory.show(context, true)),
                ui.tile(context, label("履歴を検索", "Search history"), () -> ChatHistory.show(context, false)),
                ui.tile(context, label("ユーザー検索", "Find user"), () -> UserSearch.show(context))),
                Ui.matchWidth());
    }

    private int columns() {
        return Ui.landscape(context) ? 2 : (context.getResources().getConfiguration().screenWidthDp >= 480 ? 4 : 2);
    }

    private TextView note(String text) {
        TextView note = ui.text(context, text, 12, ui.secondary, false);
        note.setPadding(Ui.dp(context, 2), Ui.dp(context, 8), 0, 0);
        panel.addView(note);
        return note;
    }

    private static String format(float rate) {
        String text = rate == (int) rate ? String.valueOf((int) rate) : String.valueOf(rate);
        return text + "×";
    }
}
