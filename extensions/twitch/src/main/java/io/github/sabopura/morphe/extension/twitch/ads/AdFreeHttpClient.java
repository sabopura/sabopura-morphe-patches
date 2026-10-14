package io.github.sabopura.morphe.extension.twitch.ads;

import com.amazonaws.ivs.net.HttpClient;
import com.amazonaws.ivs.net.Method;
import com.amazonaws.ivs.net.ReadCallback;
import com.amazonaws.ivs.net.Request;
import com.amazonaws.ivs.net.Response;
import com.amazonaws.ivs.net.ResponseCallback;
import com.amazonaws.ivs.net.StreamConsumer;
import io.github.sabopura.morphe.extension.twitch.player.LiveProxy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Wraps the HTTP client of Amazon IVS, the player behind both Twitch's native and React Native
 * screens. Live playlists with a stitched ad are answered with the same rendition from an
 * ad-free backup session; nothing extra is requested while there is no ad.
 */
public final class AdFreeHttpClient implements HttpClient {
    private static final int BODY_LIMIT = 1024 * 1024;
    /** How long a playlist answer may wait for a backup, so playback never stalls for long. */
    private static final long BACKUP_BUDGET_MS = 4000;
    /** The first playlist of a stream: the player shows its loading state meanwhile. */
    private static final long FIRST_BUDGET_MS = 6000;
    /** Opening the backup in the background when a stream starts. */
    private static final long WARM_UP_MS = 10000;
    private static final int MAX_SESSIONS = 4;

