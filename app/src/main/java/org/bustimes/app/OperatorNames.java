package org.bustimes.app;

import android.content.Context;
import android.util.Log;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Turns an operator code from the live feed (for example "HNTS") into its public name, using the
 * official Traveline National Operator Codes database.
 *
 * The database is downloaded once, reduced to code and name pairs, cached on disk for 30 days and kept
 * in memory. Until it has loaded, or for a code that is not in it, the raw code is shown rather than a
 * guessed name.
 */
final class OperatorNames {

    private static final String TAG = "OperatorNames";
    private static final String NOC_URL = "https://www.travelinedata.org.uk/noc/api/1.0/nocrecords.xml";
    private static final String NOC_ENCODING = "windows-1252";
    private static final long CACHE_TTL_MS = 30L * 24 * 60 * 60 * 1000;
    private static final long RETRY_AFTER_FAILURE_MS = 6L * 60 * 60 * 1000;

    private static final Map<String, String> NAMES = new ConcurrentHashMap<>();
    private static final AtomicBoolean LOADING = new AtomicBoolean(false);
    private static volatile long lastFailureMs;

    private OperatorNames() { }

    /** Public name for an operator code, or the code itself when unknown. Empty stays empty. */
    static String display(String code) {
        if (code == null || code.isEmpty()) {
            return "";
        }
        String name = NAMES.get(code);
        return name == null ? code : name;
    }

    /** How many operator names are loaded. Useful for a debug line. */
    static int size() {
        return NAMES.size();
    }

    private static File cacheFile(Context context) {
        return new File(context.getCacheDir(), "noc_operator_names.tsv");
    }

    /** Blocking: call from a background thread. Safe to call repeatedly. */
    static void load(Context context) {
        if (!LOADING.compareAndSet(false, true)) {
            return;
        }
        try {
            long now = System.currentTimeMillis();
            File file = cacheFile(context);
            boolean haveCache = file.isFile();
            boolean fresh = haveCache && now - file.lastModified() < CACHE_TTL_MS;
            if (NAMES.isEmpty() && haveCache) {
                readCache(file);
            }
            if (fresh || now - lastFailureMs < RETRY_AFTER_FAILURE_MS) {
                return;
            }
            try {
                download(file);
            } catch (Exception e) {
                lastFailureMs = now;
                Log.w(TAG, "Could not download operator names", e);
            }
        } finally {
            LOADING.set(false);
        }
    }

    private static void download(File file) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(NOC_URL).openConnection();
        try {
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(60_000);
            connection.setRequestProperty("User-Agent", "BusTimesLive/1.0 (Android)");
            int code = connection.getResponseCode();
            if (code != 200) {
                throw new IOException("HTTP " + code);
            }
            Map<String, String> parsed = new HashMap<>();
            try (InputStream in = new BufferedInputStream(connection.getInputStream())) {
                XmlPullParser parser = Xml.newPullParser();
                parser.setInput(in, NOC_ENCODING);
                NocParser.parse(parser, parsed);
            }
            if (parsed.isEmpty()) {
                throw new IOException("No operator names found in the NOC file");
            }
            NAMES.putAll(parsed);
            writeCache(file, parsed);
        } finally {
            connection.disconnect();
        }
    }

    private static void readCache(File file) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int tab = line.indexOf('\t');
                if (tab > 0 && tab < line.length() - 1) {
                    NAMES.put(line.substring(0, tab), line.substring(tab + 1));
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "Could not read operator name cache", e);
        }
    }

    private static void writeCache(File file, Map<String, String> names) {
        File temp = new File(file.getParentFile(), file.getName() + ".tmp");
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(temp), StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> entry : names.entrySet()) {
                writer.write(entry.getKey() + "\t" + entry.getValue().replace('\t', ' ').replace('\n', ' ') + "\n");
            }
        } catch (IOException e) {
            Log.w(TAG, "Could not write operator name cache", e);
            return;
        }
        if (!temp.renameTo(file)) {
            temp.delete();
        }
    }
}
