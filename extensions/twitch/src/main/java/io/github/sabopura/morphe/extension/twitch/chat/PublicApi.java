package io.github.sabopura.morphe.extension.twitch.chat;

import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

/** Separate, anonymous public-data client. Never reuses Twitch cookies, interceptors or tokens. */
public final class PublicApi {
    public static final Handler UI = new Handler(Looper.getMainLooper());
    private static final ThreadPoolExecutor WORK =
            new ThreadPoolExecutor(
                    2,
                    2,
                    30,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(64),
                    r -> {
                        Thread t = new Thread(r, "ChatPublicData");
                        t.setDaemon(true);
                        return t;
                    });

    private PublicApi() {}

    public static boolean submit(Runnable work) {
        try {
            WORK.execute(work);
            return true;
        } catch (RejectedExecutionException e) {
            return false;
        }
    }

    public static String get(String address) throws IOException {
        return request(address, null);
    }

    /** Public Twitch metadata only. Client-ID identifies the app and is not a login credential. */
    public static String twitchMetadata(String body) throws IOException {
        return request("https://gql.twitch.tv/gql", body);
    }

    public static String sevenTvMetadata(String body) throws IOException {
        return request("https://7tv.io/v4/gql", body);
    }

    private static String request(String address, String body) throws IOException {
        URL url = new URL(address);
        if (!"https".equals(url.getProtocol()) || url.getUserInfo() != null)
            throw new IOException("Invalid public URL");
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        c.setInstanceFollowRedirects(false);
        c.setRequestProperty("User-Agent", "SabopuraChat/1.0");
        c.setRequestProperty("Accept", "application/json");
        try {
            if (body != null) {
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                if ("gql.twitch.tv".equals(url.getHost()))
                    c.setRequestProperty("Client-ID", "kd1unb4b3q4t58fwlpcbzcbnm76a8fp");
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                c.setFixedLengthStreamingMode(bytes.length);
                try (java.io.OutputStream out = c.getOutputStream()) {
                    out.write(bytes);
                }
            }
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream();
                    ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) != -1) {
                    if (out.size() + n > 4 * 1024 * 1024)
                        throw new IOException("Response too large");
                    out.write(b, 0, n);
                }
                return out.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            c.disconnect();
        }
    }
}
