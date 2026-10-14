package io.github.sabopura.morphe.extension.twitch.ui;

import io.github.sabopura.morphe.extension.twitch.ads.AdBlock;

import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.ColorDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import io.github.sabopura.morphe.extension.twitch.chat.ChatSettings;
import io.github.sabopura.morphe.extension.twitch.danmaku.DanmakuSettings;
import io.github.sabopura.morphe.extension.twitch.danmaku.TwitchDanmaku;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static io.github.sabopura.morphe.extension.twitch.ui.Text.label;

/**
 * One settings screen for every Twitch patch. Wide screens show the categories beside the
 * options; narrow screens show the category list first. Changes are saved immediately.
 */
public final class SettingsScreen extends Dialog implements Catalog.Env {
    private final Ui ui;
    private final List<Catalog.Category> categories;
    private final List<Runnable> refreshers = new ArrayList<>();
    private final boolean wide;

    private Catalog.Category current;
    private String query = "";

    private LinearLayout rail;
    private ScrollView detailScroll;
    private LinearLayout detail;
    private TextView title;
    private EditText search;
    private TextView back;
    private TextView searchButton;

    public static void show(Context context) {
        show(context, null);
    }

    /** Opens the screen, optionally on one category such as {@link Catalog#DANMAKU}. */
    public static void show(Context context, String categoryId) {
        SettingsScreen screen = new SettingsScreen(context, categoryId);
        Ui.show(screen, context);
    }

    /** Opens the screen with the search field filled in. */
    public static void showSearch(Context context, String text) {
        SettingsScreen screen = new SettingsScreen(context, null);
        Ui.show(screen, context);
        screen.toggleSearch();
        screen.search.setText(text);
    }

    private SettingsScreen(Context context, String categoryId) {
        super(context, android.R.style.Theme_DeviceDefault_NoActionBar);
        Text.use(context);
        ui = Ui.of(context);
        categories = Catalog.build();
        wide = Ui.wide(context);
        if (categoryId != null) {
            for (Catalog.Category c : categories) if (c.id.equals(categoryId)) current = c;
        }
        if (current == null && wide) current = categories.get(0);
        build();
    }

    // ---- Env ----

    @Override
    public Context context() {
        return getContext();
    }

    @Override
    public Ui ui() {
        return ui;
    }

    @Override
    public SharedPreferences prefs(Catalog.Store store) {
        if (store == Catalog.Store.ADS) return AdBlock.preferences(getContext());
        return store == Catalog.Store.DANMAKU
                ? DanmakuSettings.preferences(getContext())
                : ChatSettings.preferences(getContext());
    }

    @Override
    public void changed(Catalog.Store store) {
        if (store == Catalog.Store.ADS) {
            // Read live by every ad layer; nothing to reload.
        } else if (store == Catalog.Store.DANMAKU) {
            DanmakuSettings.reload(getContext());
            TwitchDanmaku.settingsChanged();
        } else {
            ChatSettings.reload(getContext());
        }
        for (Runnable refresher : new ArrayList<>(refreshers)) refresher.run();
    }

    @Override
    public void onRefresh(Runnable refresher) {
        refreshers.add(refresher);
    }

    @Override
    public void rebuild() {
        int scroll = detailScroll.getScrollY();
        render();
        detailScroll.post(() -> detailScroll.scrollTo(0, scroll));
    }

    // ---- layout ----

