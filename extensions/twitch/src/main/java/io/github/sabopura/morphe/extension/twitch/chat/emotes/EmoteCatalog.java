// Adapted from Hooman's patches, GPL-3.0; see NOTICE for source and revision.
package io.github.sabopura.morphe.extension.twitch.chat.emotes;

import android.content.Context;

import io.github.sabopura.morphe.extension.twitch.chat.ChatSettings;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

final class EmoteCatalog {
    private static final long CACHE_TTL_MS = 6L * 60L * 60L * 1000L;
    private static final long RETRY_BACKOFF_MS = 5L * 60L * 1000L;
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    private static final int MAX_CHANNEL_CATALOGS = 32;
    private static final String USER_AGENT = "SabopuraChat/1.0";

    private final Object channelCacheLock = new Object();
    private final LinkedHashMap<String, ChannelState> channels =
            new LinkedHashMap<>(16, 0.75f, true);
    private final ProviderState globalSevenTv = new ProviderState();
    private final ProviderState globalBetterTtv = new ProviderState();
    private final ProviderState globalFfz = new ProviderState();
    private final ThreadPoolExecutor executor =
            new ThreadPoolExecutor(
                    2,
                    2,
                    0L,
                    TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<>(64),
                    new DaemonThreadFactory());
    private final Consumer<String> onUpdated;

    EmoteCatalog(Consumer<String> onUpdated) {
        this.onUpdated = onUpdated;
    }

    void ensureLoaded(Context context, String channelId) {
        Context applicationContext = context.getApplicationContext();
        long now = System.currentTimeMillis();
        ChatSettings settings = ChatSettings.get(context);
        if (settings.sevenTv)
            schedule(globalSevenTv, now, () -> loadGlobalSevenTv(applicationContext));
        if (settings.bttv)
            schedule(globalBetterTtv, now, () -> loadGlobalBetterTtv(applicationContext));
        if (settings.ffz)
            schedule(globalFfz, now, () -> loadFfz(applicationContext, null, globalFfz));

        if (channelId == null) {
            return;
        }
        ChannelState channel = getChannel(channelId, true);
        if (settings.sevenTv)
            schedule(
                    channel.sevenTv,
                    now,
                    () -> loadChannelSevenTv(applicationContext, channelId, channel));
        if (settings.bttv)
            schedule(
                    channel.betterTtv,
                    now,
                    () -> loadChannelBetterTtv(applicationContext, channelId, channel));
        if (settings.ffz)
            schedule(channel.ffz, now, () -> loadFfz(applicationContext, channelId, channel.ffz));
    }

    Emote find(Context context, String channelId, String name) {
        ChatSettings settings = ChatSettings.get(context);
        if (channelId != null) {
            ChannelState channel = getChannel(channelId, false);
            if (channel != null) {
                Emote emote = settings.sevenTv ? channel.sevenTv.emotes.get(name) : null;
                if (emote != null) {
                    return emote;
                }
                emote = settings.bttv ? channel.betterTtv.emotes.get(name) : null;
                if (emote != null) {
                    return emote;
                }
                emote = settings.ffz ? channel.ffz.emotes.get(name) : null;
                if (emote != null) return emote;
            }
        }
        Emote emote = settings.sevenTv ? globalSevenTv.emotes.get(name) : null;
        if (emote == null && settings.bttv) emote = globalBetterTtv.emotes.get(name);
        if (emote == null && settings.ffz) emote = globalFfz.emotes.get(name);
        return emote;
    }

    java.util.List<String> names(Context context, String channelId) {
        ensureLoaded(context, channelId);
        java.util.Set<String> names = new java.util.TreeSet<>();
        ChatSettings s = ChatSettings.get(context);
        if (s.sevenTv) names.addAll(globalSevenTv.emotes.keySet());
        if (s.bttv) names.addAll(globalBetterTtv.emotes.keySet());
        if (s.ffz) names.addAll(globalFfz.emotes.keySet());
        ChannelState c = channelId == null ? null : getChannel(channelId, false);
        if (c != null) {
            if (s.sevenTv) names.addAll(c.sevenTv.emotes.keySet());
            if (s.bttv) names.addAll(c.betterTtv.emotes.keySet());
            if (s.ffz) names.addAll(c.ffz.emotes.keySet());
        }
        return new java.util.ArrayList<>(names);
    }

