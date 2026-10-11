package io.github.sabopura.morphe.extension.twitch.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

/**
 * Palette and building blocks shared by the player menu and the settings screen. Everything
 * is created in code so the patch adds no layouts, drawables or strings to Twitch.
 */
public final class Ui {
    public final boolean dark;
    public final int background;
    public final int surface;
    public final int card;
    public final int line;
    public final int text;
    public final int secondary;
    public final int accent;
    public final int onAccent;
    public final int accentSoft;

    private Ui(boolean dark) {
        this.dark = dark;
        if (dark) {
            background = 0xFF0E0E10;
            surface = 0xFF18181B;
            card = 0xFF1F1F23;
            line = 0x1FFFFFFF;
            text = 0xFFEFEFF1;
            secondary = 0xFFADADB8;
            accent = 0xFFA970FF;
            onAccent = 0xFF0E0E10;
            accentSoft = 0x33A970FF;
        } else {
            background = 0xFFF7F7F8;
            surface = 0xFFFFFFFF;
            card = 0xFFFFFFFF;
            line = 0x1F000000;
            text = 0xFF0E0E10;
            secondary = 0xFF53535F;
            accent = 0xFF772CE8;
            onAccent = 0xFFFFFFFF;
            accentSoft = 0x1F772CE8;
        }
    }

    /** Always dark; used over the video. */
    public static Ui overVideo() {
        return new Ui(true);
    }

    /** Follows Twitch's current light or dark theme. */
    public static Ui of(Context context) {
        int background = Color.BLACK;
        TypedArray a = context.getTheme().obtainStyledAttributes(
                new int[]{android.R.attr.colorBackground});
        try {
            background = a.getColor(0, Color.BLACK);
        } finally {
            a.recycle();
        }
        double luminance = (0.299 * Color.red(background) + 0.587 * Color.green(background)
                + 0.114 * Color.blue(background)) / 255.0;
        return new Ui(luminance < 0.5);
    }

    public static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    public static Activity activity(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    public static boolean wide(Context context) {
        return context.getResources().getConfiguration().screenWidthDp >= 720;
    }

    public static boolean landscape(Context context) {
        android.content.res.Configuration c = context.getResources().getConfiguration();
        return c.screenWidthDp > c.screenHeightDp;
    }

    // ---- drawables ----

    public static GradientDrawable rounded(Context context, int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(context, radiusDp));
        return d;
    }