    private final HttpClient original;
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(3, 3, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32), task -> {
                Thread t = new Thread(task, "SabopuraTwitchPlaylist");
                t.setDaemon(true);
                return t;
            });
    /** Live sessions by master playlist URL, oldest first. */
    private final Map<String, Session> sessions = new LinkedHashMap<>();
    private volatile boolean released;

    /** Injection point: the return value of HttpClientFactory.create(). */
    public static HttpClient wrap(HttpClient original) {
        return original == null || original instanceof AdFreeHttpClient ? original : new AdFreeHttpClient(original);
    }

    private AdFreeHttpClient(HttpClient original) {
        this.original = original;
        worker.allowCoreThreadTimeOut(true);
    }

    @Override
    public String description() {
        return original.description();
    }

    @Override
    public void release() {
        released = true;
        worker.shutdownNow();
        synchronized (sessions) {
            sessions.clear();
        }
        original.release();
    }

    @Override
    public void execute(Request request, ResponseCallback callback) {
        if (released || !AdBlock.enabled() || request.getMethod() != Method.GET
                || !(Playlists.isTwitchPlaylist(request.getUrl()) || LiveProxy.originalOf(request.getUrl()) != null)) {
            original.execute(request, callback);
            return;
        }
        original.execute(request, new ResponseCallback() {
            @Override
            public void onError(Exception error) {
                fail(request, callback, error);
            }

            @Override
            public void onResponse(Response response) {
                if (response.getStatus() != 200) {
                    deliver(request, callback, response);
                    return;
                }
                response.readContent(new Body() {
                    @Override
                    void complete(byte[] body) {
                        Runnable task = () -> {
                            if (request.isCancelled()) return;
                            byte[] out = body;
                            try {
                                String text = new String(body, StandardCharsets.UTF_8);
                                String changed = rewrite(request, response.getUrl(), text);
                                if (changed != null) out = changed.getBytes(StandardCharsets.UTF_8);
                            } catch (RuntimeException e) {
                                AdBlock.log("Playlist kept as is: " + e);
                            }
                            deliver(request, callback, copy(request, response, out));
                        };
                        try {
                            worker.execute(task);
                        } catch (RejectedExecutionException e) {
                            deliver(request, callback, copy(request, response, body));
                        }
                    }

                    @Override
                    void failed(Exception error) {
                        fail(request, callback, error);
                    }
                });
            }
        });
    }

    /** The playlist to give the player instead, or null to keep the original bytes. */
    private String rewrite(Request request, String responseUrl, String text) {
        String url = request.getUrl();
        if (Playlists.isMaster(text)) {
            // With the stream proxy on, the player asks the proxy; backups still need Twitch's URL.
            String usher = Playlists.channel(url) != null ? url : LiveProxy.originalOf(url);
            String login = usher == null ? null : Playlists.channel(usher);
            if (login != null) {
                List<Playlists.Variant> variants = Playlists.variants(text, responseUrl);
                Session session = new Session(login, url, usher, variants, request.getHeaders().get("User-Agent"));
                register(session);
                warmUp(session);
                AdBlock.log("Live session for " + login + ": " + variants.size() + " renditions"
                        + (usher.equals(url) ? "" : " (through the stream proxy)"));
            }
            return null;
        }
        Session session = sessionOf(url);
        if (session == null) {
            if (Playlists.hasStitchedAd(text)) AdBlock.issue("No session for an ad playlist: " + Playlists.describe(url));
            return null;
        }
        return session.media(request, text);
    }

    /**
     * Opens the backup session in the background while the stream starts, so that an ad only
     * costs one playlist request when it comes. Two small requests per stream opened.
     */
    private void warmUp(Session session) {
        try {
            worker.execute(session::warmUp);
        } catch (RejectedExecutionException ignored) {
        }
    }

    private void register(Session session) {
        synchronized (sessions) {
            sessions.remove(session.key);
            sessions.put(session.key, session);
            Iterator<String> oldest = sessions.keySet().iterator();
            while (sessions.size() > MAX_SESSIONS && oldest.hasNext()) {
                oldest.next();
                oldest.remove();
            }
        }
    }

    private Session sessionOf(String mediaUrl) {
        synchronized (sessions) {
            for (Session s : sessions.values()) if (s.variantOf(mediaUrl) != null) return s;
        }
        return null;
    }

    /** One live stream as the player loaded it, and its backup session once an ad was seen. */
    private final class Session {
        final String login;
        /** The master playlist URL the player loaded. */
        final String key;
        /** Twitch's usher URL for the stream, the template for backup tokens. */
        final String usherUrl;
        final List<Playlists.Variant> variants;
        private volatile List<Playlists.Variant> backupVariants;
        private volatile long backupExpires;
        private volatile int backupIndex = -1;
        private final StreamSwitch streamSwitch = new StreamSwitch();
        private final String userAgent;
        private long lastAdLog;
        private boolean firstMedia = true;

        Session(String login, String key, String usherUrl, List<Playlists.Variant> variants, String userAgent) {
            this.login = login;
            this.key = key;
            this.usherUrl = usherUrl;
            this.variants = variants;
            this.userAgent = userAgent;
        }

        /** Not synchronized: a slow warm-up must not hold up the player's first playlist. */
        void warmUp() {
            // Only the first source: it serves full quality and was ad-free in every measurement.
            if (backupVariants != null || released) return;
            openBackup(0, System.currentTimeMillis() + WARM_UP_MS);
        }

        /** The rendition of a media playlist URL; the player may change the query or the edge host. */
        Playlists.Variant variantOf(String url) {
            for (Playlists.Variant v : variants) if (v.uri.equals(url)) return v;
            String path = Playlists.path(url);
            if (path == null) return null;
            for (Playlists.Variant v : variants) if (path.equals(Playlists.path(v.uri))) return v;
            return null;
        }

        synchronized String media(Request request, String primary) {
            Playlists.Variant wanted = variantOf(request.getUrl());
            // The player waits for its first playlist anyway; later it plays from its buffer.
            long deadline = System.currentTimeMillis() + (firstMedia ? FIRST_BUDGET_MS : BACKUP_BUDGET_MS);
            firstMedia = false;
            String out = streamSwitch.choose(primary, () -> wanted == null ? null : backup(request, wanted, deadline));
            switch (streamSwitch.lastOutcome()) {
                case SWITCHED_TO_BACKUP:
                    AdBlock.count(AdBlock.REPLACED);
                    AdBlock.log("Ad replaced for " + login + " from " + BackupSessions.sourceName(backupIndex));
                    break;
                case BACK_TO_ORIGINAL:
                    AdBlock.log("Back to the original session of " + login);
                    break;
                case AD_KEPT:
                    long now = System.currentTimeMillis();
                    if (now - lastAdLog > 10_000) {
                        lastAdLog = now;
                        AdBlock.log("No ad-free backup for " + login + "; the ad stays");
                    }
                    break;
                default:
                    break;
            }
            return out;
        }

        /** The matching rendition of a clean backup, trying the sources in order. */
        private String backup(Request request, Playlists.Variant wanted, long deadline) {
            // Always in quality order; an opened source is reused.
            for (int index = 0; index < BackupSessions.SOURCES.length && System.currentTimeMillis() < deadline; index++) {
                String name = BackupSessions.sourceName(index);
                if (index != backupIndex || backupVariants == null || System.currentTimeMillis() > backupExpires) {
                    if (!openBackup(index, deadline)) continue;
                }
                Playlists.Variant v = Playlists.match(backupVariants, wanted);
                if (v == null) {
                    AdBlock.issue("Backup " + name + " has no matching rendition");
                    backupVariants = null;
                    continue;
                }
                Fetched f = fetch(userAgent, v.uri, deadline, request);
                if (f.text != null && Playlists.isCleanLive(f.text)) {
                    return f.text;
                }
                if (f.late) {
                    // Keep the opened backup: the next refresh, two seconds later, only needs this request.
                    AdBlock.issue("Backup " + name + " was too slow this time");
                    return null;
                }
                AdBlock.issue(f.text == null ? "Backup " + name + " playlist could not be loaded" : "Backup " + name + " also has an ad");
                backupVariants = null;
            }
            return null;
        }

        /** Opens a backup source; network work runs unlocked, the result is stored at once. */
        private boolean openBackup(int index, long deadline) {
            String name = BackupSessions.sourceName(index);
            String url;
            if (BackupSessions.PROXY.equals(BackupSessions.SOURCES[index])) {
                url = LiveProxy.proxyUrl(AdBlock.proxyBase(), login);
                if (url == null) return false; // No stream proxy chosen in the settings.
            } else {
                int left = (int) Math.max(300, deadline - System.currentTimeMillis());
                String[] token = BackupSessions.token(login, BackupSessions.SOURCES[index], Math.min(left, 3000));
                if (token == null) {
                    AdBlock.issue("No " + name + " token from Twitch");
                    return false;
                }
                url = Playlists.withToken(usherUrl, token[0], token[1]);
            }
            String master = fetch(userAgent, url, deadline, null).text;
            if (master == null || !Playlists.isMaster(master)) {
                AdBlock.issue("Backup " + name + " master playlist could not be loaded");
                return false;
            }
            List<Playlists.Variant> parsed = Playlists.variants(master, url);
            if (parsed.isEmpty()) return false;
            synchronized (this) {
                backupVariants = parsed;
                backupIndex = index;
                // Tokens last longer, but a fresh session now and then avoids expired playlists.
                backupExpires = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(5);
            }
            AdBlock.log("Backup session for " + login + ": " + name);
            return true;
        }
    }

    /** A fetched playlist, or why there is none. */
    private static final class Fetched {
        final String text;
        /** True when the deadline passed before an answer; the source itself may be fine. */
        final boolean late;

        Fetched(String text, boolean late) {
            this.text = text;
            this.late = late;
        }
    }

    /** GET through the original client, waiting at most until the deadline. */
    private Fetched fetch(String userAgent, String url, long deadline, Request parent) {
        if (released || parent != null && parent.isCancelled()) return new Fetched(null, false);
        Request request = new Request(url, "GET");
        request.setTimeout(5);
        if (userAgent != null) request.setHeader("User-Agent", userAgent);
        CountDownLatch done = new CountDownLatch(1);
        byte[][] result = new byte[1][];
        original.execute(request, new ResponseCallback() {
            @Override
            public void onError(Exception error) {
                done.countDown();
            }

            @Override
            public void onResponse(Response response) {
                if (response.getStatus() != 200) {
                    done.countDown();
                    return;
                }
                response.readContent(new Body() {
                    @Override
                    void complete(byte[] body) {
                        result[0] = body;
                        done.countDown();
                    }

                    @Override
                    void failed(Exception error) {
                        done.countDown();
                    }
                });
            }
        });
        try {
            long wait = deadline - System.currentTimeMillis();
            if (wait <= 0 || !done.await(wait, TimeUnit.MILLISECONDS)) {
                request.cancel();
                return new Fetched(null, true);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            request.cancel();
            return new Fetched(null, true);
        }
        return new Fetched(result[0] == null ? null : new String(result[0], StandardCharsets.UTF_8), false);
    }

    private static Response copy(Request request, Response original, byte[] body) {
        Response response = new Response(original.getStatus(), original.getUrl());
        response.setHeader("Content-Type", "application/vnd.apple.mpegurl");
        response.setHeader("Content-Length", Integer.toString(body.length));
        for (String name : new String[]{"Date", "Last-Modified", "ETag", "Cache-Control", "Age"}) {
            String value = original.getHeader(name);
            if (value != null) response.setHeader(name, value);
        }
        response.setConsumer(null, new StreamConsumer() {
            @Override
            public void consume(ReadCallback callback) {
                synchronized (request.lock()) {
                    if (request.isCancelled()) return;
                }
                ByteBuffer buffer = ByteBuffer.allocateDirect(body.length);
                buffer.put(body);
                ((java.nio.Buffer) buffer).flip();
                callback.onData(buffer, true);
            }
        });
        return response;
    }

    private static void deliver(Request request, ResponseCallback callback, Response response) {
        synchronized (request.lock()) {
            if (request.isCancelled()) return;
        }
        callback.onResponse(response);
    }

    private static void fail(Request request, ResponseCallback callback, Exception error) {
        synchronized (request.lock()) {
            if (request.isCancelled()) return;
        }
        callback.onError(error);
    }

    /** Collects a response body into memory; playlists are small. */
    private abstract static class Body implements ReadCallback {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private boolean finished;

        abstract void complete(byte[] body);

        abstract void failed(Exception error);

        @Override
        public int getTimeout() {
            return 5;
        }

        @Override
        public void onData(ByteBuffer data, boolean end) {
            byte[] chunk = new byte[data.remaining()];
            data.get(chunk);
            onData(chunk, chunk.length, end);
        }

        @Override
        public synchronized void onData(byte[] data, int length, boolean end) {
            if (finished) return;
            if (length < 0 || length > data.length || bytes.size() + length > BODY_LIMIT) {
                onError(new IOException("Playlist too large"));
                return;
            }
            bytes.write(data, 0, length);
            if (end) {
                finished = true;
                complete(bytes.toByteArray());
            }
        }

        @Override
        public synchronized void onError(Exception error) {
            if (finished) return;
            finished = true;
            failed(error);
        }
    }
}
