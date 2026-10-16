package io.github.sabopura.deviceprobe;

import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * Plays real streams with Twitch's IVS player inside the patched app and checks the playback
 * speed tools: which player they act on, VOD speed, the live catch-up, and the reset on load.
 */
public class SpeedProbe extends Instrumentation {
    private static final String TAG = "SpeedProbe";
    private final StringBuilder report = new StringBuilder();
    private int failed;
    private ClassLoader app;
    private Class<?> tools;

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override
    public void onStart() {
        Bundle result = new Bundle();
        Object[] players = new Object[2];
        try {
            Context target = getTargetContext();
            app = target.getClassLoader();
            tools = app.loadClass("io.github.sabopura.morphe.extension.twitch.player.PlayerTools");
            tools.getMethod("initialize", Context.class).invoke(null, target);
            for (int i = 0; i < 60; i++) {
                try {
                    java.net.InetAddress.getByName("gql.twitch.tv");
                    break;
                } catch (java.net.UnknownHostException e) {
                    Thread.sleep(1000);
                }
            }
            String[] logins = channels();
            java.util.List<String> vods = new java.util.ArrayList<>();
            for (String login : logins) {
                String id = vodOf(login);
                if (id != null) vods.add(id);
                if (vods.size() == 2) break;
            }
            String vod = vods.get(0), otherVod = vods.get(1);

            // A theatre VOD player with sound, then a muted preview that starts later.
            Object theatre = players[0] = start(vodUrl(vod), false);
            Object preview = players[1] = start(liveUrl(logins[logins.length - 1]), true);
            check(waitPlaying(theatre) && waitPlaying(preview), "both players play");
            check(vod.equals(onMain("vodId")), "the VOD on screen is known: " + onMain("vodId"));
            speed(1.5f);
            Thread.sleep(800);
            check(rate(theatre) == 1.5f && rate(preview) == 1f,
                    "speed goes to the audible theatre player, not the newer muted preview ("
                            + rate(theatre) + " / " + rate(preview) + ")");
            long p0 = (Long) call(theatre, "getPosition");
            long t0 = System.currentTimeMillis();
            Thread.sleep(5000);
            double advance = ((Long) call(theatre, "getPosition") - p0) / (double) (System.currentTimeMillis() - t0);
            check(advance > 1.35 && advance < 1.65, "the VOD plays at 1.5x ("
                    + String.format(java.util.Locale.ROOT, "%.2f", advance) + "x)");
            main(() -> call(theatre, "pause"));
            Thread.sleep(800);
            speed(1.25f);
            Thread.sleep(500);
            check(rate(theatre) == 1.25f && rate(preview) == 1f, "still the theatre player while it is paused");
            main(() -> call(theatre, "play"));

            // Reloading the same VOD keeps the speed; another VOD starts at 1x.
            load(theatre, vodUrl(vod));
            Thread.sleep(3000);
            check(rate(theatre) == 1.25f, "reloading the same VOD keeps 1.25x (" + rate(theatre) + ")");
            load(theatre, vodUrl(otherVod));
            Thread.sleep(3000);
            check(rate(theatre) == 1f, "another VOD starts at 1x (" + rate(theatre) + ")");
            release(theatre);
            players[0] = null;
            check("".equals(onMain("vodId")), "with only the live player left, no VOD tools: \"" + onMain("vodId") + "\"");

            // Live: at the live edge faster is refused; after a pause it catches up, then 1x.
            main(() -> call(preview, "setMuted", new Class<?>[]{boolean.class}, false));
            Thread.sleep(1500);
            long ahead = (Long) call(preview, "getBufferedPosition") - (Long) call(preview, "getPosition");
            int code = (Integer) tools.getMethod("speed", float.class).invoke(null, 2f);
            if (code == 1) {
                check(rate(preview) == 1f, "at the live edge 2x is refused (" + ahead + " ms loaded ahead)");
            } else {
                // A stream that keeps more loaded ahead only gets a short catch-up.
                long began = System.currentTimeMillis();
                while (rate(preview) > 1f && System.currentTimeMillis() - began < 15000) Thread.sleep(250);
                check(code == 2 && rate(preview) == 1f, "near the live edge 2x only catches up briefly ("
                        + ahead + " ms ahead, back to 1x in " + (System.currentTimeMillis() - began) + " ms)");
            }
            // Slow motion builds up a delay (a paused live stream resumes at the live edge).
            code = (Integer) tools.getMethod("speed", float.class).invoke(null, .5f);
            Thread.sleep(500);
            check(code == 0 && rate(preview) == .5f, "slower than 1x works on live");
            Thread.sleep(10000);
            long behind = (Long) call(preview, "getLiveLatency");
            line("INFO after slow motion: latency " + behind + " ms, loaded ahead "
                    + ((Long) call(preview, "getBufferedPosition") - (Long) call(preview, "getPosition")) + " ms");
            code = (Integer) tools.getMethod("speed", float.class).invoke(null, 2f);
            check(code == 2 && rate(preview) == 2f, "after 0.5x for 10 s, behind live (" + behind + " ms) 2x catches up (code " + code + ")");
            long started = System.currentTimeMillis();
            while (rate(preview) > 1f && System.currentTimeMillis() - started < 30000) Thread.sleep(250);
            long took = System.currentTimeMillis() - started;
            check(rate(preview) == 1f, "back to 1x after catching up in " + took + " ms, latency now "
                    + call(preview, "getLiveLatency") + " ms, state " + call(preview, "getState"));

            release(preview);
            players[1] = null;
            check(!(Boolean) tools.getMethod("hasPlayer").invoke(null), "released players are forgotten");
        } catch (Throwable t) {
            fail("crash " + t + " " + Log.getStackTraceString(t));
        } finally {
            for (Object p : players) {
                try {
                    if (p != null) release(p);
                } catch (Exception ignored) {
                }
            }
        }
        line("RESULT failed=" + failed);
        result.putString(Instrumentation.REPORT_KEY_STREAMRESULT, report.toString());
        finish(0, result);
    }

