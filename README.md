# Bus Times Live Android

A map-first Android wrapper for [bustimes.org](https://bustimes.org/) focused on the live tracker at `https://bustimes.org/map`. The app opens directly to the real Bus Times map so bus markers show their live route numbers and locations using the website's own live data.

## Features

- Live bus map with numbered bus markers from bustimes.org.
- Bus stop markers from bustimes.org; tapping a stop shows the served route numbers and links through to the stop's live departure times.
- Native **Home / locate me** button that asks for Android location permission and recentres the live map on the user's current position.
- Native refresh button to reload the live map and request an immediate BODS vehicle refresh.
- Voice search with Android `SpeechRecognizer`: tap the microphone and say a route number to filter live bus markers and AR stop pins, including a **Did you mean?** confirmation for phrases such as “three fifty” or “three five zero”.
- Native **+ / −** zoom controls so both zoom in and zoom out are always visible on top of the map.
- Injected WebView ad-hiding styles to remove common ad containers from the map page.
- Location-Based AR Bus Stop Finder mode backed by ARCore, GPS, and compass bearing with camera permission handling and floating route stop pins.
- Optional BODS SIRI-VM polling that refreshes bus locations every 30 seconds and animates existing bus markers to their new positions over 5 seconds.
- WebView support for JavaScript, DOM storage, cookies, geolocation prompts, back navigation, external map/mail/phone links, and downloads.
- Battery-conscious cleanup: AR camera/session and voice listener resources are shut down when leaving those modes.

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

## Build on GitHub

This repository includes a GitHub Actions workflow at `.github/workflows/build-apk.yml`. To build an APK on GitHub:

1. Push the project to GitHub.
2. Add your free BODS key as a repository secret named `BODS_API_KEY` if you want the APK to include the BODS live marker overlay.
3. Optionally add repository variables named `BODS_BOUNDING_BOX` and `BODS_API_BASE_URL` to restrict the live vehicle feed or override the BODS endpoint.
4. Open the repository's **Actions** tab.
5. Select **Build Android APK**.
6. Click **Run workflow**.
7. When the workflow finishes, download the `bus-times-live-debug-apk` artifact from the run summary.

The workflow also runs automatically for pushes to `main`/`work` and for pull requests.

The project uses the Android Gradle Plugin and has no third-party runtime dependencies.
