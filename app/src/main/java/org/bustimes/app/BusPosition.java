package org.bustimes.app;

final class BusPosition {
    static final String EXTRA_ID = "id";
    static final String EXTRA_LINE_NAME = "line_name";
    static final String EXTRA_LINE_REF = "line_ref";
    static final String EXTRA_DESTINATION_NAME = "destination_name";
    static final String EXTRA_EXPECTED_ARRIVAL_TIME = "expected_arrival_time";
    static final String EXTRA_LATITUDE = "latitude";
    static final String EXTRA_LONGITUDE = "longitude";
    static final String EXTRA_BEARING = "bearing";
    static final String EXTRA_RECORDED_AT = "recorded_at";
    static final String EXTRA_OCCUPANCY = "occupancy";

    final String id;
    final String lineName;
    final String lineRef;
    final String destinationName;
    final String expectedArrivalTime;
    final double latitude;
    final double longitude;
    final float bearing;
    final String recordedAt;
    final String occupancy;

    BusPosition(String id, String lineName, String lineRef, String destinationName, String expectedArrivalTime,
            double latitude, double longitude, float bearing, String recordedAt, String occupancy) {
        this.id = id;
        this.lineName = lineName;
        this.lineRef = lineRef;
        this.destinationName = destinationName;
        this.expectedArrivalTime = expectedArrivalTime;
        this.latitude = latitude;
        this.longitude = longitude;
        this.bearing = bearing;
        this.recordedAt = recordedAt;
        this.occupancy = occupancy;
    }
}
