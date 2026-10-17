package org.bustimes.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The timetables half of the DfT Bus Open Data Service.
 *
 * The live feed the map already uses (SIRI-VM) carries no timetable, so a stop's scheduled times come
 * from the TransXChange documents operators publish. This class lists the datasets covering an ATCO
 * area and pulls the times one stop gets out of a dataset, whether it is served as a single XML file
 * or as a zip of them.
 */
final class BodsTimetables {

    /** Dataset discovery, the same API host as the live feed. */
    private static final String DATASET_API =
            "https://data.bus-data.dft.gov.uk/api/v1/dataset/";

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 30_000;

    /** A big timetable crossing a flaky connection is worth a few tries, resuming each time. */
    private static final int DOWNLOAD_ATTEMPTS = 3;

    private static final int HTTP_OK = 200;
    private static final int HTTP_PARTIAL = 206;

    /** Where the download progress is reported, as a whole percentage at most. */
    interface Progress {
        void report(String message);

        /** Called for each byte read, so a caller can show how far a large file has got. */
        void downloaded(int percent);
    }

    /** One published timetable dataset, as the dataset API describes it. */
    static final class Dataset {
        final long id;
        final String modified;
        final String operator;
        final String description;
        final String url;
        /** "zip" when the download is a zip of TransXChange documents, "xml" for a single one. */
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

        /** True when this dataset publishes a timetable for the given route number. */
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

        /** Stable key for cached results: a new upload of the same dataset must invalidate them. */
        String versionKey() {
            return id + "-" + modified;
        }

        /** Largest first would be slowest first; smallest first gets times on screen sooner. */
        static final Comparator<Dataset> BY_LINES = new Comparator<Dataset>() {
            @Override
            public int compare(Dataset a, Dataset b) {
                return Integer.compare(a.lines.size(), b.lines.size());
            }
        };
    }

    private BodsTimetables() { }

    /**
     * The published timetables covering a stop, keyed by the ATCO area code, which is the first
     * three digits of any NaPTAN stop reference.
     */
    static List<Dataset> datasetsForArea(String apiKey, String areaCode) throws Exception {
        String url = DATASET_API + "?api_key=" + apiKey + "&adminArea=" + areaCode + "&limit=100";
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
        return datasets;
    }

    /**
     * The dataset on disk, downloading it once if needed.
     *
     * An operator's timetable is tens of megabytes and one file covers every stop that operator
     * serves, so a second stop in the same area must never download it again: the file is kept, and
     * only a genuinely new upload of the dataset (a different {@code modified} stamp) replaces it.
     */
    static File fetch(Dataset dataset, File directory, Progress progress) throws Exception {
        File file = datasetFile(directory, dataset);
        if (file.isFile() && isFresh(file)) {
            if (progress != null) {
                progress.report("Reading the saved " + dataset.label() + " timetable\u2026");
            }
            return file;
        }
        if (dataset.url.isEmpty()) {
            throw new IOException("No download link for " + dataset.label() + ".");
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
                // The part file is kept on purpose: a stalled download resumes where it stopped
                // instead of starting the whole 50 MB again.
                lastFailure = e;
            }
        }
        throw new IOException("The " + dataset.label() + " timetable download failed ("
                + (lastFailure == null ? "unknown reason" : lastFailure.getMessage()) + ").", lastFailure);
    }

    /** Fetches one dataset into {@code partial}, resuming from whatever is already there. */
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
            boolean resuming = code == HTTP_PARTIAL && already > 0;
            if (code != HTTP_OK && code != HTTP_PARTIAL) {
                throw new IOException("HTTP " + code + " for " + dataset.label());
            }
            if (!resuming) {
                already = 0;
            }
            if (progress != null && reportResume) {
                progress.report("Continuing the " + dataset.label() + " timetable download\u2026");
            }
            long length = connection.getContentLength() + already;
            InputStream stream = new ProgressStream(
                    new BufferedInputStream(connection.getInputStream()), length, progress, already);
            try (OutputStream out = new FileOutputStream(partial, resuming)) {
                byte[] buffer = new byte[16384];
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

    /** How long a downloaded timetable file is kept before a stop asks for a fresh copy. */
    static final long FILE_TTL_MS = 14L * 24 * 60 * 60 * 1000;

    /** The file a dataset is stored in: a new upload of it gets a new name, so it never goes stale. */
    static File datasetFile(File directory, Dataset dataset) {
        String stamp = dataset.modified.replaceAll("[^0-9T]", "");
        String extension = dataset.extension.isEmpty() ? "bin" : dataset.extension.toLowerCase(Locale.UK);
        return new File(directory, "dataset-" + dataset.id + "-" + stamp + "." + extension);
    }

    static boolean isFresh(File file) {
        return System.currentTimeMillis() - file.lastModified() <= FILE_TTL_MS;
    }

    /** Drops timetable files that have aged out, so the cache cannot grow without limit. */
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

    /** The scheduled departures one stored dataset gives a stop. */
    static List<TxcTimetable.Departure> departuresFrom(File file, String extension, String atcoCode)
            throws Exception {
        List<TxcTimetable.Departure> departures = new ArrayList<>();
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            if ("zip".equalsIgnoreCase(extension)) {
                ZipInputStream zip = new ZipInputStream(in);
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if (entry.isDirectory() || !isTimetableDocument(entry.getName())) {
                        continue;
                    }
                    departures.addAll(TxcTimetable.parse(zip, atcoCode));
                }
            } else {
                departures.addAll(TxcTimetable.parse(in, atcoCode));
            }
        }
        return departures;
    }

    /** The datasets that publish a timetable for at least one of the routes at a stop. */
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
            StringBuilder body = new StringBuilder();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                body.append(new String(buffer, 0, read, "UTF-8"));
            }
            return body.toString();
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * Reports how much of a download has arrived.
     *
     * A single operator's timetable runs to tens of megabytes, so a percentage is the difference
     * between a sheet that looks broken and one that is plainly working.
     */
    private static final class ProgressStream extends FilterInputStream {
        private final long length;
        private final Progress progress;
        private long read;
        private int reported = -1;

        ProgressStream(InputStream in, long length, Progress progress, long alreadyRead) {
            super(in);
            this.length = length;
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
            int read = super.read(buffer, offset, count);
            if (read > 0) {
                count(read);
            }
            return read;
        }

        private void count(int bytes) {
            read += bytes;
            if (progress == null || length <= 0) {
                return;
            }
            int percent = (int) (read * 100 / length);
            if (percent != reported) {
                reported = percent;
                progress.downloaded(percent);
            }
        }
    }
}
