package org.bustimes.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Persistence for saved ("favourite") bus stops.
 *
 * A saved stop carries its own name, routes and position instead of pointing into the live stop
 * list, because the whole point of saving one is to reach its departure board later, from wherever
 * the map happens to be looking. Identity is the OpenStreetMap node id, which survives reloads; a
 * stop read back from a cache written before ids were kept falls back to a rounded coordinate, so
 * nothing becomes unsaveable.
 *
 * The encoding and the list rules are kept free of Android types so they can be unit tested on the
 * JVM, in the same way as {@link ArrivalAlertRules}. Only the thin preferences wrappers touch
 * Android.
 */
final class StopFavorites {

    /** Its own preferences file, so saved places are separate from the UI's settings. */
    static final String PREFS = "bus_times_stops";
    private static final String KEY_STOPS = "stops";

    /** One saved stop. */
    static final class FavoriteStop {
        final long osmId;
        final String name;
        final String routes;
        final double latitude;
        final double longitude;

        FavoriteStop(long osmId, String name, String routes, double latitude, double longitude) {
            this.osmId = osmId;
            this.name = name == null ? "" : name;
            this.routes = routes == null ? "" : routes;
            this.latitude = latitude;
            this.longitude = longitude;
        }

        /** Stable identity for this stop, used to save, compare and remove it. */
        String key() {
            return keyFor(osmId, latitude, longitude);
        }

        JSONObject toJson() throws Exception {
            JSONObject json = new JSONObject();
            json.put("id", osmId);
            json.put("name", name);
            json.put("routes", routes);
            json.put("lat", latitude);
            json.put("lon", longitude);
            return json;
        }

        static FavoriteStop fromJson(JSONObject json) {
            return new FavoriteStop(json.optLong("id", 0L), json.optString("name", ""),
                    json.optString("routes", ""),
                    json.optDouble("lat", 0d), json.optDouble("lon", 0d));
        }
    }

    private StopFavorites() {
    }

    /**
     * The OpenStreetMap node id when we have one, otherwise the coordinate rounded to about a metre.
     * Both are stable for the same place, which is what makes a saved stop findable again.
     */
    static String keyFor(long osmId, double latitude, double longitude) {
        if (osmId > 0L) {
            return "osm:" + osmId;
        }
        return String.format(Locale.UK, "ll:%.5f,%.5f", latitude, longitude);
    }

    static String encode(List<FavoriteStop> stops) {
        JSONArray array = new JSONArray();
        if (stops != null) {
            for (FavoriteStop stop : stops) {
                try {
                    array.put(stop.toJson());
                } catch (Exception ignored) {
                    // skip one unreadable stop rather than losing the rest of the list
                }
            }
        }
        return array.toString();
    }

    static List<FavoriteStop> decode(String raw) {
        List<FavoriteStop> stops = new ArrayList<>();
        if (raw == null || raw.isEmpty()) {
            return stops;
        }
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject json = array.optJSONObject(i);
                if (json != null) {
                    stops.add(FavoriteStop.fromJson(json));
                }
            }
        } catch (Exception e) {
            return new ArrayList<>();
        }
        return stops;
    }

    static boolean contains(List<FavoriteStop> stops, String key) {
        if (stops == null || key == null) {
            return false;
        }
        for (FavoriteStop stop : stops) {
            if (key.equals(stop.key())) {
                return true;
            }
        }
        return false;
    }

    /** Copy of {@code stops} without the one carrying {@code key}. */
    static List<FavoriteStop> without(List<FavoriteStop> stops, String key) {
        List<FavoriteStop> kept = new ArrayList<>();
        if (stops != null) {
            for (FavoriteStop stop : stops) {
                if (!stop.key().equals(key)) {
                    kept.add(stop);
                }
            }
        }
        return kept;
    }

    /** Copy of {@code stops} with {@code stop} saved, replacing any earlier entry for the same stop. */
    static List<FavoriteStop> with(List<FavoriteStop> stops, FavoriteStop stop) {
        List<FavoriteStop> updated = without(stops, stop.key());
        updated.add(0, stop);
        return updated;
    }

    /** Alphabetical, so the list does not reshuffle as stops are added. */
    static List<FavoriteStop> sorted(List<FavoriteStop> stops) {
        List<FavoriteStop> copy = new ArrayList<>(stops);
        Collections.sort(copy, (a, b) -> {
            int byName = a.name.compareToIgnoreCase(b.name);
            return byName != 0 ? byName : a.key().compareTo(b.key());
        });
        return copy;
    }

    // ------------------------------------------------------------- preferences

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static List<FavoriteStop> load(Context context) {
        return decode(prefs(context).getString(KEY_STOPS, ""));
    }

    static void save(Context context, List<FavoriteStop> stops) {
        prefs(context).edit().putString(KEY_STOPS, encode(stops)).apply();
    }

    static boolean isFavorite(Context context, long osmId, double latitude, double longitude) {
        return contains(load(context), keyFor(osmId, latitude, longitude));
    }

    /** Saves the stop, or removes it when it is already saved. */
    static void toggle(Context context, FavoriteStop stop) {
        List<FavoriteStop> all = load(context);
        save(context, contains(all, stop.key()) ? without(all, stop.key()) : with(all, stop));
    }

    static void remove(Context context, String key) {
        save(context, without(load(context), key));
    }

    static void clear(Context context) {
        prefs(context).edit().remove(KEY_STOPS).apply();
    }

    static List<FavoriteStop> sortedSaved(Context context) {
        return sorted(load(context));
    }
}
