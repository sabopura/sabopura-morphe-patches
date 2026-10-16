package io.github.sabopura.deviceprobe;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The "Hide" options against Twitch's real layouts: each option's views inside the layouts
 * where Twitch shows them, the bottom bar's tabs (no gap left), the unfollow heart in Twitch's
 * own button lists, and chat highlights dropped by type in Twitch's own highlight filter
 * (31.5.2 fixture names: u0s, l1s, x89, y89, b99, p99, j89, f89, o89; that part is skipped on
 * other versions).
 */
public class DeclutterProbe extends Instrumentation {
    private static final String TAG = "DeclutterProbe";
    private static final String CUSTOM = "io.github.sabopura.morphe.extension.twitch.chat.ViewCustomization";
    private final StringBuilder report = new StringBuilder();
    private int failed;
    private Activity activity;
    private ClassLoader loader;
    private SharedPreferences prefs;
    private FrameLayout host;
    private final Map<String, View> layouts = new LinkedHashMap<>();

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override
    public void onStart() {
        Bundle result = new Bundle();
        Context target = getTargetContext();
        loader = target.getClassLoader();
        prefs = target.getSharedPreferences("sabopura_chat", Context.MODE_PRIVATE);
        Map<String, ?> saved = prefs.getAll();
        try {
            prefs.edit().clear().commit();
            launch(target);
            build(target);
            String[][] views = {
                    {"hide_chat_header", "chat_view_delegate", "chat_header_container"},
                    {"hide_vod_header", "chomments_view", "chomments_header_container"},
                    {"hide_leaderboards", "chat_view_delegate", "leaderboards_with_extension_header"},
                    {"hide_hype_train", "one_chat_layout", "one_chat_hype_train_landscape_container"},
                    {"hide_bits", "message_input", "bit_picker"},
                    {"hide_bits", "one_chat_layout", "bits_button"},
                    {"hide_cast", "player_control_overlay", "cast_button"},
                    {"hide_clips", "player_control_overlay", "create_clip_text_button"},
                    {"hide_share", "player_control_overlay", "share_button"},
                    {"hide_create", "viewer_landing", "custom_create_bottom_nav_button"},
                    {"hide_input", "chat_view_delegate", "chat_message_input_view_container"},
                    {"hide_input", "theatre_coordinator", "one_chat_message_input_view_container"},
                    {"hide_pinned", "chat_view_delegate", "paid_pinned_chat_container"},
                    {"hide_one_chat_popup", "one_chat_layout", "one_chat_callout"},
                    {"hide_stories", "discovery_feed_home_view", "stories_shelf_container"},
                    {"hide_stories", "discovery_feed_home_view", "stories_button_xml"},
            };
            for (String[] v : views) {
                View view = find(v[1], v[2]);
                if (view == null) {
                    fail(v[0] + ": no " + v[2] + " in " + v[1]);
                    continue;
                }
                runOnMainSync(() -> view.setVisibility(View.VISIBLE));
                set(v[0], true);
                boolean gone = visibility(view) == View.GONE;
                set(v[0], false);
                boolean back = visibility(view) == View.VISIBLE;
                check(gone && back, v[0] + " hides " + v[2] + " (" + v[1] + ") and restores it"
                        + (gone && back ? "" : " [hidden=" + gone + ", restored=" + back + "]"));
            }
            View highlights = find("chat_view_delegate", "community_highlight_container");
            set("hide_pinned", true);
            check(visibility(highlights) == View.VISIBLE, "pinned messages: polls and other highlights stay");
            set("hide_pinned", false);

            // The bottom bar: hidden tabs leave no empty slot.
            View bar = find("viewer_landing", "viewer_landing_bottom_navigation");
            Menu menu = menu(bar);
            // Twitch sets the tab icons in code; give them some for the screenshots.
            runOnMainSync(() -> {
                int[] icons = {android.R.drawable.ic_menu_view, android.R.drawable.ic_menu_search, 0,
                        android.R.drawable.ic_popup_reminder, android.R.drawable.ic_menu_myplaces};
                String[] titles = {"Home", "Explore", null, "Activity", "Profile"};
                for (int i = 0; i < menu.size() && i < icons.length; i++) {
                    if (icons[i] != 0) menu.getItem(i).setIcon(icons[i]);
                    if (titles[i] != null) menu.getItem(i).setTitle(titles[i]);
                }
            });
            shot("tabs-default");
            int all = visibleTabs(menu);
            set("hide_create", true);
            int withoutCreate = visibleTabs(menu);
            shot("tabs-no-create");
            set("hide_discover", true);
            int withoutExplore = visibleTabs(menu);
            shot("tabs-no-create-explore");
            set("hide_create", false);
            set("hide_discover", false);
            int restored = visibleTabs(menu);
            line("INFO tabs " + all + " -> " + withoutCreate + " -> " + withoutExplore + " -> " + restored);
            check(withoutCreate == all - 1, "hiding the create button also removes its empty tab slot");
            check(withoutExplore == all - 2, "the Explore tab is removed from the bar, not left blank");
            check(restored == all, "turning both off brings the tabs back");

            highlightsFilter();
        } catch (Throwable t) {
            fail("crash " + t + " " + Log.getStackTraceString(t));
        } finally {
            SharedPreferences.Editor e = prefs.edit().clear();
            for (Map.Entry<String, ?> entry : saved.entrySet()) {
                Object v = entry.getValue();
                if (v instanceof Boolean) e.putBoolean(entry.getKey(), (Boolean) v);
                else if (v instanceof Integer) e.putInt(entry.getKey(), (Integer) v);
                else if (v instanceof Long) e.putLong(entry.getKey(), (Long) v);
                else if (v instanceof String) e.putString(entry.getKey(), (String) v);
            }
            e.commit();
        }
        if (activity != null) runOnMainSync(() -> activity.finish());
        line("RESULT failed=" + failed);
        result.putString(Instrumentation.REPORT_KEY_STREAMRESULT, report.toString());
        finish(failed == 0 ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }

    /**
     * The unfollow heart, with Twitch's own button objects: the list under the player loses it,
     * and the chat header above the landscape chat, built by Twitch's own builder, has no follow
     * button on a followed channel to begin with (fixture names per version: theatre state,
     * unfollow, subscribe; header builder, its case, input, follow state, subscription state,
     * header state).
     */
    private void unfollowButtons(String version) throws Exception {
        String[] f = "31.5.2".equals(version)
                ? new String[] {"qvp", "cvp", "hvp", "zw5", "0", "ax5", "n75", "l370", "uw5"}
                : "30.7.2".equals(version)
                ? new String[] {"xsn", "nsn", "ssn", "lz4", "18", "vf5", "xr4", "pd30", "rf5"}
                : null;
        if (f == null) {
            line("INFO unfollow button fixture is for 31.5.2 / 30.7.2; skipped on " + version);
            return;
        }
        Class<?> buttonState = Class.forName(f[0], true, loader);
        Object unfollow = Class.forName(f[1], true, loader).getField("d").get(null);
        Object subscribe = Class.forName(f[2], true, loader).getField("c").get(null);
        set("hide_unfollow", true);
        Object state = buttonState.getConstructors()[0].newInstance(new ArrayList<>(Arrays.asList(subscribe, unfollow)), null);
        check(String.valueOf(state).equals("Visible(buttons=[SubscribeButton], onNotificationSettingSelected=null)"),
                "followed channel: no unfollow heart under the player (" + state + ")");
        set("hide_unfollow", false);
        state = buttonState.getConstructors()[0].newInstance(new ArrayList<>(Arrays.asList(subscribe, unfollow)), null);
        check(String.valueOf(state).contains("UnfollowIconButton"), "switched off: the heart under the player is shown");

        String followed = header(f, true, true);
        check(followed.contains("SubscribeButton") && !followed.contains("FollowButton"),
                "Twitch itself shows no follow button above the landscape chat on a followed channel (" + followed + ")");
        set("hide_unfollow", true);
        String notFollowed = header(f, false, true);
        check(notFollowed.contains("FollowButton"), "channel not followed: the chat header keeps Follow (" + notFollowed + ")");
        set("hide_unfollow", false);
    }

    /** The chat header state Twitch's builder makes for a follow state (subscriptions on). */
    private String header(String[] f, boolean following, boolean canFollow) throws Exception {
        Object unsafe = unsafe();
        java.lang.reflect.Method allocate = unsafe.getClass().getMethod("allocateInstance", Class.class);
        Object follow = allocate.invoke(unsafe, Class.forName(f[6], true, loader));
        field(follow, "a", canFollow);
        field(follow, "b", following);
        Object subscriptions = allocate.invoke(unsafe, Class.forName(f[7], true, loader));
        field(subscriptions, "b", true);
        Object input = allocate.invoke(unsafe, Class.forName(f[5], true, loader));
        field(input, "a", follow);
        field(input, "b", subscriptions);
        Object builder = Class.forName(f[3], true, loader).getConstructor(int.class).newInstance(Integer.parseInt(f[4]));
        Object result = builder.getClass().getMethod("invoke", Object.class).invoke(builder, input);
        check(result != null && result.getClass().getName().equals(f[8]), "Twitch's builder makes the chat header state (" + result + ")");
        return String.valueOf(result);
    }

    private static void field(Object owner, String name, Object value) throws Exception {
        java.lang.reflect.Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(owner, value);
    }

    /** Twitch's highlight filter with its own event objects (31.5.2 fixture). */
    private void highlightsFilter() throws Exception {
        String version = getTargetContext().getPackageManager()
                .getPackageInfo(getTargetContext().getPackageName(), 0).versionName;
        unfollowButtons(version);
        if (!"31.5.2".equals(version)) {
            line("INFO highlight filter fixture is for 31.5.2; skipped on " + version);
            return;
        }
        Class<?> filterClass = Class.forName("u0s", true, loader);
        Object unsafe = unsafe();
        Class<?> presenter = Class.forName("l1s", true, loader);
        Object fakePresenter = unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, presenter);
        Object filter = filterClass.getConstructor(presenter, int.class).newInstance(fakePresenter, 2);
        Object hype = Class.forName("j89", true, loader).getConstructor(int.class, Boolean.class, Boolean.class).newInstance(3, null, null);
        Object pinned = Class.forName("f89", true, loader).getField("c").get(null);
        Object polls = Class.forName("o89", true, loader).getField("c").get(null);
        Class<?> base = Class.forName("w89", true, loader);
        Object hypePill = Class.forName("y89", true, loader).getConstructor(base, LinkedHashMap.class).newInstance(hype, new LinkedHashMap<>());
        Object pinnedAdd = add(unsafe, pinned);
        Object pollsAdd = add(unsafe, polls);
        Object hypeRemoval = Class.forName("b99", true, loader).getConstructor(base, String.class).newInstance(hype, "hypetrain");

        line("INFO filter " + filter.getClass().getName() + " events ready");
        set("hide_hype_train", true);
        set("hide_pinned", true);
        // Dropped events return before any of Twitch's own code runs, so a bare presenter is enough.
        check(Boolean.FALSE.equals(run(filter, hypePill)), "a hype train is dropped by Twitch's highlight filter");
        check(Boolean.FALSE.equals(run(filter, pinnedAdd)), "the streamer's pinned message is dropped");
        // Kept events go on into Twitch's own checks, where the bare presenter fails at once: that
        // shows the event got past the hook. It has to come back at all; a looping hook never does.
        Object poll = run(filter, pollsAdd), removal = run(filter, hypeRemoval);
        check(passedOn(poll), "a poll goes on to Twitch's own checks (" + poll + ")");
        check(passedOn(removal), "removing a hype train is never dropped (" + removal + ")");
        // Twitch's events are never turned into text, which can take seconds: a pill whose data
        // refuses it is dropped like any other.
        LinkedHashMap<Object, Object> refuses = new LinkedHashMap<>();
        refuses.put("probe", new Object() {
            @Override
            public String toString() {
                throw new IllegalStateException("turned into text");
            }
        });
        Object refusing = Class.forName("y89", true, loader).getConstructor(base, LinkedHashMap.class).newInstance(hype, refuses);
        check(Boolean.FALSE.equals(run(filter, refusing)), "events are not turned into text");
        set("hide_hype_train", false);
        set("hide_pinned", false);
        Object pill = run(filter, hypePill), pin = run(filter, pinnedAdd);
        check(passedOn(pill) && passedOn(pin), "switched off: hype trains and pinned messages go on (" + pill + ", " + pin + ")");
    }

