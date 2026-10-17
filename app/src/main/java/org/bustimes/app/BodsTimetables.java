package org.bustimes.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The timetables half of the DfT Bus Open Data Service.
 */
final class BodsTimetables {

    private static final String DATASET_API =
            "https://data.bus-data.dft.gov.uk/api/v1/dataset/";

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 25_000;
    private static final int DOWNLOAD_ATTEMPTS = 2;

    private static final int HTTP_OK = 200;
    private static final int HTTP_PARTIAL = 206;

    /** Cache area dataset lists in memory for 30 minutes so tapping stops doesn't re-query BODS. */
    private static final long AREA_CACHE_TTL_MS = 30L * 60 * 1000;
    private static final Map<String, List<Dataset>> AREA_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Long> AREA_CACHE_TIME = new ConcurrentHashMap<>();

    interface Progress {
        void report(String message);
        void downloaded(int percent);
    }

    static final class Dataset {
        final long id;
        final String modified;
        final String operator;
        final String description;
        final String url;
        final String extension;
        final List<String> lines;

        Dataset(long id, String modified, String operator, String description, String url,
                String extension, List<String> lines) {
            this.id = id;
            this.modified = modified == null ? "" : modified;
            this.operator = operator == null ? "" : operator;
            this.description = description == null ? "" : description;
            this.url = url == null ? "" : url;
            this.extension = extension == null ? "" : extension;
            this.lines = lines == null ? new ArrayList<String>() : lines;
        }

        boolean serves(String route) {
            if (route == null || route.trim().isEmpty()) {
                return false;
            }
            String wanted = normalise(route);
            for (String line : lines) {
                if (normalise(line).equals(wanted)) {
                    return true;
                }
            }
            return false;
        }

        String label() {
            if (!operator.isEmpty()) {
                return operator;
            }
            return description.isEmpty() ? "dataset " + id : description;
        }

        String versionKey() {
            return id + "-" + modified;
        }

        static final Comparator<Dataset> BY_LINES = new Comparator<Dataset>() {
            @Override
            public int compare(Dataset a, Dataset b) {
                return Integer.compare(a.lines.size(), b.lines.size());
            }
        };
    }

    private BodsTimetables() { }

    static List<Dataset> datasetsForArea(String apiKey, String areaCode) throws Exception {
        Long cachedAt = AREA_CACHE_TIME.get(areaCode);
        List<Dataset> cached = AREA_CACHE.get(areaCode);
        if (cached != null && cachedAt != null
                && System.currentTimeMillis() - cachedAt < AREA_CACHE_TTL_MS) {
            return cached;
        }

        String url = DATASET_API + "?api_key=" + apiKey + "&adminArea=" + areaCode
                + "&status=published&limit=100";
        String body = get(url);
        List<Dataset> datasets = new ArrayList<>();
        JSONArray results = new JSONObject(body).optJSONArray("results");
        if (results == null) {
            return datasets;
        }
        for (int i = 0; i < results.length(); i++) {
            JSONObject result = results.optJSONObject(i);
            if (result == null) {
                continue;
            }
            String status = result.optString("status", "published");
            if (!status.isEmpty() && !"published".equalsIgnoreCase(status)
                    && !"live".equalsIgnoreCase(status)) {
                continue;
            }
            List<String> lines = new ArrayList<>();
            JSONArray lineArray = result.optJSONArray("lines");
            if (lineArray != null) {
                for (int j = 0; j < lineArray.length(); j++) {
                    lines.add(lineArray.optString(j, ""));
                }
            }
            datasets.add(new Dataset(
                    result.optLong("id", 0L),
                    result.optString("modified", ""),
                    result.optString("operatorName", ""),
                    result.optString("description", ""),
                    result.optString("url", ""),
                    result.optString("extension", ""),
                    lines));
        }
        if (!datasets.isEmpty()) {
            AREA_CACHE.put(areaCode, Collections.unmodifiableList(datasets));
            AREA_CACHE_TIME.put(areaCode, System.currentTimeMillis());
        }
        return datasets;
    }

