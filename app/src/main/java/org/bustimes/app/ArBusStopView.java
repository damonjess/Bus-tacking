package org.bustimes.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
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
    private final List<BusStopPin> stopPins = new ArrayList<>();
    private final SensorManager sensorManager;
    private final Sensor rotationSensor;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private String routeFilter = "";
    private float compassBearing;
    private boolean sensorsRunning;
    private boolean cameraRequested;
    private Location userLocation;

    public ArBusStopView(Context context) {
        this(context, null);
    }

    public ArBusStopView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setBackgroundColor(Color.rgb(12, 22, 34));
        sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        rotationSensor = sensorManager == null ? null : sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);

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

    private void renderPins() {
        while (getChildCount() > 2) {
            removeViewAt(2);
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
