package org.bustimes.app;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

/**
 * One armed proximity alert: "notify me when route 350 is 5 minutes away from &lt;place&gt;".
 *
 * The alert is anchored at a real place (the user's location or a bus stop) and remembers both a
 * minute threshold and the radius that threshold stands for, so the poller can raise it either
 * from a live BODS ETA or from the vehicle crossing the radius.
 */
final class ArrivalAlert {
    final String id;
    final String route;
    final String placeLabel;
    final double latitude;
    final double longitude;
    final int minutes;
    final int radiusMeters;
    final long createdMs;

    ArrivalAlert(String route, String placeLabel, double latitude, double longitude, int minutes, long createdMs) {
        this.route = route == null ? "" : route.trim();
        this.placeLabel = placeLabel == null ? "" : placeLabel.trim();
        this.latitude = latitude;
        this.longitude = longitude;
        this.minutes = ArrivalAlertRules.normaliseMinutes(minutes);
        this.radiusMeters = ArrivalAlertRules.radiusForMinutes(this.minutes);
        this.createdMs = createdMs;
        this.id = ArrivalAlertRules.alertId(this.route, latitude, longitude);
    }

    private ArrivalAlert(JSONObject json) {
        this(json.optString("route", ""), json.optString("place", ""),
                json.optDouble("lat", 0), json.optDouble("lon", 0),
                json.optInt("minutes", ArrivalAlertRules.DEFAULT_MINUTES),
                json.optLong("created", System.currentTimeMillis()));
    }

    static ArrivalAlert fromJson(JSONObject json) {
        return new ArrivalAlert(json);
    }

    JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("route", route);
        json.put("place", placeLabel);
        json.put("lat", latitude);
        json.put("lon", longitude);
        json.put("minutes", minutes);
        json.put("radius", radiusMeters);
        json.put("created", createdMs);
        return json;
    }

    /** One line description used by the Account tab and the alert picker. */
    String summary() {
        String place = placeLabel.isEmpty() ? "your area" : placeLabel;
        return route + " \u00B7 " + minutes + " min / " + radiusMeters + " m of " + place;
    }

    /** Notification line describing what triggered the alert. */
    String triggerText(boolean etaTriggered, int etaMinutes, int distanceMeters) {
        String place = placeLabel.isEmpty() ? "you" : placeLabel;
        if (etaTriggered) {
            return String.format(Locale.UK, "About %d min away from %s", Math.max(0, etaMinutes), place);
        }
        return String.format(Locale.UK, "Within %d m of %s", distanceMeters, place);
    }
}
