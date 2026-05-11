package org.bustimes.app;

final class BusPosition {
    static final String EXTRA_ID = "id";
    static final String EXTRA_LINE_NAME = "line_name";
    static final String EXTRA_LATITUDE = "latitude";
    static final String EXTRA_LONGITUDE = "longitude";
    static final String EXTRA_BEARING = "bearing";
    static final String EXTRA_RECORDED_AT = "recorded_at";

    final String id;
    final String lineName;
    final double latitude;
    final double longitude;
    final float bearing;
    final String recordedAt;

    BusPosition(String id, String lineName, double latitude, double longitude, float bearing, String recordedAt) {
        this.id = id;
        this.lineName = lineName;
        this.latitude = latitude;
        this.longitude = longitude;
        this.bearing = bearing;
        this.recordedAt = recordedAt;
    }
}
