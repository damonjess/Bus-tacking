# Bus Times Live Android

A map-first Android wrapper for [bustimes.org](https://bustimes.org/) focused on the live tracker at `https://bustimes.org/map`. The app opens directly to the real Bus Times map so bus markers show their live route numbers and locations using the website's own live data.

## Features

- Live bus map with numbered bus markers from bustimes.org.
- Bus stop markers from bustimes.org; tapping a stop shows the served route numbers and links through to the stop's live departure times.
- Native **Home / locate me** button that asks for Android location permission and recentres the live map on the user's current position.
- WebView support for JavaScript, DOM storage, cookies, geolocation prompts, back navigation, external map/mail/phone links, and downloads.

## Build locally

Open the project in Android Studio or build from the command line with an Android SDK installed:

```bash
gradle :app:assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Build on GitHub

This repository includes a GitHub Actions workflow at `.github/workflows/build-apk.yml`. To build an APK on GitHub:

1. Push the project to GitHub.
2. Open the repository's **Actions** tab.
3. Select **Build Android APK**.
4. Click **Run workflow**.
5. When the workflow finishes, download the `bus-times-live-debug-apk` artifact from the run summary.

The workflow also runs automatically for pushes to `main`/`work` and for pull requests.

The project uses the Android Gradle Plugin and has no third-party runtime dependencies.