    interface Action {
        Object run() throws Exception;
    }

    private void main(Action action) throws Exception {
        Exception[] error = new Exception[1];
        runOnMainSync(() -> {
            try {
                action.run();
            } catch (Exception e) {
                error[0] = e;
            }
        });
        if (error[0] != null) throw error[0];
    }

    private Object onMain(String method) throws Exception {
        Object[] out = new Object[1];
        main(() -> out[0] = tools.getMethod(method).invoke(null));
        return out[0];
    }

    private void speed(float value) throws Exception {
        tools.getMethod("speed", float.class).invoke(null, value);
    }

    private Object start(String url, boolean muted) throws Exception {
        Class<?> builderClass = app.loadClass("com.amazonaws.ivs.player.MediaPlayer$Builder");
        Object[] player = new Object[1];
        main(() -> {
            Object builder = builderClass.getConstructor(Context.class).newInstance(getTargetContext());
            Object p = builderClass.getMethod("build").invoke(builder);
            p.getClass().getMethod("setMuted", boolean.class).invoke(p, muted);
            player[0] = p;
            return null;
        });
        load(player[0], url);
        return player[0];
    }

    private void load(Object p, String url) throws Exception {
        Class<?> optionsClass = app.loadClass("com.amazonaws.ivs.player.MediaPlayer$LoadOptions");
        main(() -> {
            Object options = optionsClass.getConstructor(android.net.Uri.class).newInstance(android.net.Uri.parse(url));
            optionsClass.getMethod("setMediaType", String.class).invoke(options, "application/x-mpegURL");
            try {
                // As Twitch 31.5.2 configures live playback; older IVS builds lack the option.
                Class<?> sc = app.loadClass("com.amazonaws.ivs.player.SessionConfig");
                Object config = sc.getConstructor().newInstance();
                sc.getMethod("setLiveCatchUpSpeedUpEnabled", boolean.class).invoke(config, true);
                optionsClass.getMethod("setSessionConfig", sc).invoke(options, config);
            } catch (NoSuchMethodException | ClassNotFoundException ignored) {
            }
            p.getClass().getMethod("load", optionsClass).invoke(p, options);
            p.getClass().getMethod("play").invoke(p);
            return null;
        });
    }

