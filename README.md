# Bus Times Live Android

A map-first Android wrapper for [bustimes.org](https://bustimes.org/) focused on the live tracker at `https://bustimes.org/map`. The app opens directly to the real Bus Times map so bus markers show their live route numbers and locations using the website's own live data.

## Features

- Live bus map with numbered bus markers from bustimes.org.
- Bus stop markers from bustimes.org; tapping a stop shows the served route numbers and links through to the stop's live departure times.
- Native **Home / locate me** button that asks for Android location permission and recentres the live map on the user's current position.
- WebView support for JavaScript, DOM storage, cookies, geolocation prompts, back navigation, external map/mail/phone links, and downloads.

## Build

Open the project in Android Studio or build from the command line with an Android SDK installed:

```bash
gradle :app:assembleDebug
```

The project uses the Android Gradle Plugin and has no third-party runtime dependencies.
