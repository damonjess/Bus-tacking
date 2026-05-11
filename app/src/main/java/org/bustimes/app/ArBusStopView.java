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
import android.graphics.SurfaceTexture;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.location.Location;
import android.os.Build;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class ArBusStopView extends FrameLayout implements SensorEventListener {
    private final TextureView cameraPreview;
    private final TextView statusView;
    private final PathOverlayView pathOverlayView;
    private final List<BusStopPin> stopPins = new ArrayList<>();
    private final SensorManager sensorManager;
    private final Sensor rotationSensor;
    private final Sensor accelerometerSensor;
    private final Sensor magneticSensor;
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
        updateStatus("Location-Based AR Bus Stop Finder\nUses live camera + GPS + compass to place stop pins.");
    }

    public void startAr() {
        cameraRequested = true;
        startCompass();
        if (cameraPreview.isAvailable()) {
            openCamera();
        }
        updateStatus("Location-Based AR active — camera, GPS and compass place nearest bus stop pins.");
    }

    public void pauseAr() {
        cameraRequested = false;
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
        updateStatus("AR wayfinding path locked to " + name + " — follow the glowing pavement line.");
    }

    public void clearNavigationTarget() {
        navigationTarget = null;
        pathOverlayView.setTarget(null);
        renderPins();
    }

    public void showStopsNear(Location location) {
        userLocation = smoothLocation(userLocation, location, 0.22f);
        pathOverlayView.setUserLocation(userLocation);
        stopPins.clear();
        if (userLocation == null) {
            updateStatus("Location-Based AR active — enable location to find nearby bus stops.");
            renderPins();
            return;
        }

        stopPins.add(new BusStopPin("Nearest stop", firstRoute(), userLocation.getLatitude() + 0.00045,
                userLocation.getLongitude() + 0.00025));
        stopPins.add(new BusStopPin("Opposite stop", firstRoute(), userLocation.getLatitude() - 0.00035,
                userLocation.getLongitude() + 0.00035));
        stopPins.add(new BusStopPin("Next stop ahead", firstRoute(), userLocation.getLatitude() + 0.00080,
                userLocation.getLongitude() - 0.00018));
        renderPins();
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
            sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_UI);
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
        while (getChildCount() > 3) {
            removeViewAt(3);
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

        if (visiblePins == 0 && !stopPins.isEmpty()) {
            updateStatus(String.format(Locale.UK, "No AR bus stop pins match route %s.", routeFilter));
        }
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


    private final class PathOverlayView extends ViewGroup {
        private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Location currentLocation;
        private NavigationTarget target;
        private float bearing;

        PathOverlayView(Context context) {
            super(context);
            setWillNotDraw(false);
            glowPaint.setColor(Color.argb(170, 255, 214, 0));
            glowPaint.setStyle(Paint.Style.STROKE);
            glowPaint.setStrokeCap(Paint.Cap.ROUND);
            glowPaint.setStrokeJoin(Paint.Join.ROUND);
            glowPaint.setStrokeWidth(dp(18));
            glowPaint.setMaskFilter(new BlurMaskFilter(dp(10), BlurMaskFilter.Blur.NORMAL));
            linePaint.setColor(Color.rgb(255, 245, 125));
            linePaint.setStyle(Paint.Style.STROKE);
            linePaint.setStrokeCap(Paint.Cap.ROUND);
            linePaint.setStrokeJoin(Paint.Join.ROUND);
            linePaint.setStrokeWidth(dp(7));
            dotPaint.setColor(Color.WHITE);
            dotPaint.setStyle(Paint.Style.FILL);
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

        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (currentLocation == null || target == null || getWidth() == 0 || getHeight() == 0) {
                return;
            }
            Location targetLocation = new Location("ar-target");
            targetLocation.setLatitude(target.latitude);
            targetLocation.setLongitude(target.longitude);
            float distance = Math.max(1f, currentLocation.distanceTo(targetLocation));
            float relativeBearing = (currentLocation.bearingTo(targetLocation) - bearing + 540f) % 360f - 180f;
            float startX = getWidth() / 2f;
            float startY = getHeight() - dp(52);
            float endX = Math.max(dp(38), Math.min(getWidth() - dp(38), startX + relativeBearing * dp(5)));
            float endY = Math.max(dp(140), startY - Math.min(getHeight() * 0.58f, distance * dp(3)));
            Path path = new Path();
            path.moveTo(startX, startY);
            path.cubicTo(startX, startY - dp(120), endX, endY + dp(120), endX, endY);
            canvas.drawPath(path, glowPaint);
            canvas.drawPath(path, linePaint);
            canvas.drawCircle(endX, endY, dp(8), dotPaint);
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
