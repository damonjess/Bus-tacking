package org.bustimes.app;

import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.IBinder;
import android.text.TextUtils;
import android.util.Log;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.xml.parsers.DocumentBuilderFactory;

public class BusTrackingService extends Service {
    static final String ACTION_BUS_POSITION = "org.bustimes.app.action.BUS_POSITION";
    static final String ACTION_TRACKING_STATUS = "org.bustimes.app.action.TRACKING_STATUS";
    static final String ACTION_REFRESH_NOW = "org.bustimes.app.action.REFRESH_NOW";
    static final String EXTRA_STATUS_MESSAGE = "status_message";

    private static final String TAG = "BusTrackingService";
    private static final long POLL_INTERVAL_SECONDS = 30;
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 20_000;

    private ScheduledExecutorService executorService;
    private boolean pollingStarted;

    @Override
    public void onCreate() {
        super.onCreate();
        executorService = Executors.newSingleThreadScheduledExecutor();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (TextUtils.isEmpty(BuildConfig.BODS_API_KEY)) {
            broadcastStatus("Add a BODS_API_KEY GitHub secret or Gradle property to enable live BODS tracking.");
            return START_NOT_STICKY;
        }

        boolean refreshRequested = intent != null && ACTION_REFRESH_NOW.equals(intent.getAction());
        if (refreshRequested) {
            executorService.execute(this::pollBodsVehicleLocations);
        }

        if (!pollingStarted) {
            executorService.scheduleWithFixedDelay(this::pollBodsVehicleLocations,
                    refreshRequested ? POLL_INTERVAL_SECONDS : 0,
                    POLL_INTERVAL_SECONDS,
                    TimeUnit.SECONDS);
            pollingStarted = true;
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        if (executorService != null) {
            executorService.shutdownNow();
        }
        super.onDestroy();
    }

    private void pollBodsVehicleLocations() {
        try {
            URL url = new URL(buildBodsUrl());
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/xml,text/xml,*/*");

            int responseCode = connection.getResponseCode();
            if (responseCode < 200 || responseCode >= 300) {
                broadcastStatus(String.format(Locale.UK, "BODS request failed: HTTP %d", responseCode));
                connection.disconnect();
                return;
            }

            try (InputStream stream = connection.getInputStream()) {
                List<BusPosition> positions = parseSiriVehiclePositions(stream);
                for (BusPosition position : positions) {
                    broadcastPosition(position);
                }
                broadcastStatus(String.format(Locale.UK, "Updated %d BODS vehicle positions", positions.size()));
            } finally {
                connection.disconnect();
            }
        } catch (Exception exception) {
            Log.w(TAG, "Unable to poll BODS SIRI-VM feed", exception);
            broadcastStatus("Unable to update BODS vehicle positions: " + exception.getMessage());
        }
    }

    private String buildBodsUrl() {
        Uri.Builder builder = Uri.parse(BuildConfig.BODS_API_BASE_URL).buildUpon()
                .appendQueryParameter("api_key", BuildConfig.BODS_API_KEY);
        if (!TextUtils.isEmpty(BuildConfig.BODS_BOUNDING_BOX)) {
            builder.appendQueryParameter("boundingBox", BuildConfig.BODS_BOUNDING_BOX);
        }
        return builder.build().toString();
    }

    private List<BusPosition> parseSiriVehiclePositions(InputStream stream) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document document = factory.newDocumentBuilder().parse(stream);
        NodeList activities = document.getElementsByTagNameNS("*", "VehicleActivity");
        List<BusPosition> positions = new ArrayList<>();

        for (int i = 0; i < activities.getLength(); i++) {
            Node item = activities.item(i);
            if (!(item instanceof Element)) {
                continue;
            }

            Element activity = (Element) item;
            Element journey = firstChild(activity, "MonitoredVehicleJourney");
            Element location = journey == null ? null : firstChild(journey, "VehicleLocation");
            if (journey == null || location == null) {
                continue;
            }

            String latitudeText = text(location, "Latitude");
            String longitudeText = text(location, "Longitude");
            if (TextUtils.isEmpty(latitudeText) || TextUtils.isEmpty(longitudeText)) {
                continue;
            }

            double latitude = Double.parseDouble(latitudeText);
            double longitude = Double.parseDouble(longitudeText);
            String vehicleRef = text(journey, "VehicleRef");
            String datedJourneyRef = text(journey, "DatedVehicleJourneyRef");
            String lineRef = text(journey, "LineRef");
            String lineName = text(journey, "PublishedLineName");
            String id = firstNonEmpty(vehicleRef, datedJourneyRef, lineRef + ":" + i);
            float bearing = parseFloat(text(journey, "Bearing"), Float.NaN);
            String recordedAt = text(activity, "RecordedAtTime");

            positions.add(new BusPosition(id, firstNonEmpty(lineName, lineRef, "Bus"), latitude, longitude, bearing, recordedAt));
        }

        return positions;
    }

    private void broadcastPosition(BusPosition position) {
        Intent intent = new Intent(ACTION_BUS_POSITION);
        intent.setPackage(getPackageName());
        intent.putExtra(BusPosition.EXTRA_ID, position.id);
        intent.putExtra(BusPosition.EXTRA_LINE_NAME, position.lineName);
        intent.putExtra(BusPosition.EXTRA_LATITUDE, position.latitude);
        intent.putExtra(BusPosition.EXTRA_LONGITUDE, position.longitude);
        intent.putExtra(BusPosition.EXTRA_BEARING, position.bearing);
        intent.putExtra(BusPosition.EXTRA_RECORDED_AT, position.recordedAt);
        sendBroadcast(intent);
    }

    private void broadcastStatus(String message) {
        Intent intent = new Intent(ACTION_TRACKING_STATUS);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_STATUS_MESSAGE, message);
        sendBroadcast(intent);
    }

    private static Element firstChild(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagNameNS("*", tagName);
        if (nodes.getLength() == 0 || !(nodes.item(0) instanceof Element)) {
            return null;
        }
        return (Element) nodes.item(0);
    }

    private static String text(Element parent, String tagName) {
        Element child = firstChild(parent, tagName);
        if (child == null) {
            return "";
        }
        return child.getTextContent().trim();
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (!TextUtils.isEmpty(value)) {
                return value;
            }
        }
        return "unknown";
    }

    private static float parseFloat(String value, float fallback) {
        try {
            return TextUtils.isEmpty(value) ? fallback : Float.parseFloat(value);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }
}
