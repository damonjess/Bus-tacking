package org.bustimes.app;

/** Mutable live-bus record rendered as an AR card, ghost silhouette and radar blip. */
final class BusBillboard {
    final String id;
    String lineName = "";
    String destinationName = "";
    String etaText = "";
    String occupancy = "Information Unknown";
    double latitude;
    double longitude;
    float bearingDegrees;
    float speedKph;
    long lastUpdatedMs;
    float displayedX = Float.NaN;
    float displayedY = Float.NaN;

    BusBillboard(String id) {
        this.id = id;
    }
}
