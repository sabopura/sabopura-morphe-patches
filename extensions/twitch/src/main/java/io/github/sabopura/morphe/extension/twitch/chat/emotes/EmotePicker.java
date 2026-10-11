package io.github.sabopura.morphe.extension.twitch.chat.emotes;

import io.github.sabopura.morphe.extension.twitch.ui.Text;
import android.app.AlertDialog;
import android.text.*;
import android.view.*;
import android.widget.*;

import io.github.sabopura.morphe.extension.twitch.chat.*;

import java.util.*;

public final class EmotePicker {
    private static final WeakHashMap<EditText, Boolean> ATTACHED = new WeakHashMap<>();

    private static String label(String ja, String en) {
        return Text.label(ja, en);
    }

    public static void show(EditText target) {
        android.content.Context c = target.getContext();
        LinearLayout content = new LinearLayout(c);
        content.setOrientation(1);
        EditText search = new EditText(c);
        search.setHint(label("エモートを検索（長押しでお気に入り）", "Search emotes; hold to favorite"));
        content.addView(search);
        CheckBox only = new CheckBox(c);
        only.setText(label("お気に入りのみ", "Favorites only"));
        content.addView(only);
        ListView list = new ListView(c);
        content.addView(
                list,
                new LinearLayout.LayoutParams(
                        -1, Math.round(300 * c.getResources().getDisplayMetrics().density)));
        List<String> names = new ArrayList<>();
        ArrayAdapter<String> adapter =
                new ArrayAdapter<String>(c, android.R.layout.simple_list_item_1, names) {
                    public View getView(int i, View old, ViewGroup parent) {
                        TextView row = (TextView) super.getView(i, old, parent);
                        row.setText(
                                (favorites(target).contains(getItem(i)) ? "★ " : "") + getItem(i));
                        EmoteSupport.bind(row, ChatEnhancements.channelId());
                        return row;
                    }
                };
        list.setAdapter(adapter);
        Runnable refresh =
                () -> {
                    String q = search.getText().toString().toLowerCase(Locale.ROOT);
                    Set<String> fav = favorites(target);
                    names.clear();
                    for (String name : EmoteSupport.names(c, ChatEnhancements.channelId()))
                        if (name.toLowerCase(Locale.ROOT).contains(q)
                                && (!only.isChecked() || fav.contains(name))) names.add(name);
                    names.sort(
                            (a, b) -> {
                                int order = Boolean.compare(fav.contains(b), fav.contains(a));
                                return order != 0 ? order : a.compareToIgnoreCase(b);
                            });
                    adapter.notifyDataSetChanged();
                };
        AlertDialog dialog =
                new AlertDialog.Builder(c)
                        .setTitle(label("追加エモート", "Additional emotes"))
                        .setView(content)
                        .setNegativeButton(android.R.string.cancel, null)
                        .create();
        list.setOnItemClickListener(
                (a, v, pos, id) -> {
                    insert(target, names.get(pos), false);
                    dialog.dismiss();
                });
        list.setOnItemLongClickListener(
                (a, v, pos, id) -> {
                    Set<String> fav = favorites(target);
                    String n = names.get(pos);
                    if (!fav.remove(n) && fav.size() < 300) fav.add(n);
                    ChatSettings.preferences(c).edit().putStringSet("favorite_emotes", fav).apply();
                    refresh.run();
                    return true;
                });
        search.addTextChangedListener(watch(refresh));
        only.setOnCheckedChangeListener((v, b) -> refresh.run());
        Runnable poll =
                new Runnable() {
                    public void run() {
                        if (dialog.isShowing()) {
                            refresh.run();
                            PublicApi.UI.postDelayed(this, 1500);
                        }
                    }
                };
        dialog.setOnDismissListener(d -> PublicApi.UI.removeCallbacks(poll));
        dialog.show();
        poll.run();
    }

    private static Set<String> favorites(EditText t) {
        return new HashSet<>(
                ChatSettings.preferences(t.getContext())
                        .getStringSet("favorite_emotes", Collections.emptySet()));
    }

    public static void attach(EditText input) {
        if (ATTACHED.put(input, true) != null) return;
        ListPopupWindow popup = new ListPopupWindow(input.getContext());
        popup.setAnchorView(input);
        popup.setModal(false);
        List<String> names = new ArrayList<>();
        ArrayAdapter<String> adapter =
                new ArrayAdapter<>(input.getContext(), android.R.layout.simple_list_item_1, names);
        popup.setAdapter(adapter);
        popup.setOnItemClickListener(
                (a, v, p, id) -> {
                    insert(input, names.get(p), true);
                    popup.dismiss();
                });
        input.addTextChangedListener(
                watch(
                        () -> {
                            if (!input.hasFocus()
                                    || !ChatSettings.preferences(input.getContext())
                                            .getBoolean("emote_completion", true)) {
                                popup.dismiss();
                                return;
                            }
                            int end = input.getSelectionStart(),
                                    start = tokenStart(input.getText(), end);
                            if (end < 0
                                    || end - start < 2
                                    || input.getText().charAt(start) != ':') {
                                popup.dismiss();
                                return;
                            }
                            String prefix =
                                    input.getText()
                                            .subSequence(start + 1, end)
                                            .toString()
                                            .toLowerCase(Locale.ROOT);
                            names.clear();
                            for (String name :
                                    EmoteSupport.names(
                                            input.getContext(), ChatEnhancements.channelId()))
                                if (name.toLowerCase(Locale.ROOT).startsWith(prefix)
                                        && names.size() < 20) names.add(name);
                            adapter.notifyDataSetChanged();
                            if (names.isEmpty()) popup.dismiss();
                            else popup.show();
                        }));
        input.addOnAttachStateChangeListener(
                new View.OnAttachStateChangeListener() {
                    public void onViewAttachedToWindow(View v) {}

                    public void onViewDetachedFromWindow(View v) {
                        popup.dismiss();
                    }
                });
    }

    public static int tokenStart(CharSequence text, int cursor) {
        if (cursor < 0) return 0;
        int s = Math.min(cursor, text.length());
        while (s > 0 && !Character.isWhitespace(text.charAt(s - 1))) s--;
        return s;
    }

    private static void insert(EditText t, String name, boolean completion) {
        int end = Math.max(0, t.getSelectionEnd()), start = Math.max(0, t.getSelectionStart());
        if (completion) start = tokenStart(t.getText(), start);
        if (start > end) {
            int n = start;
            start = end;
            end = n;
        }
        t.getText().replace(start, end, name + " ");
        t.setSelection(Math.min(t.length(), start + name.length() + 1));
    }

    private static TextWatcher watch(Runnable r) {
        return new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int c, int f) {}

            public void onTextChanged(CharSequence s, int a, int b, int c) {
                r.run();
            }

            public void afterTextChanged(Editable e) {}
        };
    }
}
