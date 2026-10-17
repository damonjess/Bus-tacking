package org.bustimes.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Queries OpenStreetMap Nominatim for UK towns, streets, landmarks and postcodes so the Search tab
 * can jump the map anywhere in the country.
 */
final class PlaceSearch {

    private static final String ENDPOINT =
            "https://nominatim.openstreetmap.org/search?format=jsonv2&countrycodes=gb&limit=5&q=";
    private static final int CONNECT_TIMEOUT_MS = 8_000;
    private static final int READ_TIMEOUT_MS = 12_000;

    private static final Map<String, List<Place>> CACHE = Collections.synchronizedMap(
            new LinkedHashMap<String, List<Place>>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, List<Place>> eldest) {
                    return size() > 40;
                }
            });

    static final class Place {
        final String title;
        final String subtitle;
        final double latitude;
        final double longitude;

        Place(String title, String subtitle, double latitude, double longitude) {
            this.title = title == null ? "" : title;
            this.subtitle = subtitle == null ? "" : subtitle;
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }

    private PlaceSearch() { }

    static List<Place> cached(String query) {
        if (query == null) return null;
        return CACHE.get(normalise(query));
    }

    static List<Place> search(String query) throws Exception {
        String key = normalise(query);
        if (key.length() < 2) {
            return Collections.emptyList();
        }
        List<Place> hit = CACHE.get(key);
        if (hit != null) {
            return hit;
        }
        String url = ENDPOINT + URLEncoder.encode(query.trim(), "UTF-8");
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", "BusTimesLive/1.0 (Android)");
            conn.setRequestProperty("Accept-Language", "en-GB,en;q=0.8");
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + code);
            }
            BufferedInputStream in = new BufferedInputStream(conn.getInputStream());
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                bytes.write(buffer, 0, read);
            }
            List<Place> places = parse(bytes.toString("UTF-8"));
            CACHE.put(key, places);
            return places;
        } finally {
            conn.disconnect();
        }
    }

    static List<Place> parse(String json) throws Exception {
        List<Place> list = new ArrayList<>();
        JSONArray array = new JSONArray(json);
        for (int i = 0; i < array.length(); i++) {
            JSONObject obj = array.optJSONObject(i);
            if (obj == null) continue;
            double lat = obj.optDouble("lat", Double.NaN);
            double lon = obj.optDouble("lon", Double.NaN);
            if (Double.isNaN(lat) || Double.isNaN(lon)) continue;
            String display = obj.optString("display_name", "").trim();
            if (display.isEmpty()) continue;
            int comma = display.indexOf(',');
            String title = comma > 0 ? display.substring(0, comma).trim() : display;
            String subtitle = comma > 0 ? display.substring(comma + 1).trim() : "";
            list.add(new Place(title, subtitle, lat, lon));
        }
        return list;
    }

    private static String normalise(String q) {
        return q.trim().toLowerCase(Locale.UK).replaceAll("\\s+", " ");
    }
}
