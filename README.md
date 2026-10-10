# Bus Times Live

A native Android app for real-time UK bus tracking, interactive OpenStreetMap maps, live bus stop departures, arrival alerts and a camera (AR) view, using data from the **UK Bus Open Data Service (BODS)** and **OpenStreetMap**.

---

## 🌟 Features

- **Live Bus Map**: Track live buses on an interactive map (Standard, Dark, or Terrain styles) with smooth marker animation.
- **Route Lines**: Tap a bus or a route chip to draw that route's line on the map, taken from OpenStreetMap.
- **Stop Departures**: Tap any bus stop to see a departure board built from live buses, and the routes served.
- **Bus Details**: Speed, heading, ETA, line, operator name, occupancy, and how recent the bus's last GPS report is.
- **AR View**: Open the camera and see nearby buses and stops labelled where they really are. Tap a label for details.
- **Search & Favorites**: Search for routes, destinations, and stops, and save favorite routes and stops.
- **Arrival Alerts**: Background notifications when a tracked bus is approaching (2, 5, 10, or 15 minutes away).
- **Nearby Tab**: A GPS-sorted list of nearby buses and stops.

---

## 📡 About the Live Data

- **Old reports are hidden.** The BODS feed keeps serving a vehicle's last report long after it stops transmitting. The app hides any report older than 10 minutes, and the Account tab status says how many were hidden.
- **Operator names** come from Traveline's National Operator Codes (NOC) list, downloaded once and cached for 30 days. If a code isn't in the list, the raw code is shown.
- **Departure times are estimates.** Departure boards use live bus positions, not timetables, so they show estimated minutes from distance and speed, and nothing when speed is unknown.
- Bus stops and route lines come from OpenStreetMap, so coverage depends on how well your area is mapped.

---

## 📷 AR View

Tap **AR view** in the header on the Map tab.

- Needs **camera** and **location** permission, and a phone with a compass sensor.
- Works best outdoors with a clear view of the sky. Indoors, compass interference and a weak GPS fix can put labels in the wrong place.
- The status bar shows the direction you are facing and the GPS accuracy, and the bottom hint warns about magnetic interference or a poor location fix.
- If the compass needs calibrating, wave the phone in a figure 8.
- If every label is consistently off to one side, use the **Aim** arrows under the labels to nudge them. The setting is remembered.
- AR is built on CameraX, the phone's rotation sensor and GPS. It does not need Google Maps keys.

---

## 🛠️ Tech Stack

- **Language**: Java (Android SDK 35, Min SDK 24), built with JDK 17
- **Map Engine**: `osmdroid`
- **Camera**: CameraX
- **UI Framework**: Material Components & AppCompat
- **Data Sources**: BODS SIRI-VM feed, OpenStreetMap Overpass API, Traveline NOC operator list

---

## ⚙️ Building the App

### Prerequisites
- Android Studio or JDK 17 with the Android SDK installed.

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
| `BODS_API_KEY` | UK Bus Open Data Service consumer API key (free, from your BODS account settings) | Optional (map and stops still work) |
| `BODS_API_BASE_URL` | BODS SIRI-VM feed base URL | `https://data.bus-data.dft.gov.uk/api/v1/datafeed/` |
| `BODS_BOUNDING_BOX` | Geographic bounding box filter `(minLon,minLat,maxLon,maxLat)` | Automatically uses visible map bounds |

Example `local.properties`:

```properties
BODS_API_KEY=your_bods_api_key_here
```

Keep your key private: don't commit `local.properties` or paste the key into screenshots or chats.

---

## 📜 License

Distributed under open source terms. Map, bus stop and route data © [OpenStreetMap](https://www.openstreetmap.org/copyright) contributors. Real-time bus location data provided by the UK Department for Transport Bus Open Data Service (BODS). Operator names from Traveline's National Operator Codes database.
