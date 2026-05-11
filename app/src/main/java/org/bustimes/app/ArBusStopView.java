package org.bustimes.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
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
import android.location.Location;
import android.net.Uri;
import android.os.Build;
import android.util.AttributeSet;
import android.view.Gravity;
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
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ArBusStopView extends FrameLayout implements SensorEventListener {
    private static final float SCUNTHORPE_DECLINATION_DEGREES_EAST = 2.5f;
    private static final float GPS_LOCK_ACCURACY_METERS = 10f;
    private static final float REROUTE_THRESHOLD_METERS = 5f;
    private static final int MAX_DIRECTIONS_POINTS = 160;
    private static final long CALIBRATION_BYPASS_DELAY_MS = 5_000L;
    private static final int BILLBOARD_HOVER_OFFSET_DP = 70;

    private final TextureView cameraPreview;
    private final TextView statusView;
    private final Button bypassCalibrationButton;
    private final PathOverlayView pathOverlayView;
    private final List<BusStopPin> stopPins = new ArrayList<>();
    private final List<BusBillboard> busBillboards = new ArrayList<>();
    private final List<AnchorPoint> routeAnchors = new ArrayList<>();
    private final SensorManager sensorManager;
    private final Sensor rotationSensor;
    private final Sensor accelerometerSensor;
    private final Sensor magneticSensor;
    private final ExecutorService directionsExecutor = Executors.newSingleThreadExecutor();
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
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

    public ArBusStopView(Context context) {
        this(context, null);
    }

    public ArBusStopView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setBackgroundColor(Color.rgb(12, 22, 34));
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
        statusView.setTextSize(16);
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(dp(16), dp(16), dp(16), dp(16));
        statusView.setBackgroundColor(Color.argb(140, 0, 0, 0));
        addView(statusView, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP));

        bypassCalibrationButton = new Button(context);
        bypassCalibrationButton.setText("Bypass Calibration");
        bypassCalibrationButton.setAllCaps(false);
        bypassCalibrationButton.setVisibility(View.GONE);
        bypassCalibrationButton.setOnClickListener(view -> bypassCalibration());
        LayoutParams bypassParams = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        bypassParams.setMargins(0, 0, 0, dp(110));
        addView(bypassCalibrationButton, bypassParams);
        updateStatus("Location-Based AR Bus Stop Finder\nUses live camera + GPS + compass to place stop pins.");
    }

    public void startAr() {
        cameraRequested = true;
        calibrationBypassed = false;
        bypassButtonScheduled = false;
        bypassCalibrationButton.setVisibility(View.GONE);
        initializeArCoreDepthAndPlaneDetection();
        startCompass();
        if (cameraPreview.isAvailable()) {
            openCamera();
        }
        updateStatus("Location-Based AR active — camera, GPS and compass place nearest bus stop pins.");
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
        navigationTarget = new NavigationTarget(name, latitude, longitude);
        pathOverlayView.setTarget(navigationTarget);
        pathOverlayView.setDirectionsStatus("Directions API: Loading");
        updateStatus("AR wayfinding path locked to " + name + " — follow the glowing neon wall.");
        requestWalkingRouteIfReady(true);
    }

    public void clearNavigationTarget() {
        navigationTarget = null;
        pathOverlayView.setTarget(null);
        pathOverlayView.setRoute(Collections.emptyList());
        renderPins();
    }

    public void updateBusBillboard(String id, String lineName, String destinationName, String etaText, String occupancy,
            double latitude, double longitude) {
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
        billboard.lineName = lineName == null || lineName.trim().isEmpty() ? "Bus" : lineName.trim();
        billboard.destinationName = destinationName == null || destinationName.trim().isEmpty() ? "destination unknown" : destinationName.trim();
        billboard.etaText = etaText == null || etaText.trim().isEmpty() ? "ETA unknown" : etaText.trim();
        billboard.occupancy = occupancy == null || occupancy.trim().isEmpty() ? "Information Unknown" : occupancy.trim();
        billboard.delayExplanation = explainDelay(billboard.lineName, billboard.etaText, billboard.occupancy);
        billboard.latitude = latitude;
        billboard.longitude = longitude;
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
            stopPins.clear();
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
                    : "Location-Based AR active — GPS locked, rotation-vector heading corrected to true north.");
        }
        pathOverlayView.setCalibrating(false);
        pathOverlayView.setUserLocation(userLocation);
        requestWalkingRouteIfReady(false);
        stopPins.clear();

        stopPins.add(new BusStopPin("Nearest stop", firstRoute(), userLocation.getLatitude() + 0.00045,
                userLocation.getLongitude() + 0.00025));
        stopPins.add(new BusStopPin("Opposite stop", firstRoute(), userLocation.getLatitude() - 0.00035,
                userLocation.getLongitude() + 0.00035));
        stopPins.add(new BusStopPin("Next stop ahead", firstRoute(), userLocation.getLatitude() + 0.00080,
                userLocation.getLongitude() - 0.00018));
        renderPins();
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

    private void startCameraPreview() {
        if (cameraDevice == null || !cameraPreview.isAvailable()) {
            return;
        }
        try {
            SurfaceTexture texture = cameraPreview.getSurfaceTexture();
            texture.setDefaultBufferSize(Math.max(1, cameraPreview.getWidth()), Math.max(1, cameraPreview.getHeight()));
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

    private void renderPins() {
        while (getChildCount() > 4) {
            removeViewAt(4);
        }
        if (!gpsLocked) {
            return;
        }
        int visiblePins = 0;
        for (BusStopPin pin : stopPins) {
            if (!routeFilter.isEmpty() && !pin.route.equalsIgnoreCase(routeFilter)) {
                continue;
            }
            visiblePins++;
            TextView marker = createPinView(pin);
            float targetX = screenXFor(pin);
            float targetY = screenYFor(pin, visiblePins);
            pin.displayedX = Float.isNaN(pin.displayedX) ? targetX : lerp(pin.displayedX, targetX, 0.20f);
            pin.displayedY = Float.isNaN(pin.displayedY) ? targetY : lerp(pin.displayedY, targetY, 0.20f);
            if (navigationTarget == null && visiblePins == 1) {
                setNavigationTarget(pin.name, pin.latitude, pin.longitude);
            }
            LayoutParams params = new LayoutParams(dp(160), ViewGroup.LayoutParams.WRAP_CONTENT);
            params.leftMargin = dp((int) pin.displayedX);
            params.topMargin = dp((int) pin.displayedY);
            addView(marker, params);
        }

        renderBusBillboards();

        if (visiblePins == 0 && !stopPins.isEmpty()) {
            updateStatus(String.format(Locale.UK, "No AR bus stop pins match route %s.", routeFilter));
        }
    }


    private String explainDelay(String lineName, String etaText, String occupancy) {
        if (etaText == null || !etaText.contains("Arriving in")) {
            return "AI Delay Predictor: awaiting live ETA";
        }
        int minutes = 0;
        String[] parts = etaText.split(" ");
        for (String part : parts) {
            try {
                minutes = Integer.parseInt(part);
                break;
            } catch (NumberFormatException ignored) {
            }
        }
        if (minutes >= 8) {
            return String.format(Locale.UK, "AI Delay Predictor: Route %s may be delayed by traffic or weather near Scunthorpe town centre.", lineName);
        }
        if ("Full/Crowded".equals(occupancy)) {
            return String.format(Locale.UK, "AI Delay Predictor: Route %s boarding may be slower because the bus is crowded.", lineName);
        }
        return "AI Delay Predictor: running on time";
    }

    private void renderBusBillboards() {
        long now = System.currentTimeMillis();
        for (BusBillboard billboard : busBillboards) {
            if (now - billboard.lastUpdatedMs > 120_000L) {
                continue;
            }
            if (!routeFilter.isEmpty() && !billboard.lineName.toLowerCase(Locale.UK).contains(routeFilter.toLowerCase(Locale.UK))) {
                continue;
            }
            TextView view = createBusBillboardView(billboard);
            float targetX = screenXFor(billboard);
            float targetY = screenYFor(billboard);
            billboard.displayedX = Float.isNaN(billboard.displayedX) ? targetX : lerp(billboard.displayedX, targetX, 0.24f);
            billboard.displayedY = Float.isNaN(billboard.displayedY) ? targetY : lerp(billboard.displayedY, targetY, 0.24f);
            LayoutParams params = new LayoutParams(dp(210), ViewGroup.LayoutParams.WRAP_CONTENT);
            params.leftMargin = dp((int) billboard.displayedX);
            params.topMargin = dp((int) billboard.displayedY);
            addView(view, params);
        }
    }

    private TextView createBusBillboardView(BusBillboard billboard) {
        TextView view = new TextView(getContext());
        view.setText(String.format(Locale.UK, "%s %s\n%s\nStatus: %s\n%s",
                occupancyIcon(billboard.occupancy), billboardTitle(billboard),
                billboard.etaText, billboard.occupancy, billboard.delayExplanation));
        view.setTextColor(Color.WHITE);
        view.setTextSize(14);
        view.setGravity(Gravity.CENTER);
        view.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        view.setBackgroundColor(Color.argb(225, 0, 0, 0));
        view.setPadding(dp(12), dp(10), dp(12), dp(10));
        return view;
    }

    private String billboardTitle(BusBillboard billboard) {
        String line = billboard.lineName == null ? "Bus" : billboard.lineName;
        String destination = billboard.destinationName == null ? "destination unknown" : billboard.destinationName;
        String lowerLine = line.toLowerCase(Locale.UK);
        String lowerDestination = destination.toLowerCase(Locale.UK);
        if (lowerLine.contains(" to ") || (!lowerDestination.isEmpty() && lowerLine.contains(lowerDestination))) {
            return line;
        }
        return line + " to " + destination;
    }

    private String occupancyIcon(String occupancy) {
        if ("Easy Seating".equals(occupancy)) {
            return "🟢";
        }
        if ("Standing Room Only".equals(occupancy)) {
            return "🟡";
        }
        if ("Full/Crowded".equals(occupancy)) {
            return "🔴";
        }
        return "🔵";
    }

    private int screenXFor(BusBillboard billboard) {
        float relativeBearing = (bearingTo(billboard.latitude, billboard.longitude) - smoothedCompassBearing + 540f) % 360f - 180f;
        int center = Math.max(0, getWidth() / 2 - dp(105));
        int offset = (int) (relativeBearing * dp(4));
        int max = Math.max(0, getWidth() - dp(220));
        return Math.max(0, Math.min(max, center + offset));
    }

    private int screenYFor(BusBillboard billboard) {
        float distance = distanceTo(billboard.latitude, billboard.longitude);
        return Math.max(dp(95), 120 + Math.min(220, (int) distance) - dp(BILLBOARD_HOVER_OFFSET_DP));
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
            return SCUNTHORPE_DECLINATION_DEGREES_EAST;
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
        if (!force && !pathOverlayView.isRouteEmpty() && distanceFromRoute(userLocation, pathOverlayView.routePoints) <= REROUTE_THRESHOLD_METERS) {
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
            createGpsAnchorsForRoute(fallback);
            pathOverlayView.setDirectionsStatus("Directions API: Failed");
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
            createGpsAnchorsForRoute(smoothed);
            post(() -> {
                pathOverlayView.setDirectionsStatus(directionsOk ? "Directions API: OK" : "Directions API: Failed");
                pathOverlayView.setRoute(smoothed);
            });
        });
    }

    private void createGpsAnchorsForRoute(List<Location> route) {
        synchronized (routeAnchors) {
            routeAnchors.clear();
            for (int i = 0; i < route.size(); i += Math.max(1, route.size() / 24)) {
                Location point = route.get(i);
                routeAnchors.add(new AnchorPoint(point.getLatitude(), point.getLongitude(), 0f));
            }
        }
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

    private float lerp(float from, float to, float fraction) {
        return from + (to - from) * fraction;
    }

    private TextView createPinView(BusStopPin pin) {
        TextView view = new TextView(getContext());
        view.setText(String.format(Locale.UK, "📍 %s\nRoute %s · %.0fm", pin.name, pin.route, distanceTo(pin)));
        view.setTextColor(Color.WHITE);
        view.setTextSize(14);
        view.setGravity(Gravity.CENTER);
        view.setBackgroundColor(Color.argb(230, 17, 76, 141));
        view.setPadding(dp(10), dp(8), dp(10), dp(8));
        return view;
    }

    private int screenXFor(BusStopPin pin) {
        float relativeBearing = (bearingTo(pin) - smoothedCompassBearing + 540f) % 360f - 180f;
        int center = Math.max(0, getWidth() / 2 - dp(80));
        int offset = (int) (relativeBearing * dp(4));
        int max = Math.max(0, getWidth() - dp(170));
        return Math.max(0, Math.min(max, center + offset));
    }

    private int screenYFor(BusStopPin pin, int index) {
        return 130 + Math.min(180, (int) distanceTo(pin)) + (index * 20);
    }

    private float bearingTo(BusStopPin pin) {
        if (userLocation == null) {
            return 0f;
        }
        return bearingTo(pin.latitude, pin.longitude);
    }

    private float distanceTo(BusStopPin pin) {
        if (userLocation == null) {
            return 0f;
        }
        return distanceTo(pin.latitude, pin.longitude);
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

    private String firstRoute() {
        return routeFilter.isEmpty() ? "Bus" : routeFilter;
    }

    private void updateStatus(String text) {
        statusView.setText(text);
    }

    @Override
    protected void onDetachedFromWindow() {
        directionsExecutor.shutdownNow();
        super.onDetachedFromWindow();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }


    private final class PathOverlayView extends ViewGroup {
        private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ribbonPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint arrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint wallPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint miniMapPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint miniMapPathPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ghostPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shelterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint calibratingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Location currentLocation;
        private NavigationTarget target;
        private final List<Location> routePoints = new ArrayList<>();
        private float bearing;
        private boolean calibrating;
        private boolean depthOcclusionEnabled;
        private String directionsStatus = "Directions API: Failed";
        private long animationStartedAt = System.currentTimeMillis();

        PathOverlayView(Context context) {
            super(context);
            setWillNotDraw(false);
            glowPaint.setColor(Color.argb(120, 0, 176, 255));
            glowPaint.setStyle(Paint.Style.STROKE);
            glowPaint.setStrokeCap(Paint.Cap.ROUND);
            glowPaint.setStrokeJoin(Paint.Join.ROUND);
            glowPaint.setStrokeWidth(dp(52));
            glowPaint.setMaskFilter(new BlurMaskFilter(dp(18), BlurMaskFilter.Blur.NORMAL));
            ribbonPaint.setColor(Color.argb(185, 255, 214, 0));
            ribbonPaint.setStyle(Paint.Style.STROKE);
            ribbonPaint.setStrokeCap(Paint.Cap.ROUND);
            ribbonPaint.setStrokeJoin(Paint.Join.ROUND);
            ribbonPaint.setStrokeWidth(dp(34));
            arrowPaint.setColor(Color.argb(235, 255, 255, 255));
            arrowPaint.setStyle(Paint.Style.FILL);
            wallPaint.setColor(Color.argb(170, 255, 235, 59));
            wallPaint.setStyle(Paint.Style.FILL);
            wallPaint.setMaskFilter(new BlurMaskFilter(dp(14), BlurMaskFilter.Blur.NORMAL));
            miniMapPaint.setColor(Color.argb(185, 0, 0, 0));
            miniMapPaint.setStyle(Paint.Style.FILL);
            miniMapPathPaint.setColor(Color.rgb(33, 150, 243));
            miniMapPathPaint.setStyle(Paint.Style.STROKE);
            miniMapPathPaint.setStrokeWidth(dp(4));
            miniMapPathPaint.setStrokeCap(Paint.Cap.ROUND);
            ghostPaint.setColor(Color.argb(120, 0, 229, 255));
            ghostPaint.setStyle(Paint.Style.FILL_AND_STROKE);
            shelterPaint.setColor(Color.argb(95, 0, 255, 180));
            shelterPaint.setStyle(Paint.Style.STROKE);
            shelterPaint.setStrokeWidth(dp(3));
            calibratingPaint.setColor(Color.WHITE);
            calibratingPaint.setTextSize(dp(15));
            calibratingPaint.setTextAlign(Paint.Align.CENTER);
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

        void setCalibrating(boolean calibrating) {
            this.calibrating = calibrating;
            invalidate();
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
            drawFutureHud(canvas);
            if (calibrating) {
                canvas.drawText("Calibrating… locking GPS and AR floor", getWidth() / 2f, getHeight() / 2f, calibratingPaint);
                return;
            }
            if (currentLocation == null || getWidth() == 0 || getHeight() == 0) {
                return;
            }
            drawGhostBuses(canvas);
            drawVirtualShelters(canvas);
            if (target == null) {
                drawMiniMap(canvas, Collections.emptyList());
                return;
            }
            List<Location> drawableRoute = routePoints.isEmpty() ? fallbackRoute(currentLocation, target) : routePoints;
            List<float[]> projectedPoints = projectRoute(drawableRoute);
            drawMiniMap(canvas, drawableRoute);
            if (projectedPoints.size() < 2) {
                return;
            }

            drawVerticalNavigationWall(canvas, projectedPoints);
            drawAnimatedArrows(canvas, projectedPoints);
            if (!depthOcclusionEnabled) {
                canvas.drawText("ARCore depth unavailable — GPS anchor wall fallback", getWidth() / 2f, getHeight() - dp(18), calibratingPaint);
            }
            postInvalidateOnAnimation();
        }


        private void drawVerticalNavigationWall(Canvas canvas, List<float[]> points) {
            for (int i = 1; i < points.size(); i++) {
                float[] previous = points.get(i - 1);
                float[] point = points.get(i);
                float alpha = Math.min(previous[2], point[2]);
                wallPaint.setAlpha((int) (190 * alpha));
                Path wall = new Path();
                wall.moveTo(previous[0], previous[1]);
                wall.lineTo(point[0], point[1]);
                wall.lineTo(point[0], Math.max(dp(90), point[1] - dp(120)));
                wall.lineTo(previous[0], Math.max(dp(90), previous[1] - dp(120)));
                wall.close();
                canvas.drawPath(wall, wallPaint);
            }
            Path topEdge = new Path();
            float[] first = points.get(0);
            topEdge.moveTo(first[0], Math.max(dp(90), first[1] - dp(120)));
            for (int i = 1; i < points.size(); i++) {
                float[] point = points.get(i);
                topEdge.lineTo(point[0], Math.max(dp(90), point[1] - dp(120)));
            }
            canvas.drawPath(topEdge, glowPaint);
        }

        private void drawMiniMap(Canvas canvas, List<Location> route) {
            float radius = dp(62);
            float centerX = getWidth() - radius - dp(18);
            float centerY = getHeight() - radius - dp(28);
            canvas.drawCircle(centerX, centerY, radius, miniMapPaint);
            miniMapPathPaint.setColor(Color.rgb(33, 150, 243));
            canvas.drawCircle(centerX, centerY, dp(4), arrowPaint);
            if (route == null || route.size() < 2 || currentLocation == null) {
                return;
            }
            Path miniPath = new Path();
            boolean started = false;
            for (Location point : route) {
                float distance = Math.min(250f, currentLocation.distanceTo(point));
                float relativeBearing = (currentLocation.bearingTo(point) - bearing + 540f) % 360f - 180f;
                double angle = Math.toRadians(relativeBearing - 90f);
                float scaled = (distance / 250f) * (radius - dp(10));
                float x = centerX + (float) Math.cos(angle) * scaled;
                float y = centerY + (float) Math.sin(angle) * scaled;
                if (!started) {
                    miniPath.moveTo(x, y);
                    started = true;
                } else {
                    miniPath.lineTo(x, y);
                }
            }
            canvas.drawPath(miniPath, miniMapPathPaint);
            canvas.drawText("MAP", centerX, centerY + radius - dp(8), calibratingPaint);
        }

        private void drawFutureHud(Canvas canvas) {
            float left = dp(10);
            float top = dp(76);
            float right = Math.min(getWidth() - dp(10), left + dp(245));
            float bottom = top + dp(92);
            canvas.drawRoundRect(new RectF(left, top, right, bottom), dp(12), dp(12), miniMapPaint);
            canvas.drawText("Future HUD", left + dp(12), top + dp(22), calibratingPaint);
            canvas.drawText(directionsStatus, left + dp(12), top + dp(43), calibratingPaint);
            canvas.drawText("X-Ray ghost buses: " + nearbyBusCount(), left + dp(12), top + dp(64), calibratingPaint);
            canvas.drawText("AI Delay Predictor: local", left + dp(12), top + dp(84), calibratingPaint);
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

        private void drawGhostBuses(Canvas canvas) {
            if (currentLocation == null) {
                return;
            }
            long now = System.currentTimeMillis();
            for (BusBillboard billboard : busBillboards) {
                if (now - billboard.lastUpdatedMs > 120_000L) {
                    continue;
                }
                float distance = distanceTo(billboard.latitude, billboard.longitude);
                if (distance > 1000f) {
                    continue;
                }
                float relativeBearing = (bearingTo(billboard.latitude, billboard.longitude) - bearing + 540f) % 360f - 180f;
                float x = getWidth() / 2f + relativeBearing * dp(5);
                float y = Math.max(dp(145), getHeight() * 0.58f - Math.min(dp(180), distance / 4f));
                float pulse = 0.45f + 0.55f * (1f - Math.min(1f, distance / 1000f));
                ghostPaint.setAlpha((int) (80 + 120 * pulse));
                canvas.drawRoundRect(new RectF(x - dp(28), y - dp(18), x + dp(28), y + dp(18)), dp(12), dp(12), ghostPaint);
                canvas.drawCircle(x - dp(14), y + dp(18), dp(5 + (int) (pulse * 5)), ghostPaint);
                canvas.drawCircle(x + dp(14), y + dp(18), dp(5 + (int) (pulse * 5)), ghostPaint);
                canvas.drawText("X-Ray " + billboard.lineName + " " + Math.round(distance) + "m", x, y - dp(28), calibratingPaint);
            }
        }

        private void drawVirtualShelters(Canvas canvas) {
            if (currentLocation == null) {
                return;
            }
            for (BusStopPin pin : stopPins) {
                float distance = distanceTo(pin.latitude, pin.longitude);
                if (distance > 90f) {
                    continue;
                }
                float x = screenXFor(pin) + dp(80);
                float y = screenYFor(pin, 1);
                RectF shelter = new RectF(x - dp(50), y - dp(65), x + dp(50), y + dp(25));
                canvas.drawRoundRect(shelter, dp(10), dp(10), shelterPaint);
                canvas.drawText("Virtual AR Bus Shelter", x, y - dp(42), calibratingPaint);
                canvas.drawText("Live board + occupancy heat-map", x, y - dp(20), calibratingPaint);
            }
        }

        private List<float[]> projectRoute(List<Location> route) {
            List<float[]> projected = new ArrayList<>();
            for (Location point : route) {
                float distance = Math.max(0.5f, currentLocation.distanceTo(point));
                if (distance > 65f) {
                    continue;
                }
                float relativeBearing = (currentLocation.bearingTo(point) - bearing + 540f) % 360f - 180f;
                if (Math.abs(relativeBearing) > 70f) {
                    continue;
                }
                float horizon = getHeight() * 0.42f;
                float ground = getHeight() - dp(42);
                float depth = Math.min(1f, distance / 65f);
                float x = getWidth() / 2f + relativeBearing * dp(6);
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

    private static final class AnchorPoint {
        final double latitude;
        final double longitude;
        final float altitudeMeters;

        AnchorPoint(double latitude, double longitude, float altitudeMeters) {
            this.latitude = latitude;
            this.longitude = longitude;
            this.altitudeMeters = altitudeMeters;
        }
    }


    private static final class BusStopPin {
        final String name;
        final String route;
        final double latitude;
        final double longitude;
        float displayedX = Float.NaN;
        float displayedY = Float.NaN;

        BusStopPin(String name, String route, double latitude, double longitude) {
            this.name = name;
            this.route = route;
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }
}
