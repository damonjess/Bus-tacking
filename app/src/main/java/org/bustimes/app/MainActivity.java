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
import android.content.pm.PackageManager;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.graphics.Color;
import android.graphics.Typeface;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
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
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.view.animation.LinearInterpolator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {
    private static final int LOCATION_PERMISSION_REQUEST = 1001;
    private static final int CENTER_MAP_PERMISSION_REQUEST = 1002;
    private static final int AUDIO_PERMISSION_REQUEST = 1003;
    private static final int CAMERA_PERMISSION_REQUEST = 1004;
    private static final String SAVED_URL = "saved_url";
    private static final String MAP_URL = "https://bustimes.org/map";
    private static final long BUS_MARKER_ANIMATION_MS = 5_000L;

    private WebView webView;
    private ProgressBar progressBar;
    private Button locateButton;
    private Button refreshButton;
    private Button microphoneButton;
    private Button arToggleButton;
    private LinearLayout zoomControls;
    private TextView helperChip;
    private ArBusStopView arBusStopView;
    private final Map<String, AnimatedBusMarker> trackedBusMarkers = new HashMap<>();
    private final BroadcastReceiver busTrackingReceiver = new BusTrackingReceiver();
    private GeolocationPermissions.Callback geolocationCallback;
    private String geolocationOrigin;
    private boolean busTrackingReceiverRegistered;
    private SpeechRecognizer speechRecognizer;
    private String activeRouteFilter = "";
    private boolean arModeEnabled;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        webView = new WebView(this);
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        locateButton = createLocateButton();
        refreshButton = createRefreshButton();
        microphoneButton = createMicrophoneButton();
        arToggleButton = createArToggleButton();
        zoomControls = createZoomControls();
        helperChip = createHelperChip();
        arBusStopView = new ArBusStopView(this);
        arBusStopView.setVisibility(View.GONE);

        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(arBusStopView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP);
        root.addView(progressBar, progressParams);

        FrameLayout.LayoutParams locateParams = new FrameLayout.LayoutParams(dp(56), dp(56),
                Gravity.BOTTOM | Gravity.END);
        locateParams.setMargins(0, 0, dp(20), dp(28));
        root.addView(locateButton, locateParams);

        FrameLayout.LayoutParams refreshParams = new FrameLayout.LayoutParams(dp(56), dp(56),
                Gravity.BOTTOM | Gravity.START);
        refreshParams.setMargins(dp(20), 0, 0, dp(28));
        root.addView(refreshButton, refreshParams);

        FrameLayout.LayoutParams microphoneParams = new FrameLayout.LayoutParams(dp(56), dp(56),
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        microphoneParams.setMargins(0, 0, 0, dp(28));
        root.addView(microphoneButton, microphoneParams);

        FrameLayout.LayoutParams arToggleParams = new FrameLayout.LayoutParams(dp(112), dp(48),
                Gravity.TOP | Gravity.START);
        arToggleParams.setMargins(dp(14), dp(72), 0, 0);
        root.addView(arToggleButton, arToggleParams);

        FrameLayout.LayoutParams zoomParams = new FrameLayout.LayoutParams(dp(52),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        zoomParams.setMargins(0, dp(72), dp(14), 0);
        root.addView(zoomControls, zoomParams);

        FrameLayout.LayoutParams helperParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        helperParams.setMargins(dp(12), dp(14), dp(12), 0);
        root.addView(helperChip, helperParams);

        setContentView(root);
        configureWebView();

        String url = MAP_URL;
        if (savedInstanceState != null) {
            url = savedInstanceState.getString(SAVED_URL, MAP_URL);
        }
        webView.loadUrl(url);
        registerBusTrackingReceiver();
    }

    @Override
    protected void onStart() {
        super.onStart();
        setMapTrackingActive(true);
    }

    @Override
    protected void onStop() {
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

        webView.setBackgroundColor(Color.WHITE);
        webView.addJavascriptInterface(new BusMarkerBridge(), "BusMarkerBridge");
        webView.setWebViewClient(new BusTimesWebViewClient());
        webView.setWebChromeClient(new BusTimesChromeClient());
        webView.setDownloadListener(new BusTimesDownloadListener());
    }

    private Button createLocateButton() {
        Button button = new Button(this);
        button.setText("⌂");
        button.setTextSize(26);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setTextColor(Color.WHITE);
        button.setBackgroundColor(Color.rgb(17, 76, 141));
        button.setContentDescription("Home: find my location on the live bus map");
        button.setOnClickListener(v -> centerMapOnUserLocation());
        return button;
    }

    private Button createRefreshButton() {
        Button button = new Button(this);
        button.setText("↻");
        button.setTextSize(26);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setTextColor(Color.WHITE);
        button.setBackgroundColor(Color.rgb(17, 76, 141));
        button.setContentDescription("Refresh the live bus map");
        button.setOnClickListener(v -> refreshLiveMap());
        return button;
    }

    private Button createMicrophoneButton() {
        Button button = new Button(this);
        button.setText("🎙");
        button.setTextSize(22);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setTextColor(Color.WHITE);
        button.setBackgroundColor(Color.rgb(17, 76, 141));
        button.setContentDescription("Voice search for a bus route");
        button.setOnClickListener(v -> startVoiceSearch());
        return button;
    }

    private Button createArToggleButton() {
        Button button = new Button(this);
        button.setText("AR View");
        button.setTextSize(13);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setTextColor(Color.WHITE);
        button.setBackgroundColor(Color.rgb(17, 76, 141));
        button.setContentDescription("Switch between standard map and AR bus stop finder");
        button.setOnClickListener(v -> toggleArMode());
        return button;
    }

    private LinearLayout createZoomControls() {
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setGravity(Gravity.CENTER);
        controls.setBackgroundColor(Color.argb(235, 255, 255, 255));
        controls.setContentDescription("Map zoom controls");

        Button zoomInButton = createZoomButton("+", "Zoom in on the live bus map");
        zoomInButton.setOnClickListener(v -> zoomWebMap(true));
        controls.addView(zoomInButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48)));

        Button zoomOutButton = createZoomButton("−", "Zoom out on the live bus map");
        zoomOutButton.setOnClickListener(v -> zoomWebMap(false));
        controls.addView(zoomOutButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48)));

        return controls;
    }

    private Button createZoomButton(String label, String description) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(24);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setTextColor(Color.rgb(17, 76, 141));
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setContentDescription(description);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinHeight(0);
        button.setPadding(0, 0, 0, 0);
        return button;
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

    private TextView createHelperChip() {
        TextView chip = new TextView(this);
        chip.setText("Tap mic to filter a route, AR for bus stop finder, buses for last seen");
        chip.setTextColor(Color.WHITE);
        chip.setTextSize(13);
        chip.setTypeface(Typeface.DEFAULT_BOLD);
        chip.setGravity(Gravity.CENTER);
        chip.setBackgroundColor(Color.argb(220, 17, 76, 141));
        chip.setPadding(dp(12), dp(8), dp(12), dp(8));
        chip.setOnClickListener(v -> chip.setVisibility(View.GONE));
        return chip;
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
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (busTrackingReceiverRegistered) {
            unregisterReceiver(busTrackingReceiver);
            busTrackingReceiverRegistered = false;
        }
        for (AnimatedBusMarker marker : trackedBusMarkers.values()) {
            marker.cancelAnimation();
        }
        trackedBusMarkers.clear();
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
        Location location = getBestLastKnownLocation(locationManager);
        if (location != null) {
            loadMapAtLocation(location);
            return;
        }

        requestFreshLocation(locationManager);
    }

    @SuppressLint("MissingPermission")
    private Location getBestLastKnownLocation(LocationManager locationManager) {
        Location bestLocation = null;
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

    @SuppressLint("MissingPermission")
    private void requestFreshLocation(LocationManager locationManager) {
        Criteria criteria = new Criteria();
        criteria.setAccuracy(Criteria.ACCURACY_FINE);
        String provider = locationManager.getBestProvider(criteria, true);
        if (provider == null) {
            Toast.makeText(this, "Turn on location services to find buses near you", Toast.LENGTH_LONG).show();
            return;
        }

        Toast.makeText(this, "Finding your location…", Toast.LENGTH_SHORT).show();
        locationManager.requestSingleUpdate(provider, new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
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
                Toast.makeText(MainActivity.this, "Turn on location services to find buses near you", Toast.LENGTH_LONG).show();
            }
        }, null);
    }

    private void loadMapAtLocation(Location location) {
        String url = MAP_URL + "#16/" + location.getLatitude() + "/" + location.getLongitude();
        webView.loadUrl(url);
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
        activeRouteFilter = query.route;
        filterMapMarkersForRoute(activeRouteFilter);
        if (arModeEnabled) {
            showStandardMap();
        }

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
        for (int i = 0; i < words.length; i++) {
            String word = words[i];
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
                + "var marker=window.__bodsMarkers[key];var route=(marker.dataset.route||marker.textContent||'').trim();"
                + "marker.style.display=(!window.__bodsRouteFilter||route.toLowerCase()===window.__bodsRouteFilter.toLowerCase())?'flex':'none';"
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
        arToggleButton.setText("Map View");
        refreshButton.setVisibility(View.GONE);
        zoomControls.setVisibility(View.GONE);
        arBusStopView.setRouteFilter(activeRouteFilter);
        arBusStopView.showStopsNear(hasLocationPermission()
                ? getBestLastKnownLocation((LocationManager) getSystemService(Context.LOCATION_SERVICE))
                : null);
        arBusStopView.startAr();
        bringNativeControlsToFront();
    }

    private void showStandardMap() {
        arModeEnabled = false;
        arBusStopView.destroyAr();
        arBusStopView.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
        arToggleButton.setText("AR View");
        refreshButton.setVisibility(View.VISIBLE);
        zoomControls.setVisibility(View.VISIBLE);
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
        helperChip.bringToFront();
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

    private void updateTrackedBusMarker(Intent intent) {
        String id = intent.getStringExtra(BusPosition.EXTRA_ID);
        if (id == null || id.trim().isEmpty()) {
            return;
        }

        String lineName = intent.getStringExtra(BusPosition.EXTRA_LINE_NAME);
        String recordedAt = intent.getStringExtra(BusPosition.EXTRA_RECORDED_AT);
        double nextLatitude = intent.getDoubleExtra(BusPosition.EXTRA_LATITUDE, Double.NaN);
        double nextLongitude = intent.getDoubleExtra(BusPosition.EXTRA_LONGITUDE, Double.NaN);
        float nextBearing = intent.getFloatExtra(BusPosition.EXTRA_BEARING, Float.NaN);
        if (Double.isNaN(nextLatitude) || Double.isNaN(nextLongitude)) {
            return;
        }

        AnimatedBusMarker marker = trackedBusMarkers.get(id);
        if (marker == null) {
            marker = new AnimatedBusMarker(id, firstNonEmpty(lineName, "Bus"), nextLatitude, nextLongitude,
                    Float.isNaN(nextBearing) ? 0f : nextBearing, firstNonEmpty(recordedAt, "Unknown"));
            trackedBusMarkers.put(id, marker);
            marker.renderAt(nextLatitude, nextLongitude, marker.bearing);
            return;
        }

        float bearing = Float.isNaN(nextBearing)
                ? bearingBetween(marker.latitude, marker.longitude, nextLatitude, nextLongitude)
                : nextBearing;
        marker.animateTo(nextLatitude, nextLongitude, bearing, firstNonEmpty(recordedAt, "Unknown"));
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

    private void renderNativeBusMarker(String id, String label, double latitude, double longitude, float bearing,
            String lastSeen) {
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
                        + "if(!marker){marker=document.createElement('div');marker.className='native-bods-bus-marker';"
                        + "marker.style.cssText='position:absolute;z-index:50;width:34px;height:34px;margin-left:-17px;margin-top:-17px;border-radius:17px;background:#114c8d;color:white;border:2px solid white;box-shadow:0 2px 6px rgba(0,0,0,.45);display:flex;align-items:center;justify-content:center;font:bold 12px sans-serif;pointer-events:auto;cursor:pointer;transform-origin:center center;';"
                        + "container.appendChild(marker);window.__bodsMarkers['%s']=marker;}"
                        + "marker.textContent='%s';marker.dataset.route='%s';"
                        + "marker.dataset.lng=%f;marker.dataset.lat=%f;marker.dataset.bearing=%f;"
                        + "marker.dataset.lastSeen='%s';marker.title='Route %s\\nLast seen: %s';"
                        + "marker.onclick=function(event){if(event)event.stopPropagation();if(window.BusMarkerBridge){window.BusMarkerBridge.showBusDetails('%s',marker.dataset.lastSeen);}};"
                        + "if(window.__bodsRouteFilter){marker.style.display=(marker.dataset.route.toLowerCase()===window.__bodsRouteFilter.toLowerCase())?'flex':'none';}"
                        + "window.__bodsPlaceMarker=function(m){var p=map.project([parseFloat(m.dataset.lng),parseFloat(m.dataset.lat)]);m.style.left=p.x+'px';m.style.top=p.y+'px';m.style.transform='rotate('+parseFloat(m.dataset.bearing)+'deg)';};"
                        + "window.__bodsPlaceMarker(marker);"
                        + "if(!window.__bodsMarkersListening){window.__bodsMarkersListening=true;var update=function(){Object.keys(window.__bodsMarkers).forEach(function(key){window.__bodsPlaceMarker(window.__bodsMarkers[key]);});};map.on&&map.on('move',update);map.on&&map.on('zoom',update);map.on&&map.on('resize',update);}"
                        + "return true;})();",
                escapeJs(id), escapeJs(id), escapeJs(label), escapeJs(label), longitude, latitude, bearing,
                escapeJs(lastSeen), escapeJs(label), escapeJs(lastSeen), escapeJs(label));
        webView.evaluateJavascript(script, ignored -> {
        });
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
            applyVoiceRouteFilter(matches.get(0));
        }
    }

    private class BusMarkerBridge {
        @JavascriptInterface
        public void showBusDetails(String route, String lastSeen) {
            runOnUiThread(() -> new AlertDialog.Builder(MainActivity.this)
                    .setTitle("Route " + firstNonEmpty(route, "Bus"))
                    .setMessage("Last seen: " + firstNonEmpty(lastSeen, "Unknown"))
                    .setPositiveButton(android.R.string.ok, null)
                    .show());
        }
    }

    private class AnimatedBusMarker {
        private final String id;
        private final String label;
        private double latitude;
        private double longitude;
        private float bearing;
        private String lastSeen;
        private ValueAnimator animator;

        AnimatedBusMarker(String id, String label, double latitude, double longitude, float bearing, String lastSeen) {
            this.id = id;
            this.label = label;
            this.latitude = latitude;
            this.longitude = longitude;
            this.bearing = bearing;
            this.lastSeen = lastSeen;
        }

        void animateTo(double nextLatitude, double nextLongitude, float nextBearing, String nextLastSeen) {
            cancelAnimation();
            double startLatitude = latitude;
            double startLongitude = longitude;
            float startBearing = bearing;
            lastSeen = nextLastSeen;
            animator = ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(BUS_MARKER_ANIMATION_MS);
            animator.setInterpolator(new LinearInterpolator());
            animator.addUpdateListener(animation -> {
                float fraction = (float) animation.getAnimatedValue();
                latitude = startLatitude + ((nextLatitude - startLatitude) * fraction);
                longitude = startLongitude + ((nextLongitude - startLongitude) * fraction);
                bearing = startBearing + shortestBearingDelta(startBearing, nextBearing) * fraction;
                renderAt(latitude, longitude, bearing);
            });
            animator.start();
        }

        void renderAt(double latitude, double longitude, float bearing) {
            renderNativeBusMarker(id, label, latitude, longitude, bearing, lastSeen);
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
                if (message != null && message.startsWith("Add a BODS_API_KEY")) {
                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                }
            }
        }
    }

    private class BusTimesWebViewClient extends WebViewClient {
        @Override
        public void onPageFinished(WebView view, String url) {
            hideAdsOnPage();
            if (!activeRouteFilter.isEmpty()) {
                filterMapMarkersForRoute(activeRouteFilter);
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
