package org.bustimes.app;

final class BusBillboard {
    final String id;
    String lineName = "Bus";
    String destinationName = "destination unknown";
    String etaText = "ETA unknown";
    String occupancy = "Information Unknown";
    public String delayExplanation;
    double latitude;
    double longitude;
    long lastUpdatedMs;
    float displayedX = Float.NaN;
    float displayedY = Float.NaN;

    BusBillboard(String id) {
        this.id = id;
        this.delayExplanation = "";
    }
}