    private static boolean passedOn(Object result) {
        return Boolean.TRUE.equals(result) || result instanceof String && ((String) result).startsWith("passed on");
    }

    private Object add(Object unsafe, Object type) throws Exception {
        Class<?> model = Class.forName("p99", true, loader);
        Object highlight = unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, model);
        java.lang.reflect.Field b = model.getDeclaredField("b");
        b.setAccessible(true);
        b.set(highlight, type);
        java.lang.reflect.Field a = model.getDeclaredField("a");
        a.setAccessible(true);
        a.set(highlight, "probe");
        return Class.forName("x89", true, loader).getConstructor(model).newInstance(highlight);
    }

    /**
     * What the filter returns, on a thread of its own: anything after our check (here: a fake
     * presenter fails) means "passed on", and "hang" that it did not come back within 3 s.
     */
    private Object run(Object filter, Object event) throws InterruptedException {
        Object[] result = {"hang"};
        Thread thread = new Thread(() -> {
            try {
                result[0] = filter.getClass().getMethod("invoke", Object.class).invoke(filter, event);
            } catch (Exception e) {
                result[0] = "passed on (" + (e.getCause() == null ? e : e.getCause()).getClass().getSimpleName() + ")";
            }
        });
        thread.setDaemon(true);
        thread.start();
        thread.join(3000);
        return thread.isAlive() ? "hang" : result[0];
    }

    private static Object unsafe() throws Exception {
        Class<?> c = Class.forName("sun.misc.Unsafe");
        java.lang.reflect.Field f = c.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return f.get(null);
    }

    private void set(String key, boolean on) throws Exception {
        prefs.edit().putBoolean(key, on).commit();
        Class<?> custom = Class.forName(CUSTOM, true, loader);
        runOnMainSync(() -> {
            try {
                custom.getMethod("refresh").invoke(null);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        waitForIdleSync();
        Thread.sleep(150);
    }

    private int visibility(View view) {
        int[] v = new int[1];
        runOnMainSync(() -> v[0] = view.getVisibility());
        return v[0];
    }

    private Menu menu(View bar) throws Exception {
        return (Menu) bar.getClass().getMethod("getMenu").invoke(bar);
    }

    private int visibleTabs(Menu menu) {
        int[] n = new int[1];
        runOnMainSync(() -> {
            for (int i = 0; i < menu.size(); i++) {
                MenuItem item = menu.getItem(i);
                if (item.isVisible()) n[0]++;
            }
        });
        return n[0];
    }

    private View find(String layout, String id) {
        View root = layouts.get(layout);
        int res = getTargetContext().getResources().getIdentifier(id, "id", getTargetContext().getPackageName());
        return root == null || res == 0 ? null : root.findViewById(res);
    }

    private void shot(String name) throws Exception {
        Thread.sleep(300);
        Bitmap shot = getUiAutomation().takeScreenshot();
        File dir = getTargetContext().getExternalFilesDir("declutter-probe");
        if (shot != null && dir != null) {
            dir.mkdirs();
            try (FileOutputStream out = new FileOutputStream(new File(dir, name + ".png"))) {
                shot.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
        }
    }

    private void launch(Context context) throws Exception {
        Activity[] resumed = new Activity[1];
        ((android.app.Application) context.getApplicationContext()).registerActivityLifecycleCallbacks(
                new android.app.Application.ActivityLifecycleCallbacks() {
                    public void onActivityResumed(Activity a) { resumed[0] = a; }
                    public void onActivityPaused(Activity a) { }
                    public void onActivityCreated(Activity a, Bundle b) { }
                    public void onActivityStarted(Activity a) { }
                    public void onActivityStopped(Activity a) { }
                    public void onActivitySaveInstanceState(Activity a, Bundle b) { }
                    public void onActivityDestroyed(Activity a) { if (resumed[0] == a) resumed[0] = null; }
                });
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivitySync(launch);
        for (int i = 0; i < 30 && (resumed[0] == null || resumed[0].isFinishing()); i++) Thread.sleep(300);
        Thread.sleep(3000);
        activity = resumed[0];
        if (activity == null) throw new AssertionError("no resumed Twitch activity");
    }

    private void build(Context c) throws Throwable {
        Throwable[] error = new Throwable[1];
        runOnMainSync(() -> {
            try {
                activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
                // The sign-in screen behind may raise the keyboard and shrink the window.
                activity.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                        | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
                if (activity.getCurrentFocus() != null) activity.getCurrentFocus().clearFocus();
                LayoutInflater inflater = LayoutInflater.from(activity);
                LinearLayout stack = new LinearLayout(activity);
                stack.setOrientation(LinearLayout.VERTICAL);
                // discovery_feed_home_view: the home feed with the stories shelf and button.
                for (String name : new String[]{"chat_view_delegate", "chomments_view", "one_chat_layout", "message_input",
                        "player_control_overlay", "theatre_coordinator", "discovery_feed_home_view"}) {
                    int id = c.getResources().getIdentifier(name, "layout", c.getPackageName());
                    View view = inflater.inflate(id, stack, false);
                    layouts.put(name, view);
                    stack.addView(view, new LinearLayout.LayoutParams(-1, 1));
                }
                // The home screen frame as Twitch shows it: bar visible, insets applied.
                View landing = inflater.inflate(c.getResources().getIdentifier("viewer_landing", "layout", c.getPackageName()), null, false);
                layouts.put("viewer_landing", landing);
                WindowInsets insets = activity.getWindow().getDecorView().getRootWindowInsets();
                int top = insets.getInsets(WindowInsets.Type.statusBars()).top;
                int bottom = insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
                View layout = landing.findViewById(c.getResources().getIdentifier("viewer_landing_layout", "id", c.getPackageName()));
                layout.setPadding(0, top, 0, 0);
                View nav = landing.findViewById(c.getResources().getIdentifier("viewer_landing_bottom_navigation_group", "id", c.getPackageName()));
                ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) nav.getLayoutParams();
                lp.bottomMargin = bottom;
                nav.setLayoutParams(lp);
                nav.setVisibility(View.VISIBLE);
                landing.findViewById(c.getResources().getIdentifier("splash_overlay", "id", c.getPackageName())).setVisibility(View.GONE);
                host = new FrameLayout(activity);
                host.setClickable(true);
                host.addView(stack, new FrameLayout.LayoutParams(-1, -1));
                host.addView(landing, new FrameLayout.LayoutParams(-1, -1));
                activity.addContentView(host, new ViewGroup.LayoutParams(-1, -1));
                Class.forName(CUSTOM, true, loader).getMethod("observe", View.class).invoke(null, host);
            } catch (Throwable t) {
                error[0] = t;
            }
        });
        if (error[0] != null) throw error[0];
        waitForIdleSync();
        Thread.sleep(1000);
    }

    private void check(boolean ok, String what) {
        if (ok) line("PASS " + what);
        else fail(what);
    }

    private void fail(String what) {
        failed++;
        line("FAIL " + what);
    }

    private void line(String text) {
        Log.i(TAG, text);
        report.append(text).append('\n');
    }
}
