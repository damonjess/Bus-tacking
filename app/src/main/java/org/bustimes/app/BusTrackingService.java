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
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.xml.parsers.DocumentBuilderFactory;

public class BusTrackingService extends Service {
    static final String ACTION_BUS_POSITION = "org.bustimes.app.action.BUS_POSITION";
    static final String ACTION_TRACKING_STATUS = "org.bustimes.app.action.TRACKING_STATUS";
    static final String ACTION_REFRESH_NOW = "org.bustimes.app.action.REFRESH_NOW";
    static final String ACTION_START_MAP_TRACKING = "org.bustimes.app.action.START_MAP_TRACKING";
    static final String ACTION_STOP_MAP_TRACKING = "org.bustimes.app.action.STOP_MAP_TRACKING";
    static final String EXTRA_STATUS_MESSAGE = "status_message";

    private static final String TAG = "BusTrackingService";
    private static final long POLL_INTERVAL_SECONDS = 15;
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 20_000;

    private ScheduledExecutorService executorService;
    private ScheduledFuture<?> pollingFuture;
    private boolean mapActive;

    @Override
    public void onCreate() {
        super.onCreate();
        executorService = Executors.newSingleThreadScheduledExecutor();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START_MAP_TRACKING : intent.getAction();

        if (ACTION_STOP_MAP_TRACKING.equals(action)) {
            mapActive = false;
            stopPolling();
            stopSelf();
            return START_NOT_STICKY;
        }

        if (TextUtils.isEmpty(BuildConfig.BODS_API_KEY)) {
            broadcastStatus("Add a BODS_API_KEY GitHub secret or Gradle property to enable live BODS tracking.");
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
        pollingFuture = executorService.scheduleWithFixedDelay(this::pollBodsVehicleLocations,
                0,
                POLL_INTERVAL_SECONDS,
                TimeUnit.SECONDS);
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
        try {
            URL url = new URL(buildBodsUrl());
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/json,application/xml,text/xml,*/*");

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
        byte[] body = readAllBytes(stream);
        String payload = new String(body, "UTF-8").trim();
        if (payload.startsWith("{") || payload.startsWith("[")) {
            return parseJsonVehiclePositions(payload);
        }
        return parseXmlVehiclePositions(body);
    }

    private List<BusPosition> parseXmlVehiclePositions(byte[] body) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(body));
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
            String destinationName = text(journey, "DestinationName");
            String expectedArrivalTime = firstNonEmpty(text(journey, "ExpectedArrivalTime"), text(journey, "AimedArrivalTime"));
            String id = firstNonEmpty(vehicleRef, datedJourneyRef, lineRef + ":" + i);
            float bearing = parseFloat(text(journey, "Bearing"), Float.NaN);
            String recordedAt = text(activity, "RecordedAtTime");
            String occupancy = normalizeOccupancy(firstNonEmpty(
                    text(journey, "OccupancyStatus"),
                    text(journey, "Occupancy"),
                    text(journey, "VehicleOccupancy"),
                    text(journey, "PassengerCount"),
                    text(activity, "OccupancyStatus"),
                    text(activity, "Occupancy")));

            positions.add(new BusPosition(id, firstNonEmpty(lineName, lineRef, "Bus"), lineRef, destinationName,
                    expectedArrivalTime, latitude, longitude, bearing, recordedAt, occupancy));
        }

        return positions;
    }

    private List<BusPosition> parseJsonVehiclePositions(String payload) throws Exception {
        List<BusPosition> positions = new ArrayList<>();
        Object root = payload.startsWith("[") ? new JSONArray(payload) : new JSONObject(payload);
        collectJsonVehicleActivities(root, positions);
        return positions;
    }

    private void collectJsonVehicleActivities(Object node, List<BusPosition> positions) throws Exception {
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            JSONObject journey = object.optJSONObject("MonitoredVehicleJourney");
            if (journey != null) {
                BusPosition position = positionFromJsonVehicleActivity(object, journey, positions.size());
                if (position != null) {
                    positions.add(position);
                }
            }
            JSONArray names = object.names();
            if (names == null) {
                return;
            }
            for (int i = 0; i < names.length(); i++) {
                collectJsonVehicleActivities(object.opt(names.getString(i)), positions);
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                collectJsonVehicleActivities(array.opt(i), positions);
            }
        }
    }

    private BusPosition positionFromJsonVehicleActivity(JSONObject activity, JSONObject journey, int index) {
        JSONObject location = journey.optJSONObject("VehicleLocation");
        if (location == null) {
            return null;
        }
        double latitude = location.optDouble("Latitude", Double.NaN);
        double longitude = location.optDouble("Longitude", Double.NaN);
        if (Double.isNaN(latitude) || Double.isNaN(longitude)) {
            return null;
        }

        String vehicleRef = jsonText(journey, "VehicleRef");
        String datedJourneyRef = jsonText(journey, "DatedVehicleJourneyRef");
        String lineRef = jsonText(journey, "LineRef");
        String lineName = jsonText(journey, "PublishedLineName");
        String destinationName = jsonText(journey, "DestinationName");
        String expectedArrivalTime = firstNonEmpty(jsonText(journey, "ExpectedArrivalTime"), jsonText(journey, "AimedArrivalTime"),
                jsonNestedText(journey, "MonitoredCall", "ExpectedArrivalTime"));
        String id = firstNonEmpty(vehicleRef, datedJourneyRef, lineRef + ":" + index);
        float bearing = parseFloat(jsonText(journey, "Bearing"), Float.NaN);
        String recordedAt = jsonText(activity, "RecordedAtTime");
        String occupancy = normalizeOccupancy(firstNonEmpty(
                jsonText(journey, "OccupancyStatus"),
                jsonText(journey, "Occupancy"),
                jsonText(journey, "VehicleOccupancy"),
                jsonText(journey, "PassengerCount"),
                jsonText(activity, "OccupancyStatus"),
                jsonText(activity, "Occupancy"),
                jsonNestedText(journey, "VehicleJourney", "OccupancyStatus"),
                jsonNestedText(journey, "VehicleJourney", "Occupancy"),
                jsonNestedText(activity, "VehicleJourney", "OccupancyStatus"),
                jsonNestedText(activity, "VehicleJourney", "Occupancy")));
        return new BusPosition(id, firstNonEmpty(lineName, lineRef, "Bus"), lineRef, destinationName,
                expectedArrivalTime, latitude, longitude, bearing, recordedAt, occupancy);
    }

    private static String jsonNestedText(JSONObject parent, String objectName, String key) {
        JSONObject object = parent == null ? null : parent.optJSONObject(objectName);
        return object == null ? "" : jsonText(object, key);
    }

    private static String jsonText(JSONObject parent, String key) {
        if (parent == null || !parent.has(key) || parent.isNull(key)) {
            return "";
        }
        Object value = parent.opt(key);
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            return firstNonEmpty(object.optString("value", ""), object.optString("Value", ""), object.toString());
        }
        return String.valueOf(value).trim();
    }

    private static byte[] readAllBytes(InputStream stream) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = stream.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private void broadcastPosition(BusPosition position) {
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
        return "";
    }

    private static String normalizeOccupancy(String value) {
        if (TextUtils.isEmpty(value)) {
            return "Information Unknown";
        }
        String lower = value.toLowerCase(Locale.UK).replace("_", "").replace("-", "").replace(" ", "");
        if (lower.contains("full") || lower.contains("crowded") || lower.contains("noseats")
                || lower.contains("crushedstandingroomonly") || lower.contains("notacceptingpassengers")
                || lower.contains("atcapacity") || lower.contains("high")) {
            return "Full/Crowded";
        }
        if (lower.contains("standing") || lower.contains("limited") || lower.contains("fewseatsavailable")
                || lower.contains("medium") || lower.contains("half")) {
            return "Standing Room Only";
        }
        if (lower.contains("empty") || lower.contains("manyseatsavailable") || lower.contains("seatsavailable")
                || lower.contains("low") || lower.contains("quiet") || lower.contains("easy")) {
            return "Easy Seating";
        }
        try {
            int passengerCount = Integer.parseInt(value.trim());
            if (passengerCount >= 45) {
                return "Full/Crowded";
            }
            if (passengerCount >= 20) {
                return "Standing Room Only";
            }
            return "Easy Seating";
        } catch (NumberFormatException exception) {
            return "Information Unknown";
        }
    }

    private static float parseFloat(String value, float fallback) {
        try {
            return TextUtils.isEmpty(value) ? fallback : Float.parseFloat(value);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }
}