    static File fetch(Dataset dataset, File directory, Progress progress) throws Exception {
        File file = datasetFile(directory, dataset);
        if (file.isFile() && isFresh(file)) {
            if (progress != null) {
                progress.report("Reading saved " + dataset.label() + " timetable\u2026");
            }
            return file;
        }
        if (dataset.url.isEmpty()) {
            throw new IOException("No download link for " + dataset.label() + ".");
        }
        if (progress != null) {
            progress.report("Downloading " + dataset.label() + " timetable\u2026");
        }
        File partial = new File(file.getPath() + ".part");
        IOException lastFailure = null;
        for (int attempt = 1; attempt <= DOWNLOAD_ATTEMPTS; attempt++) {
            try {
                download(dataset, partial, attempt > 1, progress);
                if (file.exists() && !file.delete()) {
                    throw new IOException("Could not replace the saved " + dataset.label()
                            + " timetable.");
                }
                if (!partial.renameTo(file)) {
                    throw new IOException("Could not save the " + dataset.label() + " timetable.");
                }
                prune(directory, file);
                return file;
            } catch (IOException e) {
                lastFailure = e;
            }
        }
        throw new IOException("The " + dataset.label() + " timetable download failed ("
                + (lastFailure == null ? "unknown reason" : lastFailure.getMessage()) + ").", lastFailure);
    }

