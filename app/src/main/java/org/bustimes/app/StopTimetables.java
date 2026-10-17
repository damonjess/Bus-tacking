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
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A stop's published timetable: scheduled departures, cached on disk and in memory.
 */
final class StopTimetables {

    private static final String TAG = "StopTimetables";
    private static final String DIRECTORY = "timetables";
    private static final long CACHE_TTL_MS = 7L * 24 * 60 * 60 * 1000;

    /** Maximum new dataset files to download over the network for a single stop lookup. */
    private static final int MAX_NEW_DOWNLOADS = 3;

    /** Day filter modes for the stop sheet timetable switcher. */
    static final int MODE_TODAY = 0;
    static final int MODE_WEEKDAYS = 1;
    static final int MODE_SATURDAY = 2;
    static final int MODE_SUNDAY = 3;

    /** In-memory cache of (datasetFileName + "|" + atcoCode) known to contain no departures. */
    private static final Set<String> KNOWN_EMPTY =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    /** In-memory cache of parsed stop departures for instant repeat opens. */
    private static final ConcurrentHashMap<String, List<TxcTimetable.Departure>> MEMORY_CACHE =
            new ConcurrentHashMap<>();

    /** Rows the card shows before it says how many more there are. */
    static final int MAX_ROWS = 6;

    /** A departure row paired with whether it rolls over into tomorrow morning. */
    static final class ScheduledRow {
        final TxcTimetable.Departure departure;
        final boolean tomorrow;

        ScheduledRow(TxcTimetable.Departure departure, boolean tomorrow) {
            this.departure = departure;
            this.tomorrow = tomorrow;
        }
    }

    interface Progress {
        void onProgress(String message);
    }

    private StopTimetables() { }

