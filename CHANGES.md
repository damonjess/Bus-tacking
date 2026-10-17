# Native rewrite (no WebView, no bustimes.org)

## 2026-10 fix: no bus markers when the map viewport was measured too early

**Root cause:** the feed request followed the map viewport, but `MapView` reports a zero-size box until it
has been laid out (`-0.6548,53.5786,-0.6548,53.5786`). `MainActivity.onResume()` runs before that first
layout, so the service asked BODS for an empty area and got zero vehicles back, leaving the map blank.

**Changes:**
- New `BoundingBoxes` helper validates every `west,south,east,north` box; zero-size, reversed or
  malformed boxes are rejected instead of being sent to the feed.
- `MainActivity` only reports the viewport once the map has a real width/height, and re-requests the
  feed after the first layout. Zooming now refreshes the feed as well as panning.
- `BusTrackingService` skips an unusable `activeBoundingBox` and falls back to `BODS_BOUNDING_BOX`
  (or the built-in Humber-area default).
- Live viewport still wins over `BODS_BOUNDING_BOX`, so panning outside the old fixed box keeps working;
  the `BODS_BOUNDING_BOX` default in `app/build.gradle.kts` is empty (= follow the map).
- Restored the full `MainActivity` (tabs, stop boards, route lines, favourites and alerts) that an
  earlier rewrite had replaced with a stripped-down 628-line version.

Rebuild in Android Studio or with:

```bash
BODS_API_KEY=your-key ./gradlew :app:assembleDebug
```

The APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

---

Original notes:

Replace/add these files in your project:
- app/src/main/java/org/bustimes/app/MainActivity.java  (new, replaces the old one)
- app/build.gradle.kts  (adds osmdroid + appcompat)
- app/src/main/res/layout/sheet_bus_details.xml  (button renamed "Walk there")

Needs `BODS_API_KEY` in local.properties for live buses. Map, stops, search and layers work without it.
