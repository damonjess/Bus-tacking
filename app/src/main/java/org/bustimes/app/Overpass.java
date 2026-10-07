package org.bustimes.app;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * The one Overpass API client used for every piece of OpenStreetMap data the map draws: bus stops,
 * route shapes and the AR stop pins.
 *
 * Public Overpass instances are volunteer-run, and the canonical one answers a large share of
 * requests with "server too busy" (HTTP 504) or "too many requests" (HTTP 429) even for a trivial
 * query. Trying each mirror exactly once therefore failed most of the time, which is what left the
 * map with no stop markers and no route line when a bus was tapped. Every mirror is now retried
 * with a short backoff, and the whole call is bounded by a deadline so a dead mirror cannot stall
 * the map.
 *
 * Mirrors are ordered most reliable first, with the canonical instance as the first fallback. Only
 * instances that hold the whole planet are listed: a regional mirror (for example
 * {@code overpass.osm.ch}, which only serves Switzerland) answers HTTP 200 with no elements, which
 * looks like "there are no bus stops here" and would then be cached as such.
 */
final class Overpass {

    private static final String TAG = "Overpass";

    /** Public instances that serve the whole planet, tried in order. */
    private static final String[] ENDPOINTS = {
            "https://overpass.openstreetmap.fr/api/interpreter",
            "https://overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter",
            "https://overpass.private.coffee/api/interpreter"};

    /**
     * Attempts per mirror, matching {@link #ENDPOINTS} by index. The busy instances are worth
     * retrying because their failures are transient, while the two volunteer mirrors at the end
     * are kept as a last resort only.
     */
    private static final int[] ATTEMPTS = {2, 3, 1, 1};

    private static final int CONNECT_TIMEOUT_MS = 8_000;
    private static final int READ_TIMEOUT_MS = 25_000;
    /** Give up after this long, however many mirrors are still untried. */
    private static final long DEADLINE_MS = 30_000;
    private static final long BACKOFF_MS = 300;

    /** 429 "too many requests"; the constant is missing from the Android HttpURLConnection. */
    private static final int HTTP_TOO_MANY_REQUESTS = 429;

    private Overpass() {
    }

    /**
     * Runs an Overpass QL query against the mirrors in turn.
     *
     * @param query Overpass QL body, without the {@code data=} prefix
     * @return the raw JSON response
     * @throws IOException when every mirror failed, or the deadline passed
     */
    static String post(String query) throws IOException {
        String body = "data=" + URLEncoder.encode(query, "UTF-8");
        long deadline = System.currentTimeMillis() + DEADLINE_MS;
        IOException lastFailure = null;
        for (int index = 0; index < ENDPOINTS.length; index++) {
            String endpoint = ENDPOINTS[index];
            for (int attempt = 1; attempt <= ATTEMPTS[index]; attempt++) {
                if (System.currentTimeMillis() > deadline) {
                    throw lastFailure != null ? lastFailure : new IOException("Overpass deadline passed");
                }
                try {
                    return request(endpoint, body);
                } catch (BusyException busy) {
                    // The instance is overloaded; the same mirror often answers on a second try.
                    lastFailure = busy;
                    Log.w(TAG, endpoint + " busy on attempt " + attempt + "/" + ATTEMPTS[index]);
                } catch (RejectedException rejected) {
                    // A malformed query is malformed everywhere, so stop rather than burn mirrors.
                    throw rejected;
                } catch (IOException failure) {
                    lastFailure = failure;
                    Log.w(TAG, endpoint + " failed on attempt " + attempt + "/" + ATTEMPTS[index]
                            + ": " + failure.getMessage());
                }
                sleep(BACKOFF_MS * attempt);
            }
        }
        throw lastFailure != null ? lastFailure : new IOException("No Overpass instance available");
    }

    private static String request(String endpoint, String body) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("User-Agent", "BusTimesLive/1.0 (Android)");
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int code = connection.getResponseCode();
            if (code == HttpURLConnection.HTTP_OK) {
                try (InputStream in = connection.getInputStream()) {
                    return read(in);
                }
            }
            // Drain the error page so the connection is released before the next mirror is tried.
            try (InputStream error = connection.getErrorStream()) {
                if (error != null) {
                    read(error);
                }
            }
            if (isBusy(code)) {
                throw new BusyException(code);
            }
            throw new RejectedException(code);
        } finally {
            connection.disconnect();
        }
    }

    private static boolean isBusy(int code) {
        return code == HTTP_TOO_MANY_REQUESTS || code == HttpURLConnection.HTTP_BAD_GATEWAY
                || code == HttpURLConnection.HTTP_UNAVAILABLE
                || code == HttpURLConnection.HTTP_GATEWAY_TIMEOUT || code >= 500;
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = in.read(buffer)) != -1) {
            bytes.write(buffer, 0, count);
        }
        return bytes.toString("UTF-8");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** The instance is overloaded right now; another attempt or mirror may still succeed. */
    private static final class BusyException extends IOException {
        BusyException(int code) {
            super("Overpass busy (HTTP " + code + ")");
        }
    }

    /** The request itself was refused, so retrying it elsewhere would not help. */
    private static final class RejectedException extends IOException {
        RejectedException(int code) {
            super("Overpass rejected the query (HTTP " + code + ")");
        }
    }
}