    private void loadFfz(Context context, String channelId, ProviderState state) {
        boolean updated = false;
        try {
            LoadedValue<JSONObject> response =
                    loadOptionalJson(
                            context,
                            channelId == null ? "ffz-global" : "ffz-channel-" + channelId,
                            "https://api.frankerfacez.com/v1/"
                                    + (channelId == null ? "set/global" : "room/id/" + channelId));
            Map<String, Emote> loaded = new LinkedHashMap<>();
            parseFfz(response.value, loaded, channelId == null);
            state.publish(loaded, response.fresh);
            updated = true;
        } catch (Exception ignored) {
            state.failed();
        } finally {
            state.loading.set(false);
        }
        if (updated) onUpdated.accept(channelId);
    }

    static void parseFfz(JSONObject root, Map<String, Emote> target, boolean global) {
        JSONObject sets = root.optJSONObject("sets");
        if (sets == null) return;
        JSONArray defaults = root.optJSONArray("default_sets");
        java.util.Iterator<String> keys = sets.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (global) {
                boolean selected = false;
                if (defaults != null)
                    for (int i = 0; i < defaults.length(); i++)
                        if (key.equals(defaults.optString(i))) selected = true;
                if (!selected) continue;
            }
            JSONObject set = sets.optJSONObject(key);
            JSONArray emotes = set == null ? null : set.optJSONArray("emoticons");
            if (emotes == null) continue;
            for (int i = 0; i < emotes.length(); i++) {
                JSONObject emote = emotes.optJSONObject(i);
                if (emote == null) continue;
                JSONObject animated = emote.optJSONObject("animated");
                JSONObject urls = animated != null ? animated : emote.optJSONObject("urls");
                String name = emote.optString("name", "");
                String url = urls == null ? "" : urls.optString("2", urls.optString("1", ""));
                if (url.startsWith("//")) url = "https:" + url;
                if (!name.isEmpty() && EmoteUrls.allowed(url))
                    target.put(name, new Emote(name, url, animated != null));
            }
        }
    }

    private void schedule(ProviderState state, long now, Runnable load) {
        if (!state.needsRefresh(now) || !state.loading.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.execute(load);
        } catch (RejectedExecutionException ignored) {
            state.loading.set(false);
            state.retryAfter = now + RETRY_BACKOFF_MS;
        }
    }

    private void loadGlobalSevenTv(Context context) {
        boolean updated = false;
        try {
            LoadedValue<JSONObject> response =
                    loadJson(context, "7tv-global", "https://7tv.io/v3/emote-sets/global");
            Map<String, Emote> loaded = new LinkedHashMap<>();
            parseSevenTv(response.value, loaded);
            globalSevenTv.publish(loaded, response.fresh);
            updated = true;
        } catch (Exception ignored) {
            globalSevenTv.failed();
        } finally {
            globalSevenTv.loading.set(false);
        }
        if (updated) {
            onUpdated.accept(null);
        }
    }

    private void loadGlobalBetterTtv(Context context) {
        boolean updated = false;
        try {
            LoadedValue<JSONArray> response =
                    loadJsonArray(
                            context,
                            "bttv-global",
                            "https://api.betterttv.net/3/cached/emotes/global");
            Map<String, Emote> loaded = new LinkedHashMap<>();
            parseBetterTtvArray(response.value, loaded);
            globalBetterTtv.publish(loaded, response.fresh);
            updated = true;
        } catch (Exception ignored) {
            globalBetterTtv.failed();
        } finally {
            globalBetterTtv.loading.set(false);
        }
        if (updated) {
            onUpdated.accept(null);
        }
    }

    private void loadChannelSevenTv(Context context, String channelId, ChannelState channel) {
        boolean updated = false;
        try {
            LoadedValue<JSONObject> response =
                    loadOptionalJson(
                            context,
                            "7tv-channel-" + channelId,
                            "https://7tv.io/v3/users/twitch/" + channelId);
            Map<String, Emote> loaded = new LinkedHashMap<>();
            JSONObject set = response.value.optJSONObject("emote_set");
            if (set != null) {
                parseSevenTv(set, loaded);
            }
            channel.sevenTv.publish(loaded, response.fresh);
            updated = true;
        } catch (Exception ignored) {
            channel.sevenTv.failed();
        } finally {
            channel.sevenTv.loading.set(false);
        }
        if (updated) {
            onUpdated.accept(channelId);
        }
    }

    private void loadChannelBetterTtv(Context context, String channelId, ChannelState channel) {
        boolean updated = false;
        try {
            LoadedValue<JSONObject> response =
                    loadOptionalJson(
                            context,
                            "bttv-channel-" + channelId,
                            "https://api.betterttv.net/3/cached/users/twitch/" + channelId);
            Map<String, Emote> loaded = new LinkedHashMap<>();
            parseBetterTtvArray(response.value.optJSONArray("channelEmotes"), loaded);
            parseBetterTtvArray(response.value.optJSONArray("sharedEmotes"), loaded);
            channel.betterTtv.publish(loaded, response.fresh);
            updated = true;
        } catch (Exception ignored) {
            channel.betterTtv.failed();
        } finally {
            channel.betterTtv.loading.set(false);
        }
        if (updated) {
            onUpdated.accept(channelId);
        }
    }

    private ChannelState getChannel(String channelId, boolean create) {
        synchronized (channelCacheLock) {
            ChannelState channel = channels.get(channelId);
            if (channel == null && create) {
                channel = new ChannelState();
                channels.put(channelId, channel);
                while (channels.size() > MAX_CHANNEL_CATALOGS) {
                    String oldest = channels.keySet().iterator().next();
                    channels.remove(oldest);
                }
            }
            return channel;
        }
    }

    private static void parseSevenTv(JSONObject root, Map<String, Emote> target)
            throws JSONException {
        JSONArray emotes = root.optJSONArray("emotes");
        if (emotes == null) {
            return;
        }
        for (int index = 0; index < emotes.length(); index++) {
            JSONObject item = emotes.optJSONObject(index);
            if (item == null) {
                continue;
            }
            String name = item.optString("name", "");
            JSONObject data = item.optJSONObject("data");
            JSONObject host = data == null ? null : data.optJSONObject("host");
            String hostUrl = host == null ? "" : host.optString("url", "");
            JSONArray files = host == null ? null : host.optJSONArray("files");
            String fileName = chooseSevenTvFile(files);
            if (name.isEmpty() || hostUrl.isEmpty() || fileName == null) {
                continue;
            }
            if (hostUrl.startsWith("//")) {
                hostUrl = "https:" + hostUrl;
            } else if (!hostUrl.startsWith("http://") && !hostUrl.startsWith("https://")) {
                hostUrl = "https://" + hostUrl;
            }
            String url = hostUrl.endsWith("/") ? hostUrl + fileName : hostUrl + "/" + fileName;
            if (EmoteUrls.allowed(url))
                target.put(
                        name,
                        new Emote(
                                name,
                                url,
                                data != null && data.optBoolean("animated", false),
                                (item.optInt("flags", 0) & 1) != 0));
        }
    }

    private static String chooseSevenTvFile(JSONArray files) {
        if (files == null) {
            return null;
        }
        String fallback = null;
        for (int index = 0; index < files.length(); index++) {
            JSONObject file = files.optJSONObject(index);
            if (file == null || !"WEBP".equalsIgnoreCase(file.optString("format"))) {
                continue;
            }
            String name = file.optString("name", "");
            if (name.isEmpty()) {
                continue;
            }
            if ("2x.webp".equalsIgnoreCase(name)) {
                return name;
            }
            fallback = name;
        }
        return fallback;
    }

    private static void parseBetterTtvArray(JSONArray emotes, Map<String, Emote> target) {
        if (emotes == null) {
            return;
        }
        for (int index = 0; index < emotes.length(); index++) {
            JSONObject item = emotes.optJSONObject(index);
            if (item == null) {
                continue;
            }
            String id = item.optString("id", "");
            String name = item.optString("code", "");
            if (!id.matches("[a-zA-Z0-9]+") || name.isEmpty()) {
                continue;
            }
            target.put(
                    name,
                    new Emote(
                            name,
                            "https://cdn.betterttv.net/emote/" + id + "/2x.webp",
                            item.optBoolean("animated", false)));
        }
    }

    private static LoadedValue<JSONObject> loadJson(Context context, String cacheKey, String url)
            throws IOException, JSONException {
        LoadedValue<String> text = loadText(context, cacheKey, url, false);
        try {
            return new LoadedValue<>(new JSONObject(text.value), text.fresh);
        } catch (JSONException failure) {
            deleteCachedText(context, cacheKey);
            throw failure;
        }
    }

    private static LoadedValue<JSONObject> loadOptionalJson(
            Context context, String cacheKey, String url) throws IOException, JSONException {
        LoadedValue<String> text = loadText(context, cacheKey, url, true);
        try {
            return new LoadedValue<>(new JSONObject(text.value), text.fresh);
        } catch (JSONException failure) {
            deleteCachedText(context, cacheKey);
            throw failure;
        }
    }

    private static LoadedValue<JSONArray> loadJsonArray(
            Context context, String cacheKey, String url) throws IOException, JSONException {
        LoadedValue<String> text = loadText(context, cacheKey, url, false);
        try {
            return new LoadedValue<>(new JSONArray(text.value), text.fresh);
        } catch (JSONException failure) {
            deleteCachedText(context, cacheKey);
            throw failure;
        }
    }

    private static void deleteCachedText(Context context, String cacheKey) {
        File cache =
                new File(
                        new File(context.getCacheDir(), "morphe_emote_catalog"),
                        cacheKey + ".json");
        //noinspection ResultOfMethodCallIgnored
        cache.delete();
    }

    private static LoadedValue<String> loadText(
            Context context, String cacheKey, String url, boolean allowNotFound)
            throws IOException {
        File directory = new File(context.getCacheDir(), "morphe_emote_catalog");
        File cache = new File(directory, cacheKey + ".json");
        long now = System.currentTimeMillis();
        if (cache.isFile() && now - cache.lastModified() <= CACHE_TTL_MS) {
            try {
                return new LoadedValue<>(readText(cache), true);
            } catch (IOException ignored) {
                //noinspection ResultOfMethodCallIgnored
                cache.delete();
            }
        }

        try {
            String result = request(url, allowNotFound);
            if (directory.isDirectory() || directory.mkdirs()) {
                writeText(cache, result);
            }
            return new LoadedValue<>(result, true);
        } catch (IOException failure) {
            if (cache.isFile()) {
                return new LoadedValue<>(readText(cache), false);
            }
            throw failure;
        }
    }

    private static String request(String url, boolean allowNotFound) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(7_000);
        connection.setReadTimeout(10_000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", USER_AGENT);
        try {
            int status = connection.getResponseCode();
            if (allowNotFound && status == HttpURLConnection.HTTP_NOT_FOUND) {
                return "{}";
            }
            if (status < 200 || status >= 300) {
                throw new IOException("HTTP " + status + " for " + url);
            }
            try (InputStream input = connection.getInputStream()) {
                return new String(readLimited(input, MAX_RESPONSE_BYTES), StandardCharsets.UTF_8);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static String readText(File file) throws IOException {
        try (InputStream input = new FileInputStream(file)) {
            return new String(readLimited(input, MAX_RESPONSE_BYTES), StandardCharsets.UTF_8);
        }
    }

    private static byte[] readLimited(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8_192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > limit) {
                throw new IOException("Response exceeds " + limit + " bytes");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static synchronized void writeText(File file, String text) throws IOException {
        File temporary = new File(file.getParentFile(), file.getName() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(text.getBytes(StandardCharsets.UTF_8));
        }
        if (!temporary.renameTo(file)) {
            try (FileOutputStream output = new FileOutputStream(file)) {
                output.write(text.getBytes(StandardCharsets.UTF_8));
            }
            //noinspection ResultOfMethodCallIgnored
            temporary.delete();
        }
        File[] entries = file.getParentFile().listFiles((dir, name) -> name.endsWith(".json"));
        if (entries != null) {
            java.util.Arrays.sort(entries, java.util.Comparator.comparingLong(File::lastModified));
            long bytes = 0;
            for (File entry : entries) bytes += entry.length();
            int count = entries.length;
            for (File entry : entries) {
                if (bytes <= 32L * 1024 * 1024 && count <= 100) break;
                long size = entry.length();
                if (entry.delete()) {
                    bytes -= size;
                    count--;
                }
            }
        }
    }

    private static final class ProviderState {
        final AtomicBoolean loading = new AtomicBoolean();
        volatile Map<String, Emote> emotes = Collections.emptyMap();
        volatile long loadedAt;
        volatile long retryAfter;

        boolean needsRefresh(long now) {
            return now >= retryAfter && (loadedAt == 0L || now - loadedAt >= CACHE_TTL_MS);
        }

        void publish(Map<String, Emote> loaded, boolean fresh) {
            long now = System.currentTimeMillis();
            emotes = Collections.unmodifiableMap(loaded);
            loadedAt = fresh ? now : 0L;
            retryAfter = fresh ? 0L : now + RETRY_BACKOFF_MS;
        }

        void failed() {
            retryAfter = System.currentTimeMillis() + RETRY_BACKOFF_MS;
        }
    }

    private static final class ChannelState {
        final ProviderState sevenTv = new ProviderState();
        final ProviderState betterTtv = new ProviderState();
        final ProviderState ffz = new ProviderState();
    }

    private static final class LoadedValue<T> {
        final T value;
        final boolean fresh;

        LoadedValue(T value, boolean fresh) {
            this.value = value;
            this.fresh = fresh;
        }
    }

    private static final class DaemonThreadFactory implements ThreadFactory {
        private int nextId;

        @Override
        public synchronized Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "morphe-emote-catalog-" + nextId++);
            thread.setDaemon(true);
            return thread;
        }
    }
}
