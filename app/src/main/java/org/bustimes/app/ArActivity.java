package org.bustimes.app;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.hardware.GeomagneticField;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.SizeF;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Camera view with live buses and bus stops labelled where they really are.
 *
 * The camera preview is the background; the rotation-vector sensor (compass + gyro) says where the
 * phone is pointing and GPS says where it is, so each bus's position can be projected onto the
 * screen with {@link ArMath}. Everything comes from the same sources as the map: BODS for buses and
 * OpenStreetMap stops handed over by MainActivity. No Google keys, no ARCore.
 */
public class ArActivity extends AppCompatActivity implements BusDetailsSheet.Callbacks {

    // inputs from MainActivity
    static final String EXTRA_BBOX = "ar_bbox";
    static final String EXTRA_STOP_LATS = "ar_stop_lats";
    static final String EXTRA_STOP_LONS = "ar_stop_lons";
    static final String EXTRA_STOP_NAMES = "ar_stop_names";
    static final String EXTRA_STOP_ROUTES = "ar_stop_routes";
    // hand-back to MainActivity
    static final String EXTRA_FOCUS_LAT = "ar_focus_lat";
    static final String EXTRA_FOCUS_LON = "ar_focus_lon";
    static final String EXTRA_FOLLOW_BUS = "ar_follow_bus";
    static final String EXTRA_ALERT_ROUTE = "ar_alert_route";
    static final String EXTRA_STOP_LAT = "ar_stop_lat";
    static final String EXTRA_STOP_LON = "ar_stop_lon";

    private static final String PREF_AIM_OFFSET = "ar_aim_offset";
    private static final int REQ_PERMISSIONS = 61;
    private static final long STALE_BUS_MS = 4 * 60 * 1000L;
    private static final long GLIDE_MS = 2500L;
    private static final double MAX_BUS_RANGE_M = 2500d;
    private static final double MAX_STOP_RANGE_M = 400d;
    private static final int MAX_BUS_LABELS = 25;
    private static final int MAX_STOP_LABELS = 12;
    private static final double EYE_HEIGHT_M = 1.5d;
    private static final double DEFAULT_VERTICAL_FOV_DEG = 65d;

    private static final class ArBus {
        BusPosition pos;
        long receivedMs;
        double fromLat;
        double fromLon;
        double toLat;
        double toLon;
        long moveStartMs;

        double lat(long now) {
            return fromLat + (toLat - fromLat) * glide(now);
        }

        double lon(long now) {
            return fromLon + (toLon - fromLon) * glide(now);
        }

        private double glide(long now) {
            double t = (now - moveStartMs) / (double) GLIDE_MS;
            return t < 0 ? 0 : Math.min(t, 1);
        }
    }

    private static final class ArStop {
        String name;
        String routes;
        double lat;
        double lon;
    }

    private static final class Candidate {
        ArBus bus;
        ArStop stop;
        double east;
        double north;
        double distance;
        float[] screen;
    }

    private static final class Label {
        final RectF rect;
        final Candidate target;

        Label(RectF rect, Candidate target) {
            this.rect = rect;
            this.target = target;
        }
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, ArBus> buses = new LinkedHashMap<>();
    private final List<ArStop> stops = new ArrayList<>();

    private SensorManager sensorManager;
    private Sensor rotationSensor;
    private volatile float[] rotation;
    private volatile int compassAccuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH;
    private volatile float declination;
    private volatile Location userLocation;
    private LocationManager locationManager;
    private boolean cameraStarted;
    private boolean recentredFeed;
    private boolean sweepPending;
    private long sweepStartMs;
    private String bbox;
    private double tanHalfV;
    private double aimOffset = 0.0;

    private PreviewView previewView;
    private OverlayView overlay;
    private TextView statusView;
    private TextView hintView;
    private LinearLayout topBar;
    private LinearLayout aimBar;
    private TextView btnAim;
    private volatile int visibleBuses;
    private volatile int visibleStops;
    private String serviceStatus = "";

