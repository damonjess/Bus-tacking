package org.bustimes.app;

import android.Manifest;
import android.animation.ValueAnimator;
import android.app.AlertDialog;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.view.animation.LinearInterpolator;

import java.util.ArrayList;
import java.util.HashMap;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

public class MainActivity extends Activity {
    private static final int LOCATION_PERMISSION_REQUEST = 1001;
    private static final int CENTER_MAP_PERMISSION_REQUEST = 1002;
    private static final int AUDIO_PERMISSION_REQUEST = 1003;
    private static final int CAMERA_PERMISSION_REQUEST = 1004;
    private static final String SAVED_URL = "saved_url";
    private static final String MAP_URL = "https://bustimes.org/map";
    private static final String PREFS_NAME = "bustimes_prefs";
    private static final String PREF_NIGHT_MODE = "night_mode";
    private static final long BUS_MARKER_ANIMATION_MS = 5_000L;
    private static final long MARKER_JS_THROTTLE_MS = 300L;
    private static final long BILLBOARD_THROTTLE_MS = 600L;
    private static final long MARKER_STALE_MS = 120_000L;
    private static final long FOLLOW_INTERVAL_MS = 5_000L;
    private static final long CHIPS_REBUILD_INTERVAL_MS = 5_000L;

    private WebView webView;
    private ProgressBar progressBar;
    private Button locateButton;
    private Button refreshButton;
    private Button microphoneButton;
    private Button arToggleButton;
    private LinearLayout zoomControls;
    private Button nightModeButton;
    private TextView statusPill;
    private TextView liveCountPill;
    private HorizontalScrollView routeChipsScroll;
    private LinearLayout routeChipRow;
    private ArBusStopView arBusStopView;
    private final Map<String, AnimatedBusMarker> trackedBusMarkers = new HashMap<>();
    private final BroadcastReceiver busTrackingReceiver = new BusTrackingReceiver();
    private GeolocationPermissions.Callback geolocationCallback;
    private String geolocationOrigin;
    private boolean busTrackingReceiverRegistered;
    private SpeechRecognizer speechRecognizer;
    private LocationListener arLocationListener;
    private LocationListener locateLocationListener;
    private Runnable locateTimeoutRunnable;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private String activeRouteFilter = "";
    private String followBusId;
    private boolean nightModeEnabled;
    private boolean arModeEnabled;
    private String pendingNavigationName;
    private double pendingNavigationLatitude = Double.NaN;
    private double pendingNavigationLongitude = Double.NaN;
    private long lastChipsRebuildMs;
    private final Runnable followRunnable = new Runnable() {
        @Override
        public void run() {
            stepFollowMode();
        }
    };
    private final Runnable markerCleanupRunnable = new Runnable() {
        @Override
        public void run() {
            removeStaleMarkers();
            uiHandler.postDelayed(this, 30_000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SharedPreferences preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        nightModeEnabled = preferences.getBoolean(PREF_NIGHT_MODE, false);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(UiTheme.INK);
        webView = new WebView(this);
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        locateButton = createFabButton("⌖", 26, "Home: find my location on the live bus map", v -> centerMapOnUserLocation());
        refreshButton = createFabButton("⟳", 26, "Refresh the live bus map", v -> refreshLiveMap());
        microphoneButton = createFabButton("🎙", 22, "Voice search for a bus route", v -> startVoiceSearch());
        arToggleButton = createArToggleButton();
        zoomControls = createZoomControls();
        statusPill = createStatusPill();
        liveCountPill = createLiveCountPill();
        routeChipsScroll = createRouteChipsScroll();
        arBusStopView = new ArBusStopView(this);
        arBusStopView.setVisibility(View.GONE);

        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(arBusStopView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        arBusStopView.setBusTapListener(this::openBusDetailsFromAr);

        FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP);
        root.addView(progressBar, progressParams);
        progressBar.getProgressDrawable().setColorFilter(UiTheme.CYAN, PorterDuff.Mode.SRC_IN);

        FrameLayout.LayoutParams locateParams = new FrameLayout.LayoutParams(dp(60), dp(60),
                Gravity.BOTTOM | Gravity.END);
        locateParams.setMargins(0, 0, dp(20), dp(30));
        root.addView(locateButton, locateParams);

        FrameLayout.LayoutParams refreshParams = new FrameLayout.LayoutParams(dp(60), dp(60),
                Gravity.BOTTOM | Gravity.START);
        refreshParams.setMargins(dp(20), 0, 0, dp(30));
        root.addView(refreshButton, refreshParams);

        FrameLayout.LayoutParams microphoneParams = new FrameLayout.LayoutParams(dp(60), dp(60),
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        microphoneParams.setMargins(0, 0, 0, dp(30));
        root.addView(microphoneButton, microphoneParams);

        FrameLayout.LayoutParams arToggleParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        arToggleParams.setMargins(dp(16), dp(72), 0, 0);
        root.addView(arToggleButton, arToggleParams);

        FrameLayout.LayoutParams zoomParams = new FrameLayout.LayoutParams(dp(54),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        zoomParams.setMargins(0, dp(72), dp(16), 0);
        root.addView(zoomControls, zoomParams);

        FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP);
        statusParams.setMargins(dp(64), dp(14), dp(80), 0);
        root.addView(statusPill, statusParams);

        FrameLayout.LayoutParams liveCountParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        liveCountParams.setMargins(0, dp(58), 0, 0);
        root.addView(liveCountPill, liveCountParams);

        FrameLayout.LayoutParams chipsParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP);
        chipsParams.setMargins(dp(150), dp(96), dp(80), 0);
        root.addView(routeChipsScroll, chipsParams);

        setContentView(root);
        configureWebView();

        String url = MAP_URL;
        if (savedInstanceState != null) {
            url = savedInstanceState.getString(SAVED_URL, MAP_URL);
        }
        webView.loadUrl(url);
        registerBusTrackingReceiver();
        uiHandler.postDelayed(markerCleanupRunnable, 30_000L);
    }

    @Override
    protected void onStart() {
        super.onStart();
        setMapTrackingActive(true);
    }

    @Override
    protected void onStop() {
        stopLocateUpdates();
        setMapTrackingActive(false);
        super.onStop();
    }

    private void setMapTrackingActive(boolean active) {
        Intent intent = new Intent(this, BusTrackingService.class);
        intent.setAction(active
                ? BusTrackingService.ACTION_START_MAP_TRACKING
                : BusTrackingService.ACTION_STOP_MAP_TRACKING);
        startService(intent);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setGeolocationEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setSupportMultipleWindows(false);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.setSafeBrowsingEnabled(true);
        }

        CookieManager.getInstance().setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        }

        webView.setBackgroundColor(UiTheme.INK);
        webView.addJavascriptInterface(new BusMarkerBridge(), "BusMarkerBridge");
        webView.setWebViewClient(new BusTimesWebViewClient());
        webView.setWebChromeClient(new BusTimesChromeClient());
        webView.setDownloadListener(new BusTimesDownloadListener());
    }

    private Button createFabButton(String glyph, int glyphSize, String description, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(glyph);
        button.setTextSize(glyphSize);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setTextColor(Color.WHITE);
        button.setStateListAnimator(null);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinHeight(0);
        button.setPadding(0, 0, 0, 0);
        android.graphics.drawable.GradientDrawable gradient = UiTheme.circleGradient(this, UiTheme.BLUE, UiTheme.BLUE_DEEP);
        gradient.setStroke(dp(1), UiTheme.withAlpha(Color.WHITE, 70));
        button.setBackground(UiTheme.ripple(gradient));
        button.setContentDescription(description);
        button.setOnClickListener(listener);
        UiTheme.pressScale(button);
        return button;
    }

    private Button createArToggleButton() {
        Button button = new Button(this);
        button.setText("✦ AR View");
        button.setTextSize(13);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setTextColor(Color.WHITE);
        button.setStateListAnimator(null);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinHeight(0);
        button.setPadding(dp(18), dp(10), dp(18), dp(10));
        android.graphics.drawable.GradientDrawable gradient = UiTheme.pill(this,
                UiTheme.withAlpha(UiTheme.INK_LIGHT, 235), UiTheme.withAlpha(UiTheme.CYAN, 160), 1.2f, 24f);
        button.setBackground(UiTheme.ripple(gradient));
        button.setContentDescription("Switch between standard map and AR bus stop finder");
        button.setOnClickListener(v -> toggleArMode());
        UiTheme.pressScale(button);
        return button;
    }

