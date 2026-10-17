package org.bustimes.app;

import android.Manifest;
import android.animation.ValueAnimator;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;

import org.json.JSONArray;
import org.json.JSONObject;
import org.osmdroid.config.Configuration;
import org.osmdroid.events.MapListener;
import org.osmdroid.events.ScrollEvent;
import org.osmdroid.events.ZoomEvent;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.tileprovider.tilesource.XYTileSource;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.CustomZoomButtonsController;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polyline;
import org.osmdroid.views.overlay.TilesOverlay;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Fully native Bus Times Live screen host. No WebView and no bustimes.org.
 *
 * Data sources: BODS SIRI-VM (via BusTrackingService) for live buses, OpenStreetMap
 * (osmdroid tiles + Overpass) for the map and bus stops.
 */
public class MainActivity extends AppCompatActivity implements BusDetailsSheet.Callbacks {

    private static final int TAB_MAP = 0;
    private static final int TAB_NEARBY = 1;
    private static final int TAB_SEARCH = 2;
    private static final int TAB_FAVORITES = 3;
    private static final int TAB_ACCOUNT = 4;

    private static final String PREFS = "bus_times";
    private static final String PREF_STYLE = "map_style";
    private static final String PREF_SHOW_BUSES = "show_buses";
    private static final String PREF_SHOW_STOPS = "show_stops";
    private static final String PREF_FAVORITES = "favorite_routes";

    /** Rows a departure board shows before it reports how many are left. */
    private static final int MAX_BOARD_ROWS = 8;

    /** Minimum zoom level to render individual bus stop markers so zooming out stays uncluttered. */
    private static final double MIN_STOP_ZOOM = 13.2;

    private static final long STALE_BUS_MS = 4 * 60 * 1000L;
    private static final int REQ_LOCATION = 41;
    private static final int REQ_NOTIFICATIONS = 42;

    /** Below this the vehicle is stationary; above it the vehicle was re-reported elsewhere. */
    private static final double GLIDE_SNAP_METERS = 5000d;
    private static final double GLIDE_MIN_METERS = 3d;

    // Default view until the first GPS fix arrives.
    private static final double DEFAULT_LAT = 53.5786;
    private static final double DEFAULT_LON = -0.6548;

    private static final class Bus {
        BusPosition pos;
        long receivedMs;
        float speedKph = Float.NaN;
        /** Gap between the last two fixes the feed published, or 0 when it is not known. */
        long fixIntervalMs;
        Marker marker;
        /** Runs while the marker slides from its previous poll position to the latest one. */
        ValueAnimator glide;
    }

    private static final class Stop {
        /** OpenStreetMap node id, or 0 for a stop from a cache written before ids were kept. */
        long id;
        String name;
        String routes;
        /** NaPTAN ATCO code from OpenStreetMap, which is how a published timetable is found. */
        String atcoCode = "";
        double lat;
        double lon;
        Marker marker;
    }

    private static final class RouteInfo {
        int count;
        String destination = "";
        double nearestMeters = Double.MAX_VALUE;
        int color = UiTheme.BLUE;
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(2);
    /** Dedicated executor so Overpass map queries never block stop timetable downloads. */
    private final ExecutorService timetableIo = Executors.newSingleThreadExecutor();
    /** True while a redraw is already queued for the next animation frame. */
    private boolean mapInvalidatePosted;

    private SharedPreferences prefs;
    private LocationManager locationManager;
    private Location userLocation;
    private boolean centeredOnUser;

    private final Map<String, Bus> buses = new LinkedHashMap<>();
    private final List<Stop> stops = new ArrayList<>();
    private final Map<String, BitmapDrawable> iconCache = new HashMap<>();
    private BitmapDrawable stopIcon;
    private BitmapDrawable userIcon;
    private Marker userMarker;

    private String routeFilter;
    private String followId;
    private boolean showBuses;
    private boolean showStops;
    private String mapStyle;
    private String lastStatus = "";
    private String stopsError;
    private boolean fetchingStops;
    private double lastFetchLat = Double.NaN;
    private double lastFetchLon = Double.NaN;
    private boolean refreshPending;
    private String lastChipSignature = "";
    private long lastListRebuildMs;
    private long sweepStartMs;
    private boolean sweepPending;

    // route line overlay state
    private final List<Polyline> routeLineOverlays = new ArrayList<>();
    private String routeLineFor;
    private String routeLineLoadingFor;

    // UI
    private int currentTab = -1;
    private TextView statusText;
    private TextView countPill;
    private FrameLayout content;
    private final View[] screens = new View[5];
    private final IconView[] navIcons = new IconView[5];
    private final TextView[] navLabels = new TextView[5];

    private MapView map;
    private LinearLayout chipRow;
    private TextView followPill;
    private TextView routeLinePill;
    private LinearLayout nearbyList;
    private LinearLayout searchResults;
    private EditText searchInput;
    private LinearLayout favoritesList;
    private LinearLayout accountList;

    // departure board state, held while a stop sheet is open so it can follow the live feed
    private BottomSheetDialog stopBoardDialog;
    private LinearLayout stopRouteFilterRow;
    private String stopSheetRouteFilter;
    private LinearLayout stopBoardRows;
    private TextView stopBoardTitle;
    private TextView stopBoardNote;
    private Stop stopBoardStop;

    // published timetable state for the open stop sheet
    private LinearLayout stopTimetableDayRow;
    private int stopTimetableDayMode = StopTimetables.MODE_TODAY;
    private LinearLayout stopTimetableRows;
    private TextView stopTimetableNote;
    private TextView stopTimetableButton;
    private List<TxcTimetable.Departure> stopTimetableAll;
    private boolean stopTimetableLoading;
    private int stopTimetableRequestId;

    private final Runnable stopFetchRunnable = () -> {
        if (map == null) return;
        maybeFetchStops(map.getMapCenter().getLatitude(), map.getMapCenter().getLongitude(), false);
        Intent update = new Intent(this, BusTrackingService.class);
        update.setAction(BusTrackingService.ACTION_REFRESH_NOW);
        update.putExtra(BusTrackingService.EXTRA_BOUNDING_BOX, getMapBoundingBoxString());
        startService(update);
    };

