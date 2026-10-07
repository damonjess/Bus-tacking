package org.bustimes.app;

/**
 * Pure decision logic for proximity / arrival alerts.
 *
 * An alert is armed for one route at one place. Polling raises a notification as soon as a
 * vehicle on that route is either inside the alert radius or - when the BODS feed supplies a
 * live ETA - expected within the alert's minute threshold. The minute presets are also offered
 * to the user as an approximate walking-time radius, which is the only signal the feed always
 * provides (see {@link #radiusForMinutes(int)}).
 *
 * Kept free of Android types so the notification rules can be unit tested on the JVM.
 */
final class ArrivalAlertRules {

    /** Minute presets surfaced in the alert picker, in order. */
    static final int[] MINUTE_PRESETS = {2, 5, 10, 15};

    /** Radius each preset stands for, used when the feed has no ETA for the vehicle. */
    private static final int[] RADIUS_FOR_MINUTE = {400, 800, 1600, 2400};

    static final int DEFAULT_MINUTES = 5;

    /** Do not re-notify for the same alert more often than this. */
    static final long COOLDOWN_MS = 10 * 60 * 1000L;

    private static final double EARTH_RADIUS_METERS = 6_371_000d;

    private ArrivalAlertRules() {
    }

    /** Approximate radius, in metres, that a "N minutes away" alert watches. */
    static int radiusForMinutes(int minutes) {
        for (int i = 0; i < MINUTE_PRESETS.length; i++) {
            if (MINUTE_PRESETS[i] == minutes) {
                return RADIUS_FOR_MINUTE[i];
            }
        }
        return RADIUS_FOR_MINUTE[1];
    }

    /** Nearest preset to an arbitrary stored value, so old alerts stay editable. */
    static int normaliseMinutes(int minutes) {
        int best = MINUTE_PRESETS[0];
        int bestDelta = Integer.MAX_VALUE;
        for (int preset : MINUTE_PRESETS) {
            int delta = Math.abs(preset - minutes);
            if (delta < bestDelta) {
                bestDelta = delta;
                best = preset;
            }
        }
        return best;
    }

    /**
     * @param etaMinutes     live ETA for the vehicle in minutes, or negative when the feed has none
     * @param distanceMeters straight-line distance from the vehicle to the alert place
     * @param radiusMeters   radius armed for this alert
     * @param minutesThreshold minute threshold armed for this alert
     * @return true when the vehicle should raise a notification
     */
    static boolean shouldNotify(int etaMinutes, double distanceMeters, int radiusMeters, int minutesThreshold) {
        if (etaMinutes >= 0 && etaMinutes <= minutesThreshold) {
            return true;
        }
        return distanceMeters <= radiusMeters;
    }

    /** True when the notification was raised by the live ETA rather than the radius. */
    static boolean etaTriggered(int etaMinutes, int minutesThreshold) {
        return etaMinutes >= 0 && etaMinutes <= minutesThreshold;
    }

    /** Great-circle distance in metres. */
    static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_RADIUS_METERS * Math.asin(Math.min(1d, Math.sqrt(a)));
    }

    /** Stable identity for an alert so duplicates collapse into one. */
    static String alertId(String route, double latitude, double longitude) {
        return (route == null ? "" : route.trim())
                + "|" + Math.round(latitude * 1000d)
                + "|" + Math.round(longitude * 1000d);
    }
}
