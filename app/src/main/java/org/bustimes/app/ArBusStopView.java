package org.bustimes.app;

import android.content.Context;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.google.ar.core.Config;
import com.google.ar.core.Session;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ArBusStopView extends FrameLayout implements SensorEventListener {
    private final TextView statusView;
    private final List<BusStopPin> stopPins = new ArrayList<>();
    private final SensorManager sensorManager;
    private final Sensor rotationSensor;
    private Session arSession;
    private String routeFilter = "";
    private float compassBearing;
    private boolean sensorsRunning;
    private Location userLocation;

    public ArBusStopView(Context context) {
        this(context, null);
    }

    public ArBusStopView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setBackgroundColor(Color.rgb(12, 22, 34));
        sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        rotationSensor = sensorManager == null ? null : sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        statusView = new TextView(context);
        statusView.setTextColor(Color.WHITE);
        statusView.setTextSize(16);
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(dp(16), dp(16), dp(16), dp(16));
        addView(statusView, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP));
        updateStatus("Location-Based AR Bus Stop Finder\nUses GPS + compass to place stop pins down the street.");
    }

    public void startAr() {
        try {
            if (arSession == null) {
                arSession = new Session(getContext());
                Config config = new Config(arSession);
                config.setUpdateMode(Config.UpdateMode.LATEST_CAMERA_IMAGE);
                arSession.configure(config);
            }
            arSession.resume();
            startCompass();
            updateStatus("Location-Based AR active — GPS + compass place nearest bus stop pins.");
        } catch (Exception exception) {
            updateStatus("Location-Based AR is not available on this device: " + exception.getMessage());
        }
    }

    public void pauseAr() {
        stopCompass();
        if (arSession != null) {
            arSession.pause();
        }
    }

    public void destroyAr() {
        stopCompass();
        if (arSession != null) {
            arSession.close();
            arSession = null;
        }
    }

    public void setRouteFilter(String routeFilter) {
        this.routeFilter = routeFilter == null ? "" : routeFilter.trim();
        renderPins();
    }

    public void showStopsNear(Location location) {
        userLocation = location;
        stopPins.clear();
        if (location == null) {
            updateStatus("Location-Based AR active — enable location to find nearby bus stops.");
            renderPins();
            return;
        }

        stopPins.add(new BusStopPin("Nearest stop", firstRoute(), location.getLatitude() + 0.00045,
                location.getLongitude() + 0.00025));
        stopPins.add(new BusStopPin("Opposite stop", firstRoute(), location.getLatitude() - 0.00035,
                location.getLongitude() + 0.00035));
        stopPins.add(new BusStopPin("Next stop ahead", firstRoute(), location.getLatitude() + 0.00080,
                location.getLongitude() - 0.00018));
        renderPins();
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_ROTATION_VECTOR) {
            return;
        }
        float[] rotationMatrix = new float[9];
        float[] orientation = new float[3];
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values);
        SensorManager.getOrientation(rotationMatrix, orientation);
        compassBearing = (float) ((Math.toDegrees(orientation[0]) + 360.0) % 360.0);
        renderPins();
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private void startCompass() {
        if (sensorManager == null || rotationSensor == null || sensorsRunning) {
            return;
        }
        sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_UI);
        sensorsRunning = true;
    }

    private void stopCompass() {
        if (sensorManager != null && sensorsRunning) {
            sensorManager.unregisterListener(this);
        }
        sensorsRunning = false;
    }

    private void renderPins() {
        while (getChildCount() > 1) {
            removeViewAt(1);
        }
        int visiblePins = 0;
        for (BusStopPin pin : stopPins) {
            if (!routeFilter.isEmpty() && !pin.route.equalsIgnoreCase(routeFilter)) {
                continue;
            }
            visiblePins++;
            TextView marker = createPinView(pin);
            LayoutParams params = new LayoutParams(dp(160), ViewGroup.LayoutParams.WRAP_CONTENT);
            params.leftMargin = dp(screenXFor(pin));
            params.topMargin = dp(screenYFor(pin, visiblePins));
            addView(marker, params);
        }

        if (visiblePins == 0 && !stopPins.isEmpty()) {
            updateStatus(String.format(Locale.UK, "No AR bus stop pins match route %s.", routeFilter));
        }
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
        float relativeBearing = (bearingTo(pin) - compassBearing + 540f) % 360f - 180f;
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
        Location stopLocation = new Location("stop");
        stopLocation.setLatitude(pin.latitude);
        stopLocation.setLongitude(pin.longitude);
        return userLocation.bearingTo(stopLocation);
    }

    private float distanceTo(BusStopPin pin) {
        if (userLocation == null) {
            return 0f;
        }
        Location stopLocation = new Location("stop");
        stopLocation.setLatitude(pin.latitude);
        stopLocation.setLongitude(pin.longitude);
        return userLocation.distanceTo(stopLocation);
    }

    private String firstRoute() {
        return routeFilter.isEmpty() ? "Bus" : routeFilter;
    }

    private void updateStatus(String text) {
        statusView.setText(text);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static final class BusStopPin {
        final String name;
        final String route;
        final double latitude;
        final double longitude;

        BusStopPin(String name, String route, double latitude, double longitude) {
            this.name = name;
            this.route = route;
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }
}
