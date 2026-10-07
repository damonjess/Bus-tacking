package org.bustimes.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.GeomagneticField;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.location.Location;
import android.net.Uri;
import android.os.Build;
import android.util.AttributeSet;
import android.util.Size;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Location-based AR view: live camera + GPS + compass. Places real nearby bus
 * stops (OpenStreetMap Overpass) and live BODS buses in the camera view, with a
 * neon navigation wall, compass ribbon, radar, edge chevrons and tap details.
 */
public class ArBusStopView extends FrameLayout implements SensorEventListener {
    private static final float GPS_LOCK_ACCURACY_METERS = 10f;
    private static final float REROUTE_THRESHOLD_METERS = 5f;
    private static final int MAX_DIRECTIONS_POINTS = 160;
    private static final long CALIBRATION_BYPASS_DELAY_MS = 5_000L;
    private static final float HALF_FOV_DEGREES = 32f;      // approximate horizontal half-FOV
    private static final int EDGE_CHEVRON_INSET_DP = 18;
    private static final float STOPS_FETCH_RADIUS_METERS = 150f;
    private static final float OVERPASS_SEARCH_RADIUS_METERS = 10000f;
    private static final float ARRIVAL_DISTANCE_METERS = 40f;
    private static final int MAX_VISIBLE_STOPS = 8;

    private final TextureView cameraPreview;
    private final TextView statusView;
    private final TextView targetChip;
    private final Button bypassCalibrationButton;
    private final Button exitArButton;
    private final PathOverlayView pathOverlayView;
    private final List<BusStopPin> stopPins = new ArrayList<>();
    private final List<BusBillboard> busBillboards = new ArrayList<>();
    private final SensorManager sensorManager;
    private final Sensor rotationSensor;
    private final Sensor accelerometerSensor;
    private final Sensor magneticSensor;
    private final ExecutorService directionsExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService stopsExecutor = Executors.newSingleThreadExecutor();
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private Size previewSize;
    private String routeFilter = "";
    private final float[] smoothedAcceleration = new float[3];
    private final float[] smoothedMagneticField = new float[3];
    private boolean hasAcceleration;
    private boolean hasMagneticField;
    private float compassBearing;
    private float smoothedCompassBearing;
    private boolean sensorsRunning;
    private boolean cameraRequested;
    private Location userLocation;
    private NavigationTarget navigationTarget;
    private long lastDirectionsRequestMs;
    private boolean gpsLocked;
    private boolean arCoreDepthReady;
    private boolean calibrationBypassed;
    private boolean bypassButtonScheduled;
    private boolean arrivalAnnounced;
    private boolean fetchingStops;
    private Location lastStopsFetchLocation;
    private ArBusTapListener busTapListener;

    /** Callback so AR taps can open the rich bus details sheet in the activity. */
    public interface ArBusTapListener {
        void onBusTapped(BusSnapshot snapshot);
    }

    public ArBusStopView(Context context) {
        this(context, null);
    }

