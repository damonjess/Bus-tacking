package org.bustimes.app;

/** Mutable live-bus record rendered as an AR card, ghost silhouette and radar blip. */
final class BusBillboard {
    final String id;
    String lineName = "Bus";
    String destinationName = "destination unknown";
    String etaText = "ETA unknown";
    String occupancy = "Information Unknown";
    public String delayExplanation;
    double latitude;
    double longitude;
    float bearingDegrees;
    float speedKph;
    long lastUpdatedMs;
    float displayedX = Float.NaN;
    float displayedY = Float.NaN;

    BusBillboard(String id) {
        this.id = id;
        this.delayExplanation = "";
    }
}
