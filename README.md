# Bus Times Live

A native Android app for real-time UK bus tracking, interactive OpenStreetMap maps, live bus stop departures, and arrival alerts using data from the **UK Bus Open Data Service (BODS)** and **OpenStreetMap**.

---

## 🌟 Features

- **Live Bus Map**: Track live buses on an interactive map (Standard, Dark, or Terrain styles) with smooth marker animation.
- **Route Lines**: Tap any bus marker or route filter chip to draw its full OpenStreetMap route line on the map.
- **Stop Departures & Favorites**: Tap any bus stop to view real-time departure boards, and save favorite stops and routes for single-tap access.
- **Bus Metrics**: View real-time speed, heading, ETA, line, operator, and occupancy status.
- **Search**: Search for routes, destinations, and bus stops with instant suggestions.
- **Arrival Alerts**: Receive background notifications when a tracked bus is approaching (2, 5, 10, or 15 minutes away).
- **Nearby Tab**: View a GPS-sorted list of nearby buses and stops within walking distance.

---

## 🛠️ Tech Stack

- **Language**: Java (Built with JDK 17, Android SDK 35, Min SDK 24)
- **Map Engine**: `osmdroid`
- **UI Framework**: Material Components & AppCompat
- **Data Sources**: BODS SIRI-VM feed & OpenStreetMap Overpass API

---

## ⚙️ Building the App

### Prerequisites
- Android Studio or JDK 17 with Android SDK installed.

### Build Commands

Build debug APK:
```bash
./gradlew :app:assembleDebug
```

Run unit tests:
```bash
./gradlew :app:testDebugUnitTest
```

The compiled APK is placed at `app/build/outputs/apk/debug/app-debug.apk`.

---

## 🔑 Configuration & API Keys

API keys and configuration can be added to `local.properties` or environment variables:

| Variable | Description | Default |
| :--- | :--- | :--- |
| `BODS_API_KEY` | UK Bus Open Data Service consumer API key | Optional (map and stops still work) |
| `BODS_API_BASE_URL` | BODS SIRI-VM feed base URL | `https://data.bus-data.dft.gov.uk/api/v1/datafeed/` |
| `BODS_BOUNDING_BOX` | Geographic bounding box filter `(minLon,minLat,maxLon,maxLat)` | Automatically uses visible map bounds |

Example `local.properties`:

```properties
BODS_API_KEY=your_bods_api_key_here
```

---

## 📜 License

Distributed under open source terms. Map and bus stop data © [OpenStreetMap](https://www.openstreetmap.org/copyright) contributors. Real-time bus location data provided by the UK Department for Transport Bus Open Data Service (BODS).
