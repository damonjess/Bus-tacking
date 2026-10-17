package org.bustimes.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * A stop's published timetable: today's scheduled departures, cached on disk.
 *
 * Opening a stop must not start a download — one operator's timetable is tens of megabytes — so the
 * card shows whatever is already cached and offers to fetch the rest. Once a stop has been loaded it
 * keeps working offline for a week, and a fresh copy is fetched when the cache goes stale.
 */
final class StopTimetables {

    private static final String TAG = "StopTimetables";
    private static final String DIRECTORY = "timetables";
    private static final long CACHE_TTL_MS = 7L * 24 * 60 * 60 * 1000;

    /** Rows the card shows before it says how many more there are. */
    static final int MAX_ROWS = 6;

    interface Progress {
        void onProgress(String message);
    }

    private StopTimetables() { }

    /** The cached departures for a stop, or null when nothing usable is stored. */
    static List<TxcTimetable.Departure> cached(Context context, String atcoCode) {
        File file = cacheFile(context, atcoCode);
        if (file == null || !file.isFile()
                || System.currentTimeMillis() - file.lastModified() > CACHE_TTL_MS) {
            return null;
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream in = new FileInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    bytes.write(buffer, 0, read);
                }
            }
            JSONArray array = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8))
                    .optJSONArray("departures");
            if (array == null) {
                return null;
            }
            List<TxcTimetable.Departure> departures = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                JSONObject json = array.optJSONObject(i);
                if (json == null) {
                    continue;
                }
                departures.add(new TxcTimetable.Departure(
                        json.optString("line", ""),
                        json.optString("destination", ""),
                        json.optInt("seconds", 0),
                        json.optInt("days", TxcTimetable.ALL_DAYS),
                        json.optString("from", ""),
                        json.optString("to", "")));
            }
            return departures;
        } catch (Exception e) {
            Log.w(TAG, "Unreadable timetable cache", e);
            return null;
        }
    }

    /**
     * Loads a stop's scheduled departures, from the DfT datasets if the cache is stale or missing.
     *
     * Blocking: the caller runs this off the UI thread. {@code routes} are the route numbers known to
     * serve the stop, used to fetch only the datasets that publish a timetable for them.
     */
    static List<TxcTimetable.Departure> departures(Context context, String atcoCode,
            List<String> routes, Progress progress) throws Exception {
        if (TextUtils.isEmpty(BuildConfig.BODS_API_KEY)) {
            throw new IOException("Add a BODS_API_KEY to load timetables.");
        }
        String area = areaCode(atcoCode);
        if (area.isEmpty()) {
            throw new IOException("This stop has no NaPTAN reference to look a timetable up by.");
        }

        say(progress, "Finding published timetables\u2026");
        List<BodsTimetables.Dataset> datasets =
                BodsTimetables.datasetsForArea(BuildConfig.BODS_API_KEY, area);
        if (datasets.isEmpty()) {
            throw new IOException("No operator publishes a timetable for this area yet.");
        }

        File directory = directory(context);
        if (directory == null) {
            throw new IOException("Could not store timetables on this device.");
        }

        List<TxcTimetable.Departure> all = new ArrayList<>();
        for (BodsTimetables.Dataset dataset : candidates(datasets, routes, preferred(context, area))) {
            // The file is downloaded once per dataset, not once per stop: another stop served by the
            // same operator reads the copy already on disk.
            File file = BodsTimetables.fetch(dataset, directory, progressFor(dataset, progress));
            say(progress, "Reading " + dataset.label() + " timetable\u2026");
            all.addAll(BodsTimetables.departuresFrom(file, dataset.extension, atcoCode));
            if (!all.isEmpty()) {
                // The datasets that name one of this stop's routes are tried first, and a stop's own
                // times are in one file, so there is no reason to keep reading once they appear.
                rememberDataset(context, area, dataset);
                break;
            }
        }
        if (all.isEmpty()) {
            throw new IOException("No published timetable has times for this stop.");
        }
        writeCache(context, atcoCode, all);
        return all;
    }

    /**
     * The datasets worth trying, in the order worth trying them.
     *
     * A stop's routes are often unknown: OpenStreetMap may carry no {@code route_ref} and no vehicle
     * may be running near enough to ask. Then the only way to find the stop's times is to look in the
     * area's timetables, smallest first, and stop as soon as the times appear.
     */
    private static List<BodsTimetables.Dataset> candidates(List<BodsTimetables.Dataset> datasets,
            List<String> routes, long preferredId) {
        List<BodsTimetables.Dataset> matched = BodsTimetables.forRoutes(datasets, routes);
        List<BodsTimetables.Dataset> ordered = new ArrayList<>(matched);
        // Whatever answered for another stop in this area is the best guess here, so it is asked
        // before the rest of the area's timetables rather than after all of the small ones.
        for (BodsTimetables.Dataset dataset : datasets) {
            if (dataset.id == preferredId && !containsId(matched, preferredId)) {
                ordered.add(dataset);
                break;
            }
        }
        List<BodsTimetables.Dataset> rest = new ArrayList<>();
        for (BodsTimetables.Dataset dataset : datasets) {
            if (!containsId(matched, dataset.id) && dataset.id != preferredId) {
                rest.add(dataset);
            }
        }
        Collections.sort(rest, BodsTimetables.Dataset.BY_LINES);
        ordered.addAll(rest);
        return ordered;
    }

    private static boolean containsId(List<BodsTimetables.Dataset> datasets, long id) {
        for (BodsTimetables.Dataset dataset : datasets) {
            if (dataset.id == id) {
                return true;
            }
        }
        return false;
    }

    /** The dataset that last produced times for this area, or 0. */
    private static long preferred(Context context, String area) {
        return prefs(context).getLong("preferred-" + area, 0L);
    }

    private static void rememberDataset(Context context, String area, BodsTimetables.Dataset dataset) {
        prefs(context).edit().putLong("preferred-" + area, dataset.id).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("bus_times_timetables", Context.MODE_PRIVATE);
    }

    private static BodsTimetables.Progress progressFor(
            final BodsTimetables.Dataset dataset, final Progress progress) {
        return new BodsTimetables.Progress() {
            @Override
            public void report(String message) {
                say(progress, message);
            }

            @Override
            public void downloaded(int percent) {
                say(progress, "Downloading " + dataset.label() + " timetable\u2026 " + percent + "%");
            }
        };
    }

    /** Today's remaining times, ready to display. */
    static List<TxcTimetable.Departure> today(List<TxcTimetable.Departure> departures, long nowMs) {
        Calendar calendar = Calendar.getInstance(TimeZone.getDefault(), Locale.UK);
        calendar.setTimeInMillis(nowMs);
        int mask = TxcTimetable.dayMaskForCalendarDay(calendar.get(Calendar.DAY_OF_WEEK));
        String isoDate = new SimpleDateFormat("yyyy-MM-dd", Locale.UK).format(calendar.getTime());
        int seconds = calendar.get(Calendar.HOUR_OF_DAY) * 3600
                + calendar.get(Calendar.MINUTE) * 60 + calendar.get(Calendar.SECOND);
        return TxcTimetable.upcoming(departures, mask, isoDate, seconds, MAX_ROWS);
    }

    /** The ATCO area a stop reference belongs to: its first three digits. */
    static String areaCode(String atcoCode) {
        if (atcoCode == null || atcoCode.length() < 4) {
            return "";
        }
        String area = atcoCode.substring(0, 3);
        for (int i = 0; i < area.length(); i++) {
            if (!Character.isDigit(area.charAt(i))) {
                return "";
            }
        }
        return area;
    }

    private static void writeCache(Context context, String atcoCode,
            List<TxcTimetable.Departure> departures) {
        File file = cacheFile(context, atcoCode);
        if (file == null) {
            return;
        }
        try {
            JSONArray array = new JSONArray();
            for (TxcTimetable.Departure departure : departures) {
                JSONObject json = new JSONObject();
                json.put("line", departure.line);
                json.put("destination", departure.destination);
                json.put("seconds", departure.secondsOfDay);
                json.put("days", departure.daysMask);
                json.put("from", departure.validFrom);
                json.put("to", departure.validTo);
                array.put(json);
            }
            JSONObject root = new JSONObject();
            root.put("savedAt", System.currentTimeMillis());
            root.put("atco", atcoCode);
            root.put("departures", array);
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(root.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not store timetable", e);
        }
    }

    private static File cacheFile(Context context, String atcoCode) {
        if (TextUtils.isEmpty(atcoCode)) {
            return null;
        }
        File directory = directory(context);
        if (directory == null) {
            return null;
        }
        return new File(directory, "stop-" + atcoCode.replaceAll("[^A-Za-z0-9]", "") + ".json");
    }

    /** Where both the per-stop results and the downloaded dataset files are kept. */
    private static File directory(Context context) {
        File directory = new File(context.getCacheDir(), DIRECTORY);
        if (directory.exists() || directory.mkdirs()) {
            return directory;
        }
        return null;
    }

    private static void say(Progress progress, String message) {
        if (progress != null) {
            progress.onProgress(message);
        }
    }
}