    private String getMapBoundingBoxString() {
        if (map != null && map.getWidth() > 0 && map.getHeight() > 0) {
            BoundingBox box = map.getBoundingBox();
            if (box != null) {
                String candidate = BoundingBoxes.format(
                        box.getLonWest(), box.getLatSouth(), box.getLonEast(), box.getLatNorth());
                if (BoundingBoxes.isUsable(candidate)) {
                    return candidate;
                }
            }
        }
        return BusTrackingService.DEFAULT_BOUNDING_BOX;
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BusTrackingService.ACTION_BUS_POSITION.equals(action)) {
                onBusPosition(intent);
            } else if (BusTrackingService.ACTION_CLEAR_TRACKING.equals(action)) {
                beginSweep();
            } else if (BusTrackingService.ACTION_POLL_COMPLETE.equals(action)) {
                endSweep();
            } else if (BusTrackingService.ACTION_TRACKING_STATUS.equals(action)) {
                setStatus(intent.getStringExtra(BusTrackingService.EXTRA_STATUS_MESSAGE));
            }
        }
    };

    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            userLocation = location;
            updateUserMarker();
            if (!centeredOnUser) {
                centeredOnUser = true;
                map.getController().setZoom(16.0);
                map.getController().animateTo(new GeoPoint(location.getLatitude(), location.getLongitude()));
                maybeFetchStops(location.getLatitude(), location.getLongitude(), true);
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
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        showBuses = prefs.getBoolean(PREF_SHOW_BUSES, true);
        showStops = prefs.getBoolean(PREF_SHOW_STOPS, true);
        mapStyle = prefs.getString(PREF_STYLE, "standard");

        File base = new File(getCacheDir(), "osmdroid");
        Configuration.getInstance().setUserAgentValue(getPackageName());
        Configuration.getInstance().setOsmdroidBasePath(base);
        Configuration.getInstance().setOsmdroidTileCache(new File(base, "tiles"));

        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);

        buildUi();
        applyMapStyle();
        setTab(TAB_MAP);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (currentTab != TAB_MAP) {
                    setTab(TAB_MAP);
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });

        if (TextUtils.isEmpty(BuildConfig.BODS_API_KEY)) {
            setStatus("Add a BODS API key to see live buses");
        }
        io.execute(() -> {
            OperatorNames.load(getApplicationContext());
            handler.post(this::rebuildCurrentTab);
        });
        handleAlertRouteIntent(getIntent());
        handleArReturn(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleAlertRouteIntent(intent);
        handleArReturn(intent);
    }

    /** Tapping an arrival notification opens the map filtered to that route. */
    private void handleAlertRouteIntent(Intent intent) {
        if (intent == null) {
            return;
        }
        String route = intent.getStringExtra(AlertNotifier.EXTRA_ROUTE);
        if (TextUtils.isEmpty(route)) {
            return;
        }
        intent.removeExtra(AlertNotifier.EXTRA_ROUTE);
        filterToRoute(route);
    }

    /** Opens the camera view, handing over the bus stops already loaded for this area. */
    private void openAr() {
        Intent intent = new Intent(this, ArActivity.class);
        intent.putExtra(ArActivity.EXTRA_BBOX, getMapBoundingBoxString());
        int n = Math.min(stops.size(), 150);
        double[] lats = new double[n];
        double[] lons = new double[n];
        String[] names = new String[n];
        String[] routes = new String[n];
        for (int i = 0; i < n; i++) {
            Stop stop = stops.get(i);
            lats[i] = stop.lat;
            lons[i] = stop.lon;
            names[i] = stop.name;
            routes[i] = stop.routes;
        }
        intent.putExtra(ArActivity.EXTRA_STOP_LATS, lats);
        intent.putExtra(ArActivity.EXTRA_STOP_LONS, lons);
        intent.putExtra(ArActivity.EXTRA_STOP_NAMES, names);
        intent.putExtra(ArActivity.EXTRA_STOP_ROUTES, routes);
        startActivity(intent);
    }

    /** What the camera view asked for when the user tapped a label and came back to the map. */
    private void handleArReturn(Intent intent) {
        if (intent == null) {
            return;
        }
        final String alertRoute = intent.getStringExtra(ArActivity.EXTRA_ALERT_ROUTE);
        if (!TextUtils.isEmpty(alertRoute)) {
            intent.removeExtra(ArActivity.EXTRA_ALERT_ROUTE);
            setTab(TAB_MAP);
            handler.post(() -> onConfigureAlert(alertRoute));
            return;
        }
        if (intent.hasExtra(ArActivity.EXTRA_STOP_LAT)) {
            final double lat = intent.getDoubleExtra(ArActivity.EXTRA_STOP_LAT, Double.NaN);
            final double lon = intent.getDoubleExtra(ArActivity.EXTRA_STOP_LON, Double.NaN);
            intent.removeExtra(ArActivity.EXTRA_STOP_LAT);
            intent.removeExtra(ArActivity.EXTRA_STOP_LON);
            if (Double.isNaN(lat) || Double.isNaN(lon)) {
                return;
            }
            onShowBusOnMap(lat, lon);
            for (Stop stop : stops) {
                if (meters(stop.lat, stop.lon, lat, lon) < 10) {
                    final Stop target = stop;
                    handler.post(() -> showStopSheet(target));
                    break;
                }
            }
            return;
        }
        if (intent.hasExtra(ArActivity.EXTRA_FOCUS_LAT)) {
            double lat = intent.getDoubleExtra(ArActivity.EXTRA_FOCUS_LAT, Double.NaN);
            double lon = intent.getDoubleExtra(ArActivity.EXTRA_FOCUS_LON, Double.NaN);
            String follow = intent.getStringExtra(ArActivity.EXTRA_FOLLOW_BUS);
            intent.removeExtra(ArActivity.EXTRA_FOCUS_LAT);
            intent.removeExtra(ArActivity.EXTRA_FOCUS_LON);
            intent.removeExtra(ArActivity.EXTRA_FOLLOW_BUS);
            if (Double.isNaN(lat) || Double.isNaN(lon)) {
                return;
            }
            onShowBusOnMap(lat, lon);
            if (!TextUtils.isEmpty(follow)) {
                onFollowBus(follow, true);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        map.onResume();
        IntentFilter filter = new IntentFilter();
        filter.addAction(BusTrackingService.ACTION_BUS_POSITION);
        filter.addAction(BusTrackingService.ACTION_CLEAR_TRACKING);
        filter.addAction(BusTrackingService.ACTION_POLL_COMPLETE);
        filter.addAction(BusTrackingService.ACTION_TRACKING_STATUS);
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        Intent start = new Intent(this, BusTrackingService.class);
        start.setAction(BusTrackingService.ACTION_START_MAP_TRACKING);
        start.putExtra(BusTrackingService.EXTRA_BOUNDING_BOX, getMapBoundingBoxString());
        startService(start);
        map.post(this::refreshFeedForViewport);
        startLocation();
        updateCountPill();
    }

    /** Re-fetches the feed for the area the map is actually showing. */
    private void refreshFeedForViewport() {
        if (map == null) {
            return;
        }
        Intent update = new Intent(this, BusTrackingService.class);
        update.setAction(BusTrackingService.ACTION_REFRESH_NOW);
        update.putExtra(BusTrackingService.EXTRA_BOUNDING_BOX, getMapBoundingBoxString());
        startService(update);
    }

    @Override
    protected void onPause() {
        super.onPause();
        map.onPause();
        cancelAllGlides();
        try {
            unregisterReceiver(receiver);
        } catch (IllegalArgumentException ignored) {
            // not registered
        }
        if (ArrivalAlertStore.hasAlerts(this)) {
            Intent pauseMap = new Intent(this, BusTrackingService.class);
            pauseMap.setAction(BusTrackingService.ACTION_STOP_MAP_TRACKING);
            startService(pauseMap);
        } else {
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
        cancelAllGlides();
        for (Stop stop : stops) {
            stop.marker = null;
        }
        handler.removeCallbacksAndMessages(null);
        io.shutdownNow();
        timetableIo.shutdownNow();
        if (map != null) {
            map.onDetach();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LOCATION) {
            startLocation();
        }
        if (requestCode == REQ_NOTIFICATIONS && currentTab == TAB_ACCOUNT) {
            rebuildAccount();
        }
    }

    // ------------------------------------------------------------------ UI construction

    private int dp(float value) {
        return UiTheme.dp(this, value);
    }

    private TextView tv(String text, float sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(sp);
        view.setTextColor(color);
        if (bold) {
            view.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return view;
    }

    private void buildUi() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiTheme.INK);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(0, insets.getSystemWindowInsetTop(), 0, insets.getSystemWindowInsetBottom());
            return insets;
        });

        // Header
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16), dp(10), dp(16), dp(10));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(tv("Bus Times Live", 22, UiTheme.WHITE, true));
        statusText = tv("Live vehicle tracking", 12, UiTheme.TEXT_DIM, false);
        statusText.setSingleLine(true);
        statusText.setEllipsize(TextUtils.TruncateAt.END);
        titles.addView(statusText);
        header.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView arPill = UiTheme.pillText(this, "AR view", UiTheme.WHITE,
                UiTheme.withAlpha(UiTheme.BLUE, 60), UiTheme.BLUE);
        arPill.setOnClickListener(v -> openAr());
        LinearLayout.LayoutParams arLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        arLp.rightMargin = dp(8);
        header.addView(arPill, arLp);
        countPill = UiTheme.pillText(this, "", UiTheme.CYAN, UiTheme.withAlpha(UiTheme.CYAN, 30), UiTheme.CYAN);
        header.addView(countPill);
        root.addView(header);

        // Content
        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        screens[TAB_MAP] = buildMapScreen();
        screens[TAB_NEARBY] = buildListScreen(0);
        screens[TAB_SEARCH] = buildSearchScreen();
        screens[TAB_FAVORITES] = buildListScreen(1);
        screens[TAB_ACCOUNT] = buildListScreen(2);
        for (View screen : screens) {
            content.addView(screen, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        // Bottom navigation
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setBackgroundColor(UiTheme.INK_LIGHT);
        int[] icons = {IconView.MAP, IconView.PIN, IconView.SEARCH, IconView.HEART, IconView.PERSON};
        String[] labels = {"Map", "Nearby", "Search", "Favorites", "Account"};
        for (int i = 0; i < 5; i++) {
            final int tab = i;
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            item.setPadding(0, dp(8), 0, dp(8));
            navIcons[i] = new IconView(this, icons[i]);
            item.addView(navIcons[i], new LinearLayout.LayoutParams(dp(24), dp(24)));
            navLabels[i] = tv(labels[i], 11, UiTheme.TEXT_DIM, false);
            navLabels[i].setGravity(Gravity.CENTER);
            item.addView(navLabels[i]);
            item.setOnClickListener(v -> setTab(tab));
            nav.addView(item, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        root.addView(nav);

        setContentView(root);

        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (controller != null) {
            controller.setAppearanceLightStatusBars(false);
            controller.setAppearanceLightNavigationBars(false);
        }
    }

    private View buildMapScreen() {
        FrameLayout frame = new FrameLayout(this);

        map = new MapView(this);
        map.setMultiTouchControls(true);
        map.setTilesScaledToDpi(true);
        map.getZoomController().setVisibility(CustomZoomButtonsController.Visibility.NEVER);
        map.setMinZoomLevel(4.0);
        map.getController().setZoom(15.0);
        map.getController().setCenter(new GeoPoint(DEFAULT_LAT, DEFAULT_LON));
        map.addMapListener(new MapListener() {
            @Override
            public boolean onScroll(ScrollEvent event) {
                handler.removeCallbacks(stopFetchRunnable);
                handler.postDelayed(stopFetchRunnable, 900);
                return false;
            }

            @Override
            public boolean onZoom(ZoomEvent event) {
                applyVisibility();
                handler.removeCallbacks(stopFetchRunnable);
                handler.postDelayed(stopFetchRunnable, 900);
                return false;
            }
        });
        frame.addView(map, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Route filter chips
        HorizontalScrollView chipScroll = new HorizontalScrollView(this);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chipRow = new LinearLayout(this);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        chipRow.setPadding(dp(10), dp(10), dp(10), dp(6));
        chipScroll.addView(chipRow);
        frame.addView(chipScroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        // Control stack
        LinearLayout stack = new LinearLayout(this);
        stack.setOrientation(LinearLayout.VERTICAL);
        addControl(stack, IconView.PLUS, v -> map.getController().zoomIn());
        addControl(stack, IconView.MINUS, v -> map.getController().zoomOut());
        addControl(stack, IconView.TARGET, v -> onLocatePressed());
        addControl(stack, IconView.LAYERS, v -> showLayersSheet());
        addControl(stack, IconView.REFRESH, v -> onRefreshPressed());
        FrameLayout.LayoutParams stackLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END | Gravity.BOTTOM);
        stackLp.setMargins(0, 0, dp(12), dp(24));
        frame.addView(stack, stackLp);

        // Follow pill
        followPill = UiTheme.pillText(this, "", UiTheme.INK, UiTheme.CYAN, UiTheme.CYAN);
        followPill.setVisibility(View.GONE);
        followPill.setOnClickListener(v -> stopFollowing());
        FrameLayout.LayoutParams followLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.START | Gravity.BOTTOM);
        followLp.setMargins(dp(12), 0, 0, dp(36));
        frame.addView(followPill, followLp);

        // Route line pill
        routeLinePill = UiTheme.pillText(this, "", UiTheme.CYAN,
                UiTheme.withAlpha(UiTheme.INK, 225), UiTheme.CYAN);
        routeLinePill.setVisibility(View.GONE);
        routeLinePill.setOnClickListener(v -> clearRouteLine());
        FrameLayout.LayoutParams lineLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.START | Gravity.BOTTOM);
        lineLp.setMargins(dp(12), 0, 0, dp(84));
        frame.addView(routeLinePill, lineLp);

        // Required OpenStreetMap attribution
        TextView attribution = tv("\u00A9 OpenStreetMap contributors", 10, UiTheme.WHITE, false);
        attribution.setBackground(UiTheme.pill(this, UiTheme.withAlpha(UiTheme.INK, 170), 0, 0, 6));
        attribution.setPadding(dp(6), dp(2), dp(6), dp(2));
        FrameLayout.LayoutParams attrLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.START | Gravity.BOTTOM);
        attrLp.setMargins(dp(8), 0, 0, dp(8));
        frame.addView(attribution, attrLp);

        return frame;
    }

    private void addControl(LinearLayout stack, int icon, View.OnClickListener listener) {
        FrameLayout button = new FrameLayout(this);
        button.setBackground(UiTheme.ripple(UiTheme.pill(this,
                UiTheme.withAlpha(UiTheme.INK, 235), UiTheme.BLUE, 1.5f, 26)));
        IconView iconView = new IconView(this, icon);
        iconView.setIconColor(UiTheme.WHITE);
        iconView.setAccentColor(UiTheme.CYAN);
        button.addView(iconView, new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
        button.setOnClickListener(listener);
        UiTheme.pressScale(button);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(52), dp(52));
        lp.topMargin = dp(10);
        stack.addView(button, lp);
    }

    /** kind: 0 = nearby, 1 = favorites, 2 = account. */
    private View buildListScreen(int kind) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(16), dp(8), dp(16), dp(16));
        scroll.addView(list, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (kind == 0) {
            nearbyList = list;
        } else if (kind == 1) {
            favoritesList = list;
        } else {
            accountList = list;
        }
        return scroll;
    }

    private View buildSearchScreen() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(16), dp(8), dp(16), 0);

        searchInput = new EditText(this);
        searchInput.setHint("Search routes, destinations or stops");
        searchInput.setHintTextColor(UiTheme.withAlpha(UiTheme.WHITE, 120));
        searchInput.setTextColor(UiTheme.WHITE);
        searchInput.setTextSize(16);
        searchInput.setSingleLine(true);
        searchInput.setInputType(InputType.TYPE_CLASS_TEXT);
        searchInput.setBackground(UiTheme.pill(this, UiTheme.INK_LIGHT, UiTheme.BLUE, 1f, 24));
        searchInput.setPadding(dp(18), dp(12), dp(18), dp(12));
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) { }

            @Override
            public void afterTextChanged(Editable s) {
                rebuildSearch();
            }
        });
        column.addView(searchInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(this);
        searchResults = new LinearLayout(this);
        searchResults.setOrientation(LinearLayout.VERTICAL);
        searchResults.setPadding(0, dp(12), 0, dp(16));
        scroll.addView(searchResults, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        column.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return column;
    }

    // ------------------------------------------------------------------ tabs

    private void setTab(int tab) {
        currentTab = tab;
        for (int i = 0; i < screens.length; i++) {
            screens[i].setVisibility(i == tab ? View.VISIBLE : View.GONE);
            boolean selected = i == tab;
            navIcons[i].setIconColor(selected ? UiTheme.CYAN : UiTheme.TEXT_DIM);
            navIcons[i].setAccentColor(selected ? UiTheme.CYAN : UiTheme.TEXT_DIM);
            navLabels[i].setTextColor(selected ? UiTheme.CYAN : UiTheme.TEXT_DIM);
        }
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null && searchInput != null && tab != TAB_SEARCH) {
            imm.hideSoftInputFromWindow(searchInput.getWindowToken(), 0);
        }
        switch (tab) {
            case TAB_NEARBY:
                double[] ref = refPoint();
                maybeFetchStops(ref[0], ref[1], false);
                rebuildNearby();
                break;
            case TAB_SEARCH:
                rebuildSearch();
                break;
            case TAB_FAVORITES:
                rebuildFavorites();
                break;
            case TAB_ACCOUNT:
                rebuildAccount();
                break;
            default:
                break;
        }
    }

    private void rebuildCurrentTab() {
        switch (currentTab) {
            case TAB_NEARBY:
                rebuildNearby();
                break;
            case TAB_SEARCH:
                rebuildSearch();
                break;
            case TAB_FAVORITES:
                rebuildFavorites();
                break;
            case TAB_ACCOUNT:
                rebuildAccount();
                break;
            default:
                break;
        }
    }

    // ------------------------------------------------------------------ map styles

    private static final XYTileSource TOPO = new XYTileSource("OpenTopoMap", 0, 17, 256, ".png",
            new String[]{
                    "https://a.tile.opentopomap.org/",
                    "https://b.tile.opentopomap.org/",
                    "https://c.tile.opentopomap.org/"},
            "\u00A9 OpenTopoMap (CC-BY-SA)");

    private void applyMapStyle() {
        if ("terrain".equals(mapStyle)) {
            map.setTileSource(TOPO);
        } else {
            map.setTileSource(TileSourceFactory.MAPNIK);
        }
        map.getOverlayManager().getTilesOverlay()
                .setColorFilter("dark".equals(mapStyle) ? TilesOverlay.INVERT_COLORS : null);
        map.invalidate();
    }

    private void showLayersSheet() {
        LinearLayout card = sheetCard();
        card.addView(tv("Map layers", 20, UiTheme.WHITE, true));

        TextView styleTitle = tv("Map style", 13, UiTheme.TEXT_DIM, false);
        styleTitle.setPadding(0, dp(14), 0, dp(4));
        card.addView(styleTitle);

        final BottomSheetDialog dialog = sheetDialog(card);
        String[][] styles = {{"standard", "Standard"}, {"dark", "Dark"}, {"terrain", "Terrain"}};
        for (String[] style : styles) {
            boolean selected = style[0].equals(mapStyle);
            TextView row = tv((selected ? "\u25CF  " : "\u25CB  ") + style[1], 16,
                    selected ? UiTheme.CYAN : UiTheme.WHITE, selected);
            row.setPadding(0, dp(10), 0, dp(10));
            row.setOnClickListener(v -> {
                mapStyle = style[0];
                prefs.edit().putString(PREF_STYLE, mapStyle).apply();
                applyMapStyle();
                dialog.dismiss();
            });
            card.addView(row);
        }

        TextView showTitle = tv("Show on map", 13, UiTheme.TEXT_DIM, false);
        showTitle.setPadding(0, dp(14), 0, dp(4));
        card.addView(showTitle);

        SwitchMaterial busSwitch = new SwitchMaterial(this);
        busSwitch.setText("Live buses");
        busSwitch.setTextColor(UiTheme.WHITE);
        busSwitch.setChecked(showBuses);
        busSwitch.setOnCheckedChangeListener((b, checked) -> {
            showBuses = checked;
            prefs.edit().putBoolean(PREF_SHOW_BUSES, checked).apply();
            applyVisibility();
        });
        card.addView(busSwitch);

        SwitchMaterial stopSwitch = new SwitchMaterial(this);
        stopSwitch.setText("Bus stops");
        stopSwitch.setTextColor(UiTheme.WHITE);
        stopSwitch.setChecked(showStops);
        stopSwitch.setOnCheckedChangeListener((b, checked) -> {
            showStops = checked;
            prefs.edit().putBoolean(PREF_SHOW_STOPS, checked).apply();
            applyVisibility();
        });
        card.addView(stopSwitch);

        dialog.show();
    }

    private LinearLayout sheetCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(UiTheme.pill(this, Color.parseColor("#0D1424"), Color.parseColor("#24304F"), 1f, 24));
        card.setPadding(dp(20), dp(20), dp(20), dp(20));
        return card;
    }

    private BottomSheetDialog sheetDialog(LinearLayout card) {
        FrameLayout wrapper = new FrameLayout(this);
        wrapper.setPadding(dp(12), 0, dp(12), dp(16));
        wrapper.addView(card, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        dialog.setContentView(wrapper);
        View parent = (View) wrapper.getParent();
        if (parent != null) {
            parent.setBackgroundColor(Color.TRANSPARENT);
        }
        return dialog;
    }

    // ------------------------------------------------------------------ live buses

    private void onBusPosition(Intent intent) {
        String id = intent.getStringExtra(BusPosition.EXTRA_ID);
        double lat = intent.getDoubleExtra(BusPosition.EXTRA_LATITUDE, Double.NaN);
        double lon = intent.getDoubleExtra(BusPosition.EXTRA_LONGITUDE, Double.NaN);
        if (id == null || Double.isNaN(lat) || Double.isNaN(lon)) {
            return;
        }
        float bearing = intent.getFloatExtra(BusPosition.EXTRA_BEARING, Float.NaN);
        Bus existingBus = buses.get(id);
        if ((Float.isNaN(bearing) || bearing < 0f) && existingBus != null && existingBus.pos != null) {
            double d = meters(existingBus.pos.latitude, existingBus.pos.longitude, lat, lon);
            if (d >= 15.0) {
                bearing = (float) StopDepartures.bearingBetween(existingBus.pos.latitude, existingBus.pos.longitude, lat, lon);
            } else if (existingBus.pos.bearing >= 0f) {
                bearing = existingBus.pos.bearing;
            }
        }
        BusPosition pos = new BusPosition(id,
                nz(intent.getStringExtra(BusPosition.EXTRA_LINE_NAME)),
                nz(intent.getStringExtra(BusPosition.EXTRA_LINE_REF)),
                tidyName(intent.getStringExtra(BusPosition.EXTRA_DESTINATION_NAME)),
                nz(intent.getStringExtra(BusPosition.EXTRA_EXPECTED_ARRIVAL_TIME)),
                lat, lon,
                bearing,
                nz(intent.getStringExtra(BusPosition.EXTRA_RECORDED_AT)),
                nz(intent.getStringExtra(BusPosition.EXTRA_OCCUPANCY)),
                nz(intent.getStringExtra(BusPosition.EXTRA_OPERATOR)));

        long now = System.currentTimeMillis();
        Bus bus = buses.get(id);
        if (bus == null) {
            bus = new Bus();
            buses.put(id, bus);
        } else if (bus.pos != null) {
            bus.speedKph = VehicleSpeed.estimate(
                    bus.pos.latitude, bus.pos.longitude, ArrivalAlerts.parseIso(bus.pos.recordedAt),
                    lat, lon, ArrivalAlerts.parseIso(pos.recordedAt), bus.speedKph, now);
            long previousRecorded = FixAge.parse(bus.pos.recordedAt);
            long recorded = FixAge.parse(pos.recordedAt);
            bus.fixIntervalMs = previousRecorded > 0 && recorded > previousRecorded
                    ? recorded - previousRecorded
                    : Math.max(0L, now - bus.receivedMs);
        }
        bus.pos = pos;
        bus.receivedMs = now;

        boolean isNewMarker = bus.marker == null;
        if (bus.marker == null) {
            final String busId = id;
            Marker marker = new Marker(map);
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
            marker.setInfoWindow(null);
            marker.setOnMarkerClickListener((m, mv) -> {
                showBus(busId);
                return true;
            });
            map.getOverlays().add(marker);
            bus.marker = marker;
        }
        bus.marker.setIcon(busIcon(pos.lineName, pos.occupancy, pos.bearing));
        bus.marker.setEnabled(isBusVisible(pos));
        glideMarkerTo(bus, lat, lon, isNewMarker);
        scheduleRefresh();
    }

    private void glideMarkerTo(Bus bus, double lat, double lon, boolean firstFix) {
        if (bus.marker == null || map == null) {
            return;
        }
        cancelGlide(bus);
        GeoPoint from = bus.marker.getPosition();
        if (firstFix || from == null) {
            bus.marker.setPosition(new GeoPoint(lat, lon));
            return;
        }
        final double fromLat = from.getLatitude();
        final double fromLon = from.getLongitude();
        double distance = meters(fromLat, fromLon, lat, lon);
        if (distance < GLIDE_MIN_METERS || distance > GLIDE_SNAP_METERS) {
            bus.marker.setPosition(new GeoPoint(lat, lon));
            return;
        }
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(MarkerGlide.durationMs(distance, bus.speedKph, bus.fixIntervalMs));
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(animation -> {
            float t = (float) animation.getAnimatedValue();
            bus.marker.setPosition(new GeoPoint(
                    fromLat + (lat - fromLat) * t,
                    fromLon + (lon - fromLon) * t));
            invalidateMapForAnimation();
        });
        bus.glide = animator;
        animator.start();
    }

    private void invalidateMapForAnimation() {
        if (map == null || mapInvalidatePosted) {
            return;
        }
        mapInvalidatePosted = true;
        map.postOnAnimation(() -> {
            mapInvalidatePosted = false;
            if (map != null) {
                map.invalidate();
            }
        });
    }

    private void cancelGlide(Bus bus) {
        if (bus.glide != null) {
            bus.glide.cancel();
            bus.glide = null;
        }
    }

    private void cancelAllGlides() {
        for (Bus bus : buses.values()) {
            cancelGlide(bus);
        }
    }

    private void scheduleRefresh() {
        if (refreshPending) {
            return;
        }
        refreshPending = true;
        handler.postDelayed(() -> {
            refreshPending = false;
            pruneStale();
            updateCountPill();
            rebuildChips();
            if (followId != null) {
                Bus followed = buses.get(followId);
                if (followed != null && followed.pos != null) {
                    map.getController().animateTo(new GeoPoint(followed.pos.latitude, followed.pos.longitude));
                }
            }
            map.invalidate();
            evaluateArrivalAlerts();
            long now = System.currentTimeMillis();
            if (currentTab != TAB_MAP && currentTab != TAB_SEARCH && now - lastListRebuildMs > 4000) {
                lastListRebuildMs = now;
                rebuildCurrentTab();
            }
        }, 700);
    }

    private void pruneStale() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Bus>> it = buses.entrySet().iterator();
        while (it.hasNext()) {
            Bus bus = it.next().getValue();
            if (now - bus.receivedMs > STALE_BUS_MS) {
                cancelGlide(bus);
                if (bus.marker != null) {
                    map.getOverlays().remove(bus.marker);
                }
                it.remove();
            }
        }
    }

    private void beginSweep() {
        sweepStartMs = System.currentTimeMillis();
        sweepPending = true;
    }

    private void endSweep() {
        if (!sweepPending) {
            return;
        }
        sweepPending = false;
        boolean changed = false;
        Iterator<Map.Entry<String, Bus>> it = buses.entrySet().iterator();
        while (it.hasNext()) {
            Bus bus = it.next().getValue();
            if (bus.receivedMs < sweepStartMs) {
                cancelGlide(bus);
                if (bus.marker != null) {
                    map.getOverlays().remove(bus.marker);
                }
                it.remove();
                changed = true;
            }
        }
        if (!changed) {
            renderStopBoard();
            return;
        }
        if (followId != null && !buses.containsKey(followId)) {
            stopFollowing();
        }
        updateCountPill();
        rebuildChips();
        map.invalidate();
        rebuildCurrentTab();
        renderStopBoard();
    }

    private boolean isBusVisible(BusPosition pos) {
        return showBuses && (routeFilter == null || routeFilter.equalsIgnoreCase(pos.lineName));
    }

    private boolean shouldShowStopsOnMap() {
        return showStops && (map == null || map.getZoomLevelDouble() >= MIN_STOP_ZOOM);
    }

    private void applyVisibility() {
        for (Bus bus : buses.values()) {
            if (bus.marker != null && bus.pos != null) {
                bus.marker.setEnabled(isBusVisible(bus.pos));
            }
        }
        boolean stopsVisible = shouldShowStopsOnMap();
        for (Stop stop : stops) {
            if (stop.marker != null) {
                stop.marker.setEnabled(stopsVisible);
            }
        }
        map.invalidate();
    }

    private void setStatus(String message) {
        lastStatus = message == null ? "" : message;
        if (statusText != null) {
            statusText.setText(lastStatus.isEmpty() ? "Live vehicle tracking" : lastStatus);
        }
        if (currentTab == TAB_ACCOUNT) {
            rebuildAccount();
        }
    }

    private void updateCountPill() {
        if (countPill == null) {
            return;
        }
        if (TextUtils.isEmpty(BuildConfig.BODS_API_KEY)) {
            countPill.setText("Live off");
            countPill.setTextColor(UiTheme.AMBER);
        } else {
            countPill.setText(buses.size() + " live");
            countPill.setTextColor(UiTheme.CYAN);
        }
    }

    private void rebuildChips() {
        Map<String, Integer> counts = new HashMap<>();
        for (Bus bus : buses.values()) {
            if (bus.pos != null && !bus.pos.lineName.isEmpty()) {
                Integer n = counts.get(bus.pos.lineName);
                counts.put(bus.pos.lineName, n == null ? 1 : n + 1);
            }
        }
        List<String> routes = new ArrayList<>(counts.keySet());
        final Map<String, Integer> countsFinal = counts;
        Collections.sort(routes, (a, b) -> {
            int diff = countsFinal.get(b) - countsFinal.get(a);
            return diff != 0 ? diff : compareRoutes(a, b);
        });
        if (routes.size() > 12) {
            routes = new ArrayList<>(routes.subList(0, 12));
        }
        if (routeFilter != null && !routes.contains(routeFilter)) {
            routes.add(0, routeFilter);
        }
        String signature = routeFilter + "|" + routes;
        if (signature.equals(lastChipSignature)) {
            return;
        }
        lastChipSignature = signature;
        chipRow.removeAllViews();
        chipRow.addView(chip("All", routeFilter == null, null));
        for (String route : routes) {
            chipRow.addView(chip(route, route.equalsIgnoreCase(routeFilter), route));
        }
    }

    private View chip(String label, boolean selected, String route) {
        TextView chip = UiTheme.pillText(this, label,
                selected ? UiTheme.INK : UiTheme.WHITE,
                selected ? UiTheme.CYAN : UiTheme.withAlpha(UiTheme.INK, 225),
                selected ? UiTheme.CYAN : UiTheme.BLUE);
        chip.setOnClickListener(v -> {
            routeFilter = route;
            lastChipSignature = "";
            applyVisibility();
            rebuildChips();
            if (route == null) {
                clearRouteLine();
            } else {
                showRouteLine(route);
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        chip.setLayoutParams(lp);
        return chip;
    }

    private void filterToRoute(String route) {
        routeFilter = route;
        lastChipSignature = "";
        applyVisibility();
        rebuildChips();
        setTab(TAB_MAP);
        List<GeoPoint> points = new ArrayList<>();
        for (Bus bus : buses.values()) {
            if (bus.pos != null && route.equalsIgnoreCase(bus.pos.lineName)) {
                points.add(new GeoPoint(bus.pos.latitude, bus.pos.longitude));
            }
        }
        if (points.size() == 1) {
            map.getController().setZoom(16.0);
            map.getController().animateTo(points.get(0));
        } else if (points.size() > 1) {
            BoundingBox box = BoundingBox.fromGeoPoints(points).increaseByScale(1.4f);
            map.post(() -> map.zoomToBoundingBox(box, true));
        }
        showRouteLine(route);
    }

    // ------------------------------------------------------------------ route line overlays

    private void showRouteLine(String route) {
        double lat = map != null && map.getMapCenter() != null ? map.getMapCenter().getLatitude() : 51.5;
        double lon = map != null && map.getMapCenter() != null ? map.getMapCenter().getLongitude() : 0.0;
        showRouteLine(route, lat, lon);
    }

    private void showRouteLine(String route, double lat, double lon) {
        if (map == null || TextUtils.isEmpty(route)) {
            return;
        }
        if (route.equalsIgnoreCase(routeLineFor) && !routeLineOverlays.isEmpty()) {
            return;
        }
        routeLineFor = route;
        List<List<GeoPoint>> cached = RouteShapes.cached(route);
        if (cached != null) {
            drawRouteLine(cached);
            return;
        }
        if (route.equalsIgnoreCase(routeLineLoadingFor)) {
            return;
        }
        routeLineLoadingFor = route;
        final String requested = route;
        final double queryLat = lat;
        final double queryLon = lon;
        io.execute(() -> {
            List<List<GeoPoint>> lines = RouteShapes.load(this, requested, queryLat, queryLon);
            handler.post(() -> {
                if (requested.equalsIgnoreCase(routeLineLoadingFor)) {
                    routeLineLoadingFor = null;
                }
                if (!requested.equalsIgnoreCase(routeLineFor)) {
                    return;
                }
                if (lines == null) {
                    Toast.makeText(this, "Couldn't load the route line for " + requested,
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                drawRouteLine(lines);
                if (lines.isEmpty()) {
                    Toast.makeText(this, "No mapped route line for " + requested + " in OpenStreetMap yet",
                            Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void drawRouteLine(List<List<GeoPoint>> lines) {
        clearRouteLineOverlays();
        if (map == null || lines.isEmpty()) {
            if (map != null) {
                map.invalidate();
            }
            return;
        }
        List<Polyline> overlays = new ArrayList<>();
        for (List<GeoPoint> points : lines) {
            Polyline casing = new Polyline(map);
            casing.setPoints(points);
            casing.setColor(UiTheme.withAlpha(UiTheme.INK, 200));
            casing.setWidth(dp(9));
            overlays.add(casing);
        }
        for (List<GeoPoint> points : lines) {
            Polyline line = new Polyline(map);
            line.setPoints(points);
            line.setColor(UiTheme.CYAN);
            line.setWidth(dp(4.5f));
            overlays.add(line);
        }
        routeLineOverlays.addAll(overlays);
        map.getOverlays().addAll(0, overlays);
        updateRouteLinePill();
        map.invalidate();
    }

    private void clearRouteLine() {
        routeLineFor = null;
        routeLineLoadingFor = null;
        clearRouteLineOverlays();
        if (map != null) {
            map.invalidate();
        }
    }

    private void clearRouteLineOverlays() {
        if (map != null && !routeLineOverlays.isEmpty()) {
            map.getOverlays().removeAll(routeLineOverlays);
        }
        routeLineOverlays.clear();
        updateRouteLinePill();
    }

    private void updateRouteLinePill() {
        if (routeLinePill == null) {
            return;
        }
        if (routeLineOverlays.isEmpty() || TextUtils.isEmpty(routeLineFor)) {
            routeLinePill.setVisibility(View.GONE);
        } else {
            routeLinePill.setText("Route " + routeLineFor + "  \u2715");
            routeLinePill.setVisibility(View.VISIBLE);
        }
    }

    private void sendRouteLinesToBack() {
        if (map == null || routeLineOverlays.isEmpty()) {
            return;
        }
        map.getOverlays().removeAll(routeLineOverlays);
        map.getOverlays().addAll(0, routeLineOverlays);
    }

    // ------------------------------------------------------------------ icons

    /**
     * Draws a route badge pill with a directional heading arrow along the outer border when
     * {@code bearing} is known, quantized to 15-degree steps so the icon cache stays compact.
     */
    private Drawable busIcon(String route, String occupancy, float bearing) {
        boolean hasBearing = !Float.isNaN(bearing) && bearing >= 0f;
        int step = hasBearing ? (((Math.round(bearing / 15f) * 15) % 360 + 360) % 360) : -1;
        String key = route + "|" + occupancy + "|" + step;
        BitmapDrawable cached = iconCache.get(key);
        if (cached != null) {
            return cached;
        }
        int fill = UiTheme.occupancyColor(occupancy);
        int pillW = dp(48);
        int pillH = dp(30);
        int pad = dp(8);
        int w = pillW + pad * 2;
        int h = pillH + pad * 2;
        float cx = w / 2f;
        float cy = h / 2f;

        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        // Draw directional heading pointer attached to the edge of the pill
        if (hasBearing) {
            double rad = Math.toRadians(step);
            float dx = (float) Math.sin(rad);
            float dy = (float) -Math.cos(rad);
            float px = -dy;
            float py = dx;

            float halfW = pillW / 2f;
            float halfH = pillH / 2f;
            float sx = Math.abs(dx) > 1e-4f ? halfW / Math.abs(dx) : Float.MAX_VALUE;
            float sy = Math.abs(dy) > 1e-4f ? halfH / Math.abs(dy) : Float.MAX_VALUE;
            float edge = Math.min(sx, sy);
            // Pull slightly inward at the 4 rounded corners so the arrow base sits flush
            if (Math.abs(sx - sy) < dp(6)) {
                edge -= dp(2f);
            }

            float tipDist = edge + dp(6.5f);
            float baseDist = edge - dp(2f);
            float wing = dp(5.5f);

            Path outerArrow = new Path();
            outerArrow.moveTo(cx + dx * (tipDist + dp(1.5f)), cy + dy * (tipDist + dp(1.5f)));
            outerArrow.lineTo(cx + dx * baseDist + px * (wing + dp(1.5f)),
                    cy + dy * baseDist + py * (wing + dp(1.5f)));
            outerArrow.lineTo(cx + dx * baseDist - px * (wing + dp(1.5f)),
                    cy + dy * baseDist - py * (wing + dp(1.5f)));
            outerArrow.close();
            paint.setColor(Color.WHITE);
            canvas.drawPath(outerArrow, paint);

            Path innerArrow = new Path();
            innerArrow.moveTo(cx + dx * tipDist, cy + dy * tipDist);
            innerArrow.lineTo(cx + dx * baseDist + px * wing, cy + dy * baseDist + py * wing);
            innerArrow.lineTo(cx + dx * baseDist - px * wing, cy + dy * baseDist - py * wing);
            innerArrow.close();
            paint.setColor(fill);
            canvas.drawPath(innerArrow, paint);
        }

        RectF outerRect = new RectF(pad, pad, pad + pillW, pad + pillH);
        paint.setColor(Color.WHITE);
        canvas.drawRoundRect(outerRect, dp(10), dp(10), paint);

        RectF innerRect = new RectF(pad + dp(2), pad + dp(2), pad + pillW - dp(2), pad + pillH - dp(2));
        paint.setColor(fill);
        canvas.drawRoundRect(innerRect, dp(8), dp(8), paint);

        double luminance = Color.red(fill) * 0.299 + Color.green(fill) * 0.587 + Color.blue(fill) * 0.114;
        paint.setColor(luminance > 150 ? UiTheme.INK : Color.WHITE);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextAlign(Paint.Align.CENTER);
        float size = dp(14);
        paint.setTextSize(size);
        float maxWidth = pillW - dp(10);
        while (paint.measureText(route) > maxWidth && size > dp(8)) {
            size -= 1;
            paint.setTextSize(size);
        }
        float baseline = cy - (paint.descent() + paint.ascent()) / 2f;
        canvas.drawText(route, cx, baseline, paint);

        BitmapDrawable drawable = new BitmapDrawable(getResources(), bitmap);
        iconCache.put(key, drawable);
        return drawable;
    }

    private Drawable stopIcon() {
        if (stopIcon == null) {
            int size = dp(18);
            Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(UiTheme.CYAN);
            canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint);
            paint.setColor(UiTheme.INK);
            canvas.drawCircle(size / 2f, size / 2f, size / 2f - dp(3), paint);
            paint.setColor(UiTheme.CYAN);
            canvas.drawCircle(size / 2f, size / 2f, dp(2), paint);
            stopIcon = new BitmapDrawable(getResources(), bitmap);
        }
        return stopIcon;
    }

    private Drawable userIcon() {
        if (userIcon == null) {
            int size = dp(24);
            Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(UiTheme.withAlpha(UiTheme.BLUE, 70));
            canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint);
            paint.setColor(Color.WHITE);
            canvas.drawCircle(size / 2f, size / 2f, dp(7), paint);
            paint.setColor(UiTheme.BLUE);
            canvas.drawCircle(size / 2f, size / 2f, dp(5), paint);
            userIcon = new BitmapDrawable(getResources(), bitmap);
        }
        return userIcon;
    }

    // ------------------------------------------------------------------ location

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void startLocation() {
        if (!hasLocationPermission()) {
            ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
            return;
        }
        try {
            for (String provider : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                if (locationManager.isProviderEnabled(provider)) {
                    Location last = locationManager.getLastKnownLocation(provider);
                    if (last != null && (userLocation == null || last.getTime() > userLocation.getTime())) {
                        locationListener.onLocationChanged(last);
                    }
                    locationManager.requestLocationUpdates(provider, 5000, 10, locationListener);
                }
            }
        } catch (SecurityException | IllegalArgumentException ignored) {
            // provider unavailable or permission revoked
        }
    }

    private void onLocatePressed() {
        stopFollowing();
        if (!hasLocationPermission()) {
            startLocation();
            return;
        }
        if (userLocation != null) {
            map.getController().setZoom(16.0);
            map.getController().animateTo(new GeoPoint(userLocation.getLatitude(), userLocation.getLongitude()));
        } else {
            Toast.makeText(this, "Finding your location\u2026", Toast.LENGTH_SHORT).show();
            startLocation();
        }
    }

    private void updateUserMarker() {
        if (userLocation == null) {
            return;
        }
        if (userMarker == null) {
            userMarker = new Marker(map);
            userMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
            userMarker.setInfoWindow(null);
            userMarker.setIcon(userIcon());
            map.getOverlays().add(userMarker);
        }
        userMarker.setPosition(new GeoPoint(userLocation.getLatitude(), userLocation.getLongitude()));
        map.invalidate();
    }

    private void onRefreshPressed() {
        Intent refresh = new Intent(this, BusTrackingService.class);
        refresh.setAction(BusTrackingService.ACTION_REFRESH_NOW);
        refresh.putExtra(BusTrackingService.EXTRA_BOUNDING_BOX, getMapBoundingBoxString());
        startService(refresh);
        maybeFetchStops(map.getMapCenter().getLatitude(), map.getMapCenter().getLongitude(), true);
        Toast.makeText(this, "Refreshing\u2026", Toast.LENGTH_SHORT).show();
    }

    private double[] refPoint() {
        if (userLocation != null) {
            return new double[]{userLocation.getLatitude(), userLocation.getLongitude()};
        }
        return new double[]{map.getMapCenter().getLatitude(), map.getMapCenter().getLongitude()};
    }

    private static double meters(double lat1, double lon1, double lat2, double lon2) {
        float[] result = new float[1];
        Location.distanceBetween(lat1, lon1, lat2, lon2, result);
        return result[0];
    }

    private static String formatDistance(double meters) {
        if (meters < 1000) {
            return Math.round(meters / 10.0) * 10 + " m";
        }
        return String.format(Locale.UK, "%.1f km", meters / 1000.0);
    }

    // ------------------------------------------------------------------ bus stops (OpenStreetMap)

    private void maybeFetchStops(double lat, double lon, boolean force) {
        if (fetchingStops) {
            return;
        }
        if (!force && !Double.isNaN(lastFetchLat) && meters(lastFetchLat, lastFetchLon, lat, lon) < 700) {
            return;
        }
        fetchingStops = true;
        lastFetchLat = lat;
        lastFetchLon = lon;
        io.execute(() -> {
            try {
                List<Stop> result = loadStops(lat, lon);
                handler.post(() -> {
                    fetchingStops = false;
                    stopsError = null;
                    replaceStops(result);
                });
            } catch (Exception e) {
                handler.post(() -> {
                    fetchingStops = false;
                    lastFetchLat = Double.NaN;
                    stopsError = "Couldn't load bus stops. Check your connection and tap refresh.";
                    if (currentTab == TAB_NEARBY) {
                        rebuildNearby();
                    }
                });
            }
        });
    }

    private static final Map<String, List<Stop>> STOP_MEMORY_CACHE = new HashMap<>();
    private static final long STOP_CACHE_TTL_MS = 24L * 60 * 60 * 1000;

    private static String stopCacheKey(double lat, double lon) {
        return String.format(Locale.UK, "%.2f_%.2f-v2", Math.round(lat * 100) / 100.0, Math.round(lon * 100) / 100.0);
    }

    private List<Stop> loadStops(double lat, double lon) throws Exception {
        String key = stopCacheKey(lat, lon);
        List<Stop> memory = STOP_MEMORY_CACHE.get(key);
        if (memory != null) {
            return memory;
        }
        List<Stop> disk = readStopCache(lat, lon);
        if (disk != null) {
            STOP_MEMORY_CACHE.put(key, disk);
            return disk;
        }

        String query = "[out:json][timeout:20];node(around:1200," + lat + "," + lon
                + ")[highway=bus_stop];out body 150;";
        JSONArray elements = new JSONObject(Overpass.post(query)).optJSONArray("elements");
        List<Stop> result = new ArrayList<>();
        if (elements != null) {
            for (int i = 0; i < elements.length(); i++) {
                JSONObject element = elements.getJSONObject(i);
                JSONObject tags = element.optJSONObject("tags");
                Stop stop = new Stop();
                stop.id = element.optLong("id", 0L);
                stop.lat = element.getDouble("lat");
                stop.lon = element.getDouble("lon");
                stop.name = tags == null ? "" : tags.optString("name", "");
                if (stop.name.isEmpty()) {
                    stop.name = "Bus stop";
                }
                stop.routes = tags == null ? "" : tags.optString("route_ref", "").replace(";", ", ");
                stop.atcoCode = atcoCodeOf(tags);
                result.add(stop);
            }
        }
        STOP_MEMORY_CACHE.put(key, result);
        writeStopCache(lat, lon, result);
        return result;
    }

    private File stopCacheFile(double lat, double lon) {
        File directory = new File(getCacheDir(), "bus_stops");
        if (!directory.exists() && !directory.mkdirs()) {
            return null;
        }
        return new File(directory, stopCacheKey(lat, lon) + ".json");
    }

    private List<Stop> readStopCache(double lat, double lon) {
        File file = stopCacheFile(lat, lon);
        if (file == null || !file.isFile() || System.currentTimeMillis() - file.lastModified() > STOP_CACHE_TTL_MS) {
            return null;
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream in = new FileInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    bytes.write(buffer, 0, read);
                }
            }
            JSONArray elements = new JSONArray(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            List<Stop> result = new ArrayList<>();
            for (int i = 0; i < elements.length(); i++) {
                JSONObject obj = elements.getJSONObject(i);
                Stop stop = new Stop();
                stop.id = obj.optLong("id", 0L);
                stop.lat = obj.getDouble("lat");
                stop.lon = obj.getDouble("lon");
                stop.name = obj.optString("name", "");
                stop.routes = obj.optString("routes", "");
                stop.atcoCode = obj.optString("atco", "");
                result.add(stop);
            }
            return result;
        } catch (Exception e) {
            return null;
        }
    }

    private void writeStopCache(double lat, double lon, List<Stop> stops) {
        File file = stopCacheFile(lat, lon);
        if (file == null) {
            return;
        }
        try {
            JSONArray array = new JSONArray();
            for (Stop stop : stops) {
                JSONObject obj = new JSONObject();
                obj.put("id", stop.id);
                obj.put("lat", stop.lat);
                obj.put("lon", stop.lon);
                obj.put("name", stop.name);
                obj.put("routes", stop.routes);
                obj.put("atco", stop.atcoCode);
                array.put(obj);
            }
            try (OutputStream out = new FileOutputStream(file)) {
                out.write(array.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            Log.w("MainActivity", "Could not cache bus stops", e);
        }
    }

    private void replaceStops(List<Stop> fresh) {
        for (Stop old : stops) {
            if (old.marker != null) {
                map.getOverlays().remove(old.marker);
            }
        }
        stops.clear();
        stops.addAll(fresh);
        boolean stopsVisible = shouldShowStopsOnMap();
        for (Stop stop : stops) {
            final Stop target = stop;
            Marker marker = new Marker(map);
            marker.setPosition(new GeoPoint(stop.lat, stop.lon));
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
            marker.setInfoWindow(null);
            marker.setIcon(stopIcon());
            marker.setEnabled(stopsVisible);
            marker.setOnMarkerClickListener((m, mv) -> {
                showStopSheet(target);
                return true;
            });
            stop.marker = marker;
            map.getOverlays().add(0, marker);
        }
        if (userMarker != null) {
            map.getOverlays().remove(userMarker);
            map.getOverlays().add(userMarker);
        }
        sendRouteLinesToBack();
        map.invalidate();
        rebuildCurrentTab();
    }

    // ------------------------------------------------------------------ sheets

    private void showBus(String id) {
        Bus bus = buses.get(id);
        if (bus == null || bus.pos == null) {
            return;
        }
        BusPosition p = bus.pos;
        String distance = "";
        if (userLocation != null) {
            distance = formatDistance(meters(userLocation.getLatitude(), userLocation.getLongitude(),
                    p.latitude, p.longitude)) + " away";
        }
        long fallbackSeconds = Math.max(0, (System.currentTimeMillis() - bus.receivedMs) / 1000);
        String lastSeen = FixAge.describe(
                FixAge.ageSeconds(p.recordedAt, System.currentTimeMillis(), fallbackSeconds));
        int etaMinutes = ArrivalAlerts.etaMinutesFrom(p.expectedArrivalTime);
        String vehicleId = p.id.startsWith("bus:") ? "" : p.id.replace('_', ' ');
        BusSnapshot snapshot = new BusSnapshot(p.id, p.lineName, p.lineRef, p.destinationName, p.occupancy,
                vehicleId, lastSeen, OperatorNames.display(p.operatorName), distance, p.latitude, p.longitude,
                p.bearing, bus.speedKph, etaMinutes, "");
        showRouteLine(p.lineName, p.latitude, p.longitude);
        BusDetailsSheet.show(this, snapshot, this);
    }

    private void showStopSheet(Stop stop) {
        LinearLayout card = sheetCard();

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(tv(stop.name, 20, UiTheme.WHITE, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        boolean saved = StopFavorites.isFavorite(this, stop.id, stop.lat, stop.lon);
        TextView star = UiTheme.pillText(this, saved ? "\u2605 Saved" : "\u2606 Save",
                saved ? UiTheme.INK : UiTheme.WHITE,
                saved ? UiTheme.CYAN : UiTheme.INK_LIGHT,
                UiTheme.CYAN);
        star.setOnClickListener(v -> {
            StopFavorites.toggle(this, new StopFavorites.FavoriteStop(
                    stop.id, stop.name, stop.routes, stop.atcoCode, stop.lat, stop.lon));
            boolean nowSaved = StopFavorites.isFavorite(this, stop.id, stop.lat, stop.lon);
            star.setText(nowSaved ? "\u2605 Saved" : "\u2606 Save");
            star.setTextColor(nowSaved ? UiTheme.INK : UiTheme.WHITE);
            star.setBackground(UiTheme.ripple(UiTheme.pill(this,
                    nowSaved ? UiTheme.CYAN : UiTheme.INK_LIGHT, UiTheme.CYAN, 1f, 20f)));
            Toast.makeText(this, nowSaved
                    ? stop.name + " saved to Favorites"
                    : stop.name + " removed from Favorites", Toast.LENGTH_SHORT).show();
            if (currentTab == TAB_FAVORITES) {
                rebuildFavorites();
            }
        });
        header.addView(star);
        card.addView(header);
        if (userLocation != null) {
            card.addView(tv(formatDistance(meters(userLocation.getLatitude(), userLocation.getLongitude(),
                    stop.lat, stop.lon)) + " from you", 13, UiTheme.TEXT_DIM, false));
        }

        // Interactive route filter chips (filters both live departures and scheduled timetable)
        HorizontalScrollView routeScroll = new HorizontalScrollView(this);
        routeScroll.setHorizontalScrollBarEnabled(false);
        stopRouteFilterRow = new LinearLayout(this);
        stopRouteFilterRow.setOrientation(LinearLayout.HORIZONTAL);
        stopRouteFilterRow.setPadding(0, dp(10), 0, dp(2));
        routeScroll.addView(stopRouteFilterRow);
        card.addView(routeScroll);

        stopBoardTitle = tv("Next departures", 13, UiTheme.TEXT_DIM, false);
        stopBoardTitle.setPadding(0, dp(14), 0, dp(6));
        card.addView(stopBoardTitle);

        stopBoardRows = new LinearLayout(this);
        stopBoardRows.setOrientation(LinearLayout.VERTICAL);
        card.addView(stopBoardRows);

        stopBoardNote = tv("", 12, UiTheme.TEXT_DIM, false);
        stopBoardNote.setPadding(0, dp(6), 0, 0);
        card.addView(stopBoardNote);

        TextView timetableTitle = tv("Timetable", 13, UiTheme.TEXT_DIM, false);
        timetableTitle.setPadding(0, dp(16), 0, dp(6));
        card.addView(timetableTitle);

        // Day-of-week selector (Today / Mon-Fri / Sat / Sun)
        stopTimetableDayRow = new LinearLayout(this);
        stopTimetableDayRow.setOrientation(LinearLayout.HORIZONTAL);
        stopTimetableDayRow.setPadding(0, 0, 0, dp(8));
        stopTimetableDayRow.setVisibility(View.GONE);
        card.addView(stopTimetableDayRow);

        stopTimetableRows = new LinearLayout(this);
        stopTimetableRows.setOrientation(LinearLayout.VERTICAL);
        card.addView(stopTimetableRows);

        stopTimetableButton = UiTheme.pillText(this, "Load timetable", UiTheme.WHITE,
                UiTheme.INK_LIGHT, UiTheme.CYAN);
        stopTimetableButton.setOnClickListener(v -> loadStopTimetable(stop));
        LinearLayout.LayoutParams timetableButtonLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        timetableButtonLp.topMargin = dp(10);
        card.addView(stopTimetableButton, timetableButtonLp);

        stopTimetableNote = tv("", 12, UiTheme.TEXT_DIM, false);
        stopTimetableNote.setPadding(0, dp(6), 0, 0);
        card.addView(stopTimetableNote);

        final BottomSheetDialog dialog = sheetDialog(card);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(14), 0, 0);
        TextView walk = UiTheme.pillText(this, "Walk here", UiTheme.INK, UiTheme.CYAN, UiTheme.CYAN);
        walk.setOnClickListener(v -> {
            dialog.dismiss();
            onNavigateToBus(stop.lat, stop.lon);
        });
        TextView notify = UiTheme.pillText(this, "Notify me", UiTheme.WHITE, UiTheme.INK_LIGHT, UiTheme.BLUE);
        notify.setOnClickListener(v -> {
            dialog.dismiss();
            openAlertSheet(timetableRoutes(stop), stop.name, stop.lat, stop.lon);
        });
        TextView show = UiTheme.pillText(this, "Show on map", UiTheme.WHITE, UiTheme.INK_LIGHT, UiTheme.BLUE);
        show.setOnClickListener(v -> {
            dialog.dismiss();
            onShowBusOnMap(stop.lat, stop.lon);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        actions.addView(walk, lp);
        LinearLayout.LayoutParams notifyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        notifyLp.rightMargin = dp(8);
        actions.addView(notify, notifyLp);
        actions.addView(show);
        card.addView(actions);

        stopBoardStop = stop;
        stopBoardDialog = dialog;
        stopSheetRouteFilter = null;
        stopTimetableDayMode = StopTimetables.MODE_TODAY;
        stopTimetableLoading = false;
        stopTimetableRequestId++;
        dialog.setOnDismissListener(d -> {
            stopBoardStop = null;
            stopBoardDialog = null;
            stopRouteFilterRow = null;
            stopSheetRouteFilter = null;
            stopBoardRows = null;
            stopBoardTitle = null;
            stopBoardNote = null;
            stopTimetableDayRow = null;
            stopTimetableRows = null;
            stopTimetableNote = null;
            stopTimetableButton = null;
            stopTimetableAll = null;
            stopTimetableLoading = false;
            stopTimetableRequestId++;
        });
        rebuildStopRouteChips(stop);
        renderStopBoard();
        showCachedTimetable(stop);
        dialog.show();
    }

    /** Builds interactive route filter pills at the top of the stop sheet. */
    private void rebuildStopRouteChips(Stop stop) {
        if (stopRouteFilterRow == null || stop == null) {
            return;
        }
        List<String> routes = new ArrayList<>();
        if (stop.routes != null && !stop.routes.trim().isEmpty()) {
            for (String r : stop.routes.split("[,;]")) {
                addRoute(routes, r);
            }
        }
        for (String r : StopTimetables.distinctLines(stopTimetableAll)) {
            addRoute(routes, r);
        }
        for (StopDepartures.Departure d : StopDepartures.board(liveVehicles(), stop.lat, stop.lon, stop.routes)) {
            addRoute(routes, d.route);
        }
        Collections.sort(routes, MainActivity::compareRoutes);

        stopRouteFilterRow.removeAllViews();
        if (routes.isEmpty()) {
            stopRouteFilterRow.setVisibility(View.GONE);
            return;
        }
        stopRouteFilterRow.setVisibility(View.VISIBLE);
        if (routes.size() == 1) {
            TextView only = tv("Route " + routes.get(0), 13, UiTheme.CYAN, true);
            stopRouteFilterRow.addView(only);
            return;
        }

        stopRouteFilterRow.addView(stopRouteChip(stop, "All routes", null));
        for (String route : routes) {
            stopRouteFilterRow.addView(stopRouteChip(stop, route, route));
        }
    }

    private View stopRouteChip(Stop stop, String label, String routeValue) {
        boolean selected = (routeValue == null && stopSheetRouteFilter == null)
                || (routeValue != null && routeValue.equalsIgnoreCase(stopSheetRouteFilter));
        TextView chip = UiTheme.pillText(this, label,
                selected ? UiTheme.INK : UiTheme.WHITE,
                selected ? UiTheme.CYAN : UiTheme.INK_LIGHT,
                selected ? UiTheme.CYAN : UiTheme.BLUE);
        chip.setOnClickListener(v -> {
            stopSheetRouteFilter = routeValue;
            rebuildStopRouteChips(stop);
            renderStopBoard();
            renderStopTimetable();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(6);
        chip.setLayoutParams(lp);
        return chip;
    }

    private void rebuildTimetableDayChips() {
        if (stopTimetableDayRow == null) {
            return;
        }
        if (stopTimetableAll == null || stopTimetableAll.isEmpty() || stopTimetableLoading) {
            stopTimetableDayRow.setVisibility(View.GONE);
            return;
        }
        stopTimetableDayRow.setVisibility(View.VISIBLE);
        stopTimetableDayRow.removeAllViews();
        String[] labels = {"Today", "Mon\u2013Fri", "Sat", "Sun"};
        int[] modes = {
                StopTimetables.MODE_TODAY,
                StopTimetables.MODE_WEEKDAYS,
                StopTimetables.MODE_SATURDAY,
                StopTimetables.MODE_SUNDAY
        };
        for (int i = 0; i < labels.length; i++) {
            final int mode = modes[i];
            boolean selected = stopTimetableDayMode == mode;
            TextView pill = UiTheme.pillText(this, labels[i],
                    selected ? UiTheme.INK : UiTheme.WHITE,
                    selected ? UiTheme.CYAN : UiTheme.INK_LIGHT,
                    selected ? UiTheme.CYAN : UiTheme.BLUE);
            pill.setOnClickListener(v -> {
                stopTimetableDayMode = mode;
                rebuildTimetableDayChips();
                renderStopTimetable();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(6);
            pill.setLayoutParams(lp);
            stopTimetableDayRow.addView(pill);
        }
    }

    /**
     * Routes a timetable lookup should ask about: the stop's own OSM route_ref list first,
     * falling back to live buses actually serving/approaching this stop.
     */
    private List<String> timetableRoutes(Stop stop) {
        List<String> routes = new ArrayList<>();
        if (stop.routes != null && !stop.routes.trim().isEmpty()) {
            for (String route : stop.routes.split("[,;]")) {
                addRoute(routes, route);
            }
        }
        List<StopDepartures.Departure> inbound =
                StopDepartures.board(liveVehicles(), stop.lat, stop.lon, stop.routes);
        for (StopDepartures.Departure d : inbound) {
            addRoute(routes, d.route);
        }
        if (routes.isEmpty()) {
            for (String route : routesAtStop(stop)) {
                addRoute(routes, route);
            }
        }
        return routes;
    }

    private static void addRoute(List<String> routes, String route) {
        String trimmed = route == null ? "" : route.trim();
        if (!trimmed.isEmpty() && !routes.contains(trimmed)) {
            routes.add(trimmed);
        }
    }

    /**
     * Shows the stop's timetable when it is already cached, or automatically extracts it in the
     * background if the operator's dataset ZIP is already on disk.
     */
    private void showCachedTimetable(Stop stop) {
        if (stopTimetableRows == null || stopTimetableNote == null) {
            return;
        }
        stopTimetableRows.removeAllViews();
        stopTimetableAll = null;
        if (TextUtils.isEmpty(stop.atcoCode)) {
            if (stopTimetableButton != null) {
                stopTimetableButton.setVisibility(View.GONE);
            }
            if (stopTimetableDayRow != null) {
                stopTimetableDayRow.setVisibility(View.GONE);
            }
            stopTimetableNote.setText("OpenStreetMap has no NaPTAN code for this stop, so there is no "
                    + "published timetable to look up.");
            return;
        }
        if (stopTimetableButton != null) {
            stopTimetableButton.setVisibility(View.VISIBLE);
        }
        stopTimetableAll = StopTimetables.cached(this, stop.atcoCode);
        if (stopTimetableAll != null) {
            stopTimetableButton.setText("Refresh");
            rebuildStopRouteChips(stop);
            rebuildTimetableDayChips();
            renderStopTimetable();
            return;
        }

        stopTimetableButton.setText("Load timetable");
        stopTimetableNote.setText("Times registered by the operators for this stop.");

        // Auto-extract from any operator dataset ZIP already downloaded on disk (zero network cost)
        final int requestId = stopTimetableRequestId;
        final String atcoCode = stop.atcoCode;
        timetableIo.execute(() -> {
            final List<TxcTimetable.Departure> local =
                    StopTimetables.departuresFromDiskOnly(getApplicationContext(), atcoCode);
            if (local != null && !local.isEmpty()) {
                handler.post(() -> {
                    if (requestId != stopTimetableRequestId || stopTimetableRows == null) {
                        return;
                    }
                    stopTimetableAll = local;
                    if (stopTimetableButton != null) {
                        stopTimetableButton.setText("Refresh");
                    }
                    rebuildStopRouteChips(stop);
                    rebuildTimetableDayChips();
                    renderStopTimetable();
                });
            }
        });
    }

    /** Fetches the stop's published timetable on its own executor, isolated from Overpass calls. */
    private void loadStopTimetable(Stop stop) {
        if (stopTimetableLoading) {
            return;
        }
        if (TextUtils.isEmpty(stop.atcoCode)) {
            showCachedTimetable(stop);
            return;
        }
        stopTimetableLoading = true;
        final int requestId = ++stopTimetableRequestId;
        if (stopTimetableButton != null) {
            stopTimetableButton.setText("Loading\u2026");
        }
        if (stopTimetableNote != null) {
            stopTimetableNote.setText("Finding published timetables\u2026");
        }
        final String atcoCode = stop.atcoCode;
        final List<String> routes = timetableRoutes(stop);
        timetableIo.execute(() -> {
            try {
                final List<TxcTimetable.Departure> departures = StopTimetables.departures(this,
                        atcoCode, routes, message -> handler.post(() -> {
                            if (requestId == stopTimetableRequestId && stopTimetableNote != null) {
                                stopTimetableNote.setText(message);
                            }
                        }));
                handler.post(() -> {
                    if (requestId != stopTimetableRequestId) {
                        return;
                    }
                    stopTimetableLoading = false;
                    if (stopTimetableButton != null) {
                        stopTimetableButton.setText("Refresh");
                    }
                    if (stopTimetableRows == null) {
                        return;
                    }
                    stopTimetableAll = departures;
                    rebuildStopRouteChips(stop);
                    rebuildTimetableDayChips();
                    renderStopTimetable();
                });
            } catch (final Exception e) {
                handler.post(() -> {
                    if (requestId != stopTimetableRequestId) {
                        return;
                    }
                    stopTimetableLoading = false;
                    if (stopTimetableButton != null) {
                        stopTimetableButton.setText("Load timetable");
                    }
                    if (stopTimetableNote != null) {
                        stopTimetableNote.setText(e.getMessage() == null
                                ? "Could not load the timetable." : e.getMessage());
                    }
                });
            }
        });
    }

    /** Renders the scheduled departures for the active day mode and route filter. */
    private void renderStopTimetable() {
        if (stopTimetableRows == null || stopTimetableAll == null || stopTimetableLoading) {
            return;
        }
        stopTimetableRows.removeAllViews();
        rebuildTimetableDayChips();

        List<StopTimetables.ScheduledRow> rows = StopTimetables.select(
                stopTimetableAll, System.currentTimeMillis(), stopTimetableDayMode, stopSheetRouteFilter);
        if (rows.isEmpty()) {
            if (stopTimetableNote != null) {
                stopTimetableNote.setText(stopSheetRouteFilter != null
                        ? "No scheduled departures for route " + stopSheetRouteFilter + " on this day."
                        : "No scheduled departures found for this day.");
            }
            return;
        }
        boolean hasTomorrow = false;
        for (StopTimetables.ScheduledRow row : rows) {
            hasTomorrow |= row.tomorrow;
            stopTimetableRows.addView(timetableRow(row));
        }
        if (stopTimetableNote != null) {
            String suffix = hasTomorrow ? " (including tomorrow morning)." : ".";
            stopTimetableNote.setText("Scheduled times from the operators' published timetables" + suffix);
        }
    }

    private View timetableRow(StopTimetables.ScheduledRow item) {
        TxcTimetable.Departure departure = item.departure;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));

        String clockText = item.tomorrow ? "Tom " + departure.clock() : departure.clock();
        row.addView(tv(clockText, 15, item.tomorrow ? UiTheme.AMBER : UiTheme.CYAN, true));

        TextView line = tv(departure.line, 13, UiTheme.WHITE, true);
        line.setPadding(dp(14), 0, dp(14), 0);
        row.addView(line);

        row.addView(tv(departure.destination.isEmpty() ? "\u2014" : departure.destination,
                13, UiTheme.TEXT_DIM, false),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    /** The live fleet as the departure board sees it. */
    private List<StopDepartures.Vehicle> liveVehicles() {
        List<StopDepartures.Vehicle> vehicles = new ArrayList<>();
        for (Bus bus : buses.values()) {
            if (bus.pos == null) {
                continue;
            }
            vehicles.add(new StopDepartures.Vehicle(bus.pos.id, bus.pos.lineName,
                    bus.pos.destinationName, bus.pos.latitude, bus.pos.longitude,
                    bus.pos.bearing, bus.speedKph));
        }
        return vehicles;
    }

    private void renderStopBoard() {
        if (stopBoardRows == null || stopBoardTitle == null || stopBoardStop == null) {
            return;
        }
        Stop stop = stopBoardStop;
        stopBoardRows.removeAllViews();
        stopBoardNote.setText("");
        renderStopTimetable();

        List<StopDepartures.Vehicle> vehicles = liveVehicles();
        String effectiveRoutes = stopSheetRouteFilter != null ? stopSheetRouteFilter : stop.routes;
        List<StopDepartures.Departure> board =
                StopDepartures.board(vehicles, stop.lat, stop.lon, effectiveRoutes);
        boolean servingThisStop = !board.isEmpty();
        if (!servingThisStop && stopSheetRouteFilter == null) {
            board = StopDepartures.board(vehicles, stop.lat, stop.lon, "");
        }
        stopBoardTitle.setText(servingThisStop || stopSheetRouteFilter != null
                ? "Next departures" : "Live buses nearby");

        if (board.isEmpty()) {
            String emptyMsg;
            if (TextUtils.isEmpty(BuildConfig.BODS_API_KEY)) {
                emptyMsg = "Live buses need a BODS API key.";
            } else if (stopSheetRouteFilter != null) {
                emptyMsg = "No live buses on route " + stopSheetRouteFilter + " near this stop right now.";
            } else {
                emptyMsg = "No live buses near this stop right now.";
            }
            stopBoardRows.addView(tv(emptyMsg, 14, UiTheme.WHITE, false));
            return;
        }

        boolean timed = false;
        int shown = 0;
        for (StopDepartures.Departure departure : board) {
            if (shown == MAX_BOARD_ROWS) {
                break;
            }
            Bus bus = buses.get(departure.busId);
            if (bus == null || bus.pos == null) {
                continue;
            }
            shown++;
            timed |= departure.hasTime();
            String label = departure.minutesLabel();
            View row = busRow(bus, departure.distanceMeters, label.isEmpty() ? null : label);
            final String busId = departure.busId;
            row.setOnClickListener(v -> {
                if (stopBoardDialog != null) {
                    stopBoardDialog.dismiss();
                }
                showBus(busId);
            });
            stopBoardRows.addView(row);
        }
        if (shown < board.size()) {
            stopBoardNote.setText("Showing the next " + shown + " of " + board.size() + ".");
        } else if (timed) {
            stopBoardNote.setText("Times are estimated from each bus's live speed and distance.");
        }
    }

    // ------------------------------------------------------------------ list screens

    private View busRow(Bus bus, double distanceMeters) {
        return busRow(bus, distanceMeters, null);
    }

    private View busRow(Bus bus, double distanceMeters, String rightLabel) {
        BusPosition p = bus.pos;
        int color = UiTheme.occupancyColor(p.occupancy);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(UiTheme.ripple(UiTheme.pill(this, UiTheme.INK_LIGHT, Color.parseColor("#24304F"), 1f, 16)));
        row.setPadding(dp(12), dp(12), dp(12), dp(12));
        row.addView(routeBadge(p.lineName.isEmpty() ? "Bus" : p.lineName, color));

        LinearLayout middle = new LinearLayout(this);
        middle.setOrientation(LinearLayout.VERTICAL);
        middle.setPadding(dp(12), 0, dp(8), 0);
        middle.addView(tv(p.destinationName.isEmpty() ? "In service" : "to " + p.destinationName,
                15, UiTheme.WHITE, true));
        StringBuilder sub = new StringBuilder(OperatorNames.display(p.operatorName));
        if (!"Information Unknown".equals(p.occupancy) && !p.occupancy.isEmpty()) {
            if (sub.length() > 0) sub.append(" \u00B7 ");
            sub.append(p.occupancy);
        }
        if (sub.length() > 0) {
            middle.addView(tv(sub.toString(), 12, UiTheme.TEXT_DIM, false));
        }
        row.addView(middle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(tv(rightLabel == null ? formatDistance(distanceMeters) : rightLabel,
                13, UiTheme.CYAN, true));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        row.setLayoutParams(lp);
        return row;
    }

    private TextView routeBadge(String route, int color) {
        TextView badge = tv(route, 16, UiTheme.WHITE, true);
        badge.setGravity(Gravity.CENTER);
        badge.setMinWidth(dp(54));
        badge.setPadding(dp(8), dp(8), dp(8), dp(8));
        badge.setBackground(UiTheme.pill(this, UiTheme.withAlpha(color, 60), color, 1.5f, 12));
        return badge;
    }

    private View stopRow(Stop stop, double distanceMeters) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(UiTheme.ripple(UiTheme.pill(this, UiTheme.INK_LIGHT, Color.parseColor("#24304F"), 1f, 16)));
        row.setPadding(dp(12), dp(12), dp(12), dp(12));
        IconView icon = new IconView(this, IconView.PIN);
        icon.setIconColor(UiTheme.CYAN);
        icon.setAccentColor(UiTheme.CYAN);
        row.addView(icon, new LinearLayout.LayoutParams(dp(28), dp(28)));
        LinearLayout middle = new LinearLayout(this);
        middle.setOrientation(LinearLayout.VERTICAL);
        middle.setPadding(dp(12), 0, dp(8), 0);
        middle.addView(tv(stop.name, 15, UiTheme.WHITE, true));
        if (!stop.routes.isEmpty()) {
            middle.addView(tv("Routes: " + stop.routes, 12, UiTheme.TEXT_DIM, false));
        }
        row.addView(middle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(tv(formatDistance(distanceMeters), 13, UiTheme.CYAN, true));
        row.setOnClickListener(v -> showStopSheet(stop));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        row.setLayoutParams(lp);
        return row;
    }

    private View routeCard(String route, RouteInfo info) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(UiTheme.ripple(UiTheme.pill(this, UiTheme.INK_LIGHT, Color.parseColor("#24304F"), 1f, 16)));
        row.setPadding(dp(12), dp(12), dp(12), dp(12));
        row.addView(routeBadge(route, info.count > 0 ? info.color : UiTheme.BLUE));
        LinearLayout middle = new LinearLayout(this);
        middle.setOrientation(LinearLayout.VERTICAL);
        middle.setPadding(dp(12), 0, dp(8), 0);
        String title = info.count == 0 ? "No buses live right now"
                : info.count + (info.count == 1 ? " bus live" : " buses live");
        middle.addView(tv(title, 15, UiTheme.WHITE, true));
        if (!info.destination.isEmpty()) {
            middle.addView(tv("e.g. to " + info.destination, 12, UiTheme.TEXT_DIM, false));
        }
        row.addView(middle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.setOnClickListener(v -> {
            if (info.count == 0) {
                Toast.makeText(this, "No live buses on " + route + " right now", Toast.LENGTH_SHORT).show();
            } else {
                filterToRoute(route);
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        row.setLayoutParams(lp);
        return row;
    }

    private TextView sectionTitle(String text) {
        TextView title = tv(text, 18, UiTheme.WHITE, true);
        title.setPadding(0, dp(10), 0, dp(10));
        return title;
    }

    private TextView note(String text) {
        TextView note = tv(text, 14, UiTheme.TEXT_DIM, false);
        note.setPadding(0, dp(4), 0, dp(14));
        return note;
    }

    private void rebuildNearby() {
        if (nearbyList == null) {
            return;
        }
        nearbyList.removeAllViews();
        double[] ref = refPoint();
        boolean fromUser = userLocation != null;

        nearbyList.addView(sectionTitle("Live buses nearby"));
        nearbyList.addView(note(fromUser ? "Closest to your location" : "Closest to the map centre"));
        List<Bus> sorted = new ArrayList<>(buses.values());
        Collections.sort(sorted, Comparator.<Bus>comparingDouble(
                b -> meters(ref[0], ref[1], b.pos.latitude, b.pos.longitude)));
        if (sorted.isEmpty()) {
            nearbyList.addView(note(TextUtils.isEmpty(BuildConfig.BODS_API_KEY)
                    ? "Live buses need a free BODS API key. See docs/BODS_API_KEY.md."
                    : "Waiting for live buses\u2026"));
        }
        for (int i = 0; i < Math.min(sorted.size(), 20); i++) {
            Bus bus = sorted.get(i);
            View row = busRow(bus, meters(ref[0], ref[1], bus.pos.latitude, bus.pos.longitude));
            final String busId = bus.pos.id;
            row.setOnClickListener(v -> showBus(busId));
            nearbyList.addView(row);
        }

        nearbyList.addView(sectionTitle("Bus stops nearby"));
        if (stopsError != null) {
            nearbyList.addView(note(stopsError));
        } else if (stops.isEmpty()) {
            nearbyList.addView(note(fetchingStops ? "Loading bus stops\u2026" : "No bus stops found here yet."));
        }
        List<Stop> sortedStops = new ArrayList<>(stops);
        Collections.sort(sortedStops, Comparator.<Stop>comparingDouble(s -> meters(ref[0], ref[1], s.lat, s.lon)));
        for (int i = 0; i < Math.min(sortedStops.size(), 20); i++) {
            Stop stop = sortedStops.get(i);
            nearbyList.addView(stopRow(stop, meters(ref[0], ref[1], stop.lat, stop.lon)));
        }
    }

    private Map<String, RouteInfo> routeSummary() {
        double[] ref = refPoint();
        Map<String, RouteInfo> routes = new LinkedHashMap<>();
        for (Bus bus : buses.values()) {
            if (bus.pos == null || bus.pos.lineName.isEmpty()) continue;
            RouteInfo info = routes.get(bus.pos.lineName);
            if (info == null) {
                info = new RouteInfo();
                routes.put(bus.pos.lineName, info);
            }
            info.count++;
            double d = meters(ref[0], ref[1], bus.pos.latitude, bus.pos.longitude);
            if (d < info.nearestMeters) {
                info.nearestMeters = d;
                info.destination = bus.pos.destinationName;
                info.color = UiTheme.occupancyColor(bus.pos.occupancy);
            }
        }
        return routes;
    }

    private void rebuildSearch() {
        if (searchResults == null) {
            return;
        }
        searchResults.removeAllViews();
        String q = searchInput.getText().toString().trim().toLowerCase(Locale.UK);
        Map<String, RouteInfo> summary = routeSummary();

        List<String> routes = new ArrayList<>();
        for (Map.Entry<String, RouteInfo> entry : summary.entrySet()) {
            if (q.isEmpty() || entry.getKey().toLowerCase(Locale.UK).contains(q)
                    || entry.getValue().destination.toLowerCase(Locale.UK).contains(q)) {
                routes.add(entry.getKey());
            }
        }
        Collections.sort(routes, MainActivity::compareRoutes);
        searchResults.addView(sectionTitle(q.isEmpty() ? "Routes running now" : "Routes"));
        if (routes.isEmpty()) {
            searchResults.addView(note(summary.isEmpty() && TextUtils.isEmpty(BuildConfig.BODS_API_KEY)
                    ? "Route search uses live buses, which need a BODS API key."
                    : "No matching routes live right now."));
        }
        for (String route : routes) {
            searchResults.addView(routeCard(route, summary.get(route)));
        }

        if (!q.isEmpty()) {
            searchResults.addView(sectionTitle("Stops"));
            double[] ref = refPoint();
            int shown = 0;
            for (Stop stop : stops) {
                if ((stop.name.toLowerCase(Locale.UK).contains(q)
                        || stop.routes.toLowerCase(Locale.UK).contains(q)) && shown < 15) {
                    searchResults.addView(stopRow(stop, meters(ref[0], ref[1], stop.lat, stop.lon)));
                    shown++;
                }
            }
            if (shown == 0) {
                searchResults.addView(note("No matching stops in the area loaded around the map."));
            }
        }

        if (q.length() >= 3) {
            searchResults.addView(sectionTitle("Places & postcodes"));
            List<PlaceSearch.Place> cachedPlaces = PlaceSearch.cached(q);
            if (cachedPlaces != null) {
                if (cachedPlaces.isEmpty()) {
                    searchResults.addView(note("No matching UK places found."));
                } else {
                    for (PlaceSearch.Place place : cachedPlaces) {
                        searchResults.addView(placeRow(place));
                    }
                }
            } else {
                TextView findBtn = UiTheme.pillText(this, "Find \"" + searchInput.getText().toString().trim()
                        + "\" across the UK", UiTheme.INK, UiTheme.CYAN, UiTheme.CYAN);
                findBtn.setOnClickListener(v -> {
                    findBtn.setText("Searching\u2026");
                    final String queryText = q;
                    io.execute(() -> {
                        try {
                            PlaceSearch.search(queryText);
                            handler.post(this::rebuildSearch);
                        } catch (Exception e) {
                            handler.post(() -> Toast.makeText(this,
                                    "Couldn't search places right now", Toast.LENGTH_SHORT).show());
                        }
                    });
                });
                LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                btnLp.bottomMargin = dp(12);
                searchResults.addView(findBtn, btnLp);
            }
        }
    }

    private View placeRow(PlaceSearch.Place place) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(UiTheme.ripple(UiTheme.pill(this, UiTheme.INK_LIGHT, Color.parseColor("#24304F"), 1f, 16)));
        row.setPadding(dp(12), dp(12), dp(12), dp(12));
        IconView icon = new IconView(this, IconView.TARGET);
        icon.setIconColor(UiTheme.CYAN);
        icon.setAccentColor(UiTheme.CYAN);
        row.addView(icon, new LinearLayout.LayoutParams(dp(26), dp(26)));
        LinearLayout middle = new LinearLayout(this);
        middle.setOrientation(LinearLayout.VERTICAL);
        middle.setPadding(dp(12), 0, dp(8), 0);
        middle.addView(tv(place.title, 15, UiTheme.WHITE, true));
        if (!place.subtitle.isEmpty()) {
            TextView sub = tv(place.subtitle, 12, UiTheme.TEXT_DIM, false);
            sub.setMaxLines(2);
            sub.setEllipsize(TextUtils.TruncateAt.END);
            middle.addView(sub);
        }
        row.addView(middle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.setOnClickListener(v -> {
            stopFollowing();
            setTab(TAB_MAP);
            map.getController().setZoom(15.5);
            map.getController().animateTo(new GeoPoint(place.latitude, place.longitude));
            maybeFetchStops(place.latitude, place.longitude, true);
            handler.postDelayed(this::refreshFeedForViewport, 350);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        row.setLayoutParams(lp);
        return row;
    }

    private Set<String> favorites() {
        return new HashSet<>(prefs.getStringSet(PREF_FAVORITES, new HashSet<>()));
    }

    private void rebuildFavorites() {
        if (favoritesList == null) {
            return;
        }
        favoritesList.removeAllViews();

        favoritesList.addView(sectionTitle("Favorite stops"));
        List<StopFavorites.FavoriteStop> savedStops = StopFavorites.sortedSaved(this);
        if (savedStops.isEmpty()) {
            favoritesList.addView(note("Tap the star on any stop to keep its departure board here."));
        } else {
            double[] ref = refPoint();
            for (StopFavorites.FavoriteStop savedStop : savedStops) {
                favoritesList.addView(favoriteStopCard(savedStop, ref));
            }
        }

        favoritesList.addView(sectionTitle("Favorite routes"));
        List<String> favs = new ArrayList<>(favorites());
        Collections.sort(favs, MainActivity::compareRoutes);
        if (favs.isEmpty()) {
            favoritesList.addView(note("Tap the star on any bus to save its route here."));
            return;
        }
        Map<String, RouteInfo> summary = routeSummary();
        for (String route : favs) {
            RouteInfo info = summary.get(route);
            favoritesList.addView(routeCard(route, info == null ? new RouteInfo() : info));
        }
    }

    private View favoriteStopCard(StopFavorites.FavoriteStop saved, double[] ref) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(UiTheme.ripple(UiTheme.pill(this, UiTheme.INK_LIGHT, Color.parseColor("#24304F"), 1f, 16)));
        row.setPadding(dp(12), dp(12), dp(12), dp(12));

        IconView icon = new IconView(this, IconView.PIN);
        icon.setIconColor(UiTheme.CYAN);
        icon.setAccentColor(UiTheme.CYAN);
        row.addView(icon, new LinearLayout.LayoutParams(dp(28), dp(28)));

        int inbound = StopDepartures.board(liveVehicles(), saved.latitude, saved.longitude,
                saved.routes).size();
        LinearLayout middle = new LinearLayout(this);
        middle.setOrientation(LinearLayout.VERTICAL);
        middle.setPadding(dp(12), 0, dp(8), 0);
        middle.addView(tv(saved.name, 15, UiTheme.WHITE, true));
        StringBuilder sub = new StringBuilder(inbound == 0
                ? "No live buses inbound"
                : inbound + (inbound == 1 ? " bus inbound" : " buses inbound"));
        if (!saved.routes.isEmpty()) {
            sub.append(" \u00B7 Routes ").append(saved.routes);
        }
        middle.addView(tv(sub.toString(), 12, UiTheme.TEXT_DIM, false));
        row.addView(middle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(tv(formatDistance(meters(ref[0], ref[1], saved.latitude, saved.longitude)),
                13, UiTheme.CYAN, true));

        TextView remove = UiTheme.pillText(this, "\u2715", UiTheme.WHITE, UiTheme.INK_LIGHT, UiTheme.BLUE);
        remove.setOnClickListener(v -> {
            StopFavorites.remove(this, saved.key());
            rebuildFavorites();
        });
        LinearLayout.LayoutParams removeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        removeLp.leftMargin = dp(8);
        row.addView(remove, removeLp);

        row.setOnClickListener(v -> showStopSheet(stopShapeFor(saved)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        row.setLayoutParams(lp);
        return row;
    }

    private Stop stopShapeFor(StopFavorites.FavoriteStop saved) {
        Stop stop = new Stop();
        stop.id = saved.osmId;
        stop.name = saved.name;
        stop.routes = saved.routes;
        stop.atcoCode = saved.atcoCode;
        stop.lat = saved.latitude;
        stop.lon = saved.longitude;
        return stop;
    }

    private static String atcoCodeOf(JSONObject tags) {
        if (tags == null) {
            return "";
        }
        String[] keys = { "naptan:AtcoCode", "naptan:ATCOCode", "naptan:atco_code", "naptan:Atco" };
        for (String key : keys) {
            String value = tags.optString(key, "").trim();
            if (!value.isEmpty()) {
                return value;
            }
        }
        return "";
    }

    private void rebuildAccount() {
        if (accountList == null) {
            return;
        }
        accountList.removeAllViews();
        accountList.addView(sectionTitle("Account"));
        accountList.addView(note("No sign-in needed. Favorites and settings stay on this device."));

        accountList.addView(sectionTitle("Live tracking"));
        boolean hasKey = !TextUtils.isEmpty(BuildConfig.BODS_API_KEY);
        accountList.addView(note(hasKey
                ? "BODS API key found. " + (lastStatus.isEmpty() ? "Waiting for the first update." : lastStatus)
                : "No BODS API key in this build. Add BODS_API_KEY to local.properties and rebuild."));

        accountList.addView(sectionTitle("Arrival alerts"));
        List<ArrivalAlert> armed = ArrivalAlertStore.sorted(this);
        if (armed.isEmpty()) {
            accountList.addView(note("Tap any live bus or route chip, then Notify me, to be told when that route is about to arrive."));
        } else {
            accountList.addView(note(armed.size() + (armed.size() == 1 ? " alert armed" : " alerts armed")
                    + ". The live poller keeps running in the background while alerts are armed."));
            for (ArrivalAlert alert : armed) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(0, dp(6), 0, dp(6));
                row.addView(tv(alert.summary(), 14, UiTheme.WHITE, false),
                        new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                TextView remove = UiTheme.pillText(this, "Remove", UiTheme.WHITE, UiTheme.INK_LIGHT, UiTheme.BLUE);
                final String alertId = alert.id;
                remove.setOnClickListener(v -> {
                    ArrivalAlertStore.remove(this, alertId);
                    rebuildAccount();
                });
                row.addView(remove);
                accountList.addView(row);
            }
            TextView clearAlerts = UiTheme.pillText(this, "Clear all alerts", UiTheme.WHITE, UiTheme.INK_LIGHT, UiTheme.BLUE);
            clearAlerts.setOnClickListener(v -> {
                ArrivalAlertStore.clear(this);
                rebuildAccount();
            });
            accountList.addView(clearAlerts, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        if (!AlertNotifier.canPost(this)) {
            accountList.addView(note("Notifications are off for this app, so alerts can't be shown."));
            TextView enable = UiTheme.pillText(this, "Allow notifications", UiTheme.INK, UiTheme.CYAN, UiTheme.CYAN);
            enable.setOnClickListener(v -> ensureNotificationPermission());
            accountList.addView(enable, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        accountList.addView(sectionTitle("This device"));
        int savedStopCount = StopFavorites.load(this).size();
        accountList.addView(note(favorites().size() + " favorite routes and " + savedStopCount
                + (savedStopCount == 1 ? " favorite stop" : " favorite stops") + " saved"));
        TextView clear = UiTheme.pillText(this, "Clear favorites", UiTheme.WHITE, UiTheme.INK_LIGHT, UiTheme.BLUE);
        clear.setOnClickListener(v -> {
            prefs.edit().remove(PREF_FAVORITES).apply();
            StopFavorites.clear(this);
            rebuildAccount();
        });
        accountList.addView(clear, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        accountList.addView(sectionTitle("About"));
        accountList.addView(note("Bus Times Live " + BuildConfig.VERSION_NAME
                + "\nBus positions: Bus Open Data Service (Department for Transport)."
                + "\nMap and stops: \u00A9 OpenStreetMap contributors."
                + "\nOperator names loaded: " + OperatorNames.size()));
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
        if (!follow) {
            stopFollowing();
            return;
        }
        Bus bus = buses.get(busId);
        if (bus == null || bus.pos == null) {
            return;
        }
        followId = busId;
        followPill.setText("Following " + bus.pos.lineName + "  \u2715");
        followPill.setVisibility(View.VISIBLE);
        setTab(TAB_MAP);
        map.getController().setZoom(17.0);
        map.getController().animateTo(new GeoPoint(bus.pos.latitude, bus.pos.longitude));
    }

    private void stopFollowing() {
        followId = null;
        if (followPill != null) {
            followPill.setVisibility(View.GONE);
        }
    }

    @Override
    public boolean isFollowingBus(String busId) {
        return busId != null && busId.equals(followId);
    }

    @Override
    public void onShowBusOnMap(double latitude, double longitude) {
        setTab(TAB_MAP);
        map.getController().setZoom(17.0);
        map.getController().animateTo(new GeoPoint(latitude, longitude));
    }

    @Override
    public void onToggleFavorite(String route) {
        Set<String> favs = favorites();
        if (!favs.remove(route)) {
            favs.add(route);
        }
        prefs.edit().putStringSet(PREF_FAVORITES, favs).apply();
        if (currentTab == TAB_FAVORITES) {
            rebuildFavorites();
        }
    }

    @Override
    public boolean isFavorite(String route) {
        return favorites().contains(route);
    }

    // ------------------------------------------------------------------ arrival alerts

    @Override
    public void onConfigureAlert(String route) {
        double[] point = refPoint();
        openAlertSheet(Collections.singletonList(route),
                userLocation != null ? "your location" : "the map centre", point[0], point[1]);
    }

    @Override
    public boolean isAlertArmedForRoute(String route) {
        return ArrivalAlertStore.isArmedForRoute(this, route);
    }

    private void evaluateArrivalAlerts() {
        if (!ArrivalAlertStore.hasAlerts(this)) {
            return;
        }
        List<BusPosition> positions = new ArrayList<>();
        for (Bus bus : buses.values()) {
            if (bus.pos != null) {
                positions.add(bus.pos);
            }
        }
        ArrivalAlerts.evaluate(this, ArrivalAlerts.candidatesFromPositions(positions));
    }

    private void ensureNotificationPermission() {
        AlertNotifier.ensureChannel(this);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[] {Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
        }
    }

    private List<String> routesAtStop(Stop stop) {
        List<String> routes = new ArrayList<>();
        for (Bus bus : buses.values()) {
            if (bus.pos == null || bus.pos.lineName.isEmpty()) {
                continue;
            }
            if (meters(stop.lat, stop.lon, bus.pos.latitude, bus.pos.longitude) > 600) {
                continue;
            }
            boolean seen = false;
            for (String route : routes) {
                if (route.equalsIgnoreCase(bus.pos.lineName)) {
                    seen = true;
                    break;
                }
            }
            if (!seen) {
                routes.add(bus.pos.lineName);
            }
        }
        Collections.sort(routes, MainActivity::compareRoutes);
        return routes;
    }

    private void styleAlertChip(TextView chip, boolean selected) {
        chip.setTextColor(selected ? UiTheme.INK : UiTheme.WHITE);
        chip.setBackground(UiTheme.ripple(UiTheme.pill(this,
                selected ? UiTheme.CYAN : UiTheme.withAlpha(UiTheme.INK, 225),
                selected ? UiTheme.CYAN : UiTheme.BLUE, 1f, 20f)));
    }

    private void openAlertSheet(List<String> routeOptions, String placeLabel, double lat, double lon) {
        if (routeOptions == null || routeOptions.isEmpty()) {
            Toast.makeText(this, "No live route to watch here yet", Toast.LENGTH_SHORT).show();
            return;
        }
        ensureNotificationPermission();

        final String place = TextUtils.isEmpty(placeLabel) ? "your area" : placeLabel;
        final String[] selectedRoute = {routeOptions.get(0)};
        final int[] selectedMinutes = {ArrivalAlertRules.normaliseMinutes(ArrivalAlertRules.DEFAULT_MINUTES)};

        LinearLayout card = sheetCard();
        card.addView(tv("Arrival alert", 20, UiTheme.WHITE, true));
        final TextView explain = tv("", 13, UiTheme.TEXT_DIM, false);
        explain.setPadding(0, dp(6), 0, dp(12));
        card.addView(explain);

        final List<TextView> routeChips = new ArrayList<>();
        if (routeOptions.size() > 1) {
            card.addView(tv("Route", 13, UiTheme.TEXT_DIM, false));
            LinearLayout routeRow = new LinearLayout(this);
            routeRow.setOrientation(LinearLayout.HORIZONTAL);
            routeRow.setPadding(0, dp(6), 0, dp(10));
            for (String route : routeOptions) {
                TextView routeChip = UiTheme.pillText(this, route, UiTheme.WHITE,
                        UiTheme.withAlpha(UiTheme.INK, 225), UiTheme.BLUE);
                routeChips.add(routeChip);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.rightMargin = dp(8);
                routeRow.addView(routeChip, lp);
            }
            card.addView(routeRow);
        }

        card.addView(tv("How close counts as close", 13, UiTheme.TEXT_DIM, false));
        LinearLayout minuteRow = new LinearLayout(this);
        minuteRow.setOrientation(LinearLayout.HORIZONTAL);
        minuteRow.setPadding(0, dp(6), 0, dp(12));
        final List<TextView> minuteChips = new ArrayList<>();
        for (int minutes : ArrivalAlertRules.MINUTE_PRESETS) {
            TextView chip = UiTheme.pillText(this, minutes + " min", UiTheme.WHITE,
                    UiTheme.withAlpha(UiTheme.INK, 225), UiTheme.BLUE);
            minuteChips.add(chip);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(8);
            minuteRow.addView(chip, lp);
        }
        card.addView(minuteRow);

        final MaterialButton arm = new MaterialButton(this);
        arm.setAllCaps(false);
        arm.setTextSize(15f);
        arm.setCornerRadius(dp(22));
        arm.setBackgroundTintList(ColorStateList.valueOf(UiTheme.CYAN));
        arm.setTextColor(UiTheme.INK);
        card.addView(arm, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView manageNote = tv("Alerts are checked by the live poller, so they still fire when the map is off screen. Manage them in Account.",
                12, UiTheme.TEXT_DIM, false);
        manageNote.setPadding(0, dp(10), 0, 0);
        card.addView(manageNote);

        final Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            for (int i = 0; i < routeChips.size(); i++) {
                styleAlertChip(routeChips.get(i), routeOptions.get(i).equals(selectedRoute[0]));
            }
            for (int i = 0; i < minuteChips.size(); i++) {
                styleAlertChip(minuteChips.get(i), ArrivalAlertRules.MINUTE_PRESETS[i] == selectedMinutes[0]);
            }
            int radius = ArrivalAlertRules.radiusForMinutes(selectedMinutes[0]);
            explain.setText("Ping me when " + selectedRoute[0] + " comes within " + radius
                    + " m of " + place + ", or when the feed says it is " + selectedMinutes[0]
                    + " min away.");
            arm.setText(ArrivalAlertStore.isArmedForRoute(this, selectedRoute[0]) ? "Remove alert" : "Arm alert");
        };
        for (int i = 0; i < routeChips.size(); i++) {
            final String route = routeOptions.get(i);
            routeChips.get(i).setOnClickListener(v -> {
                selectedRoute[0] = route;
                refresh[0].run();
            });
        }
        for (int i = 0; i < minuteChips.size(); i++) {
            final int minutes = ArrivalAlertRules.MINUTE_PRESETS[i];
            minuteChips.get(i).setOnClickListener(v -> {
                selectedMinutes[0] = minutes;
                refresh[0].run();
            });
        }
        refresh[0].run();

        final BottomSheetDialog dialog = sheetDialog(card);
        arm.setOnClickListener(v -> {
            String route = selectedRoute[0];
            if (ArrivalAlertStore.isArmedForRoute(this, route)) {
                ArrivalAlertStore.removeForRoute(this, route);
                Toast.makeText(this, "Alert removed for " + route, Toast.LENGTH_SHORT).show();
            } else {
                ArrivalAlertStore.arm(this, new ArrivalAlert(route, place, lat, lon,
                        selectedMinutes[0], System.currentTimeMillis()));
                if (AlertNotifier.canPost(this)) {
                    Toast.makeText(this, "Alerting you when " + route + " is about "
                            + selectedMinutes[0] + " min from " + place, Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, "Alert saved, but notifications are off. Turn them on in Account.",
                            Toast.LENGTH_LONG).show();
                }
            }
            dialog.dismiss();
            if (currentTab == TAB_ACCOUNT) {
                rebuildAccount();
            }
        });
        dialog.show();
    }

    // ------------------------------------------------------------------ helpers

    private static String nz(String value) {
        return value == null ? "" : value;
    }

    private static String tidyName(String value) {
        return value == null ? "" : value.replace("__", ", ").replace('_', ' ').trim();
    }

    /** Orders route names numerically where possible (2A before 12). */
    private static int compareRoutes(String a, String b) {
        int na = leadingNumber(a);
        int nb = leadingNumber(b);
        if (na != nb) {
            return Integer.compare(na, nb);
        }
        return a.compareToIgnoreCase(b);
    }

    private static int leadingNumber(String s) {
        int i = 0;
        while (i < s.length() && Character.isDigit(s.charAt(i))) {
            i++;
        }
        if (i == 0) {
            return Integer.MAX_VALUE;
        }
        try {
            return Integer.parseInt(s.substring(0, i));
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }
}
