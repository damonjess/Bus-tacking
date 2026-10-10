package org.bustimes.app;

import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.IBinder;
import android.text.TextUtils;
import android.util.JsonReader;
import android.util.Log;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipInputStream;

public class BusTrackingService extends Service {
    static final String ACTION_BUS_POSITION = "org.bustimes.app.action.BUS_POSITION";
    static final String ACTION_TRACKING_STATUS = "org.bustimes.app.action.TRACKING_STATUS";
    /** Sent once a full vehicle snapshot has been broadcast, so the map can sweep what it lost. */
    static final String ACTION_POLL_COMPLETE = "org.bustimes.app.action.POLL_COMPLETE";
    static final String ACTION_REFRESH_NOW = "org.bustimes.app.action.REFRESH_NOW";
    static final String ACTION_START_MAP_TRACKING = "org.bustimes.app.action.START_MAP_TRACKING";
    static final String ACTION_STOP_MAP_TRACKING = "org.bustimes.app.action.STOP_MAP_TRACKING";
    public static final String ACTION_CLEAR_TRACKING = "org.bustimes.app.CLEAR_TRACKING";
    public static final String EXTRA_BOUNDING_BOX = "bounding_box";
    public static final String DEFAULT_BOUNDING_BOX = "-0.8000,53.5000,-0.5000,53.6600";
    static final String EXTRA_STATUS_MESSAGE = "status_message";

    private static final String TAG = "BusTrackingService";
    private static final long POLL_INTERVAL_SECONDS = 15;
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 20_000;

    private ScheduledExecutorService executorService;
    private ScheduledFuture<?> pollingFuture;
    private boolean mapActive;
    private volatile String activeBoundingBox;
    /** Vehicles seen in the poll currently being parsed, used to check armed arrival alerts. */
    private final List<BusPosition> pollBuffer = new ArrayList<>();
    private int staleHidden;

    @Override
    public void onCreate() {
        super.onCreate();
        executorService = Executors.newSingleThreadScheduledExecutor();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START_MAP_TRACKING : intent.getAction();

        if (intent != null && intent.hasExtra(EXTRA_BOUNDING_BOX)) {
            String bbox = intent.getStringExtra(EXTRA_BOUNDING_BOX);
            if (!TextUtils.isEmpty(bbox)) {
                activeBoundingBox = bbox;
            }
        }

        if (ACTION_STOP_MAP_TRACKING.equals(action)) {
            mapActive = false;
            stopPolling();
            stopSelf();
            return START_NOT_STICKY;
        }

        if (TextUtils.isEmpty(BuildConfig.BODS_API_KEY)) {
            broadcastStatus("Add a BODS_API_KEY to enable live tracking.");
            return START_NOT_STICKY;
        }

        if (ACTION_REFRESH_NOW.equals(action)) {
            if (mapActive) {
                executorService.execute(this::pollBodsVehicleLocations);
            }
            return START_NOT_STICKY;
        }

        mapActive = true;
        startPolling();
        return START_STICKY;
    }