    private void build() {
        Context context = getContext();
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(ui.background);
        Ui.padForInsets(root);

        root.addView(topBar(context), Ui.matchWidth());

        LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        if (wide) {
            ScrollView railScroll = new ScrollView(context);
            rail = new LinearLayout(context);
            rail.setOrientation(LinearLayout.VERTICAL);
            rail.setPadding(Ui.dp(context, 12), Ui.dp(context, 8), Ui.dp(context, 8), Ui.dp(context, 24));
            railScroll.addView(rail);
            body.addView(railScroll, new LinearLayout.LayoutParams(Ui.dp(context, 300),
                    ViewGroup.LayoutParams.MATCH_PARENT));
        }

        detailScroll = new ScrollView(context);
        detailScroll.setClipToPadding(false);
        FrameLayout centre = new FrameLayout(context);
        detail = new LinearLayout(context);
        detail.setOrientation(LinearLayout.VERTICAL);
        int side = Ui.dp(context, wide ? 24 : 16);
        detail.setPadding(side, Ui.dp(context, 4), side, Ui.dp(context, 32));
        centre.addView(detail, Ui.frame(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));
        detailScroll.addView(centre);
        body.addView(detailScroll, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        detail.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> limitWidth());

        setContentView(root);
        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(ui.background));
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
            if (android.os.Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false);
            window.setStatusBarColor(ui.background);
            window.setNavigationBarColor(ui.background);
            Ui.systemBarIcons(window, ui.dark);
        }
        render();
    }

    /** Keeps option rows readable on very wide tablets. */
    private void limitWidth() {
        int max = Ui.dp(getContext(), 760);
        ViewGroup.LayoutParams lp = detail.getLayoutParams();
        int available = ((View) detail.getParent()).getWidth();
        int wanted = available > max ? max : ViewGroup.LayoutParams.MATCH_PARENT;
        if (lp.width != wanted) {
            lp.width = wanted;
            detail.post(() -> detail.setLayoutParams(lp));
        }
    }

    private View topBar(Context context) {
        LinearLayout bar = new LinearLayout(context);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setMinimumHeight(Ui.dp(context, 56));
        bar.setPadding(Ui.dp(context, 4), 0, Ui.dp(context, 8), 0);

        back = iconButton(context, "←", label("戻る", "Back"), this::goBack);
        bar.addView(back);

        title = ui.text(context, "", 20, ui.text, true);
        title.setSingleLine(true);
        title.setPadding(Ui.dp(context, 8), 0, Ui.dp(context, 8), 0);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        search = new EditText(context);
        search.setSingleLine(true);
        search.setHint(label("設定を検索", "Search settings"));
        search.setTextColor(ui.text);
        search.setHintTextColor(ui.secondary);
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setBackground(Ui.rounded(context, ui.dark ? 0x1AFFFFFF : 0x0D000000, 20));
        search.setPadding(Ui.dp(context, 16), Ui.dp(context, 8), Ui.dp(context, 16), Ui.dp(context, 8));
        search.setVisibility(View.GONE);
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                query = s.toString().trim().toLowerCase(Locale.ROOT);
                render();
            }
        });
        bar.addView(search, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        searchButton = iconButton(context, "⌕", label("検索", "Search"), this::toggleSearch);
        searchButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        bar.addView(searchButton);
        return bar;
    }

    private TextView iconButton(Context context, String glyph, String description, Runnable onClick) {
        TextView button = ui.text(context, glyph, 22, ui.text, false);
        button.setGravity(Gravity.CENTER);
        button.setContentDescription(description);
        button.setBackground(ui.pressable(context, 0, 24));
        button.setOnClickListener(v -> onClick.run());
        button.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(context, 48), Ui.dp(context, 48)));
        return button;
    }

    private boolean searching() {
        return search.getVisibility() == View.VISIBLE;
    }

    private void toggleSearch() {
        InputMethodManager ime = (InputMethodManager) getContext()
                .getSystemService(Context.INPUT_METHOD_SERVICE);
        if (searching()) {
            search.setText("");
            search.setVisibility(View.GONE);
            title.setVisibility(View.VISIBLE);
            searchButton.setText("⌕");
            if (ime != null) ime.hideSoftInputFromWindow(search.getWindowToken(), 0);
        } else {
            search.setVisibility(View.VISIBLE);
            title.setVisibility(View.GONE);
            searchButton.setText("✕");
            search.requestFocus();
            if (ime != null) ime.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT);
        }
        render();
    }

    private void goBack() {
        if (searching()) {
            toggleSearch();
        } else if (!wide && current != null) {
            current = null;
            render();
            detailScroll.scrollTo(0, 0);
        } else {
            dismiss();
        }
    }

    /** Back on older Android and in apps without predictive back. */
    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        goBack();
    }

    /**
     * Twitch targets Android 16, where the back gesture no longer arrives as a key event. Without
     * this, the gesture closed the whole screen from inside a category.
     */
    @Override
    protected void onStart() {
        super.onStart();
        if (android.os.Build.VERSION.SDK_INT >= 33 && backCallback == null) {
            android.window.OnBackInvokedCallback callback = this::goBack;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
            backCallback = callback;
        }
    }

    @Override
    protected void onStop() {
        if (android.os.Build.VERSION.SDK_INT >= 33 && backCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback((android.window.OnBackInvokedCallback) backCallback);
            backCallback = null;
        }
        super.onStop();
    }

    private Object backCallback;

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && event.isTracking() && !event.isCanceled()) {
            goBack();
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            event.startTracking();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ---- rendering ----

    private void render() {
        refreshers.clear();
        detail.removeAllViews();
        if (rail != null) renderRail();

        if (searching() && !query.isEmpty()) {
            title.setText(label("検索", "Search"));
            renderSearch();
        } else if (current == null) {
            title.setText(label("パッチの設定", "Patch settings"));
            renderCategoryList();
        } else {
            title.setText(current.title);
            renderCategory(current);
        }
        boolean canGoBack = searching() || (!wide && current != null);
        back.setText(canGoBack ? "←" : "✕");
        back.setContentDescription(canGoBack ? label("戻る", "Back") : label("閉じる", "Close"));
    }

    private void renderRail() {
        rail.removeAllViews();
        Context context = getContext();
        TextView heading = ui.text(context, label("パッチの設定", "Patch settings"), 13,
                ui.secondary, true);
        heading.setPadding(Ui.dp(context, 12), Ui.dp(context, 8), 0, Ui.dp(context, 8));
        rail.addView(heading);
        for (Catalog.Category category : categories) {
            boolean selected = category == current && !(searching() && !query.isEmpty());
            LinearLayout item = new LinearLayout(context);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setPadding(Ui.dp(context, 16), Ui.dp(context, 10), Ui.dp(context, 12), Ui.dp(context, 10));
            item.setBackground(ui.pressable(context, selected ? ui.accentSoft : 0, 14));
            item.addView(ui.text(context, category.title, 15, selected ? ui.accent : ui.text, true));
            TextView summary = ui.text(context, category.summary, 12, ui.secondary, false);
            summary.setSingleLine(true);
            summary.setEllipsize(android.text.TextUtils.TruncateAt.END);
            item.addView(summary);
            item.setOnClickListener(v -> {
                current = category;
                if (searching()) toggleSearch();
                else render();
                detailScroll.scrollTo(0, 0);
            });
            LinearLayout.LayoutParams lp = Ui.matchWidth();
            lp.bottomMargin = Ui.dp(context, 2);
            rail.addView(item, lp);
        }
    }

    private void renderCategoryList() {
        Context context = getContext();
        LinearLayout card = ui.card(context);
        for (Catalog.Category category : categories) {
            card.addView(ui.linkRow(context, category.title, category.summary, null, () -> {
                current = category;
                render();
                detailScroll.scrollTo(0, 0);
            }), Ui.matchWidth());
        }
        LinearLayout.LayoutParams lp = Ui.matchWidth();
        lp.topMargin = Ui.dp(context, 8);
        detail.addView(card, lp);
        TextView hint = ui.text(context, label("プレイヤーのボタンをタップするとメニュー、長押しで弾幕のオン/オフです。",
                "Tap the player button for the menu; long-press it to turn danmaku on or off."), 13,
                ui.secondary, false);
        hint.setPadding(Ui.dp(context, 4), Ui.dp(context, 14), Ui.dp(context, 4), 0);
        if (Features.danmaku()) detail.addView(hint);
    }

    private void renderCategory(Catalog.Category category) {
        for (Catalog.Group group : category.groups) renderGroup(group, group.title);
    }

    private void renderSearch() {
        int found = 0;
        for (Catalog.Category category : categories) {
            for (Catalog.Group group : category.groups) {
                Catalog.Group matches = new Catalog.Group(category, group.title);
                for (Catalog.Item item : group.items) if (item.matches(query)) matches.items.add(item);
                if (matches.items.isEmpty()) continue;
                found += matches.items.size();
                renderGroup(matches, category.title + " › " + group.title);
            }
        }
        if (found == 0) {
            TextView none = ui.text(getContext(), label("一致する設定はありません", "No matching settings"),
                    15, ui.secondary, false);
            none.setGravity(Gravity.CENTER);
            none.setPadding(0, Ui.dp(getContext(), 48), 0, 0);
            detail.addView(none, Ui.matchWidth());
        }
    }

    private void renderGroup(Catalog.Group group, String heading) {
        Context context = getContext();
        detail.addView(ui.sectionLabel(context, heading));
        LinearLayout card = ui.card(context);
        for (Catalog.Item item : group.items) {
            View view = item.build(this);
            card.addView(view, Ui.matchWidth());
            if (item.dependsOn != null) {
                Catalog.Store store = item.store;
                String key = item.dependsOn;
                boolean fallback = defaultOf(key);
                Runnable refresh = () -> setEnabled(view, prefs(store).getBoolean(key, fallback));
                refresh.run();
                onRefresh(refresh);
            }
        }
        detail.addView(card, Ui.matchWidth());
        if (group.note != null) {
            TextView note = ui.text(context, group.note, 12, ui.secondary, false);
            note.setLineSpacing(0, 1.15f);
            note.setPadding(Ui.dp(context, 4), Ui.dp(context, 8), Ui.dp(context, 4), 0);
            detail.addView(note);
        }
    }

    /** Default of the switch an item depends on, looked up in the catalog. */
    private boolean defaultOf(String key) {
        for (Catalog.Category category : categories)
            for (Catalog.Group group : category.groups)
                for (Catalog.Item item : group.items)
                    if (item instanceof Catalog.SwitchItem && key.equals(item.key))
                        return ((Catalog.SwitchItem) item).fallback;
        return false;
    }

    private static void setEnabled(View view, boolean enabled) {
        view.setAlpha(enabled ? 1f : 0.38f);
        view.setEnabled(enabled);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) setEnabledChild(group.getChildAt(i), enabled);
        }
    }

    private static void setEnabledChild(View view, boolean enabled) {
        view.setEnabled(enabled);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) setEnabledChild(group.getChildAt(i), enabled);
        }
    }
}
