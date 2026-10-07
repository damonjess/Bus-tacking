package org.bustimes.app;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;
import org.osmdroid.util.GeoPoint;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Loads the drawn shape of a bus route from OpenStreetMap.
 *
 * OSM publishes bus routes as {@code route=bus} relations whose member ways are the road geometry,
 * so the app asks the Overpass API (through the shared {@link Overpass} client it also uses for
 * bus stops) for the new route shapes. This is real mapped geometry: when OSM has no relation for a
 * route, nothing is drawn and the caller says so rather than showing an invented line.
 *
 * Results are cached in memory and on disk so tapping a route chip does not re-query Overpass.
 * A failed fetch is remembered for a short cooldown, because the live poller asks for the shape of
 * every route it can see on every tick and the shared client already retries each mirror.
 */
final class RouteShapes {

    private static final String TAG = "RouteShapes";
    private static final long CACHE_TTL_MS = 7L * 24 * 60 * 60 * 1000;
    /** How far around the map centre a route relation is looked for. */
    private static final int SEARCH_RADIUS_METERS = 12_000;

    private static final Map<String, List<List<GeoPoint>>> MEMORY = new HashMap<>();

    /**
     * Routes whose last fetch failed, with the time of the failure. Without this, a mirror that is
     * down would be re-queried - and now retried - for every visible route on every poll.
     */
    private static final Map<String, Long> FAILED_AT = Collections.synchronizedMap(new HashMap<>());
    private static final long FAILURE_COOLDOWN_MS = 2L * 60 * 1000;

    private RouteShapes() {
    }

    /** Route shapes already in memory, or null when the route has not been loaded yet. */
    static List<List<GeoPoint>> cached(String route) {
        return route == null ? null : MEMORY.get(key(route));
    }

    /** Loads a route shape, preferring the disk cache and falling back to Overpass. */
    static List<List<GeoPoint>> load(Context context, String route, double lat, double lon) {
        String key = key(route);
        List<List<GeoPoint>> memory = MEMORY.get(key);
        if (memory != null) {
            return memory;
        }
        List<List<GeoPoint>> disk = readCache(context, route);
        if (disk != null) {
            MEMORY.put(key, disk);
            return disk;
        }
        if (isCoolingDown(key)) {
            return null;
        }
        List<List<GeoPoint>> fetched = new ArrayList<>();
        if (!TextUtils.isEmpty(sanitiseRef(route))) {
            try {
                fetched = parse(Overpass.post(buildQuery(route, lat, lon)));
            } catch (Exception e) {
                Log.w(TAG, "Route shape fetch failed for " + route, e);
                FAILED_AT.put(key, System.currentTimeMillis());
                return null;
            }
            FAILED_AT.remove(key);
            writeCache(context, route, fetched);
        }
        MEMORY.put(key, fetched);
        return fetched;
    }

    private static String key(String route) {
        return route == null ? "" : route.trim().toUpperCase(Locale.UK);
    }

    private static boolean isCoolingDown(String key) {
        Long failedAt = FAILED_AT.get(key);
        return failedAt != null && System.currentTimeMillis() - failedAt < FAILURE_COOLDOWN_MS;
    }