    private void startPolling() {
        if (pollingFuture != null && !pollingFuture.isCancelled()) {
            return;
        }
        AlertNotifier.ensureChannel(this);
        pollingFuture = executorService.scheduleWithFixedDelay(this::pollBodsVehicleLocations,
                0, POLL_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    private void stopPolling() {
        if (pollingFuture != null) {
            pollingFuture.cancel(true);
            pollingFuture = null;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopPolling();
        if (executorService != null) {
            executorService.shutdownNow();
        }
        super.onDestroy();
    }

    private void pollBodsVehicleLocations() {
        HttpURLConnection connection = null;
        pollBuffer.clear();
        staleHidden = 0;
        try {
            URL url = new URL(buildBodsUrl());
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/json,application/xml,text/xml,*/*");
            connection.setRequestProperty("User-Agent", "BusTimesApp/1.0");

            int responseCode = connection.getResponseCode();
            if (responseCode < 200 || responseCode >= 300) {
                broadcastStatus(String.format(Locale.UK, "BODS request failed: HTTP %d", responseCode));
                return;
            }

            try (BufferedInputStream bufferedStream = new BufferedInputStream(connection.getInputStream())) {
                bufferedStream.mark(512);
                int firstByte;
                do {
                    firstByte = bufferedStream.read();
                } while (firstByte != -1 && Character.isWhitespace((char) firstByte));
                bufferedStream.reset();

                int count;
                if (firstByte == 0x50) { // 'P' in PK
                    ZipInputStream zis = new ZipInputStream(bufferedStream);
                    if (zis.getNextEntry() != null) {
                        broadcastClear();
                        count = parseXmlStreaming(zis);
                    } else {
                        count = 0;
                    }
                } else if (firstByte == '{' || firstByte == '[') {
                    broadcastClear();
                    count = parseJsonStreaming(bufferedStream);
                } else {
                    broadcastClear();
                    count = parseXmlStreaming(bufferedStream);
                }
                String hidden = staleHidden > 0
                        ? String.format(Locale.UK, " (%d old reports hidden)", staleHidden) : "";
                broadcastStatus(String.format(Locale.UK, "Updated %d BODS vehicle positions%s", count, hidden));
            }
            // armed arrival alerts are checked against this poll, even with the map off screen
            ArrivalAlerts.evaluate(this, ArrivalAlerts.candidatesFromPositions(new ArrayList<>(pollBuffer)));
            broadcastPollComplete();
        } catch (Exception exception) {
            Log.w(TAG, "Stream processing error", exception);
            broadcastStatus("BODS sync failed: " + exception.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private String buildBodsUrl() {
        Uri.Builder builder = Uri.parse(BuildConfig.BODS_API_BASE_URL).buildUpon()
                .appendQueryParameter("api_key", BuildConfig.BODS_API_KEY);
        String bbox = BuildConfig.BODS_BOUNDING_BOX;
        if (TextUtils.isEmpty(bbox)) {
            bbox = activeBoundingBox;
        }
        if (TextUtils.isEmpty(bbox)) {
            bbox = DEFAULT_BOUNDING_BOX;
        }
        // keep the vehicles that can fire an armed alert in the feed, wherever they are
        bbox = ArrivalAlertStore.expandBoundingBox(this, bbox);
        builder.appendQueryParameter("boundingBox", bbox);
        return builder.build().toString();
    }

    private int parseXmlStreaming(InputStream stream) throws Exception {
        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(stream, null);
        int eventType = parser.getEventType();
        int count = 0;

        String id = "", lineName = "", lineRef = "", destination = "", eta = "", recordedAt = "", occupancy = "", operator = "";
        double latitude = Double.NaN, longitude = Double.NaN;
        float bearing = Float.NaN;

        while (eventType != XmlPullParser.END_DOCUMENT) {
            String tagName = parser.getName();
            if (eventType == XmlPullParser.START_TAG) {
                if ("VehicleActivity".equalsIgnoreCase(tagName)) {
                    id = ""; lineName = ""; lineRef = ""; destination = ""; eta = "";
                    recordedAt = ""; occupancy = ""; operator = "";
                    latitude = Double.NaN; longitude = Double.NaN; bearing = Float.NaN;
                } else if ("RecordedAtTime".equalsIgnoreCase(tagName)) {
                    recordedAt = parser.nextText();
                } else if ("VehicleRef".equalsIgnoreCase(tagName)) {
                    id = parser.nextText();
                } else if ("PublishedLineName".equalsIgnoreCase(tagName)) {
                    lineName = parser.nextText();
                } else if ("LineRef".equalsIgnoreCase(tagName)) {
                    lineRef = parser.nextText();
                } else if ("DestinationName".equalsIgnoreCase(tagName)) {
                    destination = tidyName(parser.nextText());
                } else if ("OperatorRef".equalsIgnoreCase(tagName)) {
                    operator = parser.nextText();
                } else if ("ExpectedArrivalTime".equalsIgnoreCase(tagName)) {
                    eta = parser.nextText();
                } else if ("Bearing".equalsIgnoreCase(tagName)) {
                    bearing = parseFloat(parser.nextText(), Float.NaN);
                } else if ("Latitude".equalsIgnoreCase(tagName)) {
                    latitude = parseDouble(parser.nextText(), Double.NaN);
                } else if ("Longitude".equalsIgnoreCase(tagName)) {
                    longitude = parseDouble(parser.nextText(), Double.NaN);
                } else if ("OccupancyStatus".equalsIgnoreCase(tagName) || "Occupancy".equalsIgnoreCase(tagName)) {
                    occupancy = normalizeOccupancy(parser.nextText());
                }
            } else if (eventType == XmlPullParser.END_TAG) {
                if ("VehicleActivity".equalsIgnoreCase(tagName)) {
                    if (!Double.isNaN(latitude) && !Double.isNaN(longitude)) {
                        String busId = !TextUtils.isEmpty(id) ? id : lineRef + ":" + count;
                        if (broadcastPosition(new BusPosition(busId, firstNonEmpty(lineName, lineRef, "Bus"),
                                lineRef, destination, eta, latitude, longitude, bearing, recordedAt, occupancy, operator))) {
                            count++;
                        }
                    }
                }
            }
            eventType = parser.next();
        }
        return count;
    }

    private int parseJsonStreaming(InputStream stream) throws Exception {
        JsonReader reader = new JsonReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
        int count = 0;
        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            if ("activities".equalsIgnoreCase(name) || "VehicleActivity".equalsIgnoreCase(name) || "data".equalsIgnoreCase(name)) {
                reader.beginArray();
                while (reader.hasNext()) {
                    BusPosition pos = readJsonVehicle(reader, count);
                    if (pos != null && broadcastPosition(pos)) {
                        count++;
                    }
                }
                reader.endArray();
            } else {
                reader.skipValue();
            }
        }
        reader.endObject();
        return count;
    }

    private BusPosition readJsonVehicle(JsonReader reader, int index) throws Exception {
        String id = "", lineName = "", lineRef = "", destination = "", eta = "", recordedAt = "", occupancy = "", operator = "";
        double latitude = Double.NaN, longitude = Double.NaN;
        float bearing = Float.NaN;

        reader.beginObject();
        while (reader.hasNext()) {
            String key = reader.nextName();
            if ("RecordedAtTime".equalsIgnoreCase(key)) {
                recordedAt = reader.nextString();
            } else if ("MonitoredVehicleJourney".equalsIgnoreCase(key)) {
                reader.beginObject();
                while (reader.hasNext()) {
                    String jKey = reader.nextName();
                    if ("VehicleRef".equalsIgnoreCase(jKey)) id = reader.nextString();
                    else if ("PublishedLineName".equalsIgnoreCase(jKey)) lineName = reader.nextString();
                    else if ("LineRef".equalsIgnoreCase(jKey)) lineRef = reader.nextString();
                    else if ("DestinationName".equalsIgnoreCase(jKey)) destination = tidyName(reader.nextString());
                    else if ("OperatorRef".equalsIgnoreCase(jKey)) operator = reader.nextString();
                    else if ("Bearing".equalsIgnoreCase(jKey)) bearing = (float) reader.nextDouble();
                    else if ("VehicleLocation".equalsIgnoreCase(jKey)) {
                        reader.beginObject();
                        while (reader.hasNext()) {
                            String lKey = reader.nextName();
                            if ("Latitude".equalsIgnoreCase(lKey)) latitude = reader.nextDouble();
                            else if ("Longitude".equalsIgnoreCase(lKey)) longitude = reader.nextDouble();
                            else reader.skipValue();
                        }
                        reader.endObject();
                    } else reader.skipValue();
                }
                reader.endObject();
            } else {
                reader.skipValue();
            }
        }
        reader.endObject();

        if (Double.isNaN(latitude) || Double.isNaN(longitude)) {
            return null;
        }
        String busId = !TextUtils.isEmpty(id) ? id : "bus:" + index;
        return new BusPosition(busId, firstNonEmpty(lineName, lineRef, "Bus"), lineRef, destination,
                eta, latitude, longitude, bearing, recordedAt, occupancy, operator);
    }

    private boolean broadcastPosition(BusPosition position) {
        if (!FixAge.isLive(position.recordedAt, System.currentTimeMillis())) {
            staleHidden++;
            return false;
        }
        Intent intent = new Intent(ACTION_BUS_POSITION);
        intent.setPackage(getPackageName());
        intent.putExtra(BusPosition.EXTRA_ID, position.id);
        intent.putExtra(BusPosition.EXTRA_LINE_NAME, position.lineName);
        intent.putExtra(BusPosition.EXTRA_LINE_REF, position.lineRef);
        intent.putExtra(BusPosition.EXTRA_DESTINATION_NAME, position.destinationName);
        intent.putExtra(BusPosition.EXTRA_EXPECTED_ARRIVAL_TIME, position.expectedArrivalTime);
        intent.putExtra(BusPosition.EXTRA_LATITUDE, position.latitude);
        intent.putExtra(BusPosition.EXTRA_LONGITUDE, position.longitude);
        intent.putExtra(BusPosition.EXTRA_BEARING, position.bearing);
        intent.putExtra(BusPosition.EXTRA_RECORDED_AT, position.recordedAt);
        intent.putExtra(BusPosition.EXTRA_OCCUPANCY, position.occupancy);
        intent.putExtra(BusPosition.EXTRA_OPERATOR, position.operatorName);
        sendBroadcast(intent);
        pollBuffer.add(position);
        return true;
    }

    private void broadcastClear() {
        Intent intent = new Intent(ACTION_CLEAR_TRACKING);
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
    }

    private void broadcastPollComplete() {
        Intent intent = new Intent(ACTION_POLL_COMPLETE);
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
    }

    private void broadcastStatus(String message) {
        Intent intent = new Intent(ACTION_TRACKING_STATUS);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_STATUS_MESSAGE, message);
        sendBroadcast(intent);
    }

    private static String tidyName(String value) {
        return value == null ? "" : value.replace("__", ", ").replace('_', ' ').trim();
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (!TextUtils.isEmpty(value)) return value;
        }
        return "";
    }

    private static String normalizeOccupancy(String value) {
        if (TextUtils.isEmpty(value)) return "Information Unknown";
        String lower = value.toLowerCase(Locale.UK);
        if (lower.contains("full") || lower.contains("crowded")) return "Full/Crowded";
        if (lower.contains("standing")) return "Standing Room Only";
        if (lower.contains("seats") || lower.contains("empty")) return "Easy Seating";
        return "Information Unknown";
    }

    private static float parseFloat(String value, float fallback) {
        try {
            return TextUtils.isEmpty(value) ? fallback : Float.parseFloat(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double parseDouble(String value, double fallback) {
        try {
            return TextUtils.isEmpty(value) ? fallback : Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
