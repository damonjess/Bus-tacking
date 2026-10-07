package org.bustimes.app;

/**
 * Estimates a bus's ground speed from the fixes the feed actually publishes.
 *
 * The obvious formula - distance between our own polls divided by the poll interval - is wrong on
 * this feed. Measured against the live SIRI-VM feed, only about half of the fleet changes position
 * between two 15-second polls, and some entries go minutes without a new timestamp, so that formula
 * reports 0 km/h for vehicles that are plainly moving and shows "Stopped" for a bus driving down a
 * road.
 *
 * SIRI-VM timestamps every fix, so the honest denominator is the gap between the two fixes the feed
 * really made. When the feed repeats its last fix there is nothing new to measure, and the speed
 * already known is kept rather than replaced by a false zero. A fix whose own timestamp is old is no
 * evidence either way, so no speed is claimed at all.
 *
 * Kept free of Android types so the rule can be unit tested on the JVM.
 */
final class VehicleSpeed {

    /** Faster than this and the pair of fixes is a GPS glitch rather than a bus. */
    private static final float MAX_KPH = 120f;

    /** A fix older than this says nothing about how the vehicle is moving now. */
    private static final long STALE_FIX_MS = 90_000L;

    private VehicleSpeed() {
    }

    /**
     * @param previousRecordedMs feed timestamp of the fix already held for this vehicle, or 0
     * @param recordedMs         feed timestamp of the fix just received, or 0 when it carried none
     * @param previousSpeedKph   speed already known for this vehicle, or NaN
     * @param nowMs              wall clock, used only to judge how old the newest fix is
     * @return speed in km/h, or NaN when no honest estimate is possible
     */
    static float estimate(double previousLat, double previousLon, long previousRecordedMs,
            double lat, double lon, long recordedMs, float previousSpeedKph, long nowMs) {

        if (recordedMs <= 0 || nowMs - recordedMs > STALE_FIX_MS) {
            // The feed gave no timestamp, or one old enough that nothing can be claimed from it.
            return Float.NaN;
        }
        if (previousRecordedMs <= 0 || recordedMs <= previousRecordedMs) {
            // The feed repeated its last fix: keep what we knew instead of reporting a false stop.
            return previousSpeedKph;
        }
        double seconds = (recordedMs - previousRecordedMs) / 1000d;
        double metres = ArrivalAlertRules.distanceMeters(previousLat, previousLon, lat, lon);
        float kph = (float) (metres / seconds * 3.6);
        return kph > MAX_KPH ? Float.NaN : kph;
    }
}