    /**
     * Keeps only characters that are safe inside the Overpass regular expression, so a route label
     * coming from the live feed can never be injected into the query.
     */
    static String sanitiseRef(String route) {
        if (route == null) {
            return "";
        }
        StringBuilder safe = new StringBuilder();
        for (char c : route.trim().toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == ' ' || c == '-' || c == '+' || c == '/') {
                safe.append(c);
            }
        }
        return safe.toString().trim();
    }

    static String buildQuery(String route, double lat, double lon) {
        String ref = regexEscape(sanitiseRef(route));
        return "[out:json][timeout:25];"
                + "rel(around:" + SEARCH_RADIUS_METERS + "," + lat + "," + lon + ")"
                + "[type=route][route=bus][ref~\"^" + ref + "$\",i];"
                + "out geom;";
    }

    /** Escapes regular-expression metacharacters left after sanitising, e.g. the "+" in "X1+". */
    static String regexEscape(String value) {
        StringBuilder escaped = new StringBuilder();
        for (char c : value.toCharArray()) {
            if (".^$*+?()[]{}|\\".indexOf(c) >= 0) {
                escaped.append('\\');
            }
            escaped.append(c);
        }
        return escaped.toString();
    }

    /** Parses an Overpass JSON response into joined route lines. */
    static List<List<GeoPoint>> parse(String json) throws Exception {
        List<double[][]> ways = new ArrayList<>();
        JSONArray elements = new JSONObject(json).optJSONArray("elements");
        if (elements != null) {
            for (int i = 0; i < elements.length(); i++) {
                JSONObject element = elements.getJSONObject(i);
                String type = element.optString("type", "");
                if ("relation".equals(type)) {
                    JSONArray members = element.optJSONArray("members");
                    if (members == null) {
                        continue;
                    }
                    for (int m = 0; m < members.length(); m++) {
                        JSONObject member = members.getJSONObject(m);
                        if (!"way".equals(member.optString("type", ""))) {
                            continue;
                        }
                        if (isStopRole(member.optString("role", ""))) {
                            continue;
                        }
                        addWay(ways, member.optJSONArray("geometry"));
                    }
                } else if ("way".equals(type)) {
                    addWay(ways, element.optJSONArray("geometry"));
                }
            }
        }
        List<List<GeoPoint>> lines = new ArrayList<>();
        for (double[][] line : RouteShapeJoiner.tidy(RouteShapeJoiner.join(ways))) {
            List<GeoPoint> points = new ArrayList<>();
            for (double[] point : line) {
                points.add(new GeoPoint(point[0], point[1]));
            }
            lines.add(points);
        }
        return lines;
    }

    private static void addWay(List<double[][]> ways, JSONArray geometry) throws Exception {
        if (geometry == null || geometry.length() < 2) {
            return;
        }
        double[][] way = new double[geometry.length()][2];
        for (int g = 0; g < geometry.length(); g++) {
            JSONObject point = geometry.getJSONObject(g);
            way[g][0] = point.getDouble("lat");
            way[g][1] = point.getDouble("lon");
        }
        ways.add(way);
    }

    private static boolean isStopRole(String role) {
        return role.startsWith("stop") || "platform".equals(role) || "platform_entry_only".equals(role)
                || "platform_exit_only".equals(role);
    }

    // ---------------------------------------------------------------- disk cache

    private static File cacheFile(Context context, String route) {
        File directory = new File(context.getCacheDir(), "route_shapes");
        if (!directory.exists() && !directory.mkdirs()) {
            return null;
        }
        return new File(directory, sanitiseRef(route).replace(' ', '_').replace('/', '_') + ".json");
    }

    private static List<List<GeoPoint>> readCache(Context context, String route) {
        File file = cacheFile(context, route);
        if (file == null || !file.isFile() || System.currentTimeMillis() - file.lastModified() > CACHE_TTL_MS) {
            return null;
        }
        try {
            String raw = new String(readAll(file), StandardCharsets.UTF_8);
            JSONArray lines = new JSONArray(raw);
            List<List<GeoPoint>> result = new ArrayList<>();
            for (int i = 0; i < lines.length(); i++) {
                JSONArray line = lines.getJSONArray(i);
                List<GeoPoint> points = new ArrayList<>();
                for (int p = 0; p < line.length(); p++) {
                    JSONArray point = line.getJSONArray(p);
                    points.add(new GeoPoint(point.getDouble(0), point.getDouble(1)));
                }
                if (points.size() >= 2) {
                    result.add(points);
                }
            }
            return result;
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeCache(Context context, String route, List<List<GeoPoint>> lines) {
        File file = cacheFile(context, route);
        if (file == null) {
            return;
        }
        try {
            JSONArray array = new JSONArray();
            for (List<GeoPoint> line : lines) {
                JSONArray points = new JSONArray();
                for (GeoPoint point : line) {
                    points.put(new JSONArray().put(point.getLatitude()).put(point.getLongitude()));
                }
                array.put(points);
            }
            try (OutputStream out = new FileOutputStream(file)) {
                out.write(array.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not cache route shape for " + route, e);
        }
    }

    private static byte[] readAll(File file) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                bytes.write(buffer, 0, read);
            }
        }
        return bytes.toByteArray();
    }
}
