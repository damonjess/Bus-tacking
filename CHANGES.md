# Native rewrite (no WebView, no bustimes.org)

Replace/add these files in your project:
- app/src/main/java/org/bustimes/app/MainActivity.java  (new, replaces the old one)
- app/build.gradle.kts  (adds osmdroid + appcompat)
- app/src/main/res/layout/sheet_bus_details.xml  (button renamed "Walk there")

Needs `BODS_API_KEY` in local.properties for live buses. Map, stops, search and layers work without it.