    private static void download(Dataset dataset, File partial, boolean reportResume, Progress progress)
            throws IOException {
        long already = partial.isFile() ? partial.length() : 0;
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(dataset.url).openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("User-Agent", "BusTimesApp/1.0");
            connection.setInstanceFollowRedirects(true);
            if (already > 0) {
                connection.setRequestProperty("Range", "bytes=" + already + "-");
            }
            int code = connection.getResponseCode();
            if (code == 416 && partial.isFile()) {
                // Stale range; delete partial file and retry cleanly from 0
                partial.delete();
                throw new IOException("Restarting corrupted partial download");
            }
            boolean resuming = code == HTTP_PARTIAL && already > 0;
            if (code != HTTP_OK && code != HTTP_PARTIAL) {
                throw new IOException("HTTP " + code + " for " + dataset.label());
            }
            if (!resuming) {
                already = 0;
            }
            if (progress != null && reportResume) {
                progress.report("Continuing " + dataset.label() + " timetable download\u2026");
            }
            long contentLength = connection.getContentLengthLong();
            long totalLength = contentLength > 0 ? contentLength + already : -1L;
            InputStream stream = new ProgressStream(
                    new BufferedInputStream(connection.getInputStream()),
                    totalLength, dataset.label(), progress, already);
            try (OutputStream out = new FileOutputStream(partial, resuming)) {
                byte[] buffer = new byte[32768];
                int read;
                while ((read = stream.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            }
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    static final long FILE_TTL_MS = 14L * 24 * 60 * 60 * 1000;

    static File datasetFile(File directory, Dataset dataset) {
        String stamp = dataset.modified.replaceAll("[^0-9T]", "");
        String extension = dataset.extension.isEmpty() ? "bin" : dataset.extension.toLowerCase(Locale.UK);
        return new File(directory, "dataset-" + dataset.id + "-" + stamp + "." + extension);
    }

    static boolean isFresh(File file) {
        return System.currentTimeMillis() - file.lastModified() <= FILE_TTL_MS;
    }

    private static void prune(File directory, File keep) {
        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (!file.equals(keep) && file.isFile() && file.getName().startsWith("dataset-")
                    && !isFresh(file)) {
                file.delete();
            }
        }
    }

    /**
     * Extracts scheduled departures for {@code atcoCode}.
     * Automatically detects ZIP archives via magic bytes even if {@code extension} is empty,
     * and pre-filters XML entries by byte-scanning for {@code atcoCode} before running XmlPullParser.
     */
    static List<TxcTimetable.Departure> departuresFrom(File file, String extension, String atcoCode)
            throws Exception {
        List<TxcTimetable.Departure> departures = new ArrayList<>();
        if (atcoCode == null || atcoCode.trim().isEmpty()) {
            return departures;
        }
        String cleanAtco = atcoCode.trim();
        byte[] needle = cleanAtco.getBytes(StandardCharsets.UTF_8);

        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(file), 65536)) {
            boolean zipArchive = "zip".equalsIgnoreCase(extension) || startsWithZipMagic(in);
            if (zipArchive) {
                ZipInputStream zip = new ZipInputStream(in);
                byte[] scratch = new byte[32768];
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if (entry.isDirectory() || !isTimetableDocument(entry.getName())) {
                        continue;
                    }
                    byte[] entryBytes = readEntryIfContains(zip, needle, scratch);
                    if (entryBytes != null) {
                        departures.addAll(TxcTimetable.parse(
                                new ByteArrayInputStream(entryBytes), cleanAtco));
                    }
                }
            } else {
                departures.addAll(TxcTimetable.parse(in, cleanAtco));
            }
        }
        return departures;
    }

    /** True when the stream begins with the PK ZIP header ('P', 'K'). */
    private static boolean startsWithZipMagic(BufferedInputStream in) throws IOException {
        in.mark(4);
        int b1 = in.read();
        int b2 = in.read();
        in.reset();
        return b1 == 0x50 && b2 == 0x4B;
    }

    /**
     * Reads a ZipEntry into memory and checks whether it contains {@code needle} (the stop's ATCO
     * code). If the entry never mentions the stop, returns {@code null} so we skip XML parsing.
     */
    private static byte[] readEntryIfContains(InputStream in, byte[] needle, byte[] buffer)
            throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(65536);
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        byte[] data = out.toByteArray();
        return containsAsciiCaseInsensitive(data, needle) ? data : null;
    }

    private static boolean containsAsciiCaseInsensitive(byte[] hay, byte[] needle) {
        if (needle.length == 0 || hay.length < needle.length) {
            return false;
        }
        byte first = toLowerAscii(needle[0]);
        int max = hay.length - needle.length;
        for (int i = 0; i <= max; i++) {
            if (toLowerAscii(hay[i]) != first) {
                continue;
            }
            boolean match = true;
            for (int j = 1; j < needle.length; j++) {
                if (toLowerAscii(hay[i + j]) != toLowerAscii(needle[j])) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return true;
            }
        }
        return false;
    }

    private static byte toLowerAscii(byte b) {
        return (b >= 'A' && b <= 'Z') ? (byte) (b + 32) : b;
    }

    static List<Dataset> forRoutes(List<Dataset> datasets, List<String> routes) {
        List<Dataset> wanted = new ArrayList<>();
        for (Dataset dataset : datasets) {
            for (String route : routes) {
                if (dataset.serves(route)) {
                    wanted.add(dataset);
                    break;
                }
            }
        }
        Collections.sort(wanted, Dataset.BY_LINES);
        return wanted;
    }

    private static boolean isTimetableDocument(String name) {
        return name != null && name.toLowerCase(Locale.UK).endsWith(".xml");
    }

    private static String normalise(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").toLowerCase(Locale.UK);
    }

    private static String get(String url) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("User-Agent", "BusTimesApp/1.0");
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code + " for " + url);
            }
            BufferedInputStream in = new BufferedInputStream(connection.getInputStream());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toString("UTF-8");
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static final class ProgressStream extends FilterInputStream {
        private final long length;
        private final String label;
        private final Progress progress;
        private long read;
        private int reportedPercent = -1;
        private long lastReportedBytes = 0;

        ProgressStream(InputStream in, long length, String label, Progress progress, long alreadyRead) {
            super(in);
            this.length = length;
            this.label = label;
            this.progress = progress;
            this.read = alreadyRead;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value != -1) {
                count(1);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int count) throws IOException {
            int r = super.read(buffer, offset, count);
            if (r > 0) {
                count(r);
            }
            return r;
        }

        private void count(int bytes) {
            read += bytes;
            if (progress == null) {
                return;
            }
            if (length > 0) {
                int percent = (int) Math.min(100L, (read * 100L) / length);
                if (percent != reportedPercent) {
                    reportedPercent = percent;
                    progress.downloaded(percent);
                }
            } else if (read - lastReportedBytes >= 256 * 1024) {
                lastReportedBytes = read;
                double mb = read / (1024.0 * 1024.0);
                progress.report(String.format(Locale.UK,
                        "Downloading %s timetable\u2026 %.1f MB", label, mb));
            }
        }
    }
}