    /** The cached departures for a stop, or null when nothing usable is stored. */
    static List<TxcTimetable.Departure> cached(Context context, String atcoCode) {
        if (TextUtils.isEmpty(atcoCode)) {
            return null;
        }
        List<TxcTimetable.Departure> inMemory = MEMORY_CACHE.get(atcoCode);
        if (inMemory != null) {
            return inMemory;
        }
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
            MEMORY_CACHE.put(atcoCode, departures);
            return departures;
        } catch (Exception e) {
            Log.w(TAG, "Unreadable timetable cache", e);
            return null;
        }
    }

    /**
     * Scans any operator dataset files ALREADY downloaded on disk without touching the network.
     * Returns the stop's departures (and caches them) if found in a local dataset, or null.
     */
    static List<TxcTimetable.Departure> departuresFromDiskOnly(Context context, String atcoCode) {
        if (TextUtils.isEmpty(atcoCode)) {
            return null;
        }
        List<TxcTimetable.Departure> existing = cached(context, atcoCode);
        if (existing != null) {
            return existing;
        }
        File dir = directory(context);
        if (dir == null) {
            return null;
        }
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            return null;
        }
        // Check most recently downloaded dataset files first
        Arrays.sort(files, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(b.lastModified(), a.lastModified());
            }
        });
        for (File file : files) {
            String name = file.getName();
            if (!file.isFile() || !name.startsWith("dataset-") || name.endsWith(".part")
                    || !BodsTimetables.isFresh(file)) {
                continue;
            }
            String emptyKey = name + "|" + atcoCode;
            if (KNOWN_EMPTY.contains(emptyKey)) {
                continue;
            }
            String ext = name.endsWith(".zip") ? "zip" : "";
            try {
                List<TxcTimetable.Departure> found =
                        BodsTimetables.departuresFrom(file, ext, atcoCode);
                if (found.isEmpty()) {
                    KNOWN_EMPTY.add(emptyKey);
                } else {
                    MEMORY_CACHE.put(atcoCode, found);
                    writeCache(context, atcoCode, found);
                    return found;
                }
            } catch (Exception ignored) {
                // Ignore unreadable local file and try the next
            }
        }
        return null;
    }

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

        List<BodsTimetables.Dataset> ordered =
                candidates(datasets, routes, preferred(context, area), directory);

        List<TxcTimetable.Departure> all = new ArrayList<>();
        int networkDownloads = 0;

        for (BodsTimetables.Dataset dataset : ordered) {
            File targetFile = BodsTimetables.datasetFile(directory, dataset);
            String emptyKey = targetFile.getName() + "|" + atcoCode;
            if (KNOWN_EMPTY.contains(emptyKey)) {
                continue;
            }

            boolean alreadyOnDisk = targetFile.isFile() && BodsTimetables.isFresh(targetFile);
            if (!alreadyOnDisk) {
                if (networkDownloads >= MAX_NEW_DOWNLOADS) {
                    break;
                }
                networkDownloads++;
            }

            File file;
            try {
                file = BodsTimetables.fetch(dataset, directory, progressFor(dataset, progress));
            } catch (Exception e) {
                Log.w(TAG, "Skipping failed dataset " + dataset.label(), e);
                continue;
            }

            say(progress, "Reading " + dataset.label() + " timetable\u2026");
            List<TxcTimetable.Departure> found;
            try {
                found = BodsTimetables.departuresFrom(file, dataset.extension, atcoCode);
            } catch (Exception e) {
                Log.w(TAG, "Failed parsing dataset " + dataset.label(), e);
                continue;
            }

            if (found.isEmpty()) {
                KNOWN_EMPTY.add(emptyKey);
            } else {
                all.addAll(found);
                rememberDataset(context, area, dataset);
                break;
            }
        }

        if (all.isEmpty()) {
            throw new IOException("No published timetable has times for this stop.");
        }
        MEMORY_CACHE.put(atcoCode, all);
        writeCache(context, atcoCode, all);
        return all;
    }

    private static List<BodsTimetables.Dataset> candidates(List<BodsTimetables.Dataset> datasets,
            List<String> routes, long preferredId, File directory) {
        List<BodsTimetables.Dataset> matched = BodsTimetables.forRoutes(datasets, routes);
        List<BodsTimetables.Dataset> ordered = new ArrayList<>();

        for (BodsTimetables.Dataset d : matched) {
            File f = BodsTimetables.datasetFile(directory, d);
            if (f.isFile() && BodsTimetables.isFresh(f)) {
                ordered.add(d);
            }
        }
        for (BodsTimetables.Dataset d : datasets) {
            if (d.id == preferredId && !containsId(ordered, d.id)) {
                File f = BodsTimetables.datasetFile(directory, d);
                if (f.isFile() && BodsTimetables.isFresh(f)) {
                    ordered.add(d);
                }
            }
        }
        for (BodsTimetables.Dataset d : datasets) {
            if (!containsId(ordered, d.id)) {
                File f = BodsTimetables.datasetFile(directory, d);
                if (f.isFile() && BodsTimetables.isFresh(f)) {
                    ordered.add(d);
                }
            }
        }
        for (BodsTimetables.Dataset d : matched) {
            if (!containsId(ordered, d.id)) {
                ordered.add(d);
            }
        }
        for (BodsTimetables.Dataset d : datasets) {
            if (d.id == preferredId && !containsId(ordered, d.id)) {
                ordered.add(d);
                break;
            }
        }
        if (matched.isEmpty()) {
            List<BodsTimetables.Dataset> rest = new ArrayList<>();
            for (BodsTimetables.Dataset d : datasets) {
                if (!containsId(ordered, d.id)) {
                    rest.add(d);
                }
            }
            Collections.sort(rest, BodsTimetables.Dataset.BY_LINES);
            ordered.addAll(rest);
        }
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

    /** Today's remaining times (kept for unit test compatibility). */
    static List<TxcTimetable.Departure> today(List<TxcTimetable.Departure> departures, long nowMs) {
        Calendar calendar = Calendar.getInstance(TimeZone.getDefault(), Locale.UK);
        calendar.setTimeInMillis(nowMs);
        int mask = TxcTimetable.dayMaskForCalendarDay(calendar.get(Calendar.DAY_OF_WEEK));
        String isoDate = new SimpleDateFormat("yyyy-MM-dd", Locale.UK).format(calendar.getTime());
        int seconds = calendar.get(Calendar.HOUR_OF_DAY) * 3600
                + calendar.get(Calendar.MINUTE) * 60 + calendar.get(Calendar.SECOND);
        return TxcTimetable.upcoming(departures, mask, isoDate, seconds, MAX_ROWS);
    }

    /**
     * Selects departures for the stop sheet, supporting route filtering, day-of-week modes,
     * and automatic rollover to tomorrow morning when today has fewer than MAX_ROWS left.
     */
    static List<ScheduledRow> select(List<TxcTimetable.Departure> departures, long nowMs,
            int dayMode, String routeFilter) {
        List<ScheduledRow> rows = new ArrayList<>();
        if (departures == null || departures.isEmpty()) {
            return rows;
        }
        List<TxcTimetable.Departure> filtered = filterByRoute(departures, routeFilter);
        if (filtered.isEmpty()) {
            return rows;
        }

        Calendar calendar = Calendar.getInstance(TimeZone.getDefault(), Locale.UK);
        calendar.setTimeInMillis(nowMs);
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd", Locale.UK);
        String todayIso = fmt.format(calendar.getTime());
        int nowSeconds = calendar.get(Calendar.HOUR_OF_DAY) * 3600
                + calendar.get(Calendar.MINUTE) * 60 + calendar.get(Calendar.SECOND);

        if (dayMode == MODE_TODAY) {
            int todayMask = TxcTimetable.dayMaskForCalendarDay(calendar.get(Calendar.DAY_OF_WEEK));
            List<TxcTimetable.Departure> todayList =
                    TxcTimetable.upcoming(filtered, todayMask, todayIso, nowSeconds, MAX_ROWS);
            for (TxcTimetable.Departure d : todayList) {
                rows.add(new ScheduledRow(d, false));
            }
            if (rows.size() < MAX_ROWS) {
                calendar.add(Calendar.DAY_OF_MONTH, 1);
                int tomorrowMask = TxcTimetable.dayMaskForCalendarDay(calendar.get(Calendar.DAY_OF_WEEK));
                String tomorrowIso = fmt.format(calendar.getTime());
                int remaining = MAX_ROWS - rows.size();
                List<TxcTimetable.Departure> tomorrowList =
                        TxcTimetable.upcoming(filtered, tomorrowMask, tomorrowIso, 0, remaining);
                for (TxcTimetable.Departure d : tomorrowList) {
                    rows.add(new ScheduledRow(d, true));
                }
            }
            return rows;
        }

        int targetMask;
        switch (dayMode) {
            case MODE_WEEKDAYS:
                targetMask = TxcTimetable.MONDAY;
                break;
            case MODE_SATURDAY:
                targetMask = TxcTimetable.SATURDAY;
                break;
            case MODE_SUNDAY:
                targetMask = TxcTimetable.SUNDAY;
                break;
            default:
                targetMask = TxcTimetable.ALL_DAYS;
                break;
        }
        List<TxcTimetable.Departure> fromNow =
                TxcTimetable.upcoming(filtered, targetMask, todayIso, nowSeconds, MAX_ROWS);
        if (fromNow.size() < MAX_ROWS) {
            fromNow = TxcTimetable.upcoming(filtered, targetMask, todayIso, 0, MAX_ROWS);
        }
        for (TxcTimetable.Departure d : fromNow) {
            rows.add(new ScheduledRow(d, false));
        }
        return rows;
    }

    /** Distinct route names present in a loaded timetable. */
    static List<String> distinctLines(List<TxcTimetable.Departure> departures) {
        if (departures == null || departures.isEmpty()) {
            return Collections.emptyList();
        }
        Set<String> lines = new LinkedHashSet<>();
        for (TxcTimetable.Departure d : departures) {
            if (d.line != null && !d.line.trim().isEmpty()) {
                lines.add(d.line.trim());
            }
        }
        return new ArrayList<>(lines);
    }

    private static List<TxcTimetable.Departure> filterByRoute(
            List<TxcTimetable.Departure> departures, String routeFilter) {
        if (routeFilter == null || routeFilter.trim().isEmpty()) {
            return departures;
        }
        String wanted = routeFilter.trim();
        List<TxcTimetable.Departure> out = new ArrayList<>();
        for (TxcTimetable.Departure d : departures) {
            if (d.line != null && d.line.trim().equalsIgnoreCase(wanted)) {
                out.add(d);
            }
        }
        return out;
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
