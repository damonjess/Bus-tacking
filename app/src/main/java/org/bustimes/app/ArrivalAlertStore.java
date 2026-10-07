package org.bustimes.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Persistence for armed arrival alerts.
 *
 * Lives in its own preferences file (not the activity's UI prefs) because both MainActivity and
 * the background BusTrackingService read and write it, and the service must be able to arm,
 * evaluate and cool down alerts without the map screen being alive.
 */
final class ArrivalAlertStore {

    static final String PREFS = "bus_times_alerts";
    private static final String KEY_ALERTS = "alerts";
    private static final String KEY_LAST_FIRED = "last_fired";

    private ArrivalAlertStore() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static List<ArrivalAlert> load(Context context) {
        String raw = prefs(context).getString(KEY_ALERTS, "");
        if (TextUtils.isEmpty(raw)) {
            return new ArrayList<>();
        }
        List<ArrivalAlert> alerts = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject json = array.optJSONObject(i);
                if (json != null) {
                    alerts.add(ArrivalAlert.fromJson(json));
                }
            }
        } catch (Exception e) {
            return new ArrayList<>();
        }
        return alerts;
    }

    static void save(Context context, List<ArrivalAlert> alerts) {
        JSONArray array = new JSONArray();
        for (ArrivalAlert alert : alerts) {
            try {
                array.put(alert.toJson());
            } catch (Exception ignored) {
                // skip an alert that cannot be serialised rather than losing the rest
            }
        }
        prefs(context).edit().putString(KEY_ALERTS, array.toString()).apply();
    }

    /**
     * Arms (or replaces) the alert for a route. There is at most one alert per route, which is what
     * the armed/not-armed state in the UI shows, so arming the same route from somewhere else moves
     * the alert rather than stacking a second one.
     */
    static void arm(Context context, ArrivalAlert alert) {
        List<ArrivalAlert> alerts = replacing(load(context), alert.id, alert.route);
        alerts.add(0, alert);
        save(context, alerts);
    }

    static void remove(Context context, String id) {
        save(context, without(context, id, null));
    }

    /** Removes every alert watching a route, wherever it was armed from. */
    static void removeForRoute(Context context, String route) {
        save(context, without(context, null, route));
    }

    private static List<ArrivalAlert> without(Context context, String id, String route) {
        return replacing(load(context), id, route);
    }

    /**
     * Copy of {@code alerts} without the alert carrying {@code id} and without any alert watching
     * {@code route}. Both filters are optional; either may be null.
     */
    static List<ArrivalAlert> replacing(List<ArrivalAlert> alerts, String id, String route) {
        List<ArrivalAlert> kept = new ArrayList<>();
        for (ArrivalAlert existing : alerts) {
            boolean sameId = id != null && id.equals(existing.id);
            boolean sameRoute = route != null && !route.isEmpty() && route.equalsIgnoreCase(existing.route);
            if (!sameId && !sameRoute) {
                kept.add(existing);
            }
        }
        return kept;
    }

    static void clear(Context context) {
        prefs(context).edit().remove(KEY_ALERTS).remove(KEY_LAST_FIRED).apply();
    }

    static boolean hasAlerts(Context context) {
        return !load(context).isEmpty();
    }

    static boolean isArmedForRoute(Context context, String route) {
        if (TextUtils.isEmpty(route)) {
            return false;
        }
        for (ArrivalAlert alert : load(context)) {
            if (route.equalsIgnoreCase(alert.route)) {
                return true;
            }
        }
        return false;
    }

    /** True when the cooldown for this alert has elapsed, so it may notify again. */
    static boolean canFire(Context context, String alertId, long nowMs) {
        long last = lastFired(context).optLong(alertId, 0L);
        return nowMs - last >= ArrivalAlertRules.COOLDOWN_MS;
    }

    static void recordFired(Context context, String alertId, long nowMs) {
        JSONObject fired = lastFired(context);
        try {
            fired.put(alertId, nowMs);
        } catch (Exception ignored) {
            return;
        }
        // drop stale entries so the map cannot grow without bound
        long cutoff = nowMs - 24 * 60 * 60 * 1000L;
        JSONObject pruned = new JSONObject();
        Iterator<String> keys = fired.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            long value = fired.optLong(key, 0L);
            if (value >= cutoff) {
                try {
                    pruned.put(key, value);
                } catch (Exception ignored) {
                    // nothing sensible to do with a single unreadable entry
                }
            }
        }
        prefs(context).edit().putString(KEY_LAST_FIRED, pruned.toString()).apply();
    }

    private static JSONObject lastFired(Context context) {
        String raw = prefs(context).getString(KEY_LAST_FIRED, "");
        if (TextUtils.isEmpty(raw)) {
            return new JSONObject();
        }
        try {
            return new JSONObject(raw);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /**
     * Union of the current BODS bounding box and every armed alert point, so a backgrounded
     * poller still downloads the vehicles that can fire an alert. The map area is dropped when
     * the union would exceed {@link #MAX_SPAN_DEGREES}, keeping the feed request a sane size.
     */
    static String expandBoundingBox(Context context, String currentBbox) {
        List<ArrivalAlert> alerts = load(context);
        if (alerts.isEmpty()) {
            return currentBbox;
        }
        double[] box = parseBoundingBox(currentBbox);
        if (box == null) {
            box = parseBoundingBox(BusTrackingService.DEFAULT_BOUNDING_BOX);
        }
        if (box == null) {
            return currentBbox;
        }
        double west = Double.MAX_VALUE, south = Double.MAX_VALUE;
        double east = -Double.MAX_VALUE, north = -Double.MAX_VALUE;
        for (ArrivalAlert alert : alerts) {
            west = Math.min(west, alert.longitude);
            east = Math.max(east, alert.longitude);
            south = Math.min(south, alert.latitude);
            north = Math.max(north, alert.latitude);
        }
        // pad the alert envelope so buses heading towards it are already in the feed
        west -= ALERT_PADDING_DEGREES;
        east += ALERT_PADDING_DEGREES;
        south -= ALERT_PADDING_DEGREES;
        north += ALERT_PADDING_DEGREES;

        boolean mapUsable = (box[2] - box[0]) <= MAX_SPAN_DEGREES && (box[3] - box[1]) <= MAX_SPAN_DEGREES;
        double unionWest = mapUsable ? Math.min(box[0], west) : west;
        double unionSouth = mapUsable ? Math.min(box[1], south) : south;
        double unionEast = mapUsable ? Math.max(box[2], east) : east;
        double unionNorth = mapUsable ? Math.max(box[3], north) : north;
        if (unionEast - unionWest > MAX_SPAN_DEGREES || unionNorth - unionSouth > MAX_SPAN_DEGREES) {
            unionWest = west;
            unionSouth = south;
            unionEast = east;
            unionNorth = north;
        }
        return String.format(Locale.US, "%.4f,%.4f,%.4f,%.4f", unionWest, unionSouth, unionEast, unionNorth);
    }

    /** Alert points are padded by roughly 5 km so approaching vehicles appear in the feed. */
    private static final double ALERT_PADDING_DEGREES = 0.05;
    private static final double MAX_SPAN_DEGREES = 2.0;

    /** Parses "minLon,minLat,maxLon,maxLat" into {west, south, east, north}. */
    static double[] parseBoundingBox(String bbox) {
        if (TextUtils.isEmpty(bbox)) {
            return null;
        }
        String[] parts = bbox.split(",");
        if (parts.length != 4) {
            return null;
        }
        try {
            double[] box = new double[4];
            for (int i = 0; i < 4; i++) {
                box[i] = Double.parseDouble(parts[i].trim());
            }
            return box;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Sorted copy for display; newest first. */
    static List<ArrivalAlert> sorted(Context context) {
        List<ArrivalAlert> alerts = load(context);
        Collections.sort(alerts, (a, b) -> Long.compare(b.createdMs, a.createdMs));
        return alerts;
    }
}