    private LinearLayout createZoomControls() {
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setGravity(Gravity.CENTER);
        controls.setBackground(UiTheme.pill(this, UiTheme.withAlpha(UiTheme.INK_LIGHT, 225),
                UiTheme.withAlpha(Color.WHITE, 45), 1f, 27f));
        controls.setPadding(dp(5), dp(5), dp(5), dp(5));
        controls.setContentDescription("Map zoom controls");

        Button zoomInButton = createZoomButton("+", "Zoom in on the live bus map");
        zoomInButton.setOnClickListener(v -> zoomWebMap(true));
        controls.addView(zoomInButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

        controls.addView(createDivider());

        Button zoomOutButton = createZoomButton("−", "Zoom out on the live bus map");
        zoomOutButton.setOnClickListener(v -> zoomWebMap(false));
        controls.addView(zoomOutButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

        controls.addView(createDivider());

        nightModeButton = createZoomButton(nightModeEnabled ? "☀" : "🌙", "Toggle night mode");
        nightModeButton.setTextSize(16);
        nightModeButton.setOnClickListener(v -> applyNightMode(!nightModeEnabled, false));
        controls.addView(nightModeButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));

        return controls;
    }

    private View createDivider() {
        View divider = new View(this);
        divider.setBackgroundColor(UiTheme.withAlpha(Color.WHITE, 40));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        params.setMargins(dp(10), 0, dp(10), 0);
        divider.setLayoutParams(params);
        return divider;
    }

    private Button createZoomButton(String label, String description) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(22);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setTextColor(Color.WHITE);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setContentDescription(description);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinHeight(0);
        button.setStateListAnimator(null);
        button.setPadding(0, 0, 0, 0);
        return button;
    }

    private TextView createStatusPill() {
        TextView pill = new TextView(this);
        pill.setText("Live tracking ready · tap a bus for full details");
        pill.setTextColor(Color.WHITE);
        pill.setTextSize(12);
        pill.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        pill.setGravity(Gravity.CENTER);
        pill.setMaxLines(1);
        pill.setEllipsize(android.text.TextUtils.TruncateAt.END);
        pill.setBackground(UiTheme.ripple(UiTheme.pill(this, UiTheme.withAlpha(UiTheme.INK_LIGHT, 235),
                UiTheme.withAlpha(Color.WHITE, 45), 1f, 20f)));
        pill.setPadding(dp(16), dp(7), dp(16), dp(7));
        pill.setOnClickListener(v -> pill.setVisibility(View.GONE));
        return pill;
    }

    private TextView createLiveCountPill() {
        TextView pill = new TextView(this);
        pill.setText("0 buses live");
        pill.setTextColor(UiTheme.CYAN);
        pill.setTextSize(12);
        pill.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        pill.setGravity(Gravity.CENTER);
        pill.setBackground(UiTheme.ripple(UiTheme.pill(this, UiTheme.withAlpha(UiTheme.INK_LIGHT, 235),
                UiTheme.withAlpha(UiTheme.CYAN, 150), 1f, 20f)));
        pill.setPadding(dp(16), dp(7), dp(16), dp(7));
        pill.setOnClickListener(v -> showBusListDialog());
        return pill;
    }

