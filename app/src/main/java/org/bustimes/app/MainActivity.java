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
import android.graphics.drawable.GradientDrawable;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import android.view.animation.LinearInterpolator;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * Bus Times Live — app-chrome shell around bustimes.org.
 * Dark top bar + bottom navigation, clean map controls, light "Selected Bus"
 * sheet, ad/site-chrome blocking, hijacked site popups and AR mode.
 */
public class MainActivity extends Activity {
    private static final int LOCATION_PERMISSION_REQUEST = 1001;
    private static final int CENTER_MAP_PERMISSION_REQUEST = 1002;
    private static final int AUDIO_PERMISSION_REQUEST = 1003;
    private static final int CAMERA_PERMISSION_REQUEST = 1004;
    private static final String SAVED_URL = "saved_url";
    private static final String MAP_URL = "https://bustimes.org/map";
    private static final String SEARCH_URL = "https://bustimes.org/search?q=";
    private static final String PREFS_NAME = "bustimes_prefs";
    private static final String PREF_NIGHT_MODE = "night_mode";
    private static final String PREF_FAVORITES = "favorites_json";
    private static final String PREF_RECENT = "recent_routes_json";
    private static final long BUS_MARKER_ANIMATION_MS = 5_000L;
    private static final long MARKER_JS_THROTTLE_MS = 300L;
    private static final long BILLBOARD_THROTTLE_MS = 600L;
    private static final long MARKER_STALE_MS = 120_000L;
    private static final long FOLLOW_INTERVAL_MS = 5_000L;

    // Chrome
    private TextView topBarTitle;
    private TextView topBarSubtitle;
    private Button arToggleButton;
    private LinearLayout tabMapButton;
    private LinearLayout tabSearchButton;
    private LinearLayout tabFavoritesButton;
    private LinearLayout tabAccountButton;
    private TextView tabMapIcon;
    private TextView tabSearchIcon;
    private TextView tabFavoritesIcon;
    private TextView tabAccountIcon;

    // Content
    private FrameLayout contentArea;
    private WebView webView;
    private ArBusStopView arBusStopView;
    private LinearLayout searchScreen;
    private LinearLayout favoritesScreen;
    private LinearLayout accountScreen;
    private ProgressBar progressBar;

    // Map overlays
    private LinearLayout mapSearchBar;
    private LinearLayout mapControlStack;
    private Button themeButton;
    private EditText searchInput;
    private LinearLayout recentChipsRow;

