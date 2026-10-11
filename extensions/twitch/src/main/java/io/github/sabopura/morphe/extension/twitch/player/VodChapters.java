package io.github.sabopura.morphe.extension.twitch.player;

import io.github.sabopura.morphe.extension.twitch.ui.Text;
import android.app.AlertDialog;
import android.content.Context;
import android.widget.*;

import io.github.sabopura.morphe.extension.twitch.chat.*;

import org.json.*;

import java.util.*;

public final class VodChapters {
    private static String label(String ja, String en) {
        return Text.label(ja, en);
    }

    public static void show(Context c) {
        String id = PlayerTools.vodId();
        if (!id.matches("[0-9]{1,20}")) {
            Toast.makeText(c, label("VODを開いてください", "Open a VOD first"), Toast.LENGTH_SHORT).show();
            return;
        }
        TextView status = new TextView(c);
        status.setPadding(24, 24, 24, 24);
        status.setText(label("チャプターを取得中…", "Loading chapters…"));
        AlertDialog wait =
                new AlertDialog.Builder(c)
                        .setTitle(label("VODチャプター", "VOD chapters"))
                        .setView(status)
                        .setNegativeButton(android.R.string.cancel, null)
                        .create();
        wait.show();
        if (!PublicApi.submit(
                () -> {
                    try {
                        JSONObject body =
                                new JSONObject()
                                        .put(
                                                "query",
                                                "query($id:ID!){video(id:$id){moments(momentRequestType:VIDEO_CHAPTER_MARKERS,types:[GAME_CHANGE]){edges{node{positionMilliseconds"
                                                    + " description}}}}}")
                                        .put("variables", new JSONObject().put("id", id));
                        List<Chapter> chapters =
                                parse(new JSONObject(PublicApi.twitchMetadata(body.toString())));
                        PublicApi.UI.post(
                                () -> {
                                    if (!wait.isShowing()) return;
                                    if (chapters.isEmpty()) {
                                        status.setText(
                                                label("公開チャプターがありません", "No public chapters"));
                                        return;
                                    }
                                    wait.dismiss();
                                    String[] titles = new String[chapters.size()];
                                    for (int i = 0; i < titles.length; i++) {
                                        Chapter p = chapters.get(i);
                                        long s = p.millis / 1000;
                                        titles[i] =
                                                String.format(
                                                        Locale.ROOT,
                                                        "%d:%02d:%02d  %s",
                                                        s / 3600,
                                                        s / 60 % 60,
                                                        s % 60,
                                                        p.title);
                                    }
                                    new AlertDialog.Builder(c)
                                            .setTitle(label("VODチャプター", "VOD chapters"))
                                            .setItems(
                                                    titles,
                                                    (d, i) -> {
                                                        if (id.equals(PlayerTools.vodId()))
                                                            PlayerTools.run(
                                                                    c,
                                                                    () ->
                                                                            PlayerTools.seek(
                                                                                    chapters.get(i)
                                                                                            .millis));
                                                    })
                                            .show();
                                });
                    } catch (Exception e) {
                        PublicApi.UI.post(
                                () -> {
                                    if (wait.isShowing())
                                        status.setText(
                                                label("チャプターを取得できません", "Unable to load chapters"));
                                });
                    }
                })) status.setText(label("取得待ちが多いため再度お試しください", "Please try again later"));
    }

    public static List<Chapter> parse(JSONObject response) throws JSONException {
        if (response.has("errors")) throw new JSONException("Metadata API rejected query");
        List<Chapter> result = new ArrayList<>();
        JSONObject data = response.optJSONObject("data"),
                video = data == null ? null : data.optJSONObject("video"),
                moments = video == null ? null : video.optJSONObject("moments");
        JSONArray edges = moments == null ? null : moments.optJSONArray("edges");
        if (edges != null)
            for (int i = 0; i < edges.length() && i < 1000; i++) {
                JSONObject edge = edges.optJSONObject(i),
                        n = edge == null ? null : edge.optJSONObject("node");
                if (n == null) continue;
                long position = n.optLong("positionMilliseconds", -1);
                if (position >= 0)
                    result.add(new Chapter(position, n.optString("description", "")));
            }
        result.sort(Comparator.comparingLong(p -> p.millis));
        return result;
    }

    public static final class Chapter {
        public final long millis;
        public final String title;

        Chapter(long p, String t) {
            millis = p;
            title = t;
        }
    }
}
