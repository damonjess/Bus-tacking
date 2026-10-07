package org.bustimes.app;

import android.content.Context;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Evaluates armed arrival alerts against the latest poll of live vehicles.
 *
 * Called from both the map screen (after a broadcast update) and the BusTrackingService (once per
 * poll), so an alert still fires when the app is in the background and only the poller is running.
 */
final class ArrivalAlerts {

    /** One live vehicle as seen by the alert rules. */
    static final class Candidate {
        final String route;
        final String destination;
        final double latitude;
        final double longitude;
        final int etaMinutes;

        Candidate(String route, String destination, double latitude, double longitude, int etaMinutes) {
            this.route = route == null ? "" : route;
            this.destination = destination == null ? "" : destination;
            this.latitude = latitude;
            this.longitude = longitude;
            this.etaMinutes = etaMinutes;
        }
    }

    private ArrivalAlerts() {
    }

    static void evaluate(Context context, List<Candidate> candidates) {
        if (context == null || candidates == null || candidates.isEmpty()) {
            return;
        }
        List<ArrivalAlert> alerts = ArrivalAlertStore.load(context);
        if (alerts.isEmpty()) {
            return;
        }
        // a service evaluating alerts from a background process must not run before permission is held
        if (!AlertNotifier.canPost(context)) {
            return;
        }
        long now = System.currentTimeMillis();
        for (ArrivalAlert alert : alerts) {
            Candidate best = null;
            double bestDistance = Double.MAX_VALUE;
            boolean etaTriggered = false;
            for (Candidate candidate : candidates) {
                if (!alert.route.equalsIgnoreCase(candidate.route)) {
                    continue;
                }
                double distance = ArrivalAlertRules.distanceMeters(
                        alert.latitude, alert.longitude, candidate.latitude, candidate.longitude);
                boolean etaHit = ArrivalAlertRules.etaTriggered(candidate.etaMinutes, alert.minutes);
                boolean radiusHit = distance <= alert.radiusMeters;
                if (!etaHit && !radiusHit) {
                    continue;
                }
                if (best == null || distance < bestDistance) {
                    best = candidate;
                    bestDistance = distance;
                    etaTriggered = etaHit;
                }
            }
            if (best == null || !ArrivalAlertStore.canFire(context, alert.id, now)) {
                continue;
            }
            ArrivalAlertStore.recordFired(context, alert.id, now);
            AlertNotifier.post(context, alert, best.destination,
                    alert.triggerText(etaTriggered, best.etaMinutes, (int) Math.round(bestDistance)));
        }
    }

    /** Minutes until a SIRI-VM ExpectedArrivalTime, or -1 when the feed did not supply one. */
    static int etaMinutesFrom(String expectedArrivalTime) {
        long arrival = parseIso(expectedArrivalTime);
        if (arrival <= 0) {
            return -1;
        }
        long diff = arrival - System.currentTimeMillis();
        if (diff < -60_000L) {
            return -1;
        }
        return (int) Math.max(0, diff / 60_000L);
    }

    /** Parses an ISO-8601 timestamp with an offset; returns 0 when it can't be read. */
    static long parseIso(String value) {
        if (value == null || value.isEmpty()) {
            return 0;
        }
        try {
            String cleaned = value.replaceFirst("\\.\\d+", "");
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.UK);
            format.setTimeZone(TimeZone.getTimeZone("UTC"));
            return format.parse(cleaned).getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    /** Convenience builder used by the map screen. */
    static List<Candidate> candidatesFromPositions(List<BusPosition> positions) {
        List<Candidate> candidates = new ArrayList<>();
        for (BusPosition position : positions) {
            candidates.add(new Candidate(position.lineName, position.destinationName,
                    position.latitude, position.longitude, etaMinutesFrom(position.expectedArrivalTime)));
        }
        return candidates;
    }
}