    // State
    private String currentTab = "map";
    private boolean nightModeEnabled;
    private boolean arModeEnabled;
    private String activeRouteFilter = "";
    private String followBusId;
    private String alertRoute;
    private boolean alertFired;
    private Location lastKnownLocation;
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
    private String pendingNavigationName;
    private double pendingNavigationLatitude = Double.NaN;
    private double pendingNavigationLongitude = Double.NaN;
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
    @SuppressLint("SetJavaScriptEnabled")
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SharedPreferences preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        nightModeEnabled = preferences.getBoolean(PREF_NIGHT_MODE, false);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Window window = getWindow();
            window.setStatusBarColor(UiTheme.INK);
            window.setNavigationBarColor(UiTheme.INK);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiTheme.INK);
        root.setFitsSystemWindows(true);

        // ============ TOP APP BAR ============
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        topBar.setBackgroundColor(UiTheme.INK);
        int barPad = dp(18);
        topBar.setPadding(barPad, dp(10), barPad, dp(10));

        LinearLayout titleBlock = new LinearLayout(this);
        titleBlock.setOrientation(LinearLayout.VERTICAL);

        topBarTitle = new TextView(this);
        topBarTitle.setText("Bus Times Live");
        topBarTitle.setTextColor(Color.WHITE);
        topBarTitle.setTextSize(22);
        topBarTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        titleBlock.addView(topBarTitle);

        topBarSubtitle = new TextView(this);
        topBarSubtitle.setText("live bus tracking");
        topBarSubtitle.setTextColor(Color.argb(150, 200, 214, 245));
        topBarSubtitle.setTextSize(11);
        titleBlock.addView(topBarSubtitle);

        topBar.addView(titleBlock, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        arToggleButton = new Button(this);
        arToggleButton.setText("◧ AR View");
        arToggleButton.setTextSize(13);
        arToggleButton.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        arToggleButton.setTextColor(Color.WHITE);
        arToggleButton.setStateListAnimator(null);
        arToggleButton.setAllCaps(false);
        arToggleButton.setMinWidth(0);
        arToggleButton.setMinHeight(0);
        arToggleButton.setPadding(dp(16), dp(9), dp(16), dp(9));
        arToggleButton.setBackground(UiTheme.ripple(UiTheme.pill(this,
                UiTheme.withAlpha(Color.WHITE, 26), UiTheme.withAlpha(Color.WHITE, 70), 1f, 10f)));
        arToggleButton.setContentDescription("Switch between standard map and AR bus stop finder");
        arToggleButton.setOnClickListener(v -> toggleArMode());
        UiTheme.pressScale(arToggleButton);
        topBar.addView(arToggleButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(topBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ============ CONTENT AREA ============
        contentArea = new FrameLayout(this);
        contentArea.setBackgroundColor(nightModeEnabled ? UiTheme.INK : Color.rgb(238, 240, 244));

        webView = new WebView(this);
        contentArea.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        arBusStopView = new ArBusStopView(this);
        arBusStopView.setVisibility(View.GONE);
        contentArea.addView(arBusStopView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        arBusStopView.setBusTapListener(this::openBusDetailsFromAr);

        searchScreen = buildSearchScreen();
        searchScreen.setVisibility(View.GONE);
        contentArea.addView(searchScreen, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        favoritesScreen = buildFavoritesScreen();
        favoritesScreen.setVisibility(View.GONE);
        contentArea.addView(favoritesScreen, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        accountScreen = buildAccountScreen();
        accountScreen.setVisibility(View.GONE);
        contentArea.addView(accountScreen, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Map overlays (only visible on the map tab)
        mapSearchBar = buildMapSearchBar();
        contentArea.addView(mapSearchBar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP) {
            {
                setMargins(dp(14), dp(12), dp(84), 0);
            }
        });

        mapControlStack = buildMapControlStack();
        FrameLayout.LayoutParams stackParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        stackParams.setMargins(0, dp(12), dp(14), 0);
        contentArea.addView(mapControlStack, stackParams);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.getProgressDrawable().setColorFilter(UiTheme.CYAN, PorterDuff.Mode.SRC_IN);
        contentArea.addView(progressBar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        root.addView(contentArea, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ============ BOTTOM NAVIGATION ============
        root.addView(buildBottomNav(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));

        setContentView(root);
        configureWebView();

        String url = MAP_URL;
        if (savedInstanceState != null) {
            url = savedInstanceState.getString(SAVED_URL, MAP_URL);
        }
        webView.loadUrl(url);
        registerBusTrackingReceiver();
        switchTab("map");
        uiHandler.postDelayed(markerCleanupRunnable, 30_000L);
    }

    // ================= LAYOUT BUILDERS =================

    private LinearLayout buildMapSearchBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(UiTheme.pill(this, Color.WHITE, Color.argb(30, 0, 0, 0), 1f, 28f));
        bar.setElevation(dp(4));
        int pad = dp(14);
        bar.setPadding(pad, dp(11), pad, dp(11));
        bar.setContentDescription("Search stops, places or routes");

        TextView searchIcon = new TextView(this);
        searchIcon.setText("🔍");
        searchIcon.setTextSize(16);
        bar.addView(searchIcon);

        TextView hint = new TextView(this);
        hint.setText("Search stops, places or routes");
        hint.setTextColor(Color.argb(160, 40, 48, 70));
        hint.setTextSize(15);
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        hintParams.leftMargin = dp(10);
        bar.addView(hint, hintParams);

        TextView micIcon = new TextView(this);
        micIcon.setText("🎙");
        micIcon.setTextSize(16);
        micIcon.setPadding(dp(8), 0, 0, 0);
        micIcon.setOnClickListener(v -> startVoiceSearch());
        bar.addView(micIcon);

        bar.setOnClickListener(v -> {
            switchTab("search");
            searchInput.requestFocus();
        });
        return bar;
    }

    private LinearLayout buildMapControlStack() {
        LinearLayout stack = new LinearLayout(this);
        stack.setOrientation(LinearLayout.VERTICAL);
        stack.setGravity(Gravity.END);

        stack.addView(mapControlButton("+", "Zoom in on the live bus map", v -> zoomWebMap(true)));
        stack.addView(mapControlButton("−", "Zoom out on the live bus map", v -> zoomWebMap(false)));
        stack.addView(mapControlButton("⌖", "Find my location on the map", v -> centerMapOnUserLocation()));
        themeButton = mapControlButton(nightModeEnabled ? "🌙" : "☀",
                "Toggle dark map", v -> applyNightMode(!nightModeEnabled, false));
        stack.addView(themeButton);
        stack.addView(mapControlButton("⟳", "Refresh live buses", v -> refreshLiveMap()));
        return stack;
    }

    private Button mapControlButton(String glyph, String description, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(glyph);
        button.setTextSize(20);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setTextColor(Color.rgb(34, 42, 62));
        button.setStateListAnimator(null);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinHeight(0);
        button.setPadding(0, 0, 0, 0);
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.WHITE);
        background.setCornerRadius(dp(14));
        background.setStroke(dp(1), Color.argb(24, 0, 0, 0));
        button.setBackground(UiTheme.ripple(background));
        button.setContentDescription(description);
        button.setOnClickListener(listener);
        UiTheme.pressScale(button);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(52), dp(52));
        params.topMargin = dp(10);
        button.setLayoutParams(params);
        return button;
    }

    private LinearLayout buildBottomNav() {
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setBackgroundColor(UiTheme.INK);
        nav.setGravity(Gravity.CENTER);

        tabMapButton = navItem("🗺", "Map");
        tabMapIcon = (TextView) tabMapButton.getChildAt(0);
        tabSearchButton = navItem("🔍", "Search");
        tabSearchIcon = (TextView) tabSearchButton.getChildAt(0);
        tabFavoritesButton = navItem("♥", "Favorites");
        tabFavoritesIcon = (TextView) tabFavoritesButton.getChildAt(0);
        tabAccountButton = navItem("👤", "Account");
        tabAccountIcon = (TextView) tabAccountButton.getChildAt(0);

        View.OnClickListener mapClick = v -> switchTab("map");
        tabMapButton.setOnClickListener(mapClick);
        tabSearchButton.setOnClickListener(v -> switchTab("search"));
        tabFavoritesButton.setOnClickListener(v -> switchTab("favorites"));
        tabAccountButton.setOnClickListener(v -> switchTab("account"));

        LinearLayout.LayoutParams itemParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        nav.addView(tabMapButton, itemParams);
        nav.addView(tabSearchButton, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        nav.addView(tabFavoritesButton, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        nav.addView(tabAccountButton, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        return nav;
    }

    private LinearLayout navItem(String icon, String label) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setClickable(true);
        item.setFocusable(true);
        item.setContentDescription(label + " tab");

        TextView iconView = new TextView(this);
        iconView.setText(icon);
        iconView.setTextSize(18);
        item.addView(iconView);

        TextView labelView = new TextView(this);
        labelView.setText(label);
        labelView.setTextSize(11);
        labelView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        item.addView(labelView);
        return item;
    }

    private void setTabSelected(LinearLayout item, TextView icon, boolean selected) {
        int color = selected ? UiTheme.TEAL : Color.argb(170, 210, 218, 240);
        icon.setTextColor(color);
        for (int i = 0; i < item.getChildCount(); i++) {
            View child = item.getChildAt(i);
            if (child instanceof TextView) {
                ((TextView) child).setTextColor(color);
            }
        }
    }

    private LinearLayout buildSearchScreen() {
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setBackgroundColor(nightModeEnabled ? UiTheme.INK : Color.rgb(244, 246, 250));
        int pad = dp(20);
        screen.setPadding(pad, pad, pad, pad);

        TextView title = screenTitle("Search");
        screen.addView(title);

        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        searchRow.setBackground(UiTheme.pill(this, Color.WHITE, Color.argb(30, 0, 0, 0), 1f, 26f));
        searchRow.setPadding(dp(16), dp(6), dp(10), dp(6));

        searchInput = new EditText(this);
        searchInput.setHint("Stop, place or route number");
        searchInput.setTextSize(15);
        searchInput.setTextColor(Color.rgb(24, 30, 48));
        searchInput.setHintTextColor(Color.argb(140, 40, 48, 70));
        searchInput.setBackground(null);
        searchInput.setSingleLine(true);
        searchInput.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override
            public void afterTextChanged(Editable s) {
                // No-op; search happens on submit.
            }
        });
        searchInput.setOnEditorActionListener((v, actionId, event) -> {
            submitSearch();
            return true;
        });
        searchRow.addView(searchInput, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button mic = new Button(this);
        mic.setText("🎙");
        mic.setTextSize(16);
        mic.setStateListAnimator(null);
        mic.setMinWidth(0);
        mic.setMinHeight(0);
        mic.setPadding(dp(10), dp(6), dp(10), dp(6));
        mic.setTextColor(Color.rgb(34, 42, 62));
        mic.setBackground(UiTheme.ripple(UiTheme.pill(this, Color.argb(20, 34, 42, 62), 0, 0, 20f)));
        mic.setOnClickListener(v -> startVoiceSearch());
        searchRow.addView(mic);

        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = dp(16);
        screen.addView(searchRow, rowParams);

        Button go = new Button(this);
        go.setText("Search on bustimes.org");
        go.setTextSize(14);
        go.setTextColor(Color.WHITE);
        go.setStateListAnimator(null);
        go.setAllCaps(false);
        go.setBackground(UiTheme.ripple(UiTheme.pill(this, UiTheme.BLUE, 0, 0, 24f)));
        go.setPadding(dp(20), dp(10), dp(20), dp(10));
        go.setOnClickListener(v -> submitSearch());
        LinearLayout.LayoutParams goParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        goParams.topMargin = dp(14);
        LinearLayout goWrap = new LinearLayout(this);
        goWrap.addView(go, goParams);
        screen.addView(goWrap);

        TextView recentLabel = screenSubtitle("Recent route searches");
        screen.addView(recentLabel);

        recentChipsRow = new LinearLayout(this);
        recentChipsRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams chipsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chipsParams.topMargin = dp(10);
        screen.addView(recentChipsRow, chipsParams);
        rebuildRecentChips();

        TextView tip = screenSubtitle("Tip: tap the mic and say a route number like 350, or search a place like Beverley.");
        LinearLayout.LayoutParams tipParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tipParams.topMargin = dp(22);
        screen.addView(tip, tipParams);
        return screen;
    }

    private TextView screenTitle(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(22);
        view.setTextColor(nightModeEnabled ? Color.WHITE : Color.rgb(20, 26, 44));
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }

    private TextView screenSubtitle(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(13);
        view.setTextColor(nightModeEnabled ? UiTheme.TEXT_DIM : Color.argb(180, 60, 70, 96));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(18);
        view.setLayoutParams(params);
        return view;
    }

    private LinearLayout buildFavoritesScreen() {
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setBackgroundColor(nightModeEnabled ? UiTheme.INK : Color.rgb(244, 246, 250));
        int pad = dp(20);
        screen.setPadding(pad, pad, pad, pad);
        screen.setTag("favorites_content");
        screen.addView(screenTitle("Favorites"));
        screen.addView(screenSubtitle("No favorites yet — tap ★ Favorite Route on any bus to pin it here."));
        return screen;
    }

    private LinearLayout buildAccountScreen() {
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setBackgroundColor(nightModeEnabled ? UiTheme.INK : Color.rgb(244, 246, 250));
        int pad = dp(20);
        screen.setPadding(pad, pad, pad, pad);

        screen.addView(screenTitle("Account & settings"));

        LinearLayout nightRow = new LinearLayout(this);
        nightRow.setOrientation(LinearLayout.HORIZONTAL);
        nightRow.setGravity(Gravity.CENTER_VERTICAL);
        nightRow.setPadding(0, dp(18), 0, dp(6));

        TextView nightLabel = new TextView(this);
        nightLabel.setText("Dark map mode");
        nightLabel.setTextSize(15);
        nightLabel.setTextColor(nightModeEnabled ? Color.WHITE : Color.rgb(24, 30, 48));
        nightRow.addView(nightLabel, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Switch nightSwitch = new Switch(this);
        nightSwitch.setChecked(nightModeEnabled);
        nightSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> applyNightMode(isChecked, false));
        nightRow.addView(nightSwitch);
        screen.addView(nightRow);

        screen.addView(infoRow("Live data (BODS)",
                BuildConfig.BODS_API_KEY.isEmpty()
                        ? "Not configured — add a free BODS_API_KEY to overlay live UK buses"
                        : "Active — polling every 15 seconds"));
        screen.addView(infoRow("Buses tracked now", String.valueOf(trackedBusMarkers.size())));
        screen.addView(infoRow("Map & times", "bustimes.org"));
        screen.addView(infoRow("Bus locations", "UK Bus Open Data Service (SIRI-VM)"));
        screen.addView(infoRow("Stops in AR", "OpenStreetMap contributors (Overpass API)"));
        screen.addView(infoRow("Version", "1.1.0"));
        return screen;
    }

    private LinearLayout infoRow(String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(14), 0, dp(4));

        TextView labelView = new TextView(this);
        labelView.setText(label);
        labelView.setTextSize(12);
        labelView.setTextColor(Color.argb(150, 120, 130, 156));
        row.addView(labelView);

        TextView valueView = new TextView(this);
        valueView.setText(value);
        valueView.setTextSize(14);
        valueView.setTextColor(nightModeEnabled ? Color.WHITE : Color.rgb(24, 30, 48));
        row.addView(valueView);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(8);
        row.setLayoutParams(params);
        return row;
    }

    // ================= TABS =================

    private void switchTab(String tab) {
        currentTab = tab;
        boolean mapTab = "map".equals(tab);

        searchScreen.setVisibility("search".equals(tab) ? View.VISIBLE : View.GONE);
        favoritesScreen.setVisibility("favorites".equals(tab) ? View.VISIBLE : View.GONE);
        accountScreen.setVisibility("account".equals(tab) ? View.VISIBLE : View.GONE);

        if (!mapTab && arModeEnabled) {
            exitArMode();
        }

        boolean showWeb = mapTab && !arModeEnabled;
        webView.setVisibility(showWeb ? View.VISIBLE : View.GONE);
        arBusStopView.setVisibility(mapTab && arModeEnabled ? View.VISIBLE : View.GONE);
        mapSearchBar.setVisibility(mapTab && !arModeEnabled ? View.VISIBLE : View.GONE);
        mapControlStack.setVisibility(mapTab && !arModeEnabled ? View.VISIBLE : View.GONE);
        progressBar.setVisibility(View.GONE);

        setTabSelected(tabMapButton, tabMapIcon, mapTab);
        setTabSelected(tabSearchButton, tabSearchIcon, "search".equals(tab));
        setTabSelected(tabFavoritesButton, tabFavoritesIcon, "favorites".equals(tab));
        setTabSelected(tabAccountButton, tabAccountIcon, "account".equals(tab));

        if ("favorites".equals(tab)) {
            rebuildFavoritesScreen();
        } else if ("account".equals(tab)) {
            rebuildAccountScreen();
        }
    }

    private void rebuildAccountScreen() {
        // Simplest robust approach: rebuild the whole screen content.
        contentArea.removeView(accountScreen);
        accountScreen = buildAccountScreen();
        accountScreen.setVisibility(View.VISIBLE);
        contentArea.addView(accountScreen,
                contentArea.indexOfChild(mapSearchBar) >= 0 ? contentArea.indexOfChild(mapSearchBar) : 2,
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    // ================= FAVORITES / RECENTS =================

    private JSONArray loadJsonArrayPref(String key) {
        try {
            String raw = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(key, "[]");
            return new JSONArray(raw == null || raw.isEmpty() ? "[]" : raw);
        } catch (Exception exception) {
            return new JSONArray();
        }
    }

    private void saveJsonArrayPref(String key, JSONArray array) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putString(key, array.toString())
                .apply();
    }

    private boolean isFavoriteRoute(String route) {
        JSONArray favorites = loadJsonArrayPref(PREF_FAVORITES);
        for (int i = 0; i < favorites.length(); i++) {
            JSONObject entry = favorites.optJSONObject(i);
            if (entry != null && route.equalsIgnoreCase(entry.optString("route"))) {
                return true;
            }
        }
        return false;
    }

    private void toggleFavoriteRoute(String route, String destination) {
        JSONArray favorites = loadJsonArrayPref(PREF_FAVORITES);
        JSONArray updated = new JSONArray();
        boolean existed = false;
        for (int i = 0; i < favorites.length(); i++) {
            JSONObject entry = favorites.optJSONObject(i);
            if (entry != null && route.equalsIgnoreCase(entry.optString("route"))) {
                existed = true;
                continue;
            }
            try {
                updated.put(entry);
            } catch (Exception ignored) {
            }
        }
        if (!existed) {
            try {
                JSONObject entry = new JSONObject();
                entry.put("route", route);
                entry.put("destination", destination);
                updated.put(entry);
                Toast.makeText(this, "Route " + route + " added to favorites", Toast.LENGTH_SHORT).show();
            } catch (Exception ignored) {
            }
        } else {
            Toast.makeText(this, "Route " + route + " removed from favorites", Toast.LENGTH_SHORT).show();
        }
        saveJsonArrayPref(PREF_FAVORITES, updated);
    }

    private void rememberRecentRoute(String route) {
        if (route == null || route.trim().isEmpty()) {
            return;
        }
        JSONArray recent = loadJsonArrayPref(PREF_RECENT);
        JSONArray updated = new JSONArray();
        try {
            updated.put(route.trim());
            for (int i = 0; i < recent.length() && updated.length() < 6; i++) {
                String value = recent.optString(i, "");
                if (!value.equalsIgnoreCase(route.trim())) {
                    updated.put(value);
                }
            }
        } catch (Exception ignored) {
        }
        saveJsonArrayPref(PREF_RECENT, updated);
        rebuildRecentChips();
    }

    private void rebuildRecentChips() {
        if (recentChipsRow == null) {
            return;
        }
        recentChipsRow.removeAllViews();
        JSONArray recent = loadJsonArrayPref(PREF_RECENT);
        if (recent.length() == 0) {
            TextView empty = new TextView(this);
            empty.setText("No recent searches yet");
            empty.setTextSize(13);
            empty.setTextColor(Color.argb(140, 60, 70, 96));
            recentChipsRow.addView(empty);
            return;
        }
        for (int i = 0; i < recent.length(); i++) {
            final String route = recent.optString(i, "");
            if (route.isEmpty()) {
                continue;
            }
            TextView chip = UiTheme.pillText(this, "Route " + route, Color.WHITE,
                    UiTheme.BLUE, UiTheme.withAlpha(Color.WHITE, 70));
            chip.setOnClickListener(v -> {
                rememberRecentRoute(route);
                zoomToActiveBusRoute(route);
                switchTab("map");
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.setMargins(0, 0, dp(8), 0);
            recentChipsRow.addView(chip, params);
        }
    }

    private void rebuildFavoritesScreen() {
        favoritesScreen.removeAllViews();
        int pad = dp(20);
        favoritesScreen.setPadding(pad, pad, pad, pad);
        favoritesScreen.addView(screenTitle("Favorites"));

        JSONArray favorites = loadJsonArrayPref(PREF_FAVORITES);
        if (favorites.length() == 0) {
            favoritesScreen.addView(screenSubtitle(
                    "No favorites yet — tap ★ Favorite Route on any bus to pin it here."));
            return;
        }
        for (int i = 0; i < favorites.length(); i++) {
            final JSONObject entry = favorites.optJSONObject(i);
            if (entry == null) {
                continue;
            }
            final String route = entry.optString("route", "");
            final String destination = entry.optString("destination", "");

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(UiTheme.pill(this,
                    nightModeEnabled ? UiTheme.withAlpha(UiTheme.INK_LIGHT, 235) : Color.WHITE,
                    Color.argb(24, 0, 0, 0), 1f, 16f));
            int rowPad = dp(14);
            row.setPadding(rowPad, rowPad, rowPad, rowPad);

            TextView badge = new TextView(this);
            badge.setText(route);
            badge.setTextColor(Color.WHITE);
            badge.setTextSize(15);
            badge.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
            badge.setGravity(Gravity.CENTER);
            badge.setBackground(UiTheme.circleGradient(this, UiTheme.BLUE, UiTheme.BLUE_DEEP));
            row.addView(badge, new LinearLayout.LayoutParams(dp(42), dp(42)));

            LinearLayout textBlock = new LinearLayout(this);
            textBlock.setOrientation(LinearLayout.VERTICAL);
            textBlock.setPadding(dp(12), 0, 0, 0);

            TextView routeTitle = new TextView(this);
            routeTitle.setText("Route " + route);
            routeTitle.setTextSize(15);
            routeTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            routeTitle.setTextColor(nightModeEnabled ? Color.WHITE : Color.rgb(20, 26, 44));
            textBlock.addView(routeTitle);

            TextView destTitle = new TextView(this);
            destTitle.setText(destination.isEmpty() ? "Tap to see live buses" : "→ " + destination);
            destTitle.setTextSize(12);
            destTitle.setTextColor(Color.argb(170, 90, 100, 124));
            textBlock.addView(destTitle);

            row.addView(textBlock, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView remove = new TextView(this);
            remove.setText("✕");
            remove.setTextSize(16);
            remove.setPadding(dp(10), dp(6), dp(4), dp(6));
            remove.setTextColor(Color.argb(140, 90, 100, 124));
            remove.setOnClickListener(v -> {
                toggleFavoriteRoute(route, destination);
                rebuildFavoritesScreen();
            });
            row.addView(remove);

            row.setOnClickListener(v -> {
                zoomToActiveBusRoute(route);
                switchTab("map");
            });

            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowParams.topMargin = dp(12);
            favoritesScreen.addView(row, rowParams);
        }
    }

    // ================= WEBVIEW =================

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

        webView.setBackgroundColor(nightModeEnabled ? UiTheme.INK : Color.WHITE);
        webView.addJavascriptInterface(new BusMarkerBridge(), "BusMarkerBridge");
        webView.setWebViewClient(new BusTimesWebViewClient());
        webView.setWebChromeClient(new BusTimesChromeClient());
        webView.setDownloadListener(new BusTimesDownloadListener());
    }

    // ================= SITE CLEANUP (ads, chrome, popups) =================

    /** Blocks ad/CMP hosts at the network layer so they never render. */
    private WebResourceResponse blockIfAd(String url) {
        String lower = url == null ? "" : url.toLowerCase(Locale.UK);
        boolean blocked = lower.contains("adfirst")
                || lower.contains("googlesyndication")
                || lower.contains("doubleclick")
                || lower.contains("googleadservices")
                || lower.contains("adservice.google")
                || lower.contains("criteo")
                || lower.contains("taboola")
                || lower.contains("outbrain")
                || lower.contains("/consent");
        if (!blocked) {
            return null;
        }
        try {
            return new WebResourceResponse("text/plain", "utf-8",
                    new ByteArrayInputStream(new byte[0]));
        } catch (Exception exception) {
            return null;
        }
    }

    private void cleanupSiteChrome() {
        String script = "(function(){"
                // Kill ads + consent remnants at DOM level.
                + "var kill='.adsbygoogle,ins.adsbygoogle,[id*=\"google_ads\"],[id*=\"div-gpt-ad\"],"
                + "[id^=\"ad-\"],[id$=\"-ad\"],[class*=\"advert\"],[class*=\"ad-slot\"],[class*=\"adfirst\"],"
                + "iframe[src*=\"adfirst\"],iframe[src*=\"googlesyndication\"],iframe[src*=\"doubleclick\"],"
                + "[class*=\"consent\"],[id*=\"gdpr\"],[class*=\"cmp-\"],.skip';"
                + "document.querySelectorAll(kill).forEach(function(el){el.remove();});"
                // Hide the site's own header, zoom/locate/layer controls on the map page.
                + "var mapPage=/^\\\\/map/.test(location.pathname);"
                + "var style=document.getElementById('bustimes-chrome-hide');"
                + "if(!style){style=document.createElement('style');style.id='bustimes-chrome-hide';document.head.appendChild(style);}"
                + "style.textContent=mapPage?"
                + "'.site-header{display:none!important}"
                + ".maplibregl-ctrl-group,.mapboxgl-ctrl-group,.leaflet-control-zoom,.leaflet-control-attribution{display:none!important}"
                + ".maplibregl-popup,.mapboxgl-popup,.leaflet-popup{display:none!important}'"
                + ":'';"
                + "return true;})();";
        webView.evaluateJavascript(script, ignored -> {
        });
    }

    /** Installs a MutationObserver that hides the site's vehicle popup and forwards it to the native sheet. */
    private void installPopupHijack() {
        String script = "(function(){"
                + "if(window.__bodsPopupObs)return;window.__bodsPopupObs=true;"
                + "var SEL='.maplibregl-popup,.mapboxgl-popup,.leaflet-popup';"
                + "var lastScan=0;"
                + "function handle(el){"
                + "var text=(el.textContent||'').trim();if(!text)return;"
                + "var lines=text.split('\\n').map(function(s){return s.trim();}).filter(Boolean);"
                + "var route='',dest='',vehicle='',lastSeen='';"
                + "for(var i=0;i<lines.length;i++){"
                + "var line=lines[i];"
                + "var m=line.match(/^([A-Za-z0-9]+)\\s+to\\s+(.+)$/);"
                + "if(m&&!route){route=m[1];dest=m[2];continue;}"
                + "if(!vehicle&&/^[0-9]{1,6}\\s*-\\s*[A-Z0-9 ]{2,14}$/.test(line)){vehicle=line;continue;}"
                + "if(!lastSeen&&/^(at stop|due$)|(\\d+)\\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?)\\s*ago/.test(line)){lastSeen=line;continue;}"
                + "}"
                + "if(!route||(!vehicle&&!lastSeen))return;"
                + "el.style.display='none';"
                + "if(window.BusMarkerBridge){window.BusMarkerBridge.showSitePopup(JSON.stringify({route:route,destination:dest,vehicle:vehicle,lastSeen:lastSeen}));}"
                + "}"
                + "function scan(){var now=Date.now();if(now-lastScan<300)return;lastScan=now;"
                + "document.querySelectorAll(SEL).forEach(handle);}"
                + "new MutationObserver(function(){scan();}).observe(document.body,{childList:true,subtree:true});"
                + "scan();"
                + "return true;})();";
        webView.evaluateJavascript(script, ignored -> {
        });
    }

    private void hideAdsOnPage() {
        cleanupSiteChrome();
    }

    // ================= MAP ACTIONS =================

    private void refreshLiveMap() {
        hideAdsOnPage();
        webView.reload();
        Intent intent = new Intent(this, BusTrackingService.class);
        intent.setAction(BusTrackingService.ACTION_REFRESH_NOW);
        startService(intent);
        Toast.makeText(this, "Refreshing live bus map", Toast.LENGTH_SHORT).show();
    }

    private void zoomWebMap(boolean zoomIn) {
        String script = "(function(){"
                + "var map=window.__bodsFindMap&&window.__bodsFindMap();"
                + "if(map&&map." + (zoomIn ? "zoomIn" : "zoomOut") + "){map." + (zoomIn ? "zoomIn" : "zoomOut") + "();return true;}"
                + "var button=document.querySelector(\""
                + (zoomIn
                        ? ".maplibregl-ctrl-zoom-in, .mapboxgl-ctrl-zoom-in, .leaflet-control-zoom-in, [aria-label='Zoom in']"
                        : ".maplibregl-ctrl-zoom-out, .mapboxgl-ctrl-zoom-out, .leaflet-control-zoom-out, [aria-label='Zoom out']")
                + "\");"
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
        if (themeButton != null) {
            themeButton.setText(night ? "🌙" : "☀");
            themeButton.setContentDescription(night ? "Switch back to day map" : "Switch to dark map");
        }
        if (webView != null) {
            webView.setBackgroundColor(night ? UiTheme.INK : Color.WHITE);
        }
        String css;
        if (night) {
            css = "body{background:#0b1226!important;color:#dfe7ff!important}"
                    + ".maplibregl-canvas,.mapboxgl-canvas,.leaflet-tile-pane,.leaflet-tile-container,.leaflet-tile"
                    + "{filter:invert(1) hue-rotate(180deg) brightness(.92) contrast(.92) saturate(.85)!important}"
                    + ".maplibregl-popup-content,.mapboxgl-popup-content,.leaflet-popup-content-wrapper"
                    + "{background:#101a38!important;color:#dfe7ff!important}"
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
        if (webView != null) {
            webView.evaluateJavascript(script, ignored -> {
            });
        }
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
        if (!"map".equals(currentTab)) {
            switchTab("map");
            return;
        }
        if (arModeEnabled) {
            exitArMode();
            return;
        }
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        super.onBackPressed();
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
                enterArMode();
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
        if (bestLocation != null) {
            lastKnownLocation = bestLocation;
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
                // The user may have granted approximate location only.
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
                Toast.makeText(this, "Still waiting for GPS. Move near a window or turn on High accuracy location.",
                        Toast.LENGTH_LONG).show();
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
        lastKnownLocation = location;
        double latitude = location.getLatitude();
        double longitude = location.getLongitude();
        String url = String.format(Locale.US, "%s#16/%f/%f", MAP_URL, latitude, longitude);
        String script = String.format(Locale.US,
                "(function(){"
                        + "var lat=%f,lng=%f,zoom=16;"
                        + "var map=window.__bodsFindMap&&window.__bodsFindMap();"
                        + "if(map){"
                        + "if(map.flyTo){map.flyTo({center:[lng,lat],zoom:zoom});return true;}"
                        + "if(map.setView){map.setView([lat,lng],zoom);return true;}"
                        + "if(map.easeTo){map.easeTo({center:[lng,lat],zoom:zoom});return true;}"
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

    // ================= SEARCH =================

    private void submitSearch() {
        if (searchInput == null) {
            return;
        }
        String query = searchInput.getText() == null ? "" : searchInput.getText().toString().trim();
        if (query.isEmpty()) {
            Toast.makeText(this, "Type a stop, place or route first", Toast.LENGTH_SHORT).show();
            return;
        }
        rememberRecentRoute(query);
        webView.loadUrl(SEARCH_URL + Uri.encode(query));
        switchTab("map");
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
            Toast.makeText(this, "Route " + route + " has no live buses right now.", Toast.LENGTH_LONG).show();
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

    private void selectRouteFilter(String route) {
        activeRouteFilter = route == null ? "" : route.trim();
        rememberRecentRoute(activeRouteFilter);
        filterMapMarkersForRoute(activeRouteFilter);
        arBusStopView.setRouteFilter(activeRouteFilter);
    }

    private void animateMapCameraTo(double latitude, double longitude, float zoom) {
        String script = String.format(Locale.US,
                "(function(){"
                        + "var lat=%f,lng=%f,zoom=%f;"
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

        Location targetLocation = query.location != null ? query.location : lastKnownLocation;
        if (targetLocation != null) {
            webView.loadUrl(MAP_URL + "#14/" + targetLocation.getLatitude() + "/" + targetLocation.getLongitude());
        } else {
            webView.loadUrl(MAP_URL);
        }
        requestImmediateBusRefresh();
        switchTab("map");

        String place = query.placeName.isEmpty() ? "nearby" : "in " + query.placeName;
        Toast.makeText(this, "Showing live route " + activeRouteFilter + " buses " + place, Toast.LENGTH_LONG).show();
    }

    private VoiceRouteQuery parseVoiceRouteQuery(String text) {
        String route = extractRouteNumber(text);
        String placeName = extractKnownPlaceName(text);
        return new VoiceRouteQuery(route, placeName, locationForPlace(placeName));
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
        if (normalized.contains("beverley")) {
            return "Beverley";
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
        } else if ("Beverley".equals(placeName)) {
            location.setLatitude(53.8420);
            location.setLongitude(-0.4250);
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

        Map<String, String> digitWords = new HashMap<>();
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

        Map<String, String> hundredsPhrases = new HashMap<>();
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

        Map<String, String> tensWords = new HashMap<>();
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

    // ================= AR =================

    private void toggleArMode() {
        if (arModeEnabled) {
            exitArMode();
            return;
        }
        switchTab("map");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { Manifest.permission.CAMERA }, CAMERA_PERMISSION_REQUEST);
            return;
        }
        enterArMode();
    }

    private void enterArMode() {
        arModeEnabled = true;
        arToggleButton.setText("◧ Map View");
        webView.setVisibility(View.GONE);
        searchScreen.setVisibility(View.GONE);
        favoritesScreen.setVisibility(View.GONE);
        accountScreen.setVisibility(View.GONE);
        arBusStopView.setVisibility(View.VISIBLE);
        mapSearchBar.setVisibility(View.GONE);
        mapControlStack.setVisibility(View.GONE);
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
    }

    private void exitArMode() {
        arModeEnabled = false;
        stopArLocationUpdates();
        arBusStopView.destroyAr();
        arBusStopView.setVisibility(View.GONE);
        arToggleButton.setText("◧ AR View");
        boolean mapTab = "map".equals(currentTab);
        webView.setVisibility(mapTab ? View.VISIBLE : View.GONE);
        mapSearchBar.setVisibility(mapTab ? View.VISIBLE : View.GONE);
        mapControlStack.setVisibility(mapTab ? View.VISIBLE : View.GONE);
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
                lastKnownLocation = location;
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

    // ================= LIVE BUS DATA =================

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
            updateTopBarSubtitle();
            return;
        }

        float bearing = Float.isNaN(nextBearing)
                ? bearingBetween(marker.latitude, marker.longitude, nextLatitude, nextLongitude)
                : nextBearing;
        marker.animateTo(nextLatitude, nextLongitude, bearing, firstNonEmpty(recordedAt, "Unknown"),
                firstNonEmpty(occupancy, "Information Unknown"), destinationName, expectedArrivalTime,
                firstNonEmpty(operatorName, ""));
        updateTopBarSubtitle();
        checkArrivalAlert(marker);
    }

    private void updateTopBarSubtitle() {
        int count = trackedBusMarkers.size();
        topBarSubtitle.setText(count == 0
                ? (BuildConfig.BODS_API_KEY.isEmpty() ? "tap a bus for details" : "waiting for live buses…")
                : count + (count == 1 ? " bus live now" : " buses live now"));
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
        updateTopBarSubtitle();
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

    private void checkArrivalAlert(AnimatedBusMarker marker) {
        if (alertRoute == null || alertFired) {
            return;
        }
        if (!marker.matchesRoute(normalizeRouteSearch(alertRoute))) {
            return;
        }
        int eta = parseEtaMinutes(etaText(marker.expectedArrivalTime));
        if (eta >= 0 && eta <= 5) {
            alertFired = true;
            Toast.makeText(this, "🔔 Route " + marker.label + " is due in about " + eta + " min!", Toast.LENGTH_LONG).show();
            try {
                Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                if (vibrator != null && vibrator.hasVibrator()) {
                    vibrator.vibrate(400);
                }
            } catch (Exception ignored) {
            }
        }
    }

    private String computeDistanceText(double latitude, double longitude) {
        Location userLocation = lastKnownLocation;
        if (userLocation == null && hasLocationPermission()) {
            userLocation = getBestLastKnownLocation((LocationManager) getSystemService(Context.LOCATION_SERVICE));
        }
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

    private int parseEtaMinutes(String etaTextValue) {
        if (etaTextValue == null) {
            return -1;
        }
        String digits = etaTextValue.replaceAll("[^0-9]", " ").trim();
        if (digits.isEmpty()) {
            return etaTextValue.contains("Arriving now") ? 0 : -1;
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

    private void renderNativeBusMarker(String id, String label, String destination, String etaTextValue, double latitude,
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
                escapeJs(destination), escapeJs(etaTextValue), escapeJs(lastSeen), speedKph, escapeJs(operator),
                escapeJs(label), escapeJs(destination), escapeJs(etaTextValue), escapeJs(speedText(speedKph)),
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
                rememberRecentRoute(route);
                zoomToActiveBusRoute(route);
                stopVoiceSearch();
                return;
            }
            applyVoiceRouteFilter(spokenText);
        }
    }

    /** Bridge called from injected JS: our own BODS markers and hijacked site popups. */
    private class BusMarkerBridge {
        @JavascriptInterface
        public void showBusDetails(String busId, String route, String destination, String etaTextValue, String lastSeen,
                String occupancy, String latitude, String longitude, String speed, String bearing, String operator) {
            runOnUiThread(() -> presentBusSheet(new BusSnapshot(
                    firstNonEmpty(busId, "bus"),
                    firstNonEmpty(route, "Bus"),
                    "",
                    firstNonEmpty(destination, "destination unknown"),
                    firstNonEmpty(occupancy, "Information Unknown"),
                    firstNonEmpty(busId, "—"),
                    firstNonEmpty(lastSeen, ""),
                    firstNonEmpty(operator, ""),
                    computeDistanceText(parseDouble(latitude, Double.NaN), parseDouble(longitude, Double.NaN)),
                    parseDouble(latitude, Double.NaN),
                    parseDouble(longitude, Double.NaN),
                    (float) parseDouble(bearing, Float.NaN),
                    (float) parseDouble(speed, 0f),
                    parseEtaMinutes(etaTextValue),
                    "its next stop"), false));
        }

        /** Called when the user taps a bus marker on the bustimes.org map itself. */
        @JavascriptInterface
        public void showSitePopup(String json) {
            runOnUiThread(() -> {
                String route = "";
                String destination = "";
                String vehicle = "";
                String lastSeen = "";
                try {
                    JSONObject payload = new JSONObject(json);
                    route = payload.optString("route", "");
                    destination = payload.optString("destination", "");
                    vehicle = payload.optString("vehicle", "");
                    lastSeen = payload.optString("lastSeen", "");
                } catch (Exception ignored) {
                }
                if (route.isEmpty()) {
                    return;
                }

                // Enrich with our BODS data when the same route is tracked live.
                AnimatedBusMarker match = findActiveBusMarker(route);
                String busNumber = vehicle;
                String busReg = "";
                int dashIndex = vehicle.indexOf(" - ");
                if (dashIndex > 0) {
                    busNumber = vehicle.substring(0, dashIndex).trim();
                    busReg = vehicle.substring(dashIndex + 3).trim();
                }
                BusSnapshot snapshot = new BusSnapshot(
                        firstNonEmpty(busNumber, "site-" + route),
                        firstNonEmpty(route, "Bus"),
                        "",
                        firstNonEmpty(destination, firstNonEmpty(match == null ? "" : match.destinationName,
                                "destination unknown")),
                        match == null ? "Information Unknown" : match.occupancy,
                        firstNonEmpty(busNumber, "—"),
                        firstNonEmpty(lastSeen, match == null ? "" : match.lastSeen),
                        match == null ? "" : match.operatorName,
                        match == null ? "" : computeDistanceText(match.latitude, match.longitude),
                        match == null ? Double.NaN : match.latitude,
                        match == null ? Double.NaN : match.longitude,
                        match == null ? Float.NaN : match.bearing,
                        match == null ? 0f : match.currentSpeedKph,
                        match == null ? -1 : parseEtaMinutes(etaText(match.expectedArrivalTime)),
                        "its next stop");
                if (busReg != null && !busReg.isEmpty()) {
                    snapshot.regOverride = busReg;
                }
                presentBusSheet(snapshot, false);
            });
        }
    }

    private void openBusDetailsFromAr(BusSnapshot snapshot) {
        presentBusSheet(snapshot, true);
    }

    /** Shows the "Selected Bus" sheet for a snapshot coming from the map, a site popup or AR. */
    private void presentBusSheet(BusSnapshot snapshot, boolean fromAr) {
        if (snapshot == null) {
            return;
        }
        if (Double.isNaN(snapshot.latitude) || Double.isNaN(snapshot.longitude)) {
            // Popup without coordinates: disable map-dependent actions.
            BusDetailsSheet.show(this, snapshot, sheetCallbacks(snapshot, fromAr));
            return;
        }
        if (!fromAr) {
            arBusStopView.setNavigationTarget("route " + snapshot.lineName, snapshot.latitude, snapshot.longitude);
        }
        BusDetailsSheet.show(this, snapshot, sheetCallbacks(snapshot, fromAr));
    }

    private BusDetailsSheet.Callbacks sheetCallbacks(BusSnapshot snapshot, boolean fromAr) {
        return new BusDetailsSheet.Callbacks() {
            @Override
            public void onNavigateToBus(double navLatitude, double navLongitude) {
                if (fromAr) {
                    arBusStopView.setNavigationTarget("route " + snapshot.lineName, navLatitude, navLongitude);
                } else {
                    pendingNavigationName = "route " + snapshot.lineName;
                    pendingNavigationLatitude = navLatitude;
                    pendingNavigationLongitude = navLongitude;
                    if (!arModeEnabled) {
                        toggleArMode();
                    }
                }
            }

            @Override
            public void onFollowBus(String busId, boolean follow) {
                if (follow) {
                    startFollowingBus(busId);
                } else {
                    stopFollowingBus(null);
                    Toast.makeText(MainActivity.this, "Stopped following", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public boolean isFollowingBus(String busId) {
                return busId != null && busId.equals(followBusId);
            }

            @Override
            public void onShowBusOnMap(double mapLatitude, double mapLongitude) {
                if (arModeEnabled) {
                    exitArMode();
                }
                switchTab("map");
                animateMapCameraTo(mapLatitude, mapLongitude, 16f);
            }

            @Override
            public void onToggleFavorite(String route) {
                toggleFavoriteRoute(route, snapshot.destinationName);
            }

            @Override
            public boolean isFavorite(String route) {
                return isFavoriteRoute(route);
            }

            @Override
            public void onToggleAlert(String busId, String route) {
                if (alertRoute != null && alertRoute.equalsIgnoreCase(route)) {
                    alertRoute = null;
                    alertFired = false;
                    Toast.makeText(MainActivity.this, "Alert removed for route " + route, Toast.LENGTH_SHORT).show();
                } else {
                    alertRoute = route;
                    alertFired = false;
                    Toast.makeText(MainActivity.this,
                            "We'll buzz when route " + route + " is about 5 minutes away", Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public boolean isAlertArmed(String busId, String route) {
                return alertRoute != null && alertRoute.equalsIgnoreCase(route);
            }
        };
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
            currentSpeedKph = estimateSpeedKph(latitude, longitude, nextLatitude, nextLongitude, now);
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

        private float estimateSpeedKph(double fromLatitude, double fromLongitude,
                double toLatitude, double toLongitude, long now) {
            if (lastUpdateWallClockMs <= 0L) {
                return 0f;
            }
            long seconds = (now - lastUpdateWallClockMs) / 1000L;
            if (seconds < 1L || seconds > 180L) {
                return currentSpeedKph;
            }
            float[] distanceHolder = new float[1];
            Location.distanceBetween(fromLatitude, fromLongitude, toLatitude, toLongitude, distanceHolder);
            float kph = distanceHolder[0] / seconds * 3.6f;
            if (kph > 130f) {
                return currentSpeedKph; // impossible jump — GPS glitch
            }
            return Math.max(0f, kph);
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
                if (message != null && message.startsWith("Add a BODS_API_KEY")) {
                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                }
            }
        }
    }

    private class BusTimesWebViewClient extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            WebResourceResponse blocked = blockIfAd(request.getUrl().toString());
            return blocked != null ? blocked : super.shouldInterceptRequest(view, request);
        }

        @Override
        public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
            super.onPageStarted(view, url, favicon);
            // Pretend we are an embedded webview so the site itself switches its ad code off.
            view.evaluateJavascript("window.noAds=true;try{Object.defineProperty(window,'noAds',{value:true,writable:false,configurable:false});}catch(e){}", null);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            hideAdsOnPage();
            installPopupHijack();
            applyNightMode(nightModeEnabled, true);
            if (!activeRouteFilter.isEmpty()) {
                filterMapMarkersForRoute(activeRouteFilter);
            }
            for (AnimatedBusMarker marker : trackedBusMarkers.values()) {
                marker.renderAt(marker.latitude, marker.longitude, marker.bearing, true);
            }
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
            progressBar.setVisibility("map".equals(currentTab) && newProgress < 100 ? View.VISIBLE : View.GONE);
            if (newProgress >= 100) {
                hideAdsOnPage();
                installPopupHijack();
                applyNightMode(nightModeEnabled, true);
                if (!activeRouteFilter.isEmpty()) {
                    filterMapMarkersForRoute(activeRouteFilter);
                }
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
