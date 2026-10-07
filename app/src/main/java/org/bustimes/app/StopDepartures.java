package org.bustimes.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Builds the departure board for one stop from the latest poll of live vehicles.
 *
 * Kept free of Android types so the ranking rules can be unit tested on the JVM, in the same way as
 * {@link ArrivalAlertRules}.
 *
 * A departure board is not the same thing as "buses near this point". A vehicle driving away from
 * the stop is not a departure, and neither is one on a route that does not serve the stop. So where
 * OpenStreetMap tells us which routes use the stop, only those routes appear, and a vehicle counts
 * as a departure only while it is at the stop or heading towards it.
 *
 * Minutes are always estimated from the vehicle's live speed and its distance to the stop, because
 * the SIRI-VM {@code ExpectedArrivalTime} describes the vehicle's own next monitored stop, which is
 * usually a different place. When the vehicle is stopped, or the feed gave no usable speed, the
 * board shows the distance instead of inventing a time.
 */
final class StopDepartures {

    /** How far away a vehicle can still be worth listing. */
    static final int MAX_RADIUS_METERS = 1500;

    /** A vehicle this close is at the stop, whatever heading it reports. */
    private static final int AT_STOP_METERS = 80;

    /** Below this a vehicle counts as stopped, so no time can be estimated from it. */
    private static final float MOVING_KPH = 3f;

    /** One line of the board. */
    static final class Departure {
        final String busId;
        final String route;
        final String destination;
        final int distanceMeters;
        /** Whole minutes to the stop, or -1 when the feed cannot support an estimate. */
        final int minutes;
        final boolean atStop;

        Departure(String busId, String route, String destination,
                int distanceMeters, int minutes, boolean atStop) {
            this.busId = busId == null ? "" : busId;
            this.route = route == null ? "" : route;
            this.destination = destination == null ? "" : destination;
            this.distanceMeters = distanceMeters;
            this.minutes = minutes;
            this.atStop = atStop;
        }

        /** "Due" at the stop, "4 min" when timing is known, otherwise empty. */
        String minutesLabel() {
            if (atStop) {
                return "Due";
            }
            return minutes < 0 ? "" : minutes + " min";
        }

        boolean hasTime() {
            return atStop || minutes >= 0;
        }
    }

    /** One live vehicle as the board sees it. */
    static final class Vehicle {
        final String busId;
        final String route;
        final String destination;
        final double latitude;
        final double longitude;
        final float bearing;
        final float speedKph;

        Vehicle(String busId, String route, String destination,
                double latitude, double longitude, float bearing, float speedKph) {
            this.busId = busId == null ? "" : busId;
            this.route = route == null ? "" : route;
            this.destination = destination == null ? "" : destination;
            this.latitude = latitude;
            this.longitude = longitude;
            this.bearing = bearing;
            this.speedKph = speedKph;
        }
    }

    private StopDepartures() {
    }

    static List<Departure> board(List<Vehicle> vehicles, double stopLat, double stopLon, String stopRoutes) {
        return board(vehicles, stopLat, stopLon, stopRoutes, MAX_RADIUS_METERS);
    }

    /**
     * @param stopRoutes routes OpenStreetMap says use this stop, or empty when unknown
     * @param radiusMeters how far away a vehicle may be and still be listed
     * @return the board, soonest first; vehicles with no usable speed come last, nearest first
     */
    static List<Departure> board(List<Vehicle> vehicles, double stopLat, double stopLon,
            String stopRoutes, int radiusMeters) {
        List<Departure> result = new ArrayList<>();
        if (vehicles == null) {
            return result;
        }
        List<String> served = parseRoutes(stopRoutes);
        for (Vehicle vehicle : vehicles) {
            double distance = ArrivalAlertRules.distanceMeters(
                    stopLat, stopLon, vehicle.latitude, vehicle.longitude);
            if (distance > radiusMeters) {
                continue;
            }
            if (!served.isEmpty() && !serves(served, vehicle.route)) {
                continue;
            }
            boolean atStop = distance <= AT_STOP_METERS;
            if (!atStop && isReceding(vehicle.bearing, vehicle.latitude, vehicle.longitude,
                    stopLat, stopLon)) {
                continue;
            }
            result.add(new Departure(vehicle.busId, vehicle.route, vehicle.destination,
                    (int) Math.round(distance), minutesToStop(distance, vehicle.speedKph, atStop), atStop));
        }
        Collections.sort(result, (a, b) -> {
            boolean timedA = a.hasTime();
            boolean timedB = b.hasTime();
            if (timedA != timedB) {
                return timedA ? -1 : 1;
            }
            if (timedA && a.minutes != b.minutes) {
                return Integer.compare(a.minutes, b.minutes);
            }
            return Integer.compare(a.distanceMeters, b.distanceMeters);
        });
        return result;
    }

    /** Routes listed on the stop, e.g. "350, 4" or an OSM {@code route_ref} using semicolons. */
    static List<String> parseRoutes(String stopRoutes) {
        List<String> routes = new ArrayList<>();
        if (stopRoutes == null) {
            return routes;
        }
        for (String part : stopRoutes.split("[,;]")) {
            String route = part.trim();
            if (!route.isEmpty()) {
                routes.add(route);
            }
        }
        return routes;
    }

    private static boolean serves(List<String> served, String route) {
        for (String candidate : served) {
            if (candidate.equalsIgnoreCase(route.trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whole minutes at the vehicle's current speed, rounded up so the board never promises a bus
     * sooner than it can arrive, or -1 when the vehicle is not moving and no time can be estimated.
     */
    private static int minutesToStop(double distanceMeters, float speedKph, boolean atStop) {
        if (atStop) {
            return 0;
        }
        if (Float.isNaN(speedKph) || speedKph < MOVING_KPH) {
            return -1;
        }
        double metresPerMinute = speedKph * 1000d / 60d;
        if (metresPerMinute <= 0d) {
            return -1;
        }
        return (int) Math.ceil(distanceMeters / metresPerMinute);
    }

    /** True when the vehicle's heading points away from the stop, so it is not about to depart. */
    private static boolean isReceding(float bearing, double lat, double lon, double stopLat, double stopLon) {
        if (Float.isNaN(bearing) || bearing < 0f) {
            return false; // an unknown heading is not evidence of driving away
        }
        double toStop = bearingBetween(lat, lon, stopLat, stopLon);
        return Math.abs(normaliseDegrees(bearing - toStop)) > 90d;
    }

    /** Initial great-circle bearing from one point to another, in degrees. */
    static double bearingBetween(double lat1, double lon1, double lat2, double lon2) {
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double deltaLon = Math.toRadians(lon2 - lon1);
        double y = Math.sin(deltaLon) * Math.cos(phi2);
        double x = Math.cos(phi1) * Math.sin(phi2) - Math.sin(phi1) * Math.cos(phi2) * Math.cos(deltaLon);
        return (Math.toDegrees(Math.atan2(y, x)) + 360d) % 360d;
    }

    /** Folds an angle difference into -180..180. */
    private static double normaliseDegrees(double degrees) {
        return ((degrees % 360d) + 540d) % 360d - 180d;
    }
}