    public ArBusStopView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setBackgroundColor(Color.rgb(10, 16, 32));
        sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        rotationSensor = sensorManager == null ? null : sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        accelerometerSensor = sensorManager == null ? null : sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        magneticSensor = sensorManager == null ? null : sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);

        cameraPreview = new TextureView(context);
        cameraPreview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                if (cameraRequested) {
                    openCamera();
                }
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
                openCamera();
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                stopCamera();
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {
            }
        });
        addView(cameraPreview, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        pathOverlayView = new PathOverlayView(context);
        addView(pathOverlayView, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        statusView = new TextView(context);
        statusView.setTextColor(Color.WHITE);
        statusView.setTextSize(14);
        statusView.setGravity(Gravity.CENTER);
        statusView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        statusView.setBackground(UiTheme.pill(context, UiTheme.withAlpha(UiTheme.INK_LIGHT, 205),
                UiTheme.withAlpha(Color.WHITE, 40), 1f, 18f));
        statusView.setPadding(dp(16), dp(8), dp(16), dp(8));
        addView(statusView, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL) {
            {
                setMargins(dp(70), dp(14), dp(70), 0);
            }
        });

        targetChip = new TextView(context);
        targetChip.setTextColor(Color.WHITE);
        targetChip.setTextSize(12);
        targetChip.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        targetChip.setGravity(Gravity.CENTER);
        targetChip.setBackground(UiTheme.pill(context, UiTheme.withAlpha(UiTheme.BLUE, 60),
                UiTheme.withAlpha(UiTheme.CYAN, 160), 1f, 18f));
        targetChip.setPadding(dp(16), dp(7), dp(16), dp(7));
        targetChip.setVisibility(View.GONE);
        addView(targetChip, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL) {
            {
                setMargins(0, dp(64), 0, 0);
            }
        });

        bypassCalibrationButton = new Button(context);
        bypassCalibrationButton.setText("Bypass calibration");
        bypassCalibrationButton.setAllCaps(false);
        bypassCalibrationButton.setTextSize(13);
        bypassCalibrationButton.setTextColor(Color.WHITE);
        bypassCalibrationButton.setStateListAnimator(null);
        bypassCalibrationButton.setBackground(UiTheme.ripple(UiTheme.pill(context,
                UiTheme.withAlpha(UiTheme.AMBER, 60), UiTheme.withAlpha(UiTheme.AMBER, 200), 1.2f, 22f)));
        bypassCalibrationButton.setPadding(dp(18), dp(8), dp(18), dp(8));
        bypassCalibrationButton.setVisibility(View.GONE);
        bypassCalibrationButton.setOnClickListener(view -> bypassCalibration());
        LayoutParams bypassParams = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        bypassParams.setMargins(0, 0, 0, dp(120));
        addView(bypassCalibrationButton, bypassParams);

        exitArButton = new Button(context);
        exitArButton.setText("✕ Exit AR");
        exitArButton.setAllCaps(false);
        exitArButton.setTextSize(12);
        exitArButton.setTextColor(Color.WHITE);
        exitArButton.setStateListAnimator(null);
        exitArButton.setBackground(UiTheme.ripple(UiTheme.pill(context,
                UiTheme.withAlpha(UiTheme.INK_LIGHT, 210), UiTheme.withAlpha(Color.WHITE, 50), 1f, 20f)));
        exitArButton.setPadding(dp(14), dp(6), dp(14), dp(6));
        exitArButton.setContentDescription("Exit AR view and return to the live map");
        LayoutParams exitParams = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.START);
        exitParams.setMargins(dp(16), 0, 0, dp(24));
        addView(exitArButton, exitParams);
        exitArButton.setOnClickListener(v -> {
            // Walk the context chain to the hosting Activity and trigger its back handling,
            // which MainActivity maps to "leave AR".
            Context ctx = getContext();
            while (ctx instanceof ContextWrapper) {
                if (ctx instanceof Activity) {
                    ((Activity) ctx).onBackPressed();
                    return;
                }
                ctx = ((ContextWrapper) ctx).getBaseContext();
            }
        });

        updateStatus("AR Bus Finder — point your camera around to see stops and live buses");
    }

    public void setBusTapListener(ArBusTapListener listener) {
        this.busTapListener = listener;
    }

    public void startAr() {
        cameraRequested = true;
        calibrationBypassed = false;
        bypassButtonScheduled = false;
        arrivalAnnounced = false;
        bypassCalibrationButton.setVisibility(View.GONE);
        initializeArCoreDepthAndPlaneDetection();
        startCompass();
        if (cameraPreview.isAvailable()) {
            openCamera();
        }
        updateStatus("AR active — GPS + compass are placing real stops and live buses.");
    }

    public void pauseAr() {
        cameraRequested = false;
        bypassCalibrationButton.setVisibility(View.GONE);
        stopCompass();
        stopCamera();
    }

    public void destroyAr() {
        pauseAr();
    }

    public void setRouteFilter(String routeFilter) {
        this.routeFilter = routeFilter == null ? "" : routeFilter.trim();
        renderPins();
    }

    public void setNavigationTarget(String name, double latitude, double longitude) {
        // Follow mode nudges the target every few seconds: keep the existing route when
        // the new destination is essentially the same place to avoid re-routing storms.
        if (navigationTarget != null) {
            float[] moved = new float[1];
            Location.distanceBetween(navigationTarget.latitude, navigationTarget.longitude,
                    latitude, longitude, moved);
            if (moved[0] < 50f) {
                updateTargetChip();
                updateArrivalState();
                renderPins();
                return;
            }
        }
        navigationTarget = new NavigationTarget(name, latitude, longitude);
        arrivalAnnounced = false;
        pathOverlayView.setTarget(navigationTarget);
        pathOverlayView.setDirectionsStatus("Directions API: Loading");
        targetChip.setVisibility(View.VISIBLE);
        updateTargetChip();
        updateStatus("AR wayfinding locked to " + name + " — follow the neon wall.");
        requestWalkingRouteIfReady(true);
    }

    public void clearNavigationTarget() {
        navigationTarget = null;
        targetChip.setVisibility(View.GONE);
        pathOverlayView.setTarget(null);
        pathOverlayView.setRoute(Collections.emptyList());
        renderPins();
    }

    public void updateBusBillboard(String id, String lineName, String destinationName, String etaText, String occupancy,
            double latitude, double longitude, float bearingDegrees, float speedKph) {
        if (id == null || id.trim().isEmpty()) {
            return;
        }
        BusBillboard billboard = null;
        for (BusBillboard candidate : busBillboards) {
            if (candidate.id.equals(id)) {
                billboard = candidate;
                break;
            }
        }
        if (billboard == null) {
            billboard = new BusBillboard(id);
            busBillboards.add(billboard);
        }
        billboard.lineName = lineName == null ? "" : lineName.trim();
        billboard.destinationName = destinationName == null ? "" : destinationName.trim();
        billboard.etaText = etaText == null ? "" : etaText.trim();
        billboard.occupancy = occupancy == null || occupancy.trim().isEmpty() ? "Information Unknown" : occupancy.trim();
        billboard.latitude = latitude;
        billboard.longitude = longitude;
        billboard.bearingDegrees = bearingDegrees;
        billboard.speedKph = speedKph;
        billboard.lastUpdatedMs = System.currentTimeMillis();
        renderPins();
    }

    public void clearBusBillboards() {
        busBillboards.clear();
        renderPins();
    }

    public void showStopsNear(Location location) {
        boolean accurateEnough = location != null && (!location.hasAccuracy() || location.getAccuracy() <= GPS_LOCK_ACCURACY_METERS);
        gpsLocked = accurateEnough || (calibrationBypassed && location != null);
        if (!gpsLocked) {
            userLocation = location;
            pathOverlayView.setUserLocation(null);
            pathOverlayView.setCalibrating(true);
            scheduleCalibrationBypassButton();
            String message = location == null
                    ? "Calibrating… waiting for GPS lock before placing AR stops."
                    : String.format(Locale.UK, "Calibrating… GPS accuracy %.0fm. Hold still until it is under %.0fm.",
                            location.getAccuracy(), GPS_LOCK_ACCURACY_METERS);
            updateStatus(message);
            renderPins();
            return;
        }

        bypassCalibrationButton.setVisibility(View.GONE);
        userLocation = accurateEnough ? smoothLocation(userLocation, location, 0.22f) : new Location(location);
        if (navigationTarget == null) {
            updateStatus(calibrationBypassed && !accurateEnough
                    ? "Calibration bypassed — using best GPS with rotation-vector-stabilized AR markers."
                    : "AR locked — real stops + live buses placed around you.");
        } else {
            updateTargetChip();
        }
        pathOverlayView.setCalibrating(false);
        pathOverlayView.setUserLocation(userLocation);
        requestWalkingRouteIfReady(false);
        maybeFetchNearbyStops();
        updateArrivalState();
        renderPins();
    }

    private void maybeFetchNearbyStops() {
        if (userLocation == null) {
            return;
        }
        if (lastStopsFetchLocation != null && userLocation.distanceTo(lastStopsFetchLocation) < STOPS_FETCH_RADIUS_METERS) {
            return;
        }
        if (fetchingStops) {
            return;
        }
        fetchingStops = true;
        lastStopsFetchLocation = new Location(userLocation);
        final double lat = userLocation.getLatitude();
        final double lon = userLocation.getLongitude();
        stopsExecutor.execute(() -> {
            List<BusStopPin> fetched = fetchNearbyStops(lat, lon);
            fetchingStops = false;
            if (fetched == null) {
                post(() -> updateStatus("Could not load nearby stops — showing live buses only."));
                return;
            }
            post(() -> {
                stopPins.clear();
                stopPins.addAll(fetched);
                if (stopPins.isEmpty()) {
                    updateStatus("No bus stops found within " + Math.round(OVERPASS_SEARCH_RADIUS_METERS)
                            + "m — live buses still shown in AR.");
                }
                renderPins();
            });
        });
    }

    /** Queries OpenStreetMap Overpass for highway=bus_stop nodes around a point. Returns null on failure. */
    private List<BusStopPin> fetchNearbyStops(double latitude, double longitude) {
        try {
            String query = "[out:json][timeout:15];("
                    + "node(around:" + (int) OVERPASS_SEARCH_RADIUS_METERS + "," + latitude + "," + longitude + ")[highway=bus_stop];"
                    + "node(around:" + (int) OVERPASS_SEARCH_RADIUS_METERS + "," + latitude + "," + longitude + ")[public_transport=platform];"
                    + ");out body " + (MAX_VISIBLE_STOPS * 3) + ";";
            JSONObject json = new JSONObject(Overpass.post(query));
            JSONArray elements = json.optJSONArray("elements");
            if (elements == null) {
                return null;
            }
            List<BusStopPin> pins = new ArrayList<>();
            Location origin = new Location("origin");
            origin.setLatitude(latitude);
            origin.setLongitude(longitude);
            for (int i = 0; i < elements.length() && pins.size() < MAX_VISIBLE_STOPS; i++) {
                JSONObject element = elements.optJSONObject(i);
                if (element == null || !"node".equals(element.optString("type"))) {
                    continue;
                }
                double stopLat = element.optDouble("lat", Double.NaN);
                double stopLon = element.optDouble("lon", Double.NaN);
                if (Double.isNaN(stopLat) || Double.isNaN(stopLon)) {
                    continue;
                }
                JSONObject tags = element.optJSONObject("tags");
                String name = tags == null ? "" : tags.optString("name", "");
                String routeRef = tags == null ? "" : tags.optString("route_ref", "");
                if (name.isEmpty()) {
                    name = "Bus stop";
                }
                Location stopLocation = new Location("stop");
                stopLocation.setLatitude(stopLat);
                stopLocation.setLongitude(stopLon);
                pins.add(new BusStopPin(name, routeRef.isEmpty() ? "Bus" : routeRef, stopLat, stopLon,
                        origin.distanceTo(stopLocation)));
            }
            pins.sort((a, b) -> Float.compare(a.cachedDistance, b.cachedDistance));
            return pins;
        } catch (Exception exception) {
            return null;
        }
    }

    private void scheduleCalibrationBypassButton() {
        if (bypassButtonScheduled || calibrationBypassed) {
            return;
        }
        bypassButtonScheduled = true;
        postDelayed(() -> {
            bypassButtonScheduled = false;
            if (!gpsLocked && !calibrationBypassed && cameraRequested) {
                bypassCalibrationButton.setVisibility(View.VISIBLE);
            }
        }, CALIBRATION_BYPASS_DELAY_MS);
    }

    private void bypassCalibration() {
        if (userLocation == null) {
            updateStatus("Keep AR open while the phone finds an initial GPS fix, then bypass calibration.");
            scheduleCalibrationBypassButton();
            return;
        }
        calibrationBypassed = true;
        gpsLocked = true;
        bypassCalibrationButton.setVisibility(View.GONE);
        pathOverlayView.setCalibrating(false);
        pathOverlayView.setUserLocation(userLocation);
        updateStatus("Calibration bypassed — rendering with best GPS and rotation-vector-stabilized heading.");
        showStopsNear(userLocation);
    }

    private void updateArrivalState() {
        if (navigationTarget == null || userLocation == null) {
            return;
        }
        float distance = distanceTo(navigationTarget.latitude, navigationTarget.longitude);
        boolean arrived = distance <= ARRIVAL_DISTANCE_METERS;
        pathOverlayView.setArrived(arrived);
        if (arrived && !arrivalAnnounced) {
            arrivalAnnounced = true;
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            updateStatus("You have arrived at " + navigationTarget.name + " 🎉");
        } else if (!arrived) {
            arrivalAnnounced = false;
        }
    }

    private void updateTargetChip() {
        if (navigationTarget == null || userLocation == null) {
            targetChip.setVisibility(View.GONE);
            return;
        }
        float distance = distanceTo(navigationTarget.latitude, navigationTarget.longitude);
        int walkMinutes = Math.max(1, Math.round(distance / 80f));
        targetChip.setText(String.format(Locale.UK, "➤ %s · %.0fm · ~%d min walk",
                navigationTarget.name, distance, walkMinutes));
        targetChip.setVisibility(View.VISIBLE);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        float rawBearing = Float.NaN;
        if (event.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR) {
            float[] rotationMatrix = new float[9];
            float[] orientation = new float[3];
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values);
            SensorManager.getOrientation(rotationMatrix, orientation);
            rawBearing = (float) ((Math.toDegrees(orientation[0]) + 360.0) % 360.0);
        } else if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            lowPassVector(event.values, smoothedAcceleration, 0.15f);
            hasAcceleration = true;
            rawBearing = fallbackCompassBearing();
        } else if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
            lowPassVector(event.values, smoothedMagneticField, 0.15f);
            hasMagneticField = true;
            rawBearing = fallbackCompassBearing();
        }
        if (Float.isNaN(rawBearing)) {
            return;
        }
        rawBearing = trueNorthBearing(rawBearing);
        compassBearing = lowPassBearing(rawBearing, compassBearing, 0.12f);
        smoothedCompassBearing = lerpBearing(smoothedCompassBearing, compassBearing, 0.18f);
        pathOverlayView.setBearing(smoothedCompassBearing);
        renderPins();
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private void startCompass() {
        if (sensorManager == null || sensorsRunning) {
            return;
        }
        if (rotationSensor != null) {
            sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_GAME);
        } else {
            if (accelerometerSensor != null) {
                sensorManager.registerListener(this, accelerometerSensor, SensorManager.SENSOR_DELAY_UI);
            }
            if (magneticSensor != null) {
                sensorManager.registerListener(this, magneticSensor, SensorManager.SENSOR_DELAY_UI);
            }
        }
        sensorsRunning = rotationSensor != null || (accelerometerSensor != null && magneticSensor != null);
    }

    private void stopCompass() {
        if (sensorManager != null && sensorsRunning) {
            sensorManager.unregisterListener(this);
        }
        sensorsRunning = false;
    }

    @SuppressLint("MissingPermission")
    private void openCamera() {
        if (!cameraRequested || cameraDevice != null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && getContext().checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            updateStatus("Camera permission is needed for AR bus stop finder.");
            return;
        }

        try {
            CameraManager cameraManager = (CameraManager) getContext().getSystemService(Context.CAMERA_SERVICE);
            String cameraId = findBackCameraId(cameraManager);
            cameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    cameraDevice = camera;
                    startCameraPreview();
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    camera.close();
                    cameraDevice = null;
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    camera.close();
                    cameraDevice = null;
                    updateStatus("Unable to open AR camera preview.");
                }
            }, null);
        } catch (Exception exception) {
            updateStatus("Unable to start AR camera preview: " + exception.getMessage());
        }
    }

    private String findBackCameraId(CameraManager cameraManager) throws Exception {
        String fallbackCameraId = null;
        for (String cameraId : cameraManager.getCameraIdList()) {
            if (fallbackCameraId == null) {
                fallbackCameraId = cameraId;
            }
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
            Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                return cameraId;
            }
        }
        if (fallbackCameraId == null) {
            throw new IllegalStateException("No camera found");
        }
        return fallbackCameraId;
    }

    /** Picks the largest SurfaceTexture size that matches the sensor aspect ratio so the preview is not stretched. */
    private Size choosePreviewSize(CameraManager cameraManager, String cameraId) {
        try {
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) {
                return null;
            }
            Rect sensorSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            double targetAspect = sensorSize != null
                    ? (double) sensorSize.width() / (double) sensorSize.height()
                    : (double) cameraPreview.getWidth() / Math.max(1, cameraPreview.getHeight());
            Size[] choices = map.getOutputSizes(SurfaceTexture.class);
            Size best = null;
            double bestScore = Double.MAX_VALUE;
            int viewWidth = Math.max(1, cameraPreview.getWidth());
            int viewHeight = Math.max(1, cameraPreview.getHeight());
            for (Size candidate : choices) {
                double aspect = (double) candidate.getWidth() / (double) candidate.getHeight();
                double aspectScore = Math.abs(aspect - targetAspect);
                double areaScore = Math.abs(candidate.getWidth() * candidate.getHeight() - viewWidth * viewHeight)
                        / (double) (viewWidth * viewHeight);
                double score = aspectScore * 3.0 + areaScore;
                if (score < bestScore) {
                    bestScore = score;
                    best = candidate;
                }
            }
            return best;
        } catch (Exception exception) {
            return null;
        }
    }

    private void startCameraPreview() {
        if (cameraDevice == null || !cameraPreview.isAvailable()) {
            return;
        }
        try {
            CameraManager cameraManager = (CameraManager) getContext().getSystemService(Context.CAMERA_SERVICE);
            String cameraId = findBackCameraId(cameraManager);
            Size chosen = choosePreviewSize(cameraManager, cameraId);
            if (chosen != null) {
                previewSize = chosen;
            }
            SurfaceTexture texture = cameraPreview.getSurfaceTexture();
            if (previewSize != null) {
                texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            } else {
                texture.setDefaultBufferSize(Math.max(1, cameraPreview.getWidth()), Math.max(1, cameraPreview.getHeight()));
            }
            Surface surface = new Surface(texture);
            CaptureRequest.Builder requestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            requestBuilder.addTarget(surface);
            cameraDevice.createCaptureSession(Collections.singletonList(surface), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession session) {
                    captureSession = session;
                    try {
                        session.setRepeatingRequest(requestBuilder.build(), null, null);
                    } catch (Exception exception) {
                        updateStatus("Unable to run AR camera preview: " + exception.getMessage());
                    }
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession session) {
                    updateStatus("Unable to configure AR camera preview.");
                }
            }, null);
        } catch (Exception exception) {
            updateStatus("Unable to start AR camera preview: " + exception.getMessage());
        }
    }

    private void stopCamera() {
        if (captureSession != null) {
            captureSession.close();
            captureSession = null;
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
    }

    private float lowPassBearing(float input, float previous, float alpha) {
        if (previous == 0f) {
            return input;
        }
        return previous + shortestBearingDelta(previous, input) * alpha;
    }

    private float lerpBearing(float from, float to, float fraction) {
        return from + shortestBearingDelta(from, to) * fraction;
    }

    private float shortestBearingDelta(float from, float to) {
        return ((to - from + 540f) % 360f) - 180f;
    }

    private boolean routeMatchesFilter(String routes) {
        if (routeFilter.isEmpty() || routes == null) {
            return true;
        }
        String lowerFilter = routeFilter.toLowerCase(Locale.UK);
        for (String token : routes.split("[,\\s]+")) {
            if (token.toLowerCase(Locale.UK).contains(lowerFilter)) {
                return true;
            }
        }
        return false;
    }

    private void renderPins() {
        // Child views: 0 camera, 1 overlay, 2 status, 3 target chip, 4 bypass, 5 exit.
        while (getChildCount() > 6) {
            removeViewAt(6);
        }
        if (!gpsLocked) {
            return;
        }
        int width = Math.max(1, getWidth());
        float pixelsPerDegree = (width / 2f) / HALF_FOV_DEGREES;
        float center = width / 2f;

        int visiblePins = 0;
        for (BusStopPin pin : stopPins) {
            if (!routeMatchesFilter(pin.route)) {
                continue;
            }
            if (visiblePins >= MAX_VISIBLE_STOPS) {
                break;
            }
            visiblePins++;
            float relativeBearing = shortestBearingDelta(smoothedCompassBearing, bearingTo(pin.latitude, pin.longitude));
            FrameLayout container = new FrameLayout(getContext());
            TextView marker = createPinView(pin);
            container.addView(marker);
            container.setOnClickListener(v -> {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                setNavigationTarget("🚏 " + pin.name, pin.latitude, pin.longitude);
            });
            UiTheme.pressScale(container);

            boolean onScreen = Math.abs(relativeBearing) <= HALF_FOV_DEGREES;
            LayoutParams params = new LayoutParams(dp(170), ViewGroup.LayoutParams.WRAP_CONTENT);
            if (onScreen) {
                float x = center + relativeBearing * pixelsPerDegree - dp(85);
                pin.displayedX = Float.isNaN(pin.displayedX) ? x : lerp(pin.displayedX, x, 0.22f);
                float y = screenYForDistance(distanceTo(pin.latitude, pin.longitude), 0) - dp(20);
                pin.displayedY = Float.isNaN(pin.displayedY) ? y : lerp(pin.displayedY, y, 0.22f);
                params.leftMargin = (int) Math.max(dp(2), Math.min(width - dp(172), pin.displayedX));
                params.topMargin = (int) Math.max(dp(100), pin.displayedY);
            } else {
                // Off-screen: collapse to an edge dot; the chevron is drawn by the overlay.
                params.width = dp(10);
                params.height = dp(10);
                pin.displayedX = relativeBearing < 0 ? dp(EDGE_CHEVRON_INSET_DP)
                        : width - dp(EDGE_CHEVRON_INSET_DP) - dp(10);
                pin.displayedY = getHeight() * 0.52f;
                container.removeAllViews();
                View dot = new View(getContext());
                dot.setBackground(UiTheme.pill(getContext(), UiTheme.withAlpha(UiTheme.TEAL, 230),
                        UiTheme.withAlpha(Color.WHITE, 160), 1f, 5f));
                container.addView(dot, new LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                params.leftMargin = (int) pin.displayedX;
                params.topMargin = (int) pin.displayedY;
                container.setOnClickListener(null);
                container.setClickable(false);
            }
            addView(container, params);
        }

        renderBusBillboards(center, pixelsPerDegree);

        if (visiblePins == 0 && !stopPins.isEmpty() && !routeFilter.isEmpty()) {
            updateStatus(String.format(Locale.UK, "No AR stop pins match route %s.", routeFilter));
        }
        pathOverlayView.setChevrons(computeChevrons());
        pathOverlayView.invalidate();
    }

    private List<float[]> computeChevrons() {
        List<float[]> chevrons = new ArrayList<>();
        if (!gpsLocked) {
            return chevrons;
        }
        int width = Math.max(1, getWidth());
        float height = Math.max(1, getHeight());
        if (navigationTarget != null) {
            float relativeBearing = shortestBearingDelta(smoothedCompassBearing,
                    bearingTo(navigationTarget.latitude, navigationTarget.longitude));
            if (Math.abs(relativeBearing) > HALF_FOV_DEGREES) {
                float x = relativeBearing < 0 ? dp(EDGE_CHEVRON_INSET_DP) : width - dp(EDGE_CHEVRON_INSET_DP);
                chevrons.add(new float[] { x, height * 0.5f, relativeBearing < 0 ? 0f : 1f, UiTheme.CYAN });
            }
        }
        for (BusBillboard billboard : busBillboards) {
            if (System.currentTimeMillis() - billboard.lastUpdatedMs > 120_000L) {
                continue;
            }
            if (!routeMatchesFilter(billboard.lineName)) {
                continue;
            }
            float relativeBearing = shortestBearingDelta(smoothedCompassBearing,
                    bearingTo(billboard.latitude, billboard.longitude));
            if (Math.abs(relativeBearing) > HALF_FOV_DEGREES && Math.abs(relativeBearing) < 120f) {
                float x = relativeBearing < 0 ? dp(EDGE_CHEVRON_INSET_DP + 14) : width - dp(EDGE_CHEVRON_INSET_DP + 14);
                chevrons.add(new float[] { x, height * 0.44f, relativeBearing < 0 ? 0f : 1f,
                        UiTheme.occupancyColor(billboard.occupancy) });
            }
        }
        return chevrons;
    }

    private void renderBusBillboards(float center, float pixelsPerDegree) {
        long now = System.currentTimeMillis();
        int width = Math.max(1, getWidth());
        int shown = 0;
        for (BusBillboard billboard : busBillboards) {
            if (now - billboard.lastUpdatedMs > 120_000L) {
                continue;
            }
            if (!routeMatchesFilter(billboard.lineName)) {
                continue;
            }
            if (shown >= 6) {
                break;
            }
            float relativeBearing = shortestBearingDelta(smoothedCompassBearing,
                    bearingTo(billboard.latitude, billboard.longitude));
            if (Math.abs(relativeBearing) > HALF_FOV_DEGREES) {
                continue; // chevron drawn instead
            }
            shown++;
            FrameLayout card = new FrameLayout(getContext());
            card.addView(createBusBillboardView(billboard), new LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            card.setOnClickListener(v -> {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                if (busTapListener != null) {
                    busTapListener.onBusTapped(buildSnapshot(billboard));
                }
            });
            UiTheme.pressScale(card);
            float x = center + relativeBearing * pixelsPerDegree - dp(105);
            billboard.displayedX = Float.isNaN(billboard.displayedX) ? x : lerp(billboard.displayedX, x, 0.24f);
            float y = screenYForDistance(distanceTo(billboard.latitude, billboard.longitude), 36f);
            billboard.displayedY = Float.isNaN(billboard.displayedY) ? y : lerp(billboard.displayedY, y, 0.24f);
            LayoutParams params = new LayoutParams(dp(210), ViewGroup.LayoutParams.WRAP_CONTENT);
            params.leftMargin = (int) Math.max(dp(2), Math.min(width - dp(212), billboard.displayedX));
            params.topMargin = (int) Math.max(dp(96), billboard.displayedY);
            addView(card, params);
        }
    }

    private BusSnapshot buildSnapshot(BusBillboard billboard) {
        return new BusSnapshot(
                billboard.id,
                billboard.lineName,
                "",
                billboard.destinationName,
                billboard.occupancy,
                billboard.id,
                "",
                "",
                distanceTextTo(billboard.latitude, billboard.longitude),
                billboard.latitude,
                billboard.longitude,
                billboard.bearingDegrees,
                billboard.speedKph,
                parseEtaMinutes(billboard.etaText),
                "");
    }

    private int parseEtaMinutes(String etaText) {
        if (etaText == null) {
            return -1;
        }
        String digits = etaText.replaceAll("[^0-9]", " ").trim();
        if (digits.isEmpty()) {
            return etaText.contains("Arriving now") ? 0 : -1;
        }
        try {
            return Integer.parseInt(digits.split("\\s+")[0]);
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    private String distanceTextTo(double latitude, double longitude) {
        float metres = distanceTo(latitude, longitude);
        if (metres < 1000f) {
            return String.format(Locale.UK, "%.0fm away", metres);
        }
        return String.format(Locale.UK, "%.1fkm away", metres / 1000f);
    }

    private FrameLayout createBusBillboardView(BusBillboard billboard) {
        FrameLayout card = new FrameLayout(getContext());
        int color = UiTheme.occupancyColor(billboard.occupancy);

        StringBuilder lines = new StringBuilder(billboardTitle(billboard));
        StringBuilder metrics = new StringBuilder();
        if (!billboard.etaText.isEmpty()) {
            metrics.append(billboard.etaText);
        }
        if (billboard.speedKph > 0.5f) {
            if (metrics.length() > 0) {
                metrics.append(" · ");
            }
            metrics.append(String.format(Locale.UK, "%.0f mph", billboard.speedKph * 0.621371f));
        }
        if (metrics.length() > 0) {
            lines.append('\n').append(metrics);
        }
        TextView body = new TextView(getContext());
        body.setText(lines.toString());
        body.setTextColor(Color.WHITE);
        body.setTextSize(12);
        body.setGravity(Gravity.CENTER);
        body.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        body.setBackground(UiTheme.pill(getContext(), UiTheme.withAlpha(UiTheme.INK, 216),
                UiTheme.withAlpha(color, 200), 1.2f, 14f));
        body.setPadding(dp(10), dp(8), dp(10), dp(8));
        card.addView(body, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Route number bubble overlapping the top-left of the card.
        if (!billboard.lineName.isEmpty()) {
            TextView bubble = new TextView(getContext());
            bubble.setText(billboard.lineName);
            bubble.setTextColor(Color.WHITE);
            bubble.setTextSize(13);
            bubble.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
            bubble.setGravity(Gravity.CENTER);
            bubble.setBackground(UiTheme.circleGradient(getContext(), color, UiTheme.withAlpha(color, 180)));
            card.addView(bubble, new LayoutParams(dp(34), dp(34), Gravity.START | Gravity.TOP) {
                {
                    setMargins(dp(-6), dp(-12), 0, 0);
                }
            });
        }
        return card;
    }

    private String billboardTitle(BusBillboard billboard) {
        String line = billboard.lineName == null ? "" : billboard.lineName;
        String destination = billboard.destinationName == null ? "" : billboard.destinationName;
        if (line.isEmpty()) {
            return destination;
        }
        if (destination.isEmpty()) {
            return line;
        }
        String lowerLine = line.toLowerCase(Locale.UK);
        if (lowerLine.contains(" to ") || lowerLine.contains(destination.toLowerCase(Locale.UK))) {
            return line;
        }
        return line + " → " + destination;
    }

    private TextView createPinView(BusStopPin pin) {
        TextView view = new TextView(getContext());
        view.setText(String.format(Locale.UK, "🚏 %s\n%s · %.0fm · tap to walk",
                pin.name, pin.route.isEmpty() ? "Bus" : pin.route, distanceTo(pin.latitude, pin.longitude)));
        view.setTextColor(Color.WHITE);
        view.setTextSize(12);
        view.setGravity(Gravity.CENTER);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        view.setBackground(UiTheme.pill(getContext(), UiTheme.withAlpha(UiTheme.TEAL, 56),
                UiTheme.withAlpha(UiTheme.TEAL, 210), 1.2f, 14f));
        view.setPadding(dp(10), dp(8), dp(10), dp(8));
        return view;
    }

    private float screenYForDistance(float distanceMeters, float verticalOffsetDp) {
        float horizon = getHeight() * 0.42f;
        float ground = getHeight() - dp(64);
        float depth = Math.min(1f, distanceMeters / 220f);
        float y = ground - (ground - horizon) * depth;
        return Math.max(dp(96), y - dpf(verticalOffsetDp));
    }

    private float dpf(float value) {
        return value * getResources().getDisplayMetrics().density + 0.5f;
    }

    @Override
    protected void onDetachedFromWindow() {
        directionsExecutor.shutdownNow();
        stopsExecutor.shutdownNow();
        super.onDetachedFromWindow();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private float lerp(float from, float to, float fraction) {
        return from + (to - from) * fraction;
    }

    private Location smoothLocation(Location previous, Location next, float alpha) {
        if (next == null) {
            return previous;
        }
        if (previous == null) {
            return new Location(next);
        }
        Location smoothed = new Location(next);
        smoothed.setLatitude(lerp((float) previous.getLatitude(), (float) next.getLatitude(), alpha));
        smoothed.setLongitude(lerp((float) previous.getLongitude(), (float) next.getLongitude(), alpha));
        if (next.hasAccuracy()) {
            smoothed.setAccuracy(next.getAccuracy());
        }
        return smoothed;
    }

    private float fallbackCompassBearing() {
        if (!hasAcceleration || !hasMagneticField) {
            return Float.NaN;
        }
        float[] rotationMatrix = new float[9];
        float[] orientation = new float[3];
        if (!SensorManager.getRotationMatrix(rotationMatrix, null, smoothedAcceleration, smoothedMagneticField)) {
            return Float.NaN;
        }
        SensorManager.getOrientation(rotationMatrix, orientation);
        return (float) ((Math.toDegrees(orientation[0]) + 360.0) % 360.0);
    }

    private float trueNorthBearing(float magneticBearing) {
        return (magneticBearing + magneticDeclination() + 360f) % 360f;
    }

    private float magneticDeclination() {
        if (userLocation == null) {
            return 2.5f; // Scunthorpe default
        }
        GeomagneticField field = new GeomagneticField(
                (float) userLocation.getLatitude(),
                (float) userLocation.getLongitude(),
                userLocation.hasAltitude() ? (float) userLocation.getAltitude() : 0f,
                System.currentTimeMillis());
        return field.getDeclination();
    }

    private void requestWalkingRouteIfReady(boolean force) {
        if (!gpsLocked || userLocation == null || navigationTarget == null) {
            return;
        }
        if (!force && !pathOverlayView.isRouteEmpty()
                && distanceFromRoute(userLocation, pathOverlayView.routePoints) <= REROUTE_THRESHOLD_METERS) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!force && now - lastDirectionsRequestMs < 8_000L) {
            return;
        }
        lastDirectionsRequestMs = now;
        Location origin = new Location(userLocation);
        NavigationTarget target = navigationTarget;
        if (BuildConfig.GOOGLE_DIRECTIONS_API_KEY.isEmpty()) {
            List<Location> fallback = smoothRoute(fallbackRoute(origin, target));
            pathOverlayView.setDirectionsStatus("Directions API: off — direct hint");
            pathOverlayView.setRoute(fallback);
            return;
        }
        pathOverlayView.setDirectionsStatus("Directions API: Loading");
        directionsExecutor.execute(() -> {
            List<Location> route = fetchWalkingDirections(origin, target);
            boolean directionsOk = !route.isEmpty();
            if (!directionsOk) {
                route = fallbackRoute(origin, target);
            }
            List<Location> smoothed = smoothRoute(route);
            post(() -> {
                pathOverlayView.setDirectionsStatus(directionsOk ? "Directions API: OK" : "Directions API: Failed");
                pathOverlayView.setRoute(smoothed);
            });
        });
    }

    private List<Location> fetchWalkingDirections(Location origin, NavigationTarget target) {
        HttpURLConnection connection = null;
        try {
            Uri uri = Uri.parse("https://maps.googleapis.com/maps/api/directions/json").buildUpon()
                    .appendQueryParameter("origin", origin.getLatitude() + "," + origin.getLongitude())
                    .appendQueryParameter("destination", target.latitude + "," + target.longitude)
                    .appendQueryParameter("mode", "walking")
                    .appendQueryParameter("key", BuildConfig.GOOGLE_DIRECTIONS_API_KEY)
                    .build();
            connection = (HttpURLConnection) new URL(uri.toString()).openConnection();
            connection.setConnectTimeout(8_000);
            connection.setReadTimeout(12_000);
            connection.setRequestProperty("Accept", "application/json");
            if (connection.getResponseCode() < 200 || connection.getResponseCode() >= 300) {
                return Collections.emptyList();
            }
            JSONObject json = new JSONObject(readString(connection.getInputStream()));
            JSONArray routes = json.optJSONArray("routes");
            if (routes == null || routes.length() == 0) {
                return Collections.emptyList();
            }
            JSONArray legs = routes.getJSONObject(0).optJSONArray("legs");
            if (legs == null || legs.length() == 0) {
                return Collections.emptyList();
            }
            JSONArray steps = legs.getJSONObject(0).optJSONArray("steps");
            if (steps == null) {
                return Collections.emptyList();
            }
            List<Location> points = new ArrayList<>();
            for (int i = 0; i < steps.length() && points.size() < MAX_DIRECTIONS_POINTS; i++) {
                JSONObject polyline = steps.getJSONObject(i).optJSONObject("polyline");
                if (polyline != null) {
                    points.addAll(decodePolyline(polyline.optString("points", "")));
                }
            }
            return points;
        } catch (Exception exception) {
            return Collections.emptyList();
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private String readString(InputStream stream) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = stream.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        return output.toString("UTF-8");
    }

    private List<Location> decodePolyline(String encoded) {
        List<Location> polyline = new ArrayList<>();
        int index = 0;
        int latitude = 0;
        int longitude = 0;
        while (index < encoded.length()) {
            int[] latitudeResult = decodePolylineValue(encoded, index);
            latitude += latitudeResult[0];
            index = latitudeResult[1];
            int[] longitudeResult = decodePolylineValue(encoded, index);
            longitude += longitudeResult[0];
            index = longitudeResult[1];
            Location point = new Location("directions");
            point.setLatitude(latitude / 100000.0);
            point.setLongitude(longitude / 100000.0);
            polyline.add(point);
        }
        return polyline;
    }

    private int[] decodePolylineValue(String encoded, int startIndex) {
        int result = 0;
        int shift = 0;
        int index = startIndex;
        int value;
        do {
            value = encoded.charAt(index++) - 63;
            result |= (value & 0x1f) << shift;
            shift += 5;
        } while (value >= 0x20 && index < encoded.length());
        int delta = (result & 1) != 0 ? ~(result >> 1) : (result >> 1);
        return new int[] { delta, index };
    }

    private List<Location> fallbackRoute(Location origin, NavigationTarget target) {
        List<Location> route = new ArrayList<>();
        route.add(new Location(origin));
        Location end = new Location("target");
        end.setLatitude(target.latitude);
        end.setLongitude(target.longitude);
        route.add(end);
        return route;
    }

    private List<Location> smoothRoute(List<Location> route) {
        if (route.size() < 3) {
            return route;
        }
        List<Location> control = new ArrayList<>();
        control.add(route.get(0));
        control.addAll(route);
        control.add(route.get(route.size() - 1));
        List<Location> smoothed = new ArrayList<>();
        for (int i = 0; i + 3 < control.size(); i++) {
            Location p0 = control.get(i);
            Location p1 = control.get(i + 1);
            Location p2 = control.get(i + 2);
            Location p3 = control.get(i + 3);
            for (int step = 0; step <= 8; step++) {
                double t = step / 8.0;
                smoothed.add(bSplinePoint(p0, p1, p2, p3, t));
            }
        }
        return smoothed;
    }

    private Location bSplinePoint(Location p0, Location p1, Location p2, Location p3, double t) {
        double t2 = t * t;
        double t3 = t2 * t;
        double b0 = (-t3 + 3 * t2 - 3 * t + 1) / 6.0;
        double b1 = (3 * t3 - 6 * t2 + 4) / 6.0;
        double b2 = (-3 * t3 + 3 * t2 + 3 * t + 1) / 6.0;
        double b3 = t3 / 6.0;
        Location point = new Location("bspline");
        point.setLatitude(p0.getLatitude() * b0 + p1.getLatitude() * b1 + p2.getLatitude() * b2 + p3.getLatitude() * b3);
        point.setLongitude(p0.getLongitude() * b0 + p1.getLongitude() * b1 + p2.getLongitude() * b2 + p3.getLongitude() * b3);
        return point;
    }

    private float distanceFromRoute(Location location, List<Location> route) {
        if (route.isEmpty()) {
            return Float.MAX_VALUE;
        }
        float minDistance = Float.MAX_VALUE;
        for (Location point : route) {
            minDistance = Math.min(minDistance, location.distanceTo(point));
        }
        return minDistance;
    }

    private void initializeArCoreDepthAndPlaneDetection() {
        try {
            Class<?> sessionClass = Class.forName("com.google.ar.core.Session");
            Class<?> configClass = Class.forName("com.google.ar.core.Config");
            Object session = sessionClass.getConstructor(Context.class).newInstance(getContext());
            Object config = configClass.getConstructor(sessionClass).newInstance(session);
            Class<?> depthModeClass = Class.forName("com.google.ar.core.Config$DepthMode");
            Class<?> planeModeClass = Class.forName("com.google.ar.core.Config$PlaneFindingMode");
            Object automaticDepth = Enum.valueOf((Class<Enum>) depthModeClass.asSubclass(Enum.class), "AUTOMATIC");
            Object horizontalPlanes = Enum.valueOf((Class<Enum>) planeModeClass.asSubclass(Enum.class), "HORIZONTAL");
            configClass.getMethod("setDepthMode", depthModeClass).invoke(config, automaticDepth);
            configClass.getMethod("setPlaneFindingMode", planeModeClass).invoke(config, horizontalPlanes);
            sessionClass.getMethod("configure", configClass).invoke(session, config);
            sessionClass.getMethod("close").invoke(session);
            arCoreDepthReady = true;
        } catch (Exception exception) {
            arCoreDepthReady = false;
        }
        pathOverlayView.setDepthOcclusionEnabled(arCoreDepthReady);
    }

    private void lowPassVector(float[] input, float[] output, float alpha) {
        for (int i = 0; i < output.length && i < input.length; i++) {
            output[i] = output[i] == 0f ? input[i] : output[i] + alpha * (input[i] - output[i]);
        }
    }

    private void updateStatus(String text) {
        statusView.setText(text);
        pathOverlayView.setStatusText(text);
    }

    private float bearingTo(double latitude, double longitude) {
        if (userLocation == null) {
            return 0f;
        }
        Location target = new Location("target");
        target.setLatitude(latitude);
        target.setLongitude(longitude);
        return userLocation.bearingTo(target);
    }

    private float distanceTo(double latitude, double longitude) {
        if (userLocation == null) {
            return 0f;
        }
        Location target = new Location("target");
        target.setLatitude(latitude);
        target.setLongitude(longitude);
        return userLocation.distanceTo(target);
    }

    /**
     * Canvas overlay drawing the navigation wall, arrows, compass ribbon, radar,
     * distance rings, chevrons, HUD and arrival banner.
     */
    private final class PathOverlayView extends ViewGroup {
        private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ribbonPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint arrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint wallPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint panelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint radarPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint radarBlipPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint radarSweepPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ghostPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint chevronPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint smallTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint arrivalPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Location currentLocation;
        private NavigationTarget target;
        private final List<Location> routePoints = new ArrayList<>();
        private List<float[]> chevrons = new ArrayList<>();
        private float bearing;
        private boolean calibrating;
        private boolean arrived;
        private boolean depthOcclusionEnabled;
        private String directionsStatus = "Directions API: off";
        private String statusText = "";
        private long animationStartedAt = System.currentTimeMillis();

        PathOverlayView(Context context) {
            super(context);
            setWillNotDraw(false);
            glowPaint.setColor(UiTheme.withAlpha(UiTheme.CYAN, 130));
            glowPaint.setStyle(Paint.Style.STROKE);
            glowPaint.setStrokeCap(Paint.Cap.ROUND);
            glowPaint.setStrokeJoin(Paint.Join.ROUND);
            glowPaint.setStrokeWidth(dp(46));
            glowPaint.setMaskFilter(new BlurMaskFilter(dp(16), BlurMaskFilter.Blur.NORMAL));
            ribbonPaint.setColor(UiTheme.withAlpha(Color.rgb(255, 214, 0), 185));
            ribbonPaint.setStyle(Paint.Style.STROKE);
            ribbonPaint.setStrokeCap(Paint.Cap.ROUND);
            ribbonPaint.setStrokeJoin(Paint.Join.ROUND);
            ribbonPaint.setStrokeWidth(dp(30));
            arrowPaint.setColor(UiTheme.withAlpha(Color.WHITE, 235));
            arrowPaint.setStyle(Paint.Style.FILL);
            wallPaint.setColor(UiTheme.withAlpha(UiTheme.CYAN, 60));
            wallPaint.setStyle(Paint.Style.FILL);
            wallPaint.setMaskFilter(new BlurMaskFilter(dp(12), BlurMaskFilter.Blur.NORMAL));
            panelPaint.setColor(UiTheme.withAlpha(UiTheme.INK, 185));
            panelPaint.setStyle(Paint.Style.FILL);
            radarPaint.setStyle(Paint.Style.STROKE);
            radarPaint.setStrokeWidth(dp(1));
            radarPaint.setColor(UiTheme.withAlpha(UiTheme.TEAL, 120));
            radarBlipPaint.setStyle(Paint.Style.FILL);
            radarSweepPaint.setStyle(Paint.Style.FILL);
            radarSweepPaint.setColor(UiTheme.withAlpha(UiTheme.TEAL, 40));
            ghostPaint.setColor(UiTheme.withAlpha(UiTheme.CYAN, 120));
            ghostPaint.setStyle(Paint.Style.FILL_AND_STROKE);
            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setStrokeWidth(dp(1));
            ringPaint.setColor(UiTheme.withAlpha(Color.WHITE, 50));
            chevronPaint.setStyle(Paint.Style.FILL);
            textPaint.setColor(Color.WHITE);
            textPaint.setTextSize(dp(14));
            textPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            smallTextPaint.setColor(UiTheme.TEXT_DIM);
            smallTextPaint.setTextSize(dp(11));
            arrivalPaint.setColor(UiTheme.GREEN);
            arrivalPaint.setTextSize(dp(18));
            arrivalPaint.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
            arrivalPaint.setTextAlign(Paint.Align.CENTER);
        }

        void setUserLocation(Location currentLocation) {
            this.currentLocation = currentLocation;
            invalidate();
        }

        void setTarget(NavigationTarget target) {
            this.target = target;
            invalidate();
        }

        void setBearing(float bearing) {
            this.bearing = bearing;
            invalidate();
        }

        void setRoute(List<Location> route) {
            routePoints.clear();
            routePoints.addAll(route);
            animationStartedAt = System.currentTimeMillis();
            invalidate();
        }

        void setDirectionsStatus(String directionsStatus) {
            this.directionsStatus = directionsStatus;
            invalidate();
        }

        void setStatusText(String statusText) {
            this.statusText = statusText;
            invalidate();
        }

        void setCalibrating(boolean calibrating) {
            this.calibrating = calibrating;
            invalidate();
        }

        void setArrived(boolean arrived) {
            this.arrived = arrived;
            invalidate();
        }

        void setChevrons(List<float[]> chevronList) {
            this.chevrons = chevronList;
        }

        void setDepthOcclusionEnabled(boolean depthOcclusionEnabled) {
            this.depthOcclusionEnabled = depthOcclusionEnabled;
            invalidate();
        }

        boolean isRouteEmpty() {
            return routePoints.isEmpty();
        }

        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (getWidth() == 0 || getHeight() == 0) {
                return;
            }
            drawCompassRibbon(canvas);
            drawDistanceRings(canvas);
            if (calibrating) {
                canvas.drawText("Calibrating… locking GPS and AR floor", getWidth() / 2f, getHeight() / 2f, textPaint);
                return;
            }
            drawGhostBuses(canvas);
            if (currentLocation == null) {
                return;
            }
            if (target == null) {
                drawRadar(canvas);
                drawHud(canvas);
                return;
            }
            List<Location> drawableRoute = routePoints.isEmpty() ? fallbackRoute(currentLocation, target) : routePoints;
            List<float[]> projectedPoints = projectRoute(drawableRoute);
            if (projectedPoints.size() >= 2) {
                drawVerticalNavigationWall(canvas, projectedPoints);
                drawAnimatedArrows(canvas, projectedPoints);
            }
            drawRadar(canvas);
            drawHud(canvas);
            if (arrived) {
                drawArrivalBanner(canvas);
            }
            if (!depthOcclusionEnabled) {
                smallTextPaint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText("ARCore depth unavailable — GPS anchor wall fallback",
                        getWidth() / 2f, getHeight() - dp(16), smallTextPaint);
            }
            postInvalidateOnAnimation();
        }

        private void drawCompassRibbon(Canvas canvas) {
            float ribbonWidth = dp(230);
            float left = getWidth() / 2f - ribbonWidth / 2f;
            float top = dp(6);
            RectF panel = new RectF(left, top, left + ribbonWidth, top + dp(30));
            canvas.drawRoundRect(panel, dp(15), dp(15), panelPaint);

            float centerX = getWidth() / 2f;
            float tickTop = top + dp(6);
            float tickBottom = top + dp(14);
            smallTextPaint.setTextAlign(Paint.Align.CENTER);
            for (int angle = -180; angle < 180; angle += 15) {
                float relative = shortestBearingDelta(bearing, angle);
                if (Math.abs(relative) > 45f) {
                    continue;
                }
                float x = centerX + (relative / 45f) * (ribbonWidth / 2f - dp(10));
                boolean major = angle % 45 == 0;
                Paint tick = major ? textPaint : smallTextPaint;
                canvas.drawLine(x, tickTop, x, tickBottom, tick);
                if (major) {
                    String label;
                    switch (((angle % 360) + 360) % 360) {
                        case 0: label = "N"; break;
                        case 45: label = "NE"; break;
                        case 90: label = "E"; break;
                        case 135: label = "SE"; break;
                        case 180: label = "S"; break;
                        case 225: label = "SW"; break;
                        case 270: label = "W"; break;
                        default: label = "NW"; break;
                    }
                    canvas.drawText(label, x, top + dp(26), smallTextPaint);
                }
            }
            // Center heading notch.
            Paint notch = new Paint();
            notch.setColor(UiTheme.CYAN);
            notch.setStrokeWidth(dp(2));
            canvas.drawLine(centerX, top, centerX, top + dp(8), notch);
        }

        private void drawDistanceRings(Canvas canvas) {
            if (currentLocation == null) {
                return;
            }
            float horizon = getHeight() * 0.42f;
            float ground = getHeight() - dp(64);
            smallTextPaint.setTextAlign(Paint.Align.LEFT);
            for (float ringDistance : new float[] { 25f, 50f }) {
                float depth = Math.min(1f, ringDistance / 220f);
                float y = ground - (ground - horizon) * depth;
                canvas.drawLine(dp(12), y, getWidth() - dp(12), y, ringPaint);
                canvas.drawText(String.format(Locale.UK, "%.0fm", ringDistance), dp(16), y - dp(4), smallTextPaint);
            }
        }

        private void drawVerticalNavigationWall(Canvas canvas, List<float[]> points) {
            for (int i = 1; i < points.size(); i++) {
                float[] previous = points.get(i - 1);
                float[] point = points.get(i);
                float alpha = Math.min(previous[2], point[2]);
                wallPaint.setAlpha((int) (150 * alpha));
                Path wall = new Path();
                wall.moveTo(previous[0], previous[1]);
                wall.lineTo(point[0], point[1]);
                wall.lineTo(point[0], Math.max(dp(90), point[1] - dp(130)));
                wall.lineTo(previous[0], Math.max(dp(90), previous[1] - dp(130)));
                wall.close();
                canvas.drawPath(wall, wallPaint);
            }
            Path topEdge = new Path();
            float[] first = points.get(0);
            topEdge.moveTo(first[0], Math.max(dp(90), first[1] - dp(130)));
            for (int i = 1; i < points.size(); i++) {
                float[] point = points.get(i);
                topEdge.lineTo(point[0], Math.max(dp(90), point[1] - dp(130)));
            }
            canvas.drawPath(topEdge, glowPaint);

            // Destination flag at the end of the wall.
            float[] last = points.get(points.size() - 1);
            chevronPaint.setColor(UiTheme.CYAN);
            float flagY = Math.max(dp(90), last[1] - dp(150));
            canvas.drawCircle(last[0], flagY, dp(6), chevronPaint);
            canvas.drawLine(last[0], flagY, last[0], last[1], chevronPaint);
        }

        private void drawRadar(Canvas canvas) {
            float radius = dp(56);
            float centerX = dp(70);
            float centerY = getHeight() - radius - dp(84);
            canvas.save();
            canvas.translate(centerX, centerY);
            // Rings.
            for (float fraction : new float[] { 1f, 0.66f, 0.33f }) {
                canvas.drawCircle(0, 0, radius * fraction, radarPaint);
            }
            // Sweep.
            long sweepPhase = (System.currentTimeMillis() % 3000L);
            float sweepAngle = (sweepPhase / 3000f) * 360f;
            canvas.save();
            canvas.rotate(-sweepAngle);
            Path wedge = new Path();
            wedge.moveTo(0, 0);
            wedge.arcTo(new RectF(-radius, -radius, radius, radius), -30, 30);
            wedge.close();
            canvas.drawPath(wedge, radarSweepPaint);
            canvas.restore();
            // Blips: live buses.
            long now = System.currentTimeMillis();
            if (currentLocation != null) {
                for (BusBillboard billboard : busBillboards) {
                    if (now - billboard.lastUpdatedMs > 120_000L) {
                        continue;
                    }
                    float distance = distanceTo(billboard.latitude, billboard.longitude);
                    if (distance > 320f) {
                        continue;
                    }
                    float relativeBearing = shortestBearingDelta(bearing,
                            bearingTo(billboard.latitude, billboard.longitude));
                    double angle = Math.toRadians(relativeBearing - 90f);
                    float scaled = (Math.min(distance, 320f) / 320f) * radius;
                    radarBlipPaint.setColor(UiTheme.occupancyColor(billboard.occupancy));
                    canvas.drawCircle((float) Math.cos(angle) * scaled, (float) Math.sin(angle) * scaled, dp(4), radarBlipPaint);
                }
                // Stop blips.
                radarBlipPaint.setColor(UiTheme.withAlpha(Color.WHITE, 220));
                for (BusStopPin pin : stopPins) {
                    float distance = distanceTo(pin.latitude, pin.longitude);
                    if (distance > 320f) {
                        continue;
                    }
                    float relativeBearing = shortestBearingDelta(bearing, bearingTo(pin.latitude, pin.longitude));
                    double angle = Math.toRadians(relativeBearing - 90f);
                    float scaled = (Math.min(distance, 320f) / 320f) * radius;
                    canvas.drawCircle((float) Math.cos(angle) * scaled, (float) Math.sin(angle) * scaled, dp(3), radarBlipPaint);
                }
                // Target blip pulses.
                if (target != null) {
                    float distance = distanceTo(target.latitude, target.longitude);
                    if (distance <= 320f) {
                        float relativeBearing = shortestBearingDelta(bearing, bearingTo(target.latitude, target.longitude));
                        double angle = Math.toRadians(relativeBearing - 90f);
                        float scaled = (Math.min(distance, 320f) / 320f) * radius;
                        float pulse = 1f + 0.4f * (1f - (sweepPhase / 3000f));
                        radarBlipPaint.setColor(UiTheme.CYAN);
                        canvas.drawCircle((float) Math.cos(angle) * scaled, (float) Math.sin(angle) * scaled,
                                dp(5) * pulse, radarBlipPaint);
                    }
                }
            }
            canvas.restore();
            // Frame + label.
            canvas.drawCircle(centerX, centerY, radius, radarPaint);
            smallTextPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("RADAR", centerX, centerY + radius + dp(14), smallTextPaint);
        }

        private void drawHud(Canvas canvas) {
            float left = dp(12);
            float top = dp(44);
            float right = Math.min(getWidth() - dp(12), left + dp(215));
            float bottom = top + dp(74);
            canvas.drawRoundRect(new RectF(left, top, right, bottom), dp(14), dp(14), panelPaint);
            textPaint.setTextAlign(Paint.Align.LEFT);
            canvas.drawText("Future HUD", left + dp(12), top + dp(19), textPaint);
            canvas.drawText(directionsStatus, left + dp(12), top + dp(37), smallTextPaint);
            canvas.drawText("X-Ray ghost buses nearby: " + nearbyBusCount(), left + dp(12), top + dp(54), smallTextPaint);
            canvas.drawText(statusText.isEmpty() ? "AR ready" : statusText, left + dp(12), top + dp(69), smallTextPaint);
        }

        private void drawArrivalBanner(Canvas canvas) {
            float centerX = getWidth() / 2f;
            float centerY = getHeight() * 0.30f;
            RectF banner = new RectF(centerX - dp(140), centerY - dp(26), centerX + dp(140), centerY + dp(26));
            Paint bannerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            bannerPaint.setColor(UiTheme.withAlpha(UiTheme.GREEN, 210));
            canvas.drawRoundRect(banner, dp(22), dp(22), bannerPaint);
            canvas.drawText("YOU HAVE ARRIVED 🎉", centerX, centerY + dp(6), arrivalPaint);
            postInvalidateOnAnimation();
        }

        private void drawGhostBuses(Canvas canvas) {
            if (currentLocation == null) {
                return;
            }
            long now = System.currentTimeMillis();
            smallTextPaint.setTextAlign(Paint.Align.CENTER);
            for (BusBillboard billboard : busBillboards) {
                if (now - billboard.lastUpdatedMs > 120_000L) {
                    continue;
                }
                float distance = distanceTo(billboard.latitude, billboard.longitude);
                if (distance > 1000f) {
                    continue;
                }
                float relativeBearing = shortestBearingDelta(bearing, bearingTo(billboard.latitude, billboard.longitude));
                if (Math.abs(relativeBearing) > HALF_FOV_DEGREES) {
                    continue;
                }
                float pixelsPerDegree = (getWidth() / 2f) / HALF_FOV_DEGREES;
                float x = getWidth() / 2f + relativeBearing * pixelsPerDegree;
                float y = Math.max(dp(145), getHeight() * 0.58f - Math.min(dp(180), distance / 4f));
                float pulse = 0.45f + 0.55f * (1f - Math.min(1f, distance / 1000f));
                ghostPaint.setAlpha((int) (60 + 110 * pulse));
                canvas.drawRoundRect(new RectF(x - dp(30), y - dp(20), x + dp(30), y + dp(20)), dp(12), dp(12), ghostPaint);
                canvas.drawCircle(x - dp(15), y + dp(20), dp(4 + (int) (pulse * 4)), ghostPaint);
                canvas.drawCircle(x + dp(15), y + dp(20), dp(4 + (int) (pulse * 4)), ghostPaint);
                canvas.drawText("X-Ray " + billboard.lineName + " · " + Math.round(distance) + "m"
                                + (billboard.speedKph <= 0.5f ? " · stopped" : ""),
                        x, y - dp(28), smallTextPaint);
            }
            drawEdgeChevrons(canvas);
        }

        private void drawEdgeChevrons(Canvas canvas) {
            for (float[] chevron : chevrons) {
                chevronPaint.setColor((int) chevron[3]);
                UiTheme.drawChevron(canvas, chevron[0], chevron[1], dp(9), chevron[2] > 0.5f, chevronPaint);
            }
        }

        private int nearbyBusCount() {
            if (currentLocation == null) {
                return 0;
            }
            int count = 0;
            long now = System.currentTimeMillis();
            for (BusBillboard billboard : busBillboards) {
                if (now - billboard.lastUpdatedMs <= 120_000L
                        && distanceTo(billboard.latitude, billboard.longitude) <= 1000f) {
                    count++;
                }
            }
            return count;
        }

        private List<float[]> projectRoute(List<Location> route) {
            List<float[]> projected = new ArrayList<>();
            float pixelsPerDegree = (getWidth() / 2f) / HALF_FOV_DEGREES;
            for (Location point : route) {
                float distance = Math.max(0.5f, currentLocation.distanceTo(point));
                if (distance > 65f) {
                    continue;
                }
                float relativeBearing = shortestBearingDelta(bearing, currentLocation.bearingTo(point));
                if (Math.abs(relativeBearing) > 70f) {
                    continue;
                }
                float horizon = getHeight() * 0.42f;
                float ground = getHeight() - dp(64);
                float depth = Math.min(1f, distance / 65f);
                float x = getWidth() / 2f + relativeBearing * pixelsPerDegree;
                float y = ground - (ground - horizon) * depth;
                float alpha = Math.max(0.18f, 1f - depth);
                projected.add(new float[] { x, y, alpha });
            }
            return projected;
        }

        private void drawAnimatedArrows(Canvas canvas, List<float[]> points) {
            if (points.size() < 2) {
                return;
            }
            float phase = ((System.currentTimeMillis() - animationStartedAt) % 1400L) / 1400f;
            for (int i = 1; i < points.size(); i += 4) {
                if (((i / 4f) + phase) % 1f > 0.35f) {
                    continue;
                }
                float[] previous = points.get(i - 1);
                float[] point = points.get(i);
                float angle = (float) Math.atan2(point[1] - previous[1], point[0] - previous[0]);
                float alpha = Math.min(previous[2], point[2]);
                arrowPaint.setAlpha((int) (220 * alpha));
                drawArrow(canvas, point[0], point[1], angle);
            }
        }

        private void drawArrow(Canvas canvas, float x, float y, float angle) {
            float size = dp(13);
            Path arrow = new Path();
            arrow.moveTo(size, 0);
            arrow.lineTo(-size * 0.7f, -size * 0.55f);
            arrow.lineTo(-size * 0.35f, 0);
            arrow.lineTo(-size * 0.7f, size * 0.55f);
            arrow.close();
            canvas.save();
            canvas.translate(x, y);
            canvas.rotate((float) Math.toDegrees(angle));
            canvas.drawPath(arrow, arrowPaint);
            canvas.restore();
        }
    }

    private static final class NavigationTarget {
        final String name;
        final double latitude;
        final double longitude;

        NavigationTarget(String name, double latitude, double longitude) {
            this.name = name;
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }

    private static final class BusStopPin {
        final String name;
        final String route;
        final double latitude;
        final double longitude;
        final float cachedDistance;
        float displayedX = Float.NaN;
        float displayedY = Float.NaN;

        BusStopPin(String name, String route, double latitude, double longitude, float cachedDistance) {
            this.name = name;
            this.route = route;
            this.latitude = latitude;
            this.longitude = longitude;
            this.cachedDistance = cachedDistance;
        }
    }
}