    public static GradientDrawable corners(Context context, int color, float tl, float tr,
                                           float br, float bl) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        float a = dp(context, tl), b = dp(context, tr), c = dp(context, br), e = dp(context, bl);
        d.setCornerRadii(new float[]{a, a, b, b, c, c, e, e});
        return d;
    }

    public Drawable pressable(Context context, int color, float radiusDp) {
        int ripple = dark ? 0x33FFFFFF : 0x22000000;
        Drawable content = color == 0 ? null : rounded(context, color, radiusDp);
        return new RippleDrawable(ColorStateList.valueOf(ripple), content,
                rounded(context, Color.WHITE, radiusDp));
    }

    // ---- text ----

    public TextView text(Context context, CharSequence value, float sp, int color,
                         boolean medium) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        if (medium) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }

    public TextView sectionLabel(Context context, String value) {
        TextView view = text(context, value, 13, accent, true);
        view.setPadding(dp(context, 4), dp(context, 20), dp(context, 4), dp(context, 8));
        return view;
    }

    // ---- containers ----

    /** A rounded group of rows separated by hairlines. */
    public LinearLayout card(Context context) {
        LinearLayout card = new LinearLayout(context) {
            @Override
            public void addView(View child, int index, ViewGroup.LayoutParams params) {
                if (getChildCount() > 0) {
                    View divider = new View(getContext());
                    divider.setBackgroundColor(line);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(getContext(), 0.7f)));
                    lp.leftMargin = dp(getContext(), 16);
                    super.addView(divider, -1, lp);
                }
                super.addView(child, index, params);
            }
        };
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(context, card(), 16));
        card.setClipToOutline(true);
        return card;
    }

    private int card() {
        return card;
    }

    public static LinearLayout.LayoutParams matchWidth() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    // ---- rows ----

    private LinearLayout rowBase(Context context) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(context, 56));
        row.setPadding(dp(context, 16), dp(context, 10), dp(context, 16), dp(context, 10));
        row.setBackground(pressable(context, 0, 0));
        return row;
    }

    private LinearLayout texts(Context context, String title, String summary, TextView[] out) {
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        TextView t = text(context, title, 16, text, false);
        column.addView(t);
        TextView s = text(context, summary == null ? "" : summary, 13, secondary, false);
        s.setVisibility(TextUtils.isEmpty(summary) ? View.GONE : View.VISIBLE);
        s.setPadding(0, dp(context, 2), 0, 0);
        column.addView(s);
        if (out != null) {
            out[0] = t;
            out[1] = s;
        }
        return column;
    }

    public interface Toggle {
        void changed(boolean checked);
    }

    public Switch tint(Switch view) {
        int[][] states = {{android.R.attr.state_checked}, {}};
        view.setThumbTintList(new ColorStateList(states,
                new int[]{accent, dark ? 0xFFBDBDC6 : 0xFFF4F4F6}));
        view.setTrackTintList(new ColorStateList(states,
                new int[]{(accent & 0x00FFFFFF) | 0x88000000, dark ? 0x55FFFFFF : 0x33000000}));
        return view;
    }

    public SeekBar tint(SeekBar view) {
        view.setProgressTintList(ColorStateList.valueOf(accent));
        view.setThumbTintList(ColorStateList.valueOf(accent));
        view.setProgressBackgroundTintList(ColorStateList.valueOf(dark ? 0x55FFFFFF : 0x33000000));
        return view;
    }

    public View switchRow(Context context, String title, String summary, boolean checked,
                          Toggle listener) {
        LinearLayout row = rowBase(context);
        row.addView(texts(context, title, summary, null),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Switch toggle = tint(new Switch(context));
        toggle.setChecked(checked);
        toggle.setContentDescription(title);
        toggle.setOnCheckedChangeListener((button, value) -> listener.changed(value));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(context, 12);
        row.addView(toggle, lp);
        row.setOnClickListener(v -> toggle.toggle());
        row.setTag(toggle);
        return row;
    }

    public interface Format {
        String label(int value);
    }

    public interface Commit {
        void value(int value);
    }

    public View sliderRow(Context context, String title, String summary, int min, int max,
                          int value, Format format, Commit commit) {
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(context, 16), dp(context, 12), dp(context, 16), dp(context, 6));
        LinearLayout head = new LinearLayout(context);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(texts(context, title, summary, null),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView current = text(context, format.label(value), 15, accent, true);
        current.setPadding(dp(context, 12), 0, 0, 0);
        head.addView(current);
        column.addView(head);
        SeekBar bar = tint(new SeekBar(context));
        bar.setMax(max - min);
        bar.setProgress(Math.max(0, Math.min(max - min, value - min)));
        bar.setContentDescription(title);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                current.setText(format.label(min + progress));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                commit.value(min + seekBar.getProgress());
            }
        });
        LinearLayout.LayoutParams lp = matchWidth();
        lp.topMargin = dp(context, 4);
        column.addView(bar, lp);
        return column;
    }

    /** Title, current value and a chevron; tapping opens the editor. */
    public View linkRow(Context context, String title, String summary, String value,
                        Runnable onClick) {
        LinearLayout row = rowBase(context);
        TextView[] parts = new TextView[2];
        row.addView(texts(context, title, summary, parts),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (value != null) {
            TextView v = text(context, value, 14, secondary, false);
            v.setMaxWidth(dp(context, 160));
            v.setSingleLine(true);
            v.setEllipsize(TextUtils.TruncateAt.END);
            v.setPadding(dp(context, 12), 0, dp(context, 4), 0);
            row.addView(v);
        }
        TextView chevron = text(context, "›", 22, secondary, false);
        chevron.setPadding(dp(context, 6), 0, 0, dp(context, 2));
        row.addView(chevron);
        row.setOnClickListener(v -> onClick.run());
        return row;
    }

    public TextView chip(Context context, String label, boolean selected, Runnable onClick) {
        TextView chip = text(context, label, 14, selected ? onAccent : text, true);
        chip.setGravity(Gravity.CENTER);
        chip.setMinWidth(dp(context, 52));
        chip.setMinHeight(dp(context, 36));
        chip.setPadding(dp(context, 14), 0, dp(context, 14), 0);
        chip.setBackground(pressable(context, selected ? accent : (dark ? 0x22FFFFFF : 0x0F000000), 18));
        chip.setSelected(selected);
        chip.setOnClickListener(v -> onClick.run());
        return chip;
    }

    public HorizontalScrollView chipStrip(Context context, View... chips) {
        LinearLayout strip = new LinearLayout(context);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        for (View chip : chips) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(context, 8);
            strip.addView(chip, lp);
        }
        HorizontalScrollView scroll = new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(strip);
        return scroll;
    }

    /** Large tonal button used in the player menu grid. */
    public TextView tile(Context context, String label, Runnable onClick) {
        TextView tile = text(context, label, 14, text, true);
        tile.setGravity(Gravity.CENTER);
        tile.setMinHeight(dp(context, 48));
        tile.setMaxLines(2);
        tile.setPadding(dp(context, 8), dp(context, 6), dp(context, 8), dp(context, 6));
        tile.setBackground(pressable(context, dark ? 0x1AFFFFFF : 0x0D000000, 14));
        tile.setOnClickListener(v -> onClick.run());
        return tile;
    }

    /** Rows of equally wide tiles. */
    public LinearLayout grid(Context context, int columns, View... tiles) {
        LinearLayout grid = new LinearLayout(context);
        grid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = null;
        for (int i = 0; i < tiles.length; i++) {
            if (i % columns == 0) {
                row = new LinearLayout(context);
                LinearLayout.LayoutParams rp = matchWidth();
                if (i > 0) rp.topMargin = dp(context, 8);
                grid.addView(row, rp);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            if (i % columns != 0) lp.leftMargin = dp(context, 8);
            row.addView(tiles[i], lp);
        }
        if (row != null) {
            for (int i = tiles.length % columns; i != 0 && i < columns; i++) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, 0, 1);
                lp.leftMargin = dp(context, 8);
                row.addView(new View(context), lp);
            }
        }
        return grid;
    }

    public TextView button(Context context, String label, boolean filled, Runnable onClick) {
        TextView button = text(context, label, 15, filled ? onAccent : accent, true);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(context, 44));
        button.setPadding(dp(context, 18), 0, dp(context, 18), 0);
        if (filled) {
            button.setBackground(pressable(context, accent, 22));
        } else {
            GradientDrawable outline = rounded(context, 0, 22);
            outline.setStroke(Math.max(1, dp(context, 1)), (accent & 0x00FFFFFF) | 0x99000000);
            button.setBackground(new RippleDrawable(ColorStateList.valueOf(accentSoft), outline,
                    rounded(context, Color.WHITE, 22)));
        }
        button.setOnClickListener(v -> onClick.run());
        return button;
    }

    // ---- modal dialogs ----

    /** Positive action that may keep the dialog open, for example on invalid input. */
    public interface Validate {
        boolean accept();
    }

    /** A centred card dialog with an optional content view and up to two buttons. */
    public Dialog modal(Context context, String title, String message, View content,
                        String positive, Runnable onPositive, String negative) {
        return modal(context, title, message, content, positive, () -> {
            if (onPositive != null) onPositive.run();
            return true;
        }, negative);
    }

    public Dialog modal(Context context, String title, String message, View content,
                        String positive, Validate onPositive, String negative) {
        Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(context, 24), dp(context, 22), dp(context, 24), dp(context, 14));
        box.setBackground(rounded(context, surface, 24));
        if (title != null) {
            TextView t = text(context, title, 20, text, true);
            box.addView(t);
        }
        if (message != null) {
            TextView m = text(context, message, 14, secondary, false);
            m.setPadding(0, dp(context, 10), 0, 0);
            m.setLineSpacing(0, 1.15f);
            box.addView(m);
        }
        if (content != null) {
            LinearLayout.LayoutParams lp = matchWidth();
            lp.topMargin = dp(context, 14);
            box.addView(content, lp);
        }
        LinearLayout buttons = new LinearLayout(context);
        buttons.setGravity(Gravity.END);
        buttons.setPadding(0, dp(context, 16), 0, 0);
        if (negative != null) {
            TextView n = text(context, negative, 15, accent, true);
            n.setPadding(dp(context, 16), dp(context, 10), dp(context, 16), dp(context, 10));
            n.setBackground(pressable(context, 0, 20));
            n.setOnClickListener(v -> dialog.dismiss());
            buttons.addView(n);
        }
        if (positive != null) {
            TextView p = text(context, positive, 15, accent, true);
            p.setPadding(dp(context, 16), dp(context, 10), dp(context, 16), dp(context, 10));
            p.setBackground(pressable(context, 0, 20));
            p.setOnClickListener(v -> {
                if (onPositive == null || onPositive.accept()) dialog.dismiss();
            });
            buttons.addView(p);
        }
        box.addView(buttons, matchWidth());
        ScrollView scroll = new ScrollView(context);
        scroll.addView(box);
        dialog.setContentView(scroll);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int width = Math.min(context.getResources().getDisplayMetrics().widthPixels
                    - dp(context, 32), dp(context, 480));
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        return dialog;
    }

    public interface Pick {
        void picked(int index);
    }

    /** Single-choice list; the current choice is marked. */
    public void choose(Context context, String title, String[] labels, int selected, Pick pick) {
        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        Dialog[] holder = new Dialog[1];
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            LinearLayout row = rowBase(context);
            row.setMinimumHeight(dp(context, 48));
            row.setPadding(dp(context, 4), 0, dp(context, 4), 0);
            row.setBackground(pressable(context, 0, 12));
            TextView mark = text(context, i == selected ? "●" : "○", 16,
                    i == selected ? accent : secondary, false);
            mark.setPadding(0, 0, dp(context, 14), 0);
            row.addView(mark);
            row.addView(text(context, labels[i], 16, text, i == selected));
            row.setOnClickListener(v -> {
                pick.picked(index);
                if (holder[0] != null) holder[0].dismiss();
            });
            list.addView(row, matchWidth());
        }
        holder[0] = modal(context, title, null, list, null, (Runnable) null,
                Text.label("キャンセル", "Cancel"));
        show(holder[0], context);
    }

    public interface Save {
        void text(String value);
    }

    /** Multi-line editor for word and user lists. */
    public void editLines(Context context, String title, String help, String value, Save save) {
        EditText input = new EditText(context);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(8000)});
        input.setMinLines(5);
        input.setMaxLines(12);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setTextColor(text);
        input.setHintTextColor(secondary);
        input.setText(value);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        input.setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12));
        input.setBackground(rounded(context, dark ? 0x14FFFFFF : 0x0A000000, 12));
        Dialog dialog = modal(context, title, help, input, Text.label("保存", "Save"),
                () -> save.text(input.getText().toString()), Text.label("キャンセル", "Cancel"));
        show(dialog, context);
    }

    // ---- windows ----

    /**
     * Shows a dialog without bringing back system bars that the fullscreen player hid, and
     * without stealing focus during the show transition.
     */
    public static void show(Dialog dialog, Context context) {
        Window window = dialog.getWindow();
        Activity activity = activity(context);
        boolean immersive = activity != null && barsHidden(activity);
        if (window != null && immersive) {
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        }
        dialog.show();
        if (window != null && immersive) {
            hideBars(window);
            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        }
    }

    @SuppressWarnings("deprecation")
    private static boolean barsHidden(Activity activity) {
        View decor = activity.getWindow().getDecorView();
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsets insets = decor.getRootWindowInsets();
            return insets != null && !insets.isVisible(WindowInsets.Type.statusBars());
        }
        return (decor.getSystemUiVisibility() & View.SYSTEM_UI_FLAG_FULLSCREEN) != 0;
    }

    @SuppressWarnings("deprecation")
    private static void hideBars(Window window) {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                controller.hide(WindowInsets.Type.systemBars());
            }
        } else {
            window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    /** Dark icons on a light screen, light icons on a dark one. */
    @SuppressWarnings("deprecation")
    public static void systemBarIcons(Window window, boolean darkBackground) {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = window.getInsetsController();
            if (controller == null) return;
            int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            controller.setSystemBarsAppearance(darkBackground ? 0 : light, light);
        } else {
            View decor = window.getDecorView();
            int flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            int current = decor.getSystemUiVisibility();
            decor.setSystemUiVisibility(darkBackground ? current & ~flags : current | flags);
        }
    }

    /** Pads a full-window root by the system bar and cutout insets. */
    public static void padForInsets(View root) {
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int l, t, r, b;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                l = i.left;
                t = i.top;
                r = i.right;
                b = i.bottom;
            } else {
                l = insets.getSystemWindowInsetLeft();
                t = insets.getSystemWindowInsetTop();
                r = insets.getSystemWindowInsetRight();
                b = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(l, t, r, b);
            return insets;
        });
    }

    public static FrameLayout.LayoutParams frame(int width, int height, int gravity) {
        return new FrameLayout.LayoutParams(width, height, gravity);
    }
}