    private final Runnable hintTick = new Runnable() {
        @Override
        public void run() {
            updateHint();
            handler.postDelayed(this, 1000);
        }
    };

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BusTrackingService.ACTION_BUS_POSITION.equals(action)) {
                onBusPosition(intent);
            } else if (BusTrackingService.ACTION_CLEAR_TRACKING.equals(action)) {
                sweepStartMs = System.currentTimeMillis();
                sweepPending = true;
            } else if (BusTrackingService.ACTION_POLL_COMPLETE.equals(action)) {
                endSweep();
            } else if (BusTrackingService.ACTION_TRACKING_STATUS.equals(action)) {
                String message = intent.getStringExtra(BusTrackingService.EXTRA_STATUS_MESSAGE);
                serviceStatus = message == null ? "" : message;
            }
        }
    };

    private final SensorEventListener sensorListener = new SensorEventListener() {
        private final float[] raw = new float[9];

        @Override
        public void onSensorChanged(SensorEvent event) {
            float[] values = event.values;
            if (values.length > 4) {
                values = Arrays.copyOf(values, 4);
            }
            SensorManager.getRotationMatrixFromVector(raw, values);
            rotation = ArMath.smooth(rotation, raw, 0.25f);
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
            compassAccuracy = accuracy;
        }
    };

    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            userLocation = location;
            declination = new GeomagneticField((float) location.getLatitude(),
                    (float) location.getLongitude(), (float) location.getAltitude(),
                    System.currentTimeMillis()).getDeclination();
            if (!recentredFeed) {
                recentredFeed = true;
                bbox = bboxAround(location.getLatitude(), location.getLongitude());
                Intent refresh = new Intent(ArActivity.this, BusTrackingService.class);
                refresh.setAction(BusTrackingService.ACTION_REFRESH_NOW);
                refresh.putExtra(BusTrackingService.EXTRA_BOUNDING_BOX, bbox);
                startService(refresh);
            }
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) { }

        @Override
        public void onProviderEnabled(String provider) { }

        @Override
        public void onProviderDisabled(String provider) { }
    };

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        loadAimOffset();
        bbox = getIntent().getStringExtra(EXTRA_BBOX);
        if (TextUtils.isEmpty(bbox)) {
            bbox = BusTrackingService.DEFAULT_BOUNDING_BOX;
        }
        loadStopsFromIntent();
        tanHalfV = Math.tan(Math.toRadians(estimateVerticalFov() / 2d));

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);

        buildUi();
        requestMissingPermissions();
        if (granted(Manifest.permission.CAMERA)) {
            startCamera();
        }
        if (rotationSensor == null) {
            statusView.setText("This phone has no compass sensor, so AR can't aim.");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (rotationSensor != null) {
            sensorManager.registerListener(sensorListener, rotationSensor, SensorManager.SENSOR_DELAY_GAME);
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(BusTrackingService.ACTION_BUS_POSITION);
        filter.addAction(BusTrackingService.ACTION_CLEAR_TRACKING);
        filter.addAction(BusTrackingService.ACTION_POLL_COMPLETE);
        filter.addAction(BusTrackingService.ACTION_TRACKING_STATUS);
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        Intent start = new Intent(this, BusTrackingService.class);
        start.setAction(BusTrackingService.ACTION_START_MAP_TRACKING);
        start.putExtra(BusTrackingService.EXTRA_BOUNDING_BOX, bbox);
        startService(start);
        startLocation();
        handler.post(hintTick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(hintTick);
        sensorManager.unregisterListener(sensorListener);
        try {
            unregisterReceiver(receiver);
        } catch (IllegalArgumentException ignored) {
            // not registered
        }
        if (!ArrivalAlertStore.hasAlerts(this)) {
            stopService(new Intent(this, BusTrackingService.class));
        }
        try {
            locationManager.removeUpdates(locationListener);
        } catch (SecurityException ignored) {
            // permission revoked
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_PERMISSIONS) {
            return;
        }
        if (granted(Manifest.permission.CAMERA)) {
            startCamera();
        }
        startLocation();
        updateHint();
    }

    // ------------------------------------------------------------------ setup

    private int dp(float value) {
        return UiTheme.dp(this, value);
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        previewView = new PreviewView(this);
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        root.addView(previewView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        overlay = new OverlayView(this);
        root.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.VERTICAL);
        topBar.setPadding(dp(16), dp(12), dp(16), dp(8));
        TextView back = UiTheme.pillText(this, "\u2190  Back to map", UiTheme.WHITE,
                UiTheme.withAlpha(UiTheme.INK, 215), UiTheme.CYAN);
        back.setOnClickListener(v -> finish());
        topBar.addView(back, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        statusView = new TextView(this);
        statusView.setTextColor(UiTheme.WHITE);
        statusView.setTextSize(13);
        statusView.setBackground(UiTheme.pill(this, UiTheme.withAlpha(UiTheme.INK, 190), 0, 0, 12));
        statusView.setPadding(dp(10), dp(6), dp(10), dp(6));
        statusView.setText("Finding your location\u2026");
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusLp.topMargin = dp(8);
        topBar.addView(statusView, statusLp);
        root.addView(topBar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        aimBar = new LinearLayout(this);
        aimBar.setOrientation(LinearLayout.HORIZONTAL);
        aimBar.setGravity(Gravity.CENTER_VERTICAL);

        TextView btnLeft15 = createAimButton("\u00AB 15\u00B0");
        TextView btnLeft3 = createAimButton("\u2039 3\u00B0");
        btnAim = createAimButton("Aim");
        TextView btnRight3 = createAimButton("3\u00B0 \u203A");
        TextView btnRight15 = createAimButton("15\u00B0 \u00BB");

        btnLeft15.setOnClickListener(v -> adjustAim(15.0));
        btnLeft3.setOnClickListener(v -> adjustAim(3.0));
        btnAim.setOnClickListener(v -> resetAim());
        btnRight3.setOnClickListener(v -> adjustAim(-3.0));
        btnRight15.setOnClickListener(v -> adjustAim(-15.0));

        aimBar.addView(btnLeft15);
        aimBar.addView(btnLeft3);
        aimBar.addView(btnAim);
        aimBar.addView(btnRight3);
        aimBar.addView(btnRight15);

        FrameLayout.LayoutParams aimLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        aimLp.bottomMargin = dp(64);
        root.addView(aimBar, aimLp);

        hintView = new TextView(this);
        hintView.setTextColor(UiTheme.WHITE);
        hintView.setTextSize(13);
        hintView.setGravity(Gravity.CENTER);
        hintView.setBackground(UiTheme.pill(this, UiTheme.withAlpha(UiTheme.INK, 200), 0, 0, 14));
        hintView.setPadding(dp(14), dp(8), dp(14), dp(8));
        FrameLayout.LayoutParams hintLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        hintLp.bottomMargin = dp(20);
        root.addView(hintView, hintLp);

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            topBar.setPadding(dp(16), insets.getSystemWindowInsetTop() + dp(8), dp(16), dp(8));
            aimLp.bottomMargin = insets.getSystemWindowInsetBottom() + dp(64);
            aimBar.setLayoutParams(aimLp);
            hintLp.bottomMargin = insets.getSystemWindowInsetBottom() + dp(20);
            hintView.setLayoutParams(hintLp);
            return insets;
        });
        setContentView(root);
        updateAimButtonText();
    }

    private TextView createAimButton(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(UiTheme.WHITE);
        tv.setTextSize(12);
        tv.setGravity(Gravity.CENTER);
        tv.setBackground(UiTheme.pill(this, UiTheme.withAlpha(UiTheme.INK, 200), UiTheme.WHITE, 0, 12));
        tv.setPadding(dp(10), dp(6), dp(10), dp(6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(2);
        lp.rightMargin = dp(2);
        tv.setLayoutParams(lp);
        return tv;
    }

    private void loadAimOffset() {
        SharedPreferences prefs = getSharedPreferences("bus_times", MODE_PRIVATE);
        aimOffset = prefs.getFloat(PREF_AIM_OFFSET, 0f);
    }

    private void saveAimOffset() {
        getSharedPreferences("bus_times", MODE_PRIVATE).edit()
                .putFloat(PREF_AIM_OFFSET, (float) aimOffset)
                .apply();
    }

    private void adjustAim(double deltaDeg) {
        aimOffset = ArMath.signedAngle(0, aimOffset + deltaDeg);
        saveAimOffset();
        updateAimButtonText();
        updateHint();
        if (overlay != null) {
            overlay.postInvalidate();
        }
    }

    private void resetAim() {
        aimOffset = 0.0;
        saveAimOffset();
        updateAimButtonText();
        updateHint();
        if (overlay != null) {
            overlay.postInvalidate();
        }
    }

    private void updateAimButtonText() {
        if (btnAim == null) return;
        long round = Math.round(aimOffset);
        if (round == 0) {
            btnAim.setText("Aim");
        } else if (round > 0) {
            btnAim.setText("Aim +" + round + "\u00B0");
        } else {
            btnAim.setText("Aim " + round + "\u00B0");
        }
    }

    private void loadStopsFromIntent() {
        Intent intent = getIntent();
        double[] lats = intent.getDoubleArrayExtra(EXTRA_STOP_LATS);
        double[] lons = intent.getDoubleArrayExtra(EXTRA_STOP_LONS);
        String[] names = intent.getStringArrayExtra(EXTRA_STOP_NAMES);
        String[] routes = intent.getStringArrayExtra(EXTRA_STOP_ROUTES);
        if (lats == null || lons == null || names == null || routes == null) {
            return;
        }
        int n = Math.min(Math.min(lats.length, lons.length), Math.min(names.length, routes.length));
        for (int i = 0; i < n; i++) {
            ArStop stop = new ArStop();
            stop.lat = lats[i];
            stop.lon = lons[i];
            stop.name = names[i];
            stop.routes = routes[i];
            stops.add(stop);
        }
    }

    /** Vertical field of view of the back camera, from its sensor size and focal length. */
    private double estimateVerticalFov() {
        try {
            CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics c = manager.getCameraCharacteristics(id);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                if (facing == null || facing != CameraCharacteristics.LENS_FACING_BACK) {
                    continue;
                }
                SizeF sensor = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
                float[] focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                if (sensor == null || focal == null || focal.length == 0 || focal[0] <= 0f) {
                    continue;
                }
                // in portrait the sensor's long side runs up the screen
                double longSide = Math.max(sensor.getWidth(), sensor.getHeight());
                double fov = Math.toDegrees(2d * Math.atan(longSide / (2d * focal[0])));
                if (fov > 30d && fov < 120d) {
                    return fov;
                }
            }
        } catch (Exception ignored) {
            // fall through to the typical value
        }
        return DEFAULT_VERTICAL_FOV_DEG;
    }

    // ------------------------------------------------------------------ permissions, camera, location

    private boolean granted(String permission) {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestMissingPermissions() {
        List<String> missing = new ArrayList<>();
        if (!granted(Manifest.permission.CAMERA)) {
            missing.add(Manifest.permission.CAMERA);
        }
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)
                && !granted(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
            missing.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (!missing.isEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toArray(new String[0]), REQ_PERMISSIONS);
        }
    }

    private void startCamera() {
        if (cameraStarted) {
            return;
        }
        cameraStarted = true;
        final ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());
                provider.unbindAll();
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview);
            } catch (Exception e) {
                cameraStarted = false;
                statusView.setText("Camera unavailable");
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void startLocation() {
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)
                && !granted(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            return;
        }
        try {
            for (String provider : new String[] {LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                if (locationManager.isProviderEnabled(provider)) {
                    Location last = locationManager.getLastKnownLocation(provider);
                    Location current = userLocation;
                    if (last != null && (current == null || last.getTime() > current.getTime())) {
                        locationListener.onLocationChanged(last);
                    }
                    locationManager.requestLocationUpdates(provider, 2000, 3, locationListener);
                }
            }
        } catch (SecurityException | IllegalArgumentException ignored) {
            // provider unavailable or permission revoked
        }
    }

    private static String bboxAround(double lat, double lon) {
        return String.format(Locale.US, "%.4f,%.4f,%.4f,%.4f",
                lon - 0.04, lat - 0.025, lon + 0.04, lat + 0.025);
    }

    // ------------------------------------------------------------------ live buses

    private static String nz(String value) {
        return value == null ? "" : value;
    }

    private void onBusPosition(Intent intent) {
        String id = intent.getStringExtra(BusPosition.EXTRA_ID);
        double lat = intent.getDoubleExtra(BusPosition.EXTRA_LATITUDE, Double.NaN);
        double lon = intent.getDoubleExtra(BusPosition.EXTRA_LONGITUDE, Double.NaN);
        if (id == null || Double.isNaN(lat) || Double.isNaN(lon)) {
            return;
        }
        BusPosition pos = new BusPosition(id,
                nz(intent.getStringExtra(BusPosition.EXTRA_LINE_NAME)),
                nz(intent.getStringExtra(BusPosition.EXTRA_LINE_REF)),
                nz(intent.getStringExtra(BusPosition.EXTRA_DESTINATION_NAME)),
                nz(intent.getStringExtra(BusPosition.EXTRA_EXPECTED_ARRIVAL_TIME)),
                lat, lon,
                intent.getFloatExtra(BusPosition.EXTRA_BEARING, Float.NaN),
                nz(intent.getStringExtra(BusPosition.EXTRA_RECORDED_AT)),
                nz(intent.getStringExtra(BusPosition.EXTRA_OCCUPANCY)),
                nz(intent.getStringExtra(BusPosition.EXTRA_OPERATOR)));
        long now = System.currentTimeMillis();
        ArBus bus = buses.get(id);
        if (bus == null) {
            bus = new ArBus();
            bus.fromLat = lat;
            bus.fromLon = lon;
            buses.put(id, bus);
        } else {
            // start the slide from wherever the label currently is, so it never jumps
            bus.fromLat = bus.lat(now);
            bus.fromLon = bus.lon(now);
        }
        bus.toLat = lat;
        bus.toLon = lon;
        bus.moveStartMs = now;
        bus.pos = pos;
        bus.receivedMs = now;
    }

    private void endSweep() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, ArBus>> it = buses.entrySet().iterator();
        while (it.hasNext()) {
            ArBus bus = it.next().getValue();
            boolean notReported = sweepPending && bus.receivedMs < sweepStartMs;
            if (notReported || now - bus.receivedMs > STALE_BUS_MS) {
                it.remove();
            }
        }
        sweepPending = false;
    }

    private void updateHint() {
        Location loc = userLocation;
        if (!granted(Manifest.permission.CAMERA)) {
            statusView.setText("Allow camera access to use AR view.");
        } else if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)
                && !granted(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            statusView.setText("Allow location access to place buses around you.");
        } else if (loc == null) {
            statusView.setText("Finding your location\u2026");
        } else if (rotationSensor == null) {
            statusView.setText("This phone has no compass sensor, so AR can't aim.");
        } else if (rotation != null) {
            double heading = ArMath.headingDeg(rotation, declination, aimOffset);
            long hDeg = Math.round(heading) % 360;
            if (hDeg < 0) hDeg += 360;
            String card = ArMath.cardinal(heading);
            String gpsPart = loc.hasAccuracy()
                    ? " \u00B7 GPS \u00B1" + Math.round(loc.getAccuracy()) + " m"
                    : "";
            statusView.setText("Facing " + hDeg + "\u00B0 " + card + gpsPart);
        }

        String hint;
        if (compassAccuracy <= SensorManager.SENSOR_STATUS_ACCURACY_LOW && rotationSensor != null) {
            hint = "Strong magnetic interference: wave your phone in a figure 8.";
        } else if (loc != null && loc.hasAccuracy() && loc.getAccuracy() > 60f) {
            hint = "GPS accuracy is \u00B1" + Math.round(loc.getAccuracy()) + " m (poor fix). Labels may be offset.";
        } else if (visibleBuses + visibleStops > 0) {
            hint = visibleBuses + (visibleBuses == 1 ? " bus" : " buses") + " \u00B7 "
                    + visibleStops + (visibleStops == 1 ? " stop" : " stops") + " in view. Tap a label.";
        } else {
            hint = "Hold your phone up and turn slowly towards the street.";
        }
        hintView.setText(hint);
    }

    // ------------------------------------------------------------------ overlay

    private final class OverlayView extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint primary = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint secondary = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final List<Label> labels = new ArrayList<>();

        OverlayView(Context context) {
            super(context);
            setWillNotDraw(false);
            primary.setTypeface(Typeface.DEFAULT_BOLD);
            primary.setColor(Color.WHITE);
            secondary.setColor(UiTheme.TEXT_DIM);
            line.setStyle(Paint.Style.STROKE);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            List<Label> drawn = new ArrayList<>();
            float[] r = rotation;
            Location loc = userLocation;
            int w = getWidth();
            int h = getHeight();
            if (r != null && loc != null && w > 0 && h > 0) {
                drawScene(canvas, drawn, r, loc, w, h);
            } else {
                visibleBuses = 0;
                visibleStops = 0;
            }
            labels.clear();
            labels.addAll(drawn);
            postInvalidateOnAnimation();
        }

        private void drawScene(Canvas canvas, List<Label> drawn, float[] r, Location loc, int w, int h) {
            long now = System.currentTimeMillis();
            double tanV = tanHalfV;
            double tanH = ArMath.tanHalfHorizontal(tanV, w, h);
            double decl = declination;

            List<Candidate> busCandidates = new ArrayList<>();
            for (ArBus bus : new ArrayList<>(buses.values())) {
                if (bus.pos == null) continue;
                Candidate c = new Candidate();
                c.bus = bus;
                double[] enu = ArMath.enu(loc.getLatitude(), loc.getLongitude(), bus.lat(now), bus.lon(now));
                c.east = enu[0];
                c.north = enu[1];
                c.distance = Math.hypot(c.east, c.north);
                if (c.distance <= MAX_BUS_RANGE_M) {
                    busCandidates.add(c);
                }
            }
            Collections.sort(busCandidates, (a, b) -> Double.compare(a.distance, b.distance));
            if (busCandidates.size() > MAX_BUS_LABELS) {
                busCandidates = new ArrayList<>(busCandidates.subList(0, MAX_BUS_LABELS));
            }

            List<Candidate> stopCandidates = new ArrayList<>();
            for (ArStop stop : stops) {
                Candidate c = new Candidate();
                c.stop = stop;
                double[] enu = ArMath.enu(loc.getLatitude(), loc.getLongitude(), stop.lat, stop.lon);
                c.east = enu[0];
                c.north = enu[1];
                c.distance = Math.hypot(c.east, c.north);
                if (c.distance <= MAX_STOP_RANGE_M) {
                    stopCandidates.add(c);
                }
            }
            Collections.sort(stopCandidates, (a, b) -> Double.compare(a.distance, b.distance));
            if (stopCandidates.size() > MAX_STOP_LABELS) {
                stopCandidates = new ArrayList<>(stopCandidates.subList(0, MAX_STOP_LABELS));
            }

            List<Candidate> all = new ArrayList<>(busCandidates);
            all.addAll(stopCandidates);
            Collections.sort(all, (a, b) -> Double.compare(a.distance, b.distance));
            for (Candidate c : all) {
                c.screen = ArMath.project(r, c.east, c.north, -EYE_HEIGHT_M, decl, aimOffset, tanH, tanV, w, h);
            }

            // nearest first: they keep their natural spot and farther labels move up to make room
            List<RectF> placed = new ArrayList<>();
            int busesShown = 0;
            int stopsShown = 0;
            for (Candidate c : all) {
                if (c.screen == null) continue;
                RectF rect = measure(c);
                float width = rect.width();
                float height = rect.height();
                float x = c.screen[0];
                float y = c.screen[1];
                rect.set(x - width / 2f, y - dp(12) - height, x + width / 2f, y - dp(12));
                boolean fits = false;
                for (int tries = 0; tries < 7 && !fits; tries++) {
                    fits = true;
                    for (RectF other : placed) {
                        if (RectF.intersects(rect, other)) {
                            fits = false;
                            rect.offset(0, -(height + dp(4)));
                            break;
                        }
                    }
                }
                if (!fits || rect.top < 0 || rect.left > w || rect.right < 0) continue;
                placed.add(new RectF(rect));
                drawLabel(canvas, c, rect, x, y);
                drawn.add(new Label(new RectF(rect), c));
                if (c.bus != null) busesShown++; else stopsShown++;
            }
            visibleBuses = busesShown;
            visibleStops = stopsShown;

            drawEdgeHints(canvas, busCandidates, r, decl, w, h);
        }

        private String firstLine(Candidate c) {
            if (c.bus != null) {
                BusPosition p = c.bus.pos;
                String route = p.lineName.isEmpty() ? "Bus" : p.lineName;
                return p.destinationName.isEmpty() ? route : route + "  to " + p.destinationName;
            }
            return c.stop.name;
        }

        private String secondLine(Candidate c) {
            String distance = formatDistance(c.distance);
            if (c.bus != null) {
                String operator = OperatorNames.display(c.bus.pos.operatorName);
                return operator.isEmpty() ? distance : operator + " \u00B7 " + distance;
            }
            return "Bus stop \u00B7 " + distance;
        }

        private float scaleFor(Candidate c) {
            double t = (c.distance - 200d) / 2300d;
            return (float) Math.max(0.65d, Math.min(1d, 1d - t * 0.35d));
        }

        /** Sizes the label for a candidate; position is filled in by the caller. */
        private RectF measure(Candidate c) {
            float s = scaleFor(c);
            primary.setTextSize(dp(14) * s);
            secondary.setTextSize(dp(11) * s);
            float maxWidth = dp(210) * s;
            String one = TextUtils.ellipsize(firstLine(c), new android.text.TextPaint(primary), maxWidth,
                    TextUtils.TruncateAt.END).toString();
            String two = TextUtils.ellipsize(secondLine(c), new android.text.TextPaint(secondary), maxWidth,
                    TextUtils.TruncateAt.END).toString();
            float width = Math.max(primary.measureText(one), secondary.measureText(two)) + dp(20) * s;
            float height = (primary.getFontSpacing() + secondary.getFontSpacing()) + dp(12) * s;
            return new RectF(0, 0, width, height);
        }

        private void drawLabel(Canvas canvas, Candidate c, RectF rect, float anchorX, float anchorY) {
            float s = scaleFor(c);
            int accent = c.bus != null ? UiTheme.occupancyColor(c.bus.pos.occupancy) : UiTheme.CYAN;
            float radius = dp(12) * s;

            // leader line and dot at the true position
            line.setColor(UiTheme.withAlpha(accent, 200));
            line.setStrokeWidth(dp(1.5f));
            canvas.drawLine(rect.centerX(), rect.bottom, anchorX, anchorY, line);
            fill.setColor(accent);
            canvas.drawCircle(anchorX, anchorY, dp(4), fill);
            fill.setColor(Color.WHITE);
            canvas.drawCircle(anchorX, anchorY, dp(1.8f), fill);

            fill.setColor(UiTheme.withAlpha(UiTheme.INK, 225));
            canvas.drawRoundRect(rect, radius, radius, fill);
            line.setColor(accent);
            line.setStrokeWidth(dp(1.5f));
            canvas.drawRoundRect(rect, radius, radius, line);

            primary.setTextSize(dp(14) * s);
            secondary.setTextSize(dp(11) * s);
            float maxWidth = rect.width() - dp(20) * s;
            String one = TextUtils.ellipsize(firstLine(c), new android.text.TextPaint(primary), maxWidth,
                    TextUtils.TruncateAt.END).toString();
            String two = TextUtils.ellipsize(secondLine(c), new android.text.TextPaint(secondary), maxWidth,
                    TextUtils.TruncateAt.END).toString();
            float left = rect.left + dp(10) * s;
            float baseline1 = rect.top + dp(6) * s - primary.ascent();
            canvas.drawText(one, left, baseline1, primary);
            canvas.drawText(two, left, baseline1 + secondary.getFontSpacing() * 0.95f, secondary);
        }

        /** Little arrows at the screen edge pointing to the nearest buses that are out of view. */
        private void drawEdgeHints(Canvas canvas, List<Candidate> busCandidates, float[] r,
                                   double decl, int w, int h) {
            double heading = ArMath.headingDeg(r, decl, aimOffset);
            int shown = 0;
            for (Candidate c : busCandidates) {
                if (shown >= 3) break;
                if (c.screen != null) continue;
                double relative = ArMath.signedAngle(heading, ArMath.bearingDeg(c.east, c.north));
                boolean right = relative > 0;
                String route = c.bus.pos.lineName.isEmpty() ? "Bus" : c.bus.pos.lineName;
                String text = right ? route + " \u25B6" : "\u25C0 " + route;
                primary.setTextSize(dp(13));
                float tw = primary.measureText(text);
                float bw = tw + dp(16);
                float bh = dp(30);
                float top = h * 0.45f + shown * (bh + dp(6));
                float left = right ? w - bw - dp(8) : dp(8);
                RectF box = new RectF(left, top, left + bw, top + bh);
                fill.setColor(UiTheme.withAlpha(UiTheme.INK, 215));
                canvas.drawRoundRect(box, bh / 2f, bh / 2f, fill);
                line.setColor(UiTheme.occupancyColor(c.bus.pos.occupancy));
                line.setStrokeWidth(dp(1.5f));
                canvas.drawRoundRect(box, bh / 2f, bh / 2f, line);
                canvas.drawText(text, left + dp(8), top + bh / 2f - (primary.descent() + primary.ascent()) / 2f,
                        primary);
                shown++;
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_UP) {
                for (int i = labels.size() - 1; i >= 0; i--) {
                    Label label = labels.get(i);
                    if (label.rect.contains(event.getX(), event.getY())) {
                        performClick();
                        onLabelTapped(label.target);
                        return true;
                    }
                }
            }
            return super.onTouchEvent(event);
        }

        @Override
        public boolean performClick() {
            return super.performClick();
        }
    }

    // ------------------------------------------------------------------ taps and hand-back

    private void onLabelTapped(Candidate target) {
        if (target.bus != null) {
            openBus(target.bus);
        } else if (target.stop != null) {
            Intent result = new Intent();
            result.putExtra(EXTRA_STOP_LAT, target.stop.lat);
            result.putExtra(EXTRA_STOP_LON, target.stop.lon);
            returnToMap(result);
        }
    }

    private void openBus(ArBus bus) {
        BusPosition p = bus.pos;
        Location loc = userLocation;
        String distance = "";
        if (loc != null) {
            float[] result = new float[1];
            Location.distanceBetween(loc.getLatitude(), loc.getLongitude(), p.latitude, p.longitude, result);
            distance = formatDistance(result[0]) + " away";
        }
        long age = Math.max(0, (System.currentTimeMillis() - bus.receivedMs) / 1000);
        String lastSeen = age < 60 ? age + "s ago" : (age / 60) + "m ago";
        int eta = ArrivalAlerts.etaMinutesFrom(p.expectedArrivalTime);
        String vehicleId = p.id.startsWith("bus:") ? "" : p.id.replace('_', ' ').trim();
        BusSnapshot snapshot = new BusSnapshot(p.id, p.lineName, p.lineRef, p.destinationName, p.occupancy,
                vehicleId, lastSeen, OperatorNames.display(p.operatorName), distance,
                p.latitude, p.longitude, p.bearing, Float.NaN, eta, "");
        BusDetailsSheet.show(this, snapshot, this);
    }

    private void returnToMap(Intent extras) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (extras != null) {
            intent.putExtras(extras);
        }
        startActivity(intent);
        finish();
    }

    private static String formatDistance(double meters) {
        if (meters < 1000) {
            return Math.round(meters / 10.0) * 10 + " m";
        }
        return String.format(Locale.UK, "%.1f km", meters / 1000.0);
    }

    // ------------------------------------------------------------------ BusDetailsSheet.Callbacks

    @Override
    public void onNavigateToBus(double latitude, double longitude) {
        Uri walking = Uri.parse("google.navigation:q=" + latitude + "," + longitude + "&mode=w");
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, walking));
        } catch (ActivityNotFoundException e) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("geo:" + latitude + "," + longitude + "?q=" + latitude + "," + longitude)));
            } catch (ActivityNotFoundException again) {
                Toast.makeText(this, "No maps app installed", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    public void onFollowBus(String busId, boolean follow) {
        ArBus bus = buses.get(busId);
        if (!follow || bus == null || bus.pos == null) {
            return;
        }
        Intent result = new Intent();
        result.putExtra(EXTRA_FOCUS_LAT, bus.pos.latitude);
        result.putExtra(EXTRA_FOCUS_LON, bus.pos.longitude);
        result.putExtra(EXTRA_FOLLOW_BUS, busId);
        returnToMap(result);
    }

    @Override
    public boolean isFollowingBus(String busId) {
        return false;
    }

    @Override
    public void onShowBusOnMap(double latitude, double longitude) {
        Intent result = new Intent();
        result.putExtra(EXTRA_FOCUS_LAT, latitude);
        result.putExtra(EXTRA_FOCUS_LON, longitude);
        returnToMap(result);
    }

    private Set<String> favorites() {
        SharedPreferences prefs = getSharedPreferences("bus_times", MODE_PRIVATE);
        return new HashSet<>(prefs.getStringSet("favorite_routes", new HashSet<>()));
    }

    @Override
    public void onToggleFavorite(String route) {
        Set<String> favs = favorites();
        if (!favs.remove(route)) {
            favs.add(route);
        }
        getSharedPreferences("bus_times", MODE_PRIVATE).edit().putStringSet("favorite_routes", favs).apply();
    }

    @Override
    public boolean isFavorite(String route) {
        return favorites().contains(route);
    }

    @Override
    public void onConfigureAlert(String route) {
        Intent result = new Intent();
        result.putExtra(EXTRA_ALERT_ROUTE, route);
        returnToMap(result);
    }

    @Override
    public boolean isAlertArmedForRoute(String route) {
        return ArrivalAlertStore.isArmedForRoute(this, route);
    }
}
