package io.github.sabopura.morphe.extension.twitch.chat;

import android.app.*;
import android.content.*;
import android.os.Bundle;
import android.view.*;

import java.lang.ref.WeakReference;
import java.util.*;

/** Resource-ID based visibility options. Captured native visibility is restored on disable. */
public final class ViewCustomization {
    public static final String[][] OPTIONS = {
        {"hide_chat_header", "チャットの見出し", "Chat header", "chat_header_container", "chat_header"},
        {"hide_vod_header", "VODチャットの見出し", "VOD chat header", "chomments_header_container"},
        {
            "hide_leaderboards",
            "ランキング",
            "Leaderboards",
            "leaderboards_container",
            "leaderboards_extensions_container"
        },
        {
            "hide_hype_train",
            "ハイプトレイン",
            "Hype Train",
            "hype_train",
            "one_chat_hype_train_landscape_container"
        },
        {
            "hide_bits",
            "Bitsボタン",
            "Bits buttons",
            "bit_picker",
            "one_chat_bits_spending_view_container"
        },
        {"hide_cast", "キャストボタン", "Cast button", "cast_button"},
        {
            "hide_clips",
            "クリップ作成ボタン",
            "Create clip buttons",
            "create_clip_button_compose_view",
            "create_clip_text_button"
        },
        {
            "hide_share",
            "配信共有ボタン",
            "Share stream buttons",
            "share_stream_button",
            "share_stream_container"
        },
        {"hide_unfollow", "フォロー解除ボタン", "Unfollow button", "unfollow_button"},
        {
            "hide_create",
            "配信作成ボタン",
            "Create buttons",
            "custom_create_bottom_nav_button",
            "following_nav_rail_create_button"
        },
        {
            "hide_input",
            "チャット入力欄",
            "Chat input",
            "chat_message_input_view",
            "one_chat_message_input_view_container"
        },
        {
            "hide_landscape_input",
            "横画面のチャット入力欄",
            "Chat input in landscape",
            "chat_message_input_view",
            "one_chat_message_input_view_container"
        },
        {
            "hide_pinned",
            "ピン留めメッセージ",
            "Pinned messages",
            "paid_pinned_chat_container",
            "pinned_chat_and_highlight_container"
        },
        {
            "hide_stories",
            "ストーリー",
            "Stories",
            "stories_shelf_container",
            "stories_shelf_compose",
            "stories_shelf",
            "stories_button",
            "stories_button_xml"
        },
        {
            "hide_discover",
            "探索タブ",
            "Explore tab",
            "viewer_bottom_nav_explore",
            "viewer_bottom_nav_explore_portal"
        },
        {"hide_one_chat_popup", "チャットの案内", "Chat callouts", "one_chat_callout", "one_chat_tooltip"}
    };
    private static final Map<View, Observer> ROOTS = new WeakHashMap<>();
    private static boolean registered;

    public static void initialize(Context context) {
        if (registered || !(context.getApplicationContext() instanceof Application)) return;
        registered = true;
        ((Application) context.getApplicationContext())
                .registerActivityLifecycleCallbacks(
                        new Application.ActivityLifecycleCallbacks() {
                            public void onActivityResumed(Activity a) {
                                observe(a.getWindow().getDecorView());
                            }

                            public void onActivityDestroyed(Activity a) {
                                Observer o = ROOTS.remove(a.getWindow().getDecorView());
                                if (o != null) o.close();
                            }

                            public void onActivityCreated(Activity a, Bundle b) {}

                            public void onActivityStarted(Activity a) {}

                            public void onActivityPaused(Activity a) {}

                            public void onActivityStopped(Activity a) {}

                            public void onActivitySaveInstanceState(Activity a, Bundle b) {}
                        });
    }

    public static void observe(View root) {
        if (ROOTS.containsKey(root)) return;
        Observer o = new Observer(root);
        ROOTS.put(root, o);
        root.getViewTreeObserver().addOnGlobalLayoutListener(o);
        o.onGlobalLayout();
    }

    public static void refresh() {
        for (Observer o : new ArrayList<>(ROOTS.values())) o.onGlobalLayout();
    }

    private static final class Observer implements ViewTreeObserver.OnGlobalLayoutListener {
        final WeakReference<View> root;
        final Map<View, Integer> hidden = new WeakHashMap<>();
        boolean applying;

        Observer(View v) {
            root = new WeakReference<>(v);
        }

        void close() {
            View v = root.get();
            if (v != null && v.getViewTreeObserver().isAlive())
                v.getViewTreeObserver().removeOnGlobalLayoutListener(this);
        }

        public void onGlobalLayout() {
            View view = root.get();
            if (view == null || applying) return;
            applying = true;
            try {
                io.github.sabopura.morphe.extension.twitch.player.LayoutTools.applyBackgrounds(view);
                SharedPreferences prefs = ChatSettings.preferences(view.getContext());
                Map<Integer, Boolean> ids = new HashMap<>();
                boolean any = false;
                for (String[] option : OPTIONS) any |= prefs.getBoolean(option[0], false);
                if (!any && hidden.isEmpty()) return;
                for (String[] option : OPTIONS) {
                    boolean enabled = prefs.getBoolean(option[0], false);
                    if ("hide_landscape_input".equals(option[0]))
                        enabled &=
                                view.getResources().getConfiguration().orientation
                                        == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
                    for (int i = 3; i < option.length; i++) {
                        int id =
                                view.getResources()
                                        .getIdentifier(
                                                option[i],
                                                "id",
                                                view.getContext().getPackageName());
                        if (id != 0) ids.put(id, enabled || Boolean.TRUE.equals(ids.get(id)));
                    }
                }
                ArrayDeque<View> pending = new ArrayDeque<>();
                pending.add(view);
                int count = 0;
                while (!pending.isEmpty() && count++ < 5000) {
                    View v = pending.removeFirst();
                    boolean hide = Boolean.TRUE.equals(ids.get(v.getId()));
                    if (hide) {
                        if (!hidden.containsKey(v)) hidden.put(v, v.getVisibility());
                        if (v.getVisibility() != View.GONE) v.setVisibility(View.GONE);
                    } else if (hidden.containsKey(v)) {
                        int original = hidden.remove(v);
                        v.setVisibility(original);
                    }
                    if (v instanceof ViewGroup) {
                        ViewGroup group = (ViewGroup) v;
                        for (int i = 0; i < group.getChildCount(); i++)
                            pending.addLast(group.getChildAt(i));
                    }
                }
            } finally {
                applying = false;
            }
        }
    }
}
