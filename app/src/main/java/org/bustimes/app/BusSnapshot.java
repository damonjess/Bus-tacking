package org.bustimes.app;

import java.util.Locale;

/** Immutable view-model for the bus details bottom sheet. */
final class BusSnapshot {
    final String busId;
    final String lineName;
    final String lineRef;
    final String destinationName;
    final String occupancy;
    final String vehicleId;
    final String lastSeen;
    final String operatorName;
    final String distanceText;
    final double latitude;
    final double longitude;
    final float bearing;
    final float speedKph;
    final int expectedEtaMinutes;
    final String arrivalStopName;

    BusSnapshot(String busId, String lineName, String lineRef, String destinationName, String occupancy,
            String vehicleId, String lastSeen, String operatorName, String distanceText,
            double latitude, double longitude, float bearing, float speedKph,
            int expectedEtaMinutes, String arrivalStopName) {
        this.busId = busId;
        this.lineName = lineName == null || lineName.trim().isEmpty() ? "Bus" : lineName.trim();
        this.lineRef = lineRef == null ? "" : lineRef.trim();
        this.destinationName = destinationName == null || destinationName.trim().isEmpty()
                ? "destination unknown" : destinationName.trim();
        this.occupancy = occupancy == null || occupancy.trim().isEmpty() ? "Information Unknown" : occupancy.trim();
        this.vehicleId = vehicleId == null || vehicleId.trim().isEmpty() ? "—" : vehicleId.trim();
        this.lastSeen = lastSeen == null ? "" : lastSeen.trim();
        this.operatorName = operatorName == null ? "" : operatorName.trim();
        this.distanceText = distanceText == null ? "" : distanceText.trim();
        this.latitude = latitude;
        this.longitude = longitude;
        this.bearing = bearing;
        this.speedKph = speedKph;
        this.expectedEtaMinutes = expectedEtaMinutes;
        this.arrivalStopName = arrivalStopName == null || arrivalStopName.trim().isEmpty()
                ? "its next stop" : arrivalStopName.trim();
    }

    String speedText() {
        if (speedKph <= 0.5f) {
            return "Stopped";
        }
        return String.format(Locale.UK, "%.0f mph", speedKph * 0.621371f);
    }

    String compassBearingText() {
        if (Float.isNaN(bearing) || bearing < 0f) {
            return "—";
        }
        String[] compass = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        int index = (int) Math.floor(((bearing % 360f) + 22.5f) / 45f) % 8;
        return compass[index] + " · " + Math.round(bearing) + "°";
    }
}
