package io.github.sabopura.morphe.extension.twitch.notifications;

import java.net.URL;
import java.net.URLConnection;

/** Scope the compatibility header to Firebase Installations, never PackageManager or login. */
public final class FirebaseHeaders {
    // Public signer fingerprint of the supported official Twitch APK; not a credential.
    private static final String STOCK_CERT_SHA1 = "8C68C13822723A2B1FA844BED340031BEB1F9463";
    private FirebaseHeaders() {}

    public static String certificate(URL url, String key, String original) {
        if (url == null || !"X-Android-Cert".equalsIgnoreCase(key)
                || !"https".equalsIgnoreCase(url.getProtocol())
                || !"firebaseinstallations.googleapis.com".equalsIgnoreCase(url.getHost())
                || url.getUserInfo() != null || (url.getPort() != -1 && url.getPort() != 443)
                || !url.getPath().matches("/v1/projects/[^/]+/installations(?:/[^/]+/authTokens:generate)?")) return original;
        return STOCK_CERT_SHA1;
    }

    public static void add(URLConnection connection, String key, String value) {
        connection.addRequestProperty(key, certificate(connection.getURL(), key, value));
    }

    public static void set(URLConnection connection, String key, String value) {
        connection.setRequestProperty(key, certificate(connection.getURL(), key, value));
    }
}