    private HorizontalScrollView createRouteChipsScroll() {
        HorizontalScrollView scroller = new HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setFillViewport(true);
        routeChipRow = new LinearLayout(this);
        routeChipRow.setOrientation(LinearLayout.HORIZONTAL);
        routeChipRow.setGravity(Gravity.CENTER_VERTICAL);
        scroller.addView(routeChipRow, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        scroller.setVisibility(View.GONE);
        return scroller;
    }

    private void rebuildRouteChips() {
        long now = System.currentTimeMillis();
        if (now - lastChipsRebuildMs < CHIPS_REBUILD_INTERVAL_MS) {
            return;
        }
        lastChipsRebuildMs = now;

        Map<String, Integer> routeCounts = new LinkedHashMap<>();
        for (AnimatedBusMarker marker : trackedBusMarkers.values()) {
            String route = marker.label == null ? "" : marker.label.trim();
            if (route.isEmpty()) {
                continue;
            }
            Integer count = routeCounts.get(route);
            routeCounts.put(route, count == null ? 1 : count + 1);
        }
        if (routeCounts.isEmpty()) {
            routeChipRow.removeAllViews();
            routeChipsScroll.setVisibility(View.GONE);
            return;
        }

        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(routeCounts.entrySet());
        sorted.sort((a, b) -> b.getValue() - a.getValue());
        if (sorted.size() > 8) {
            sorted = sorted.subList(0, 8);
        }

        routeChipRow.removeAllViews();
        routeChipRow.addView(makeRouteChip("All", "", sorted.size()));
        for (Map.Entry<String, Integer> entry : sorted) {
            routeChipRow.addView(makeRouteChip(entry.getKey() + " ·" + entry.getValue(), entry.getKey(), entry.getValue()));
        }
        routeChipsScroll.setVisibility(View.VISIBLE);
    }

    private TextView makeRouteChip(String label, String route, int count) {
        boolean selected = activeRouteFilter.isEmpty() ? route.isEmpty() : activeRouteFilter.equalsIgnoreCase(route);
        TextView chip = UiTheme.pillText(this, label,
                selected ? UiTheme.INK : Color.WHITE,
                selected ? UiTheme.CYAN : UiTheme.withAlpha(UiTheme.INK_LIGHT, 235),
                selected ? UiTheme.CYAN : UiTheme.withAlpha(Color.WHITE, 55));
        chip.setOnClickListener(v -> selectRouteFilter(route));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(dp(5), 0, dp(5), 0);
        chip.setLayoutParams(params);
        return chip;
    }

    private void selectRouteFilter(String route) {
        activeRouteFilter = route == null ? "" : route.trim();
        filterMapMarkersForRoute(activeRouteFilter);
        arBusStopView.setRouteFilter(activeRouteFilter);
        lastChipsRebuildMs = 0L;
        rebuildRouteChips();
    }

    private void showBusListDialog() {
        if (trackedBusMarkers.isEmpty()) {
            Toast.makeText(this, "No live buses yet. Add a free BODS key for real-time tracking.", Toast.LENGTH_LONG).show();
            return;
        }
        List<AnimatedBusMarker> markers = new ArrayList<>(trackedBusMarkers.values());
        markers.sort((a, b) -> Float.compare(a.currentSpeedKph, b.currentSpeedKph));
        StringBuilder message = new StringBuilder();
        int shown = 0;
        for (AnimatedBusMarker marker : markers) {
            if (shown >= 12) {
                message.append("\n…and ").append(markers.size() - shown).append(" more");
                break;
            }
            message.append("• Route ").append(marker.label)
                    .append(" → ").append(firstNonEmpty(marker.destinationName, "unknown"))
                    .append(" · ").append(marker.currentSpeedKph <= 0.5f ? "stopped" : String.format(Locale.UK, "%.0f mph", marker.currentSpeedKph * 0.621371f))
                    .append("\n");
            shown++;
        }
        new AlertDialog.Builder(this)
                .setTitle(trackedBusMarkers.size() + " buses live now")
                .setMessage(message.toString().trim())
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void refreshLiveMap() {
        hideAdsOnPage();
        webView.reload();
        Intent intent = new Intent(this, BusTrackingService.class);
        intent.setAction(BusTrackingService.ACTION_REFRESH_NOW);
        startService(intent);
        Toast.makeText(this, "Refreshing live bus map", Toast.LENGTH_SHORT).show();
    }

    private void zoomWebMap(boolean zoomIn) {
        String selector = zoomIn
                ? ".maplibregl-ctrl-zoom-in, .mapboxgl-ctrl-zoom-in, .leaflet-control-zoom-in, [aria-label='Zoom in']"
                : ".maplibregl-ctrl-zoom-out, .mapboxgl-ctrl-zoom-out, .leaflet-control-zoom-out, [aria-label='Zoom out']";
        String script = "(function(){"
                + "var button=document.querySelector(\"" + selector + "\");"
                + "if(button){button.click();return true;}"
                + "return false;"
                + "})();";
        webView.evaluateJavascript(script, clicked -> {
            if (!"true".equals(clicked)) {
                if (zoomIn) {
                    webView.zoomIn();
                } else {
                    webView.zoomOut();
                }
            }
        });
    }

    private void applyNightMode(boolean night, boolean force) {
        if (!force && nightModeEnabled == night) {
            return;
        }
        nightModeEnabled = night;
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(PREF_NIGHT_MODE, night).apply();
        if (nightModeButton != null) {
            nightModeButton.setText(night ? "☀" : "🌙");
            nightModeButton.setContentDescription(night ? "Switch back to day mode" : "Switch to night mode");
        }
        String css;
        if (night) {
            css = "body{background:#0b1226!important;color:#dfe7ff!important}"
                    + ".maplibregl-canvas,.mapboxgl-canvas,.leaflet-tile-pane,.leaflet-tile-container,.leaflet-tile"
                    + "{filter:invert(1) hue-rotate(180deg) brightness(.92) contrast(.92) saturate(.85)!important}"
                    + ".maplibregl-popup-content,.mapboxgl-popup-content,.leaflet-popup-content-wrapper"
                    + "{background:#101a38!important;color:#dfe7ff!important}"
                    + ".maplibregl-popup-tip,.mapboxgl-popup-tip,.leaflet-popup-tip"
                    + "{border-top-color:#101a38!important;border-bottom-color:#101a38!important;"
                    + "border-left-color:#101a38!important;border-right-color:#101a38!important}"
                    + "h1,h2,h3,h4,p,span,td,th,li,label,small,strong,b{color:#dfe7ff!important}"
                    + "a{color:#7fc4ff!important}"
                    + "header,footer,nav,section,article,aside,table,form,main{background-color:#0e1730!important;color:#dfe7ff!important}"
                    + "button,input,select,textarea{background-color:#16224a!important;color:#dfe7ff!important;border-color:#2a3a6e!important}";
        } else {
            css = "";
        }
        String script = "(function(){"
                + "var style=document.getElementById('bustimes-night');"
                + "if(!style){style=document.createElement('style');style.id='bustimes-night';document.head.appendChild(style);}"
                + "style.textContent=\"" + escapeJs(css).replace("\"", "\\\"") + "\";"
                + "return true;})();";
        webView.evaluateJavascript(script, ignored -> {
        });
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (webView != null) {
            outState.putString(SAVED_URL, webView.getUrl());
        }
    }

    @Override
    public void onBackPressed() {
        if (arModeEnabled) {
            showStandardMap();
            return;
        }
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        uiHandler.removeCallbacksAndMessages(null);
        if (busTrackingReceiverRegistered) {
            unregisterReceiver(busTrackingReceiver);
            busTrackingReceiverRegistered = false;
        }
        for (AnimatedBusMarker marker : trackedBusMarkers.values()) {
            marker.cancelAnimation();
        }
        trackedBusMarkers.clear();
        stopLocateUpdates();
        stopVoiceSearch();
        arBusStopView.destroyAr();
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean granted = false;
        for (int result : grantResults) {
            if (result == PackageManager.PERMISSION_GRANTED) {
                granted = true;
                break;
            }
        }

        if (requestCode == LOCATION_PERMISSION_REQUEST && geolocationCallback != null) {
            geolocationCallback.invoke(geolocationOrigin, granted, false);
            geolocationCallback = null;
            geolocationOrigin = null;
            return;
        }

        if (requestCode == CENTER_MAP_PERMISSION_REQUEST) {
            if (granted) {
                centerMapOnUserLocation();
            } else {
                Toast.makeText(this, "Location permission is needed to find you on the map", Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == AUDIO_PERMISSION_REQUEST) {
            if (granted) {
                startVoiceSearch();
            } else {
                Toast.makeText(this, "Microphone permission is needed for voice route search", Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == CAMERA_PERMISSION_REQUEST) {
            if (granted) {
                showArView();
            } else {
                Toast.makeText(this, "Camera permission is needed for AR bus stop finder", Toast.LENGTH_LONG).show();
            }
        }
    }

    private boolean hasLocationPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasFineLocationPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestLocationForWebsite(String origin, GeolocationPermissions.Callback callback) {
        if (hasLocationPermission()) {
            callback.invoke(origin, true, false);
            return;
        }

        geolocationOrigin = origin;
        geolocationCallback = callback;
        requestPermissions(new String[] {
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
        }, LOCATION_PERMISSION_REQUEST);
    }

    private void centerMapOnUserLocation() {
        if (!hasLocationPermission()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                requestPermissions(new String[] {
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                }, CENTER_MAP_PERMISSION_REQUEST);
            }
            return;
        }

        LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (locationManager == null) {
            Toast.makeText(this, "Location service is not available on this device", Toast.LENGTH_LONG).show();
            return;
        }

        Location location = getBestLastKnownLocation(locationManager);
        if (location != null && isRecentEnough(location)) {
            loadMapAtLocation(location);
            requestFreshLocation(locationManager, false);
            return;
        }

        requestFreshLocation(locationManager, true);
    }

    @SuppressLint("MissingPermission")
    private Location getBestLastKnownLocation(LocationManager locationManager) {
        Location bestLocation = null;
        if (locationManager == null) {
            return null;
        }
        for (String provider : locationManager.getProviders(true)) {
            Location location = locationManager.getLastKnownLocation(provider);
            if (location == null) {
                continue;
            }
            if (bestLocation == null || location.getAccuracy() < bestLocation.getAccuracy()) {
                bestLocation = location;
            }
        }
        return bestLocation;
    }

    private boolean isRecentEnough(Location location) {
        return location.getTime() > 0 && System.currentTimeMillis() - location.getTime() < 120_000L;
    }

    @SuppressLint("MissingPermission")
    private void requestFreshLocation(LocationManager locationManager, boolean showWaitingMessage) {
        stopLocateUpdates();
        if (locationManager == null) {
            Toast.makeText(this, "Location service is not available on this device", Toast.LENGTH_LONG).show();
            return;
        }

        java.util.List<String> providers = new ArrayList<>();
        if (hasFineLocationPermission() && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            providers.add(LocationManager.GPS_PROVIDER);
        }
        if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            providers.add(LocationManager.NETWORK_PROVIDER);
        }
        if (providers.isEmpty()) {
            for (String provider : locationManager.getProviders(true)) {
                if (!providers.contains(provider)) {
                    providers.add(provider);
                }
            }
        }
        if (providers.isEmpty()) {
            Toast.makeText(this, "Turn on location services to find buses near you", Toast.LENGTH_LONG).show();
            try {
                startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
            } catch (Exception ignored) {
            }
            return;
        }

        if (showWaitingMessage) {
            Toast.makeText(this, "Finding your location…", Toast.LENGTH_SHORT).show();
        }
        locateLocationListener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                stopLocateUpdates();
                loadMapAtLocation(location);
            }

            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {
            }

            @Override
            public void onProviderEnabled(String provider) {
            }

            @Override
            public void onProviderDisabled(String provider) {
            }
        };

        for (String provider : providers) {
            try {
                locationManager.requestLocationUpdates(provider, 0L, 0f, locateLocationListener);
            } catch (SecurityException exception) {
                // The user may have granted approximate location only; keep trying providers allowed by that grant.
            }
        }
        scheduleLocateTimeout(() -> {
            if (locateLocationListener == null) {
                return;
            }
            stopLocateUpdates();
            Location fallback = getBestLastKnownLocation(locationManager);
            if (fallback != null) {
                loadMapAtLocation(fallback);
            } else {
                Toast.makeText(this, "Still waiting for GPS. Move near a window or turn on High accuracy location.", Toast.LENGTH_LONG).show();
            }
        }, 10_000L);
    }

    private void scheduleLocateTimeout(Runnable runnable, long delayMs) {
        if (locateTimeoutRunnable != null) {
            uiHandler.removeCallbacks(locateTimeoutRunnable);
        }
        locateTimeoutRunnable = runnable;
        uiHandler.postDelayed(runnable, delayMs);
    }

    private void stopLocateUpdates() {
        if (locateTimeoutRunnable != null) {
            uiHandler.removeCallbacks(locateTimeoutRunnable);
            locateTimeoutRunnable = null;
        }
        if (locateLocationListener == null) {
            return;
        }
        LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (locationManager != null) {
            locationManager.removeUpdates(locateLocationListener);
        }
        locateLocationListener = null;
    }

    private void loadMapAtLocation(Location location) {
        if (location == null) {
            return;
        }
        double latitude = location.getLatitude();
        double longitude = location.getLongitude();
        String url = String.format(Locale.US, "%s#16/%f/%f", MAP_URL, latitude, longitude);
        String script = String.format(Locale.US,
                "(function(){"
                        + "var lat=%f,lng=%f,zoom=16;"
                        + "var map=window.__bodsFindMap&&window.__bodsFindMap();"
                        + "if(map){"
                        + "if(map.flyTo){map.flyTo({center:[lng,lat],zoom:zoom});location.hash='#'+zoom+'/'+lat+'/'+lng;return true;}"
                        + "if(map.setView){map.setView([lat,lng],zoom);location.hash='#'+zoom+'/'+lat+'/'+lng;return true;}"
                        + "if(map.easeTo){map.easeTo({center:[lng,lat],zoom:zoom});location.hash='#'+zoom+'/'+lat+'/'+lng;return true;}"
                        + "}"
                        + "location.href='%s';return false;})();",
                latitude, longitude, escapeJs(url));
        webView.evaluateJavascript(script, centred -> {
            if (!"true".equals(centred)) {
                webView.loadUrl(url);
            }
        });
        Toast.makeText(this, "Map centred on your location", Toast.LENGTH_SHORT).show();
    }

    private void stopVoiceSearch() {
        if (speechRecognizer != null) {
            speechRecognizer.cancel();
            speechRecognizer.destroy();
            speechRecognizer = null;
        }
    }

    private void startVoiceSearch() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { Manifest.permission.RECORD_AUDIO }, AUDIO_PERMISSION_REQUEST);
            return;
        }

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Voice recognition is not available on this device", Toast.LENGTH_LONG).show();
            return;
        }

        stopVoiceSearch();
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        speechRecognizer.setRecognitionListener(new RouteRecognitionListener());

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a bus route number, for example 350");
        speechRecognizer.startListening(intent);
        Toast.makeText(this, "Listening for bus number…", Toast.LENGTH_SHORT).show();
    }

    private boolean zoomToActiveBusRoute(String route) {
        AnimatedBusMarker marker = findActiveBusMarker(route);
        if (marker == null) {
            Toast.makeText(this, "Route " + route + " is not currently active.", Toast.LENGTH_LONG).show();
            return false;
        }
        selectRouteFilter(route);
        arBusStopView.setNavigationTarget("route " + marker.label, marker.latitude, marker.longitude);
        animateMapCameraTo(marker.latitude, marker.longitude, 16f);
        Toast.makeText(this, arModeEnabled
                ? "AR guiding you to live route " + route
                : "Zooming to live route " + route, Toast.LENGTH_LONG).show();
        return true;
    }

    private void animateMapCameraTo(double latitude, double longitude, float zoom) {
        String script = String.format(Locale.US,
                "(function(){"
                        + "var lat=%f,lng=%f,zoom=%f;"
                        + "if(window.mMap&&window.google&&window.google.maps){"
                        + "var target=new google.maps.LatLng(lat,lng);"
                        + "if(window.CameraUpdateFactory&&window.mMap.animateCamera){window.mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(target,zoom));return true;}"
                        + "if(window.mMap.panTo){window.mMap.panTo(target);if(window.mMap.setZoom)window.mMap.setZoom(zoom);return true;}"
                        + "}"
                        + "var map=window.__bodsFindMap&&window.__bodsFindMap();"
                        + "if(map){if(map.flyTo){map.flyTo({center:[lng,lat],zoom:zoom});return true;}"
                        + "if(map.setView){map.setView([lat,lng],zoom);return true;}"
                        + "if(map.easeTo){map.easeTo({center:[lng,lat],zoom:zoom});return true;}}"
                        + "location.hash='#'+zoom+'/'+lat+'/'+lng;return false;})();",
                latitude, longitude, zoom);
        webView.evaluateJavascript(script, ignored -> {
        });
    }

    private AnimatedBusMarker findActiveBusMarker(String route) {
        String normalizedRoute = normalizeRouteSearch(route);
        if (normalizedRoute.isEmpty()) {
            return null;
        }
        for (AnimatedBusMarker marker : trackedBusMarkers.values()) {
            if (marker.matchesRoute(normalizedRoute)) {
                return marker;
            }
        }
        return null;
    }

    private boolean routeNumberTokenMatches(String normalizedValue, String normalizedRoute) {
        return normalizedValue.matches(".*(^|[^0-9])0*" + normalizedRoute + "([^0-9]|$).*");
    }

    private String normalizeRouteSearch(String value) {
        return value == null ? "" : value.toLowerCase(Locale.UK).replace("route", "").trim();
    }

    private void applyVoiceRouteFilter(String spokenText) {
        VoiceRouteQuery query = parseVoiceRouteQuery(spokenText);
        if (query.route.isEmpty()) {
            Toast.makeText(this, "I couldn't hear a bus route number", Toast.LENGTH_LONG).show();
            stopVoiceSearch();
            return;
        }

        String placeLine = query.placeName.isEmpty() ? "near your current map/location" : "in " + query.placeName;
        new AlertDialog.Builder(this)
                .setTitle("Did you mean route " + query.route + "?")
                .setMessage("I heard: \"" + spokenText + "\"\nSearch live buses " + placeLine + ".")
                .setPositiveButton("Yes", (dialog, which) -> {
                    applyConfirmedRouteFilter(query);
                    stopVoiceSearch();
                })
                .setNegativeButton("Try again", (dialog, which) -> {
                    stopVoiceSearch();
                    startVoiceSearch();
                })
                .setOnCancelListener(dialog -> stopVoiceSearch())
                .show();
    }

    private void applyConfirmedRouteFilter(VoiceRouteQuery query) {
        selectRouteFilter(query.route);

        Location targetLocation = query.location != null ? query.location : getCurrentLocalSearchLocation();
        if (targetLocation != null) {
            webView.loadUrl(MAP_URL + "#14/" + targetLocation.getLatitude() + "/" + targetLocation.getLongitude());
        } else {
            webView.loadUrl(MAP_URL);
        }
        requestImmediateBusRefresh();

        String place = query.placeName.isEmpty() ? "nearby" : "in " + query.placeName;
        Toast.makeText(this, "Showing live route " + activeRouteFilter + " buses " + place, Toast.LENGTH_LONG).show();
    }

    private VoiceRouteQuery parseVoiceRouteQuery(String text) {
        String route = extractRouteNumber(text);
        String placeName = extractKnownPlaceName(text);
        return new VoiceRouteQuery(route, placeName, locationForPlace(placeName));
    }

    private Location getCurrentLocalSearchLocation() {
        if (!hasLocationPermission()) {
            return null;
        }
        return getBestLastKnownLocation((LocationManager) getSystemService(Context.LOCATION_SERVICE));
    }

    private String extractKnownPlaceName(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.toLowerCase(Locale.UK);
        if (normalized.contains("scunthorpe")) {
            return "Scunthorpe";
        }
        if (normalized.contains("grimsby")) {
            return "Grimsby";
        }
        if (normalized.contains("doncaster")) {
            return "Doncaster";
        }
        if (normalized.contains("hull")) {
            return "Hull";
        }
        if (normalized.contains("lincoln")) {
            return "Lincoln";
        }
        return "";
    }

    private Location locationForPlace(String placeName) {
        if (placeName.isEmpty()) {
            return null;
        }

        Location location = new Location("voice-place");
        if ("Scunthorpe".equals(placeName)) {
            location.setLatitude(53.5887);
            location.setLongitude(-0.6544);
        } else if ("Grimsby".equals(placeName)) {
            location.setLatitude(53.5675);
            location.setLongitude(-0.0808);
        } else if ("Doncaster".equals(placeName)) {
            location.setLatitude(53.5228);
            location.setLongitude(-1.1285);
        } else if ("Hull".equals(placeName)) {
            location.setLatitude(53.7676);
            location.setLongitude(-0.3274);
        } else if ("Lincoln".equals(placeName)) {
            location.setLatitude(53.2307);
            location.setLongitude(-0.5406);
        } else {
            return null;
        }
        return location;
    }

    private String extractRouteNumber(String text) {
        if (text == null) {
            return "";
        }

        String direct = text.replaceAll("[^0-9A-Za-z]", " ").trim();
        for (String token : direct.split("\\s+")) {
            if (token.matches("[0-9]{1,4}[A-Za-z]?")) {
                return token.toUpperCase(Locale.UK);
            }
        }

        String normalized = text.toLowerCase(Locale.UK)
                .replace('-', ' ')
                .replaceAll("[^a-z ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        String wordsAsDigits = spokenNumberToRoute(normalized);
        return wordsAsDigits.matches("[0-9]{1,4}[A-Za-z]?") ? wordsAsDigits : "";
    }

    private String spokenNumberToRoute(String text) {
        if (text.isEmpty()) {
            return "";
        }

        Map<String, String> digitWords = new LinkedHashMap<>();
        digitWords.put("zero", "0");
        digitWords.put("oh", "0");
        digitWords.put("o", "0");
        digitWords.put("one", "1");
        digitWords.put("two", "2");
        digitWords.put("to", "2");
        digitWords.put("too", "2");
        digitWords.put("three", "3");
        digitWords.put("four", "4");
        digitWords.put("for", "4");
        digitWords.put("five", "5");
        digitWords.put("six", "6");
        digitWords.put("seven", "7");
        digitWords.put("eight", "8");
        digitWords.put("ate", "8");
        digitWords.put("nine", "9");

        Map<String, String> hundredsPhrases = new LinkedHashMap<>();
        hundredsPhrases.put("one hundred", "100");
        hundredsPhrases.put("two hundred", "200");
        hundredsPhrases.put("three hundred", "300");
        hundredsPhrases.put("four hundred", "400");
        hundredsPhrases.put("five hundred", "500");
        hundredsPhrases.put("six hundred", "600");
        hundredsPhrases.put("seven hundred", "700");
        hundredsPhrases.put("eight hundred", "800");
        hundredsPhrases.put("nine hundred", "900");

        for (Map.Entry<String, String> entry : hundredsPhrases.entrySet()) {
            text = text.replace(entry.getKey(), entry.getValue());
        }

        Map<String, String> tensWords = new LinkedHashMap<>();
        tensWords.put("ten", "10");
        tensWords.put("eleven", "11");
        tensWords.put("twelve", "12");
        tensWords.put("thirteen", "13");
        tensWords.put("fourteen", "14");
        tensWords.put("fifteen", "15");
        tensWords.put("sixteen", "16");
        tensWords.put("seventeen", "17");
        tensWords.put("eighteen", "18");
        tensWords.put("nineteen", "19");
        tensWords.put("twenty", "20");
        tensWords.put("thirty", "30");
        tensWords.put("forty", "40");
        tensWords.put("fifty", "50");
        tensWords.put("sixty", "60");
        tensWords.put("seventy", "70");
        tensWords.put("eighty", "80");
        tensWords.put("ninety", "90");

        StringBuilder result = new StringBuilder();
        String[] words = text.split(" ");
        for (String word : words) {
            if (word.matches("[0-9]+")) {
                result.append(word);
            } else if (digitWords.containsKey(word)) {
                result.append(digitWords.get(word));
            } else if (tensWords.containsKey(word)) {
                result.append(tensWords.get(word));
            }
        }

        return result.toString();
    }

    private void requestImmediateBusRefresh() {
        Intent intent = new Intent(this, BusTrackingService.class);
        intent.setAction(BusTrackingService.ACTION_REFRESH_NOW);
        startService(intent);
    }

    private void filterMapMarkersForRoute(String route) {
        String escapedRoute = escapeJs(route);
        String script = "(function(){"
                + "window.__bodsRouteFilter='" + escapedRoute + "';"
                + "Object.keys(window.__bodsMarkers||{}).forEach(function(key){"
                + "var marker=window.__bodsMarkers[key];var route=(marker.dataset.route||'').trim();"
                + "var visible=(!window.__bodsRouteFilter||route.toLowerCase().indexOf(window.__bodsRouteFilter.toLowerCase())!==-1);"
                + "marker.style.display=visible?'block':'none';"
                + "});"
                + "return true;})();";
        webView.evaluateJavascript(script, ignored -> {
        });
    }

    private void toggleArMode() {
        if (arModeEnabled) {
            showStandardMap();
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { Manifest.permission.CAMERA }, CAMERA_PERMISSION_REQUEST);
            return;
        }

        showArView();
    }

    private void showArView() {
        arModeEnabled = true;
        webView.setVisibility(View.GONE);
        arBusStopView.setVisibility(View.VISIBLE);
        arToggleButton.setText("✕ Exit AR");
        refreshButton.setVisibility(View.GONE);
        zoomControls.setVisibility(View.GONE);
        routeChipsScroll.setVisibility(View.GONE);
        statusPill.setVisibility(View.GONE);
        arBusStopView.setRouteFilter(activeRouteFilter);
        arBusStopView.showStopsNear(hasLocationPermission()
                ? getBestLastKnownLocation((LocationManager) getSystemService(Context.LOCATION_SERVICE))
                : null);
        arBusStopView.startAr();
        startArLocationUpdates();
        if (!Double.isNaN(pendingNavigationLatitude) && !Double.isNaN(pendingNavigationLongitude)) {
            arBusStopView.setNavigationTarget(pendingNavigationName, pendingNavigationLatitude, pendingNavigationLongitude);
            pendingNavigationLatitude = Double.NaN;
            pendingNavigationLongitude = Double.NaN;
            pendingNavigationName = null;
        }
        bringNativeControlsToFront();
    }

    @SuppressLint("MissingPermission")
    private void startArLocationUpdates() {
        if (!hasLocationPermission() || arLocationListener != null) {
            return;
        }
        LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (locationManager == null) {
            return;
        }
        Criteria criteria = new Criteria();
        criteria.setAccuracy(Criteria.ACCURACY_FINE);
        String provider = locationManager.getBestProvider(criteria, true);
        if (provider == null) {
            return;
        }
        arLocationListener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                if (arModeEnabled) {
                    arBusStopView.showStopsNear(location);
                }
            }

            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {
            }

            @Override
            public void onProviderEnabled(String provider) {
            }

            @Override
            public void onProviderDisabled(String provider) {
            }
        };
        locationManager.requestLocationUpdates(provider, 1_000L, 1f, arLocationListener);
    }

    private void stopArLocationUpdates() {
        if (arLocationListener == null) {
            return;
        }
        LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        locationManager.removeUpdates(arLocationListener);
        arLocationListener = null;
    }

    private void showStandardMap() {
        arModeEnabled = false;
        stopArLocationUpdates();
        arBusStopView.destroyAr();
        arBusStopView.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
        arToggleButton.setText("✦ AR View");
        refreshButton.setVisibility(View.VISIBLE);
        zoomControls.setVisibility(View.VISIBLE);
        statusPill.setVisibility(View.VISIBLE);
        if (!trackedBusMarkers.isEmpty()) {
            routeChipsScroll.setVisibility(View.VISIBLE);
        }
        bringNativeControlsToFront();
    }

    private void hideAdsOnPage() {
        String script = "(function(){"
                + "var css='#bustimes-native-ad-hide,'"
                + ".adsbygoogle,ins.adsbygoogle,[id*=\"google_ads\"],[id*=\"div-gpt-ad\"],"
                + "[id^=\"google_ads\"],[id^=\"ad-\"],[id$=\"-ad\"],"
                + "[class*=\"adsbygoogle\"],[class*=\"advert\"],[class*=\" ad-\"],"
                + "iframe[src*=\"googlesyndication\"],iframe[src*=\"doubleclick\"],"
                + "iframe[src*=\"googleads\"]{display:none!important;visibility:hidden!important;height:0!important;max-height:0!important;overflow:hidden!important;}';"
                + "var style=document.getElementById('bustimes-native-ad-hide');"
                + "if(!style){style=document.createElement('style');style.id='bustimes-native-ad-hide';document.head.appendChild(style);}"
                + "style.textContent=css;"
                + "document.querySelectorAll('.adsbygoogle,ins.adsbygoogle,[id*=\"google_ads\"],[id*=\"div-gpt-ad\"],[id^=\"ad-\"],[id$=\"-ad\"],[class*=\"advert\"],iframe[src*=\"googlesyndication\"],iframe[src*=\"doubleclick\"]').forEach(function(el){el.remove();});"
                + "return true;})();";
        webView.evaluateJavascript(script, ignored -> {
        });
    }

    private void bringNativeControlsToFront() {
        locateButton.bringToFront();
        refreshButton.bringToFront();
        microphoneButton.bringToFront();
        arToggleButton.bringToFront();
        zoomControls.bringToFront();
        statusPill.bringToFront();
        liveCountPill.bringToFront();
        routeChipsScroll.bringToFront();
    }

    private void registerBusTrackingReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(BusTrackingService.ACTION_BUS_POSITION);
        filter.addAction(BusTrackingService.ACTION_TRACKING_STATUS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(busTrackingReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(busTrackingReceiver, filter);
        }
        busTrackingReceiverRegistered = true;
    }

    private void openBusDetailsFromAr(BusSnapshot snapshot) {
        BusDetailsSheet.show(this, snapshot, new BusDetailsSheet.Callbacks() {
            @Override
            public void onNavigateToBus(double navLatitude, double navLongitude) {
                arBusStopView.setNavigationTarget("route " + snapshot.lineName, navLatitude, navLongitude);
            }

            @Override
            public boolean isFollowingBus(String followId) {
                return followId != null && followId.equals(followBusId);
            }

            @Override
            public void onShowBusOnMap(double mapLatitude, double mapLongitude) {
                if (arModeEnabled) {
                    showStandardMap();
                    animateMapCameraTo(mapLatitude, mapLongitude, 16f);
                }
            }

            @Override
            public void onFollowBus(String followId, boolean follow) {
                if (follow) {
                    startFollowingBus(followId);
                } else {
                    stopFollowingBus(null);
                    Toast.makeText(MainActivity.this, "Stopped following", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private void updateTrackedBusMarker(Intent intent) {
        String id = intent.getStringExtra(BusPosition.EXTRA_ID);
        if (id == null || id.trim().isEmpty()) {
            return;
        }

        String lineName = intent.getStringExtra(BusPosition.EXTRA_LINE_NAME);
        String lineRef = intent.getStringExtra(BusPosition.EXTRA_LINE_REF);
        String destinationName = intent.getStringExtra(BusPosition.EXTRA_DESTINATION_NAME);
        String expectedArrivalTime = intent.getStringExtra(BusPosition.EXTRA_EXPECTED_ARRIVAL_TIME);
        String recordedAt = intent.getStringExtra(BusPosition.EXTRA_RECORDED_AT);
        String occupancy = intent.getStringExtra(BusPosition.EXTRA_OCCUPANCY);
        String operatorName = intent.getStringExtra(BusPosition.EXTRA_OPERATOR);
        double nextLatitude = intent.getDoubleExtra(BusPosition.EXTRA_LATITUDE, Double.NaN);
        double nextLongitude = intent.getDoubleExtra(BusPosition.EXTRA_LONGITUDE, Double.NaN);
        float nextBearing = intent.getFloatExtra(BusPosition.EXTRA_BEARING, Float.NaN);
        if (Double.isNaN(nextLatitude) || Double.isNaN(nextLongitude)) {
            return;
        }

        AnimatedBusMarker marker = trackedBusMarkers.get(id);
        if (marker == null) {
            marker = new AnimatedBusMarker(id, firstNonEmpty(lineName, lineRef, "Bus"), lineRef, destinationName,
                    expectedArrivalTime, nextLatitude, nextLongitude, Float.isNaN(nextBearing) ? 0f : nextBearing,
                    firstNonEmpty(recordedAt, "Unknown"), firstNonEmpty(occupancy, "Information Unknown"),
                    firstNonEmpty(operatorName, ""));
            trackedBusMarkers.put(id, marker);
            marker.renderAt(nextLatitude, nextLongitude, marker.bearing, true);
            updateLiveCountPill();
            rebuildRouteChips();
            return;
        }

        float bearing = Float.isNaN(nextBearing)
                ? bearingBetween(marker.latitude, marker.longitude, nextLatitude, nextLongitude)
                : nextBearing;
        marker.animateTo(nextLatitude, nextLongitude, bearing, firstNonEmpty(recordedAt, "Unknown"),
                firstNonEmpty(occupancy, "Information Unknown"), destinationName, expectedArrivalTime,
                firstNonEmpty(operatorName, ""));
        updateLiveCountPill();
        rebuildRouteChips();
    }

    private void updateLiveCountPill() {
        int count = trackedBusMarkers.size();
        liveCountPill.setText(count + (count == 1 ? " bus live" : " buses live"));
    }

    private void removeStaleMarkers() {
        if (trackedBusMarkers.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        List<String> staleIds = new ArrayList<>();
        for (AnimatedBusMarker marker : trackedBusMarkers.values()) {
            if (now - marker.lastUpdateWallClockMs > MARKER_STALE_MS) {
                staleIds.add(marker.id);
            }
        }
        if (staleIds.isEmpty()) {
            return;
        }
        StringBuilder removals = new StringBuilder("(function(){");
        for (String id : staleIds) {
            removals.append("var m=window.__bodsMarkers&&window.__bodsMarkers['")
                    .append(escapeJs(id)).append("'];if(m){m.remove();delete window.__bodsMarkers['")
                    .append(escapeJs(id)).append("'];}");
            trackedBusMarkers.remove(id);
            if (id.equals(followBusId)) {
                stopFollowingBus("The bus you were following went offline.");
            }
        }
        removals.append("return true;})();");
        webView.evaluateJavascript(removals.toString(), ignored -> {
        });
        updateLiveCountPill();
        lastChipsRebuildMs = 0L;
        rebuildRouteChips();
    }

    private void startFollowingBus(String busId) {
        followBusId = busId;
        uiHandler.removeCallbacks(followRunnable);
        uiHandler.postDelayed(followRunnable, 400L);
        Toast.makeText(this, "Following this bus — the map will keep it centred", Toast.LENGTH_SHORT).show();
    }

    private void stopFollowingBus(String reason) {
        followBusId = null;
        uiHandler.removeCallbacks(followRunnable);
        if (reason != null && !reason.isEmpty()) {
            Toast.makeText(this, reason, Toast.LENGTH_LONG).show();
        }
    }

    private void stepFollowMode() {
        if (followBusId == null) {
            return;
        }
        AnimatedBusMarker marker = trackedBusMarkers.get(followBusId);
        if (marker == null) {
            stopFollowingBus("The bus you were following went offline.");
            return;
        }
        if (arModeEnabled) {
            arBusStopView.setNavigationTarget("route " + marker.label, marker.latitude, marker.longitude);
        } else {
            animateMapCameraTo(marker.latitude, marker.longitude, 15.5f);
        }
        uiHandler.postDelayed(followRunnable, FOLLOW_INTERVAL_MS);
    }

    private String computeDistanceText(double latitude, double longitude) {
        if (!hasLocationPermission()) {
            return "";
        }
        Location userLocation = getBestLastKnownLocation((LocationManager) getSystemService(Context.LOCATION_SERVICE));
        if (userLocation == null) {
            return "";
        }
        Location target = new Location("bus");
        target.setLatitude(latitude);
        target.setLongitude(longitude);
        float metres = userLocation.distanceTo(target);
        if (metres < 1000f) {
            return String.format(Locale.UK, "%.0fm away", metres);
        }
        return String.format(Locale.UK, "%.1fkm away", metres / 1000f);
    }

    private int parseEtaMinutes(String etaText) {
        if (etaText == null) {
            return -1;
        }
        String digits = etaText.replaceAll("[^0-9]", " ").trim();
        if (digits.isEmpty()) {
            return -1;
        }
        try {
            return Integer.parseInt(digits.split("\\s+")[0]);
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    private float bearingBetween(double startLatitude, double startLongitude, double endLatitude, double endLongitude) {
        double startLatRadians = Math.toRadians(startLatitude);
        double endLatRadians = Math.toRadians(endLatitude);
        double deltaLongitudeRadians = Math.toRadians(endLongitude - startLongitude);
        double y = Math.sin(deltaLongitudeRadians) * Math.cos(endLatRadians);
        double x = Math.cos(startLatRadians) * Math.sin(endLatRadians)
                - Math.sin(startLatRadians) * Math.cos(endLatRadians) * Math.cos(deltaLongitudeRadians);
        return (float) ((Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0);
    }

    private double parseDouble(String value, double fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private String etaText(String expectedArrivalTime) {
        Date expected = parseBodsTime(expectedArrivalTime);
        if (expected == null) {
            return "ETA unknown";
        }
        long minutes = Math.max(0L, Math.round((expected.getTime() - System.currentTimeMillis()) / 60000.0));
        if (minutes == 0) {
            return "Arriving now";
        }
        return String.format(Locale.UK, "Arriving in %d mins", minutes);
    }

    private Date parseBodsTime(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        String[] patterns = {
                "yyyy-MM-dd'T'HH:mm:ssXXX",
                "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
                "yyyy-MM-dd'T'HH:mm:ss'Z'",
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
        };
        for (String pattern : patterns) {
            try {
                SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.UK);
                format.setTimeZone(TimeZone.getTimeZone("UTC"));
                return format.parse(value.trim());
            } catch (ParseException ignored) {
            }
        }
        return null;
    }

    private String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value;
            }
        }
        return "";
    }

    private String escapeJs(String value) {
        return value.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "\\n")
                .replace("\r", "");
    }

    private void renderNativeBusMarker(String id, String label, String destination, String etaText, double latitude,
            double longitude, float bearing, String lastSeen, String occupancy, float speedKph, String operator) {
        String script = String.format(Locale.US,
                "(function(){"
                        + "window.__bodsMarkers=window.__bodsMarkers||{};"
                        + "window.__bodsFindMap=window.__bodsFindMap||function(){"
                        + "if(window.map&&window.map.project)return window.map;"
                        + "for(var k in window){try{var v=window[k];if(v&&v.project&&v.getContainer)return v;}catch(e){}}"
                        + "return null;};"
                        + "var map=window.__bodsFindMap();if(!map)return false;"
                        + "var container=map.getContainer();"
                        + "if(getComputedStyle(container).position==='static')container.style.position='relative';"
                        + "var marker=window.__bodsMarkers['%s'];"
                        + "var occupancy='%s';"
                        + "var markerColor=(occupancy==='Full/Crowded')?'#ff5a5f':(occupancy==='Standing Room Only'?'#ffb300':(occupancy==='Easy Seating'?'#2ad37e':'#2e7cf6'));"
                        + "if(!marker){"
                        + "marker=document.createElement('div');"
                        + "marker.className='native-bods-bus-marker';"
                        + "marker.style.cssText='position:absolute;z-index:50;width:0;height:0;pointer-events:auto;cursor:pointer;';"
                        + "var badge=document.createElement('div');"
                        + "badge.className='bods-badge';"
                        + "badge.style.cssText='position:absolute;left:-17px;top:-17px;width:34px;height:34px;border-radius:50%;"
                        + "border:2px solid rgba(255,255,255,.95);box-shadow:0 4px 10px rgba(0,0,0,.5);"
                        + "display:flex;align-items:center;justify-content:center;font:bold 12px/1 sans-serif;color:#fff;"
                        + "box-sizing:border-box;';"
                        + "var arrow=document.createElement('div');"
                        + "arrow.className='bods-arrow';"
                        + "arrow.style.cssText='position:absolute;left:-6px;top:-5px;width:0;height:0;"
                        + "border-left:6px solid transparent;border-right:6px solid transparent;border-bottom:10px solid '+markerColor+';"
                        + "transform-origin:6px 5px;';"
                        + "marker.appendChild(arrow);marker.appendChild(badge);"
                        + "container.appendChild(marker);window.__bodsMarkers['%s']=marker;"
                        + "marker.onclick=function(event){if(event)event.stopPropagation();"
                        + "if(window.BusMarkerBridge){window.BusMarkerBridge.showBusDetails("
                        + "marker.dataset.id,marker.dataset.route,marker.dataset.destination,marker.dataset.eta,"
                        + "marker.dataset.lastSeen,marker.dataset.occupancy,marker.dataset.lat,marker.dataset.lng,"
                        + "marker.dataset.speed,marker.dataset.bearing,marker.dataset.operator);}};"
                        + "}"
                        + "var badge=marker.firstChild.nextSibling;"
                        + "var arrow=marker.firstChild;"
                        + "badge.textContent='%s';badge.style.background=markerColor;"
                        + "badge.style.boxShadow='0 4px 10px rgba(0,0,0,.5),0 0 14px '+markerColor;"
                        + "arrow.style.borderBottomColor=markerColor;"
                        + "arrow.style.transform='rotate('+parseFloat('%f')+'deg) translateY(-26px)';"
                        + "marker.dataset.id='%s';marker.dataset.route='%s';marker.dataset.occupancy=occupancy;"
                        + "marker.dataset.lng=%f;marker.dataset.lat=%f;marker.dataset.bearing=%f;"
                        + "marker.dataset.destination='%s';marker.dataset.eta='%s';marker.dataset.lastSeen='%s';"
                        + "marker.dataset.speed='%f';marker.dataset.operator='%s';"
                        + "marker.title='Route %s to %s\\n%s\\nSpeed: %s\\nOccupancy: '+occupancy+'\\nLast seen: %s\\nTap for full details';"
                        + "if(window.__bodsRouteFilter){"
                        + "var visible=marker.dataset.route.toLowerCase().indexOf(window.__bodsRouteFilter.toLowerCase())!==-1;"
                        + "marker.style.display=visible?'block':'none';}"
                        + "window.__bodsPlaceMarker=function(m){var p=map.project([parseFloat(m.dataset.lng),parseFloat(m.dataset.lat)]);"
                        + "m.style.left=p.x+'px';m.style.top=p.y+'px';};"
                        + "window.__bodsPlaceMarker(marker);"
                        + "if(!window.__bodsMarkersListening){window.__bodsMarkersListening=true;"
                        + "var update=function(){Object.keys(window.__bodsMarkers).forEach(function(key){window.__bodsPlaceMarker(window.__bodsMarkers[key]);});};"
                        + "map.on&&map.on('move',update);map.on&&map.on('zoom',update);map.on&&map.on('resize',update);}"
                        + "return true;})();",
                escapeJs(id), escapeJs(occupancy), escapeJs(id),
                escapeJs(label), bearing,
                escapeJs(id), escapeJs(label), longitude, latitude, bearing,
                escapeJs(destination), escapeJs(etaText), escapeJs(lastSeen), speedKph, escapeJs(operator),
                escapeJs(label), escapeJs(destination), escapeJs(etaText), escapeJs(speedText(speedKph)),
                escapeJs(lastSeen));
        webView.evaluateJavascript(script, ignored -> {
        });
    }

    private String speedText(float speedKph) {
        if (speedKph <= 0.5f) {
            return "stopped";
        }
        return String.format(Locale.UK, "%.0f mph", speedKph * 0.621371f);
    }

    private void openOutsideApp(Uri uri) {
        Intent intent = new Intent(Intent.ACTION_VIEW, uri);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException exception) {
            Toast.makeText(this, R.string.app_name, Toast.LENGTH_SHORT).show();
        }
    }

    private boolean shouldOpenInsideApp(Uri uri) {
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null) {
            return false;
        }

        boolean isWeb = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
        boolean isBusTimes = "bustimes.org".equalsIgnoreCase(host) || host.endsWith(".bustimes.org");
        return isWeb && isBusTimes;
    }

    private boolean shouldRouteOutsideApp(Uri uri) {
        String scheme = uri.getScheme();
        if (scheme == null) {
            return false;
        }

        return "http".equalsIgnoreCase(scheme)
                || "https".equalsIgnoreCase(scheme)
                || "mailto".equalsIgnoreCase(scheme)
                || "tel".equalsIgnoreCase(scheme)
                || "geo".equalsIgnoreCase(scheme);
    }

    private boolean handleNavigation(Uri uri) {
        if (shouldOpenInsideApp(uri) || !shouldRouteOutsideApp(uri)) {
            return false;
        }

        openOutsideApp(uri);
        return true;
    }

    private static final class VoiceRouteQuery {
        final String route;
        final String placeName;
        final Location location;

        VoiceRouteQuery(String route, String placeName, Location location) {
            this.route = route;
            this.placeName = placeName;
            this.location = location;
        }
    }

    private class RouteRecognitionListener implements RecognitionListener {
        @Override public void onReadyForSpeech(Bundle params) { }
        @Override public void onBeginningOfSpeech() { }
        @Override public void onRmsChanged(float rmsdB) { }
        @Override public void onBufferReceived(byte[] buffer) { }
        @Override public void onEndOfSpeech() { }
        @Override public void onPartialResults(Bundle partialResults) { }
        @Override public void onEvent(int eventType, Bundle params) { }

        @Override
        public void onError(int error) {
            Toast.makeText(MainActivity.this, "Voice search did not hear a route number", Toast.LENGTH_SHORT).show();
            stopVoiceSearch();
        }

        @Override
        public void onResults(Bundle results) {
            ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (matches == null || matches.isEmpty()) {
                Toast.makeText(MainActivity.this, "No bus number heard", Toast.LENGTH_SHORT).show();
                stopVoiceSearch();
                return;
            }
            String spokenText = matches.get(0);
            String route = extractRouteNumber(spokenText);
            if (!route.isEmpty()) {
                zoomToActiveBusRoute(route);
                stopVoiceSearch();
                return;
            }
            applyVoiceRouteFilter(spokenText);
        }
    }

    private class BusMarkerBridge {
        @JavascriptInterface
        public void showBusDetails(String busId, String route, String destination, String etaText, String lastSeen,
                String occupancy, String latitude, String longitude, String speed, String bearing, String operator) {
            runOnUiThread(() -> {
                double targetLatitude = parseDouble(latitude, Double.NaN);
                double targetLongitude = parseDouble(longitude, Double.NaN);
                if (Double.isNaN(targetLatitude) || Double.isNaN(targetLongitude)) {
                    return;
                }
                BusSnapshot snapshot = new BusSnapshot(
                        firstNonEmpty(busId, "bus"),
                        firstNonEmpty(route, "Bus"),
                        "",
                        firstNonEmpty(destination, "destination unknown"),
                        firstNonEmpty(occupancy, "Information Unknown"),
                        firstNonEmpty(busId, "—"),
                        firstNonEmpty(lastSeen, ""),
                        firstNonEmpty(operator, ""),
                        computeDistanceText(targetLatitude, targetLongitude),
                        targetLatitude,
                        targetLongitude,
                        (float) parseDouble(bearing, Float.NaN),
                        (float) parseDouble(speed, 0f),
                        parseEtaMinutes(etaText),
                        "its next stop");
                BusDetailsSheet.show(MainActivity.this, snapshot, new BusDetailsSheet.Callbacks() {
                    @Override
                    public void onNavigateToBus(double navLatitude, double navLongitude) {
                        pendingNavigationName = "route " + snapshot.lineName;
                        pendingNavigationLatitude = navLatitude;
                        pendingNavigationLongitude = navLongitude;
                        if (!arModeEnabled) {
                            toggleArMode();
                        } else {
                            showArView();
                        }
                    }

                    @Override
                    public void onFollowBus(String followId, boolean follow) {
                        if (follow) {
                            startFollowingBus(followId);
                        } else {
                            stopFollowingBus(null);
                            Toast.makeText(MainActivity.this, "Stopped following", Toast.LENGTH_SHORT).show();
                        }
                    }

                    @Override
                    public boolean isFollowingBus(String followId) {
                        return followId != null && followId.equals(followBusId);
                    }

                    @Override
                    public void onShowBusOnMap(double mapLatitude, double mapLongitude) {
                        if (arModeEnabled) {
                            showStandardMap();
                        }
                        animateMapCameraTo(mapLatitude, mapLongitude, 16f);
                    }
                });
            });
        }
    }

    private class AnimatedBusMarker {
        private final String id;
        private final String label;
        private final String lineRef;
        private String destinationName;
        private String expectedArrivalTime;
        private String operatorName;
        private double latitude;
        private double longitude;
        private float bearing;
        private String lastSeen;
        private String occupancy;
        private float currentSpeedKph;
        private long lastUpdateWallClockMs;
        private long lastJsRenderMs;
        private long lastBillboardRenderMs;
        private ValueAnimator animator;

        AnimatedBusMarker(String id, String label, String lineRef, String destinationName, String expectedArrivalTime,
                double latitude, double longitude, float bearing, String lastSeen, String occupancy, String operatorName) {
            this.id = id;
            this.label = label;
            this.lineRef = lineRef;
            this.destinationName = destinationName;
            this.expectedArrivalTime = expectedArrivalTime;
            this.latitude = latitude;
            this.longitude = longitude;
            this.bearing = bearing;
            this.lastSeen = lastSeen;
            this.occupancy = occupancy;
            this.operatorName = operatorName;
            this.lastUpdateWallClockMs = System.currentTimeMillis();
        }

        boolean matchesRoute(String normalizedRoute) {
            String normalizedLabel = normalizeRouteSearch(label);
            String normalizedLineRef = normalizeRouteSearch(lineRef);
            return routeSearchValueMatches(normalizedLabel, normalizedRoute)
                    || routeSearchValueMatches(normalizedLineRef, normalizedRoute)
                    || routeSearchValueMatches(normalizeRouteSearch(id), normalizedRoute);
        }

        private boolean routeSearchValueMatches(String normalizedValue, String normalizedRoute) {
            return !normalizedValue.isEmpty()
                    && (normalizedValue.equals(normalizedRoute)
                    || normalizedValue.contains(normalizedRoute)
                    || routeNumberTokenMatches(normalizedValue, normalizedRoute));
        }

        void animateTo(double nextLatitude, double nextLongitude, float nextBearing, String nextLastSeen,
                String nextOccupancy, String nextDestinationName, String nextExpectedArrivalTime, String nextOperator) {
            cancelAnimation();
            long now = System.currentTimeMillis();
            float[] speedResult = estimateSpeedKph(latitude, longitude, nextLatitude, nextLongitude, now);
            currentSpeedKph = speedResult[0];
            lastUpdateWallClockMs = now;

            double startLatitude = latitude;
            double startLongitude = longitude;
            float startBearing = bearing;
            lastSeen = nextLastSeen;
            occupancy = nextOccupancy;
            destinationName = nextDestinationName;
            expectedArrivalTime = nextExpectedArrivalTime;
            operatorName = nextOperator;
            animator = ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(BUS_MARKER_ANIMATION_MS);
            animator.setInterpolator(new LinearInterpolator());
            animator.addUpdateListener(animation -> {
                float fraction = (float) animation.getAnimatedValue();
                latitude = startLatitude + ((nextLatitude - startLatitude) * fraction);
                longitude = startLongitude + ((nextLongitude - startLongitude) * fraction);
                bearing = startBearing + shortestBearingDelta(startBearing, nextBearing) * fraction;
                boolean finalFrame = fraction >= 0.999f;
                renderAt(latitude, longitude, bearing, finalFrame);
            });
            animator.start();
        }

        /** Returns {speedKph, unused} — speed estimated from the distance between two polls. */
        private float[] estimateSpeedKph(double fromLatitude, double fromLongitude,
                double toLatitude, double toLongitude, long now) {
            if (lastUpdateWallClockMs <= 0L) {
                return new float[] { 0f };
            }
            long seconds = (now - lastUpdateWallClockMs) / 1000L;
            if (seconds < 1L || seconds > 180L) {
                return new float[] { currentSpeedKph };
            }
            float[] results = new float[1];
            float[] distanceHolder = new float[1];
            Location.distanceBetween(fromLatitude, fromLongitude, toLatitude, toLongitude, distanceHolder);
            float kph = distanceHolder[0] / seconds * 3.6f;
            if (kph > 130f) {
                kph = currentSpeedKph; // impossible jump — GPS glitch, keep previous speed
            }
            results[0] = Math.max(0f, kph);
            return results;
        }

        void renderAt(double renderLatitude, double renderLongitude, float renderBearing, boolean force) {
            long now = System.currentTimeMillis();
            String eta = etaText(expectedArrivalTime);
            String destination = firstNonEmpty(destinationName, "destination unknown");
            if (force || now - lastJsRenderMs >= MARKER_JS_THROTTLE_MS) {
                lastJsRenderMs = now;
                renderNativeBusMarker(id, label, destination, eta, renderLatitude, renderLongitude,
                        renderBearing, lastSeen, occupancy, currentSpeedKph, operatorName);
            }
            if (force || now - lastBillboardRenderMs >= BILLBOARD_THROTTLE_MS) {
                lastBillboardRenderMs = now;
                arBusStopView.updateBusBillboard(id, label, destination, eta, occupancy,
                        renderLatitude, renderLongitude, renderBearing, currentSpeedKph);
            }
        }

        void cancelAnimation() {
            if (animator != null) {
                animator.cancel();
            }
        }

        private float shortestBearingDelta(float from, float to) {
            return ((to - from + 540f) % 360f) - 180f;
        }
    }

    private class BusTrackingReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BusTrackingService.ACTION_BUS_POSITION.equals(action)) {
                updateTrackedBusMarker(intent);
            } else if (BusTrackingService.ACTION_TRACKING_STATUS.equals(action)) {
                String message = intent.getStringExtra(BusTrackingService.EXTRA_STATUS_MESSAGE);
                if (message == null) {
                    return;
                }
                statusPill.setVisibility(View.VISIBLE);
                statusPill.setText(message);
                if (message.startsWith("Add a BODS_API_KEY")) {
                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                }
            }
        }
    }

    private class BusTimesWebViewClient extends WebViewClient {
        @Override
        public void onPageFinished(WebView view, String url) {
            hideAdsOnPage();
            applyNightMode(nightModeEnabled, true);
            if (!activeRouteFilter.isEmpty()) {
                filterMapMarkersForRoute(activeRouteFilter);
            }
            // Re-render every tracked marker after a reload so the overlay survives page refreshes.
            for (AnimatedBusMarker marker : trackedBusMarkers.values()) {
                marker.renderAt(marker.latitude, marker.longitude, marker.bearing, true);
            }
            bringNativeControlsToFront();
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return handleNavigation(request.getUrl());
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return handleNavigation(Uri.parse(url));
        }
    }

    private class BusTimesChromeClient extends WebChromeClient {
        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            progressBar.setProgress(newProgress);
            progressBar.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            if (newProgress >= 100) {
                hideAdsOnPage();
                applyNightMode(nightModeEnabled, true);
                if (!activeRouteFilter.isEmpty()) {
                    filterMapMarkersForRoute(activeRouteFilter);
                }
                bringNativeControlsToFront();
            }
        }

        @Override
        public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
            requestLocationForWebsite(origin, callback);
        }
    }

    private class BusTimesDownloadListener implements DownloadListener {
        @Override
        public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimeType,
                long contentLength) {
            Uri uri = Uri.parse(url);
            DownloadManager.Request request = new DownloadManager.Request(uri);
            request.setMimeType(mimeType);
            request.addRequestHeader("User-Agent", userAgent);
            request.addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url));
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    URLUtil.guessFileName(url, contentDisposition, mimeType));

            DownloadManager downloadManager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            downloadManager.enqueue(request);
            Toast.makeText(MainActivity.this, "Downloading file", Toast.LENGTH_SHORT).show();
        }
    }
}
