# Bus Times Live Android

A map-first Android wrapper for [bustimes.org](https://bustimes.org/) focused on the live tracker at `https://bustimes.org/map`. The app opens directly to the real Bus Times map so bus markers show their live route numbers and locations using the website's own live data, now wrapped in a completely redesigned dark "glass & neon" interface.

## What's new in the redesign

- **Whole new look**: deep-space navy theme, gradient floating action buttons, glass pills and chips, animated press feedback, a cyan page-load progress bar and a brand-new neon launcher icon.
- **Rich bus details on tap**: tapping any bus (on the map or in AR) opens a modern bottom sheet with a live ETA countdown, due-now/due-soon status ring, animated occupancy meter, live speed (mph), compass heading, distance from you, vehicle/operator line, plus **Follow this bus**, **Walk me there (AR)**, **Show on map** and **Share** actions.
- **Smarter map markers**: occupancy-tinted circular badges with a direction arrow that rotates without spinning the label, tap-through to the full details sheet, and automatic cleanup of buses that go offline.
- **Follow mode**: keeps the map (or AR guidance) locked on a bus as it moves.
- **Live status & count pills**: see how many buses are tracked and what the BODS poller is doing at a glance; tap the count pill for a "buses live now" list.
- **Route shortcut chips**: one-tap filtering to the busiest routes, plus an All chip.
- **Night mode**: toggle in the zoom stack inverts the web map into a dark theme (persisted between launches).

## Features

- Live bus map with numbered bus markers from bustimes.org.
- Bus stop markers from bustimes.org; tapping a stop shows the served route numbers and links through to the stop's live departure times.
- Native **locate me** button that asks for Android location permission and recentres the live map on the user's current position.
- Native refresh button to reload the live map and request an immediate BODS vehicle refresh.
- Smart voice navigation with Android `SpeechRecognizer`: say a route number to zoom to that live bus or filter the map.
- Native **+ / −** zoom controls and night-mode toggle, always visible on top of the map.
- Injected WebView styles to remove common ad containers from the map page.

### Location-Based AR Bus Stop Finder (overhauled)

- **Real bus stops**: pins now come from OpenStreetMap (Overpass API) around you — name, routes and distance — instead of placeholder pins. Tap a stop pin to start turn-by-turn AR walking guidance to it.
- **Tap live bus cards** in AR to open the full details bottom sheet (occupancy, ETA, speed, distance, share, follow).
- **True field-of-view placement**: markers track compass heading across the screen and slide to edge **chevrons** with colour coding when a bus or your target is behind you.
- **Compass ribbon** with cardinal directions at the top of the view.
- **Radar panel** showing live buses (occupancy-coloured blips), stops (white blips) and your pulsing target within 320 m.
- **Distance rings** on the ground plane (25 m / 50 m) and distance-scaled marker heights.
- **Arrival banner + haptic** when you reach your target stop.
- **Target chip** showing name, live distance and estimated walking minutes.
- Corrected **camera aspect ratio** (picks a sensor-matched preview size so the feed is never stretched).
- Live camera preview, rotation-vector sensor fusion, true-north magnetic declination correction, GPS accuracy lock with a 5-second **Bypass Calibration** fallback, smoothed updates, a force-rendered neon navigation wall, Future HUD, X-Ray Ghost Bus silhouettes, Virtual AR Bus Shelter hints, and AI Delay Predictor explanations.
- Optional Google walking-directions route ribbon when a `GOOGLE_DIRECTIONS_API_KEY` is supplied (on-device direct hint otherwise).

### Live BODS tracking

- Optional BODS SIRI-VM XML/JSON polling refreshes bus locations every 15 seconds while the map is active, colors markers by occupancy/crowding (green Easy Seating, amber Standing Room Only, coral Full/Crowded, blue Information Unknown), and stops polling when the map closes to save battery.
- Marker throttling (300 ms for map overlays, 600 ms for AR cards) keeps 60 fps while hundreds of vehicles animate.
- Speed and heading are estimated between polls and surfaced in the details sheet and AR cards.

## Build locally

Open the project in Android Studio or build from the command line with an Android SDK installed:

```bash
gradle :app:assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

### BODS live tracking configuration

The app can overlay live bus positions from the UK Bus Open Data Service SIRI-VM feed. BODS data is free to access, but API keys are issued to registered data-consumer accounts, so this repository does not include a shared public key. See [Free BODS API key setup](docs/BODS_API_KEY.md) for the official signup and build steps.

Provide your free BODS API key at build time so it is compiled into `BuildConfig`:

```bash
BODS_API_KEY=your-free-bods-key gradle :app:assembleDebug
```

You can optionally limit polling to an area with `BODS_BOUNDING_BOX` to avoid downloading a nationwide feed. The default API endpoint is `https://data.bus-data.dft.gov.uk/api/v1/datafeed/`; override it with `BODS_API_BASE_URL` if BODS changes the endpoint.

### Google walking directions for AR Live View paths

The AR route ribbon can use Google Directions walking steps when a `GOOGLE_DIRECTIONS_API_KEY` is supplied at build time. Without the key, the AR view falls back to an on-device direct route hint so the app still builds and runs. See [AR walking directions setup](docs/AR_DIRECTIONS.md) for setup details.

```bash
GOOGLE_DIRECTIONS_API_KEY=your-google-directions-key gradle :app:assembleDebug
```

For CI, add `GOOGLE_DIRECTIONS_API_KEY` as a repository secret alongside `BODS_API_KEY`.

## Build on GitHub

This repository includes a GitHub Actions workflow at `.github/workflows/build-apk.yml`. To build an APK on GitHub:

1. Push the project to GitHub.
2. Add your free BODS key as a repository secret named `BODS_API_KEY` if you want the APK to include the BODS live marker overlay.
3. Optionally add a repository secret named `GOOGLE_DIRECTIONS_API_KEY` to enable Google Directions walking paths in AR.
4. Optionally add repository variables named `BODS_BOUNDING_BOX` and `BODS_API_BASE_URL` to restrict the live vehicle feed or override the BODS endpoint.
5. Open the repository's **Actions** tab.
6. Select **Build Android APK**.
7. Click **Run workflow**.
8. When the workflow finishes, download the `bus-times-live-debug-apk` artifact from the run summary.

The workflow also runs automatically for pushes to `main`/`work` and for pull requests.

The project uses the Android Gradle Plugin. `gradle.properties` enables AndroidX for compatibility with Android dependencies used by local and GitHub Actions builds. ARCore support sets the app minimum SDK to Android 7.0 / API 24.
