# Native rewrite (no WebView, no bustimes.org)

## 2026-10: stop timetables, and bus markers that keep moving

**Timetable in the stop card.** The live feed (SIRI-VM) has no timetable at all, so scheduled times
come from the DfT Bus Open Data timetables, which are TransXChange documents published by operators.

- `TxcTimetable` streams one TransXChange document and returns the times it gives one stop: journey
  patterns, their run times, per-journey overrides, day-of-week rules and operating periods.
- `BodsTimetables` lists the datasets for a stop's ATCO area and pulls the stop's times out of a
  dataset, whether it is served as a zip of documents or a single XML file.
- `StopTimetables` caches the result per stop for a week and picks the datasets worth trying: those
  naming one of the stop's routes first, then the rest of the area's timetables smallest first, and
  it stops as soon as the stop's times appear.
- Tapping a stop now shows a **Timetable** section with today's next scheduled departures, plus a
  **Load timetable** / **Refresh** button. Nothing is downloaded until that button is tapped, because
  one operator's timetable can run to tens of megabytes; the download shows its own percentage.
- Stops carry their NaPTAN `naptan:AtcoCode` from OpenStreetMap (cached lists bump to `-v2`, so an old
  cache without the code is re-fetched once), and saved stops keep it too.

**Smoother bus markers.** A marker used to slide for a fixed 2.5 s and then sit still until the next
15-second poll, which read as a burst of movement followed by a jump.

- `MarkerGlide` times the slide from the vehicle's own speed (falling back to the gap between the
  last two published fixes), bounded to one feed interval, so a moving bus keeps moving.
- All glides now share one redraw per animation frame instead of one each, which stops a dozen
  moving markers queueing a dozen redraws into the same frame.

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
