package org.bustimes.app;

/**
 * How long a bus marker should take to slide from its previous fix to the new one.
 *
 * The markers used to glide for a fixed 2.5 seconds and then sit still until the next poll, so the
 * map showed a short burst of movement every 15 seconds instead of a vehicle that keeps moving. The
 * glide now lasts about as long as the vehicle really takes to cover the gap, using its own reported
 * speed, and at most one feed interval so a marker never lags behind the position the feed has
 * already published.
 *
 * Kept free of Android types so the timing rule can be unit tested on the JVM.
 */
final class MarkerGlide {

    /** Shorter than this and the slide reads as a jump. */
    static final long MIN_DURATION_MS = 1_200L;

    /**
     * A marker should reach its new reported position before the feed publishes the next one, so a
     * glide never runs on past the position it is animating towards.
     */
    static final long MAX_DURATION_MS = 15_000L;

    /** Used when neither a speed nor a previous fix interval is known. */
    static final long FALLBACK_DURATION_MS = 3_000L;

    /** Below this the vehicle is stationary, so there is no speed to time the slide with. */
    private static final float MOVING_KPH = 3f;

    /** Faster than this is a GPS glitch, not a bus, and no timing can be trusted from it. */
    private static final float MAX_KPH = 120f;

    private MarkerGlide() { }

    /**
     * @param distanceMeters        gap between the marker and the newly reported position
     * @param speedKph              speed estimated for the vehicle, or NaN when unknown
     * @param previousFixIntervalMs gap between the last two fixes the feed published, or 0
     * @return glide duration in milliseconds, bounded by {@link #MIN_DURATION_MS} and
     *         {@link #MAX_DURATION_MS}
     */
    static long durationMs(double distanceMeters, float speedKph, long previousFixIntervalMs) {
        double seconds;
        if (!Float.isNaN(speedKph) && speedKph >= MOVING_KPH && speedKph <= MAX_KPH) {
            seconds = distanceMeters / (speedKph / 3.6d);
        } else if (previousFixIntervalMs > 0) {
            // No honest speed: assume the vehicle keeps the pace it was moving at between the last
            // two published fixes, which is what the distance actually measures.
            seconds = previousFixIntervalMs / 1000d;
        } else {
            return FALLBACK_DURATION_MS;
        }
        long millis = Math.round(seconds * 1000d);
        return Math.max(MIN_DURATION_MS, Math.min(MAX_DURATION_MS, millis));
    }
}