    private void release(Object p) throws Exception {
        main(() -> call(p, "release"));
    }

    private boolean waitPlaying(Object p) throws Exception {
        for (int i = 0; i < 40; i++) {
            if ("PLAYING".equals(String.valueOf(call(p, "getState")))) return true;
            Thread.sleep(500);
        }
        return false;
    }

    private float rate(Object p) throws Exception {
        return (Float) call(p, "getPlaybackRate");
    }

    private Object call(Object target, String name) throws Exception {
        return call(target, name, new Class<?>[0]);
    }

    private Object call(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        return app.loadClass("com.amazonaws.ivs.player.Player").getMethod(name, types).invoke(target, args);
    }

    private static String[] channels() throws Exception {
        JSONObject d = new JSONObject(gql("kimne78kx3ncx6brgo4mv6wki5h1ko", new JSONObject().put("query",
                "query{streams(first:12){edges{node{broadcaster{login}}}}}").toString()));
        JSONArray edges = d.getJSONObject("data").getJSONObject("streams").getJSONArray("edges");
        String[] out = new String[edges.length()];
        for (int i = 0; i < out.length; i++)
            out[i] = edges.getJSONObject(i).getJSONObject("node").getJSONObject("broadcaster").getString("login");
        return out;
    }

    private static String vodOf(String login) throws Exception {
        JSONObject d = new JSONObject(gql("kimne78kx3ncx6brgo4mv6wki5h1ko", new JSONObject().put("query",
                "query($l:String!){user(login:$l){videos(first:3,type:ARCHIVE){edges{node{id}}}}}")
                .put("variables", new JSONObject().put("l", login)).toString()));
        JSONArray edges = d.getJSONObject("data").getJSONObject("user").getJSONObject("videos").getJSONArray("edges");
        if (edges.length() == 0) return null;
        return edges.getJSONObject(Math.min(1, edges.length() - 1)).getJSONObject("node").getString("id");
    }

    private static String liveUrl(String login) throws Exception {
        String q = "query($login:String!){streamPlaybackAccessToken(channelName:$login,params:{platform:\"android\","
                + "playerBackend:\"mediaplayer\",playerType:\"mobile_player\"}){value signature}}";
        JSONObject t = new JSONObject(gql("kd1unb4b3q4t58fwlpcbzcbnm76a8fp", new JSONObject().put("query", q)
                .put("variables", new JSONObject().put("login", login)).toString()))
                .getJSONObject("data").getJSONObject("streamPlaybackAccessToken");
        return "https://usher.ttvnw.net/api/channel/hls/" + login + ".m3u8?allow_source=true&allow_audio_only=true"
                + "&fast_bread=true&player_backend=mediaplayer&p=" + (int) (Math.random() * 1e6)
                + "&sig=" + enc(t.getString("signature")) + "&token=" + enc(t.getString("value"));
    }

    private static String vodUrl(String id) throws Exception {
        String q = "query($id:ID!){videoPlaybackAccessToken(id:$id,params:{platform:\"android\","
                + "playerBackend:\"mediaplayer\",playerType:\"mobile_player\"}){value signature}}";
        JSONObject t = new JSONObject(gql("kd1unb4b3q4t58fwlpcbzcbnm76a8fp", new JSONObject().put("query", q)
                .put("variables", new JSONObject().put("id", id)).toString()))
                .getJSONObject("data").getJSONObject("videoPlaybackAccessToken");
        return "https://usher.ttvnw.net/vod/" + id + ".m3u8?allow_source=true&allow_audio_only=true"
                + "&player_backend=mediaplayer&p=" + (int) (Math.random() * 1e6)
                + "&nauthsig=" + enc(t.getString("signature")) + "&nauth=" + enc(t.getString("value"));
    }

    private static String gql(String client, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("https://gql.twitch.tv/gql").openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Client-ID", client);
        c.setRequestProperty("Content-Type", "application/json");
        try (OutputStream out = c.getOutputStream()) {
            out.write(body.getBytes("UTF-8"));
        }
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            return out.toString("UTF-8");
        }
    }

    private static String enc(String s) throws Exception {
        return URLEncoder.encode(s, "UTF-8");
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
