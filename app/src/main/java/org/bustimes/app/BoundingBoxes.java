package org.bustimes.app;

import java.util.Locale;

/**
 * Validation for the {@code west,south,east,north} bounding box strings shared with BODS.
 * <p>
 * A map that has not been laid out yet reports a zero-size viewport, and formatting that as a
 * box produces something like {@code -0.6548,53.5786,-0.6548,53.5786}. The feed treats that as a
 * real (empty) area, so every vehicle disappears. Anything that is not a sane box is rejected
 * here and the caller falls back to a known-good area instead.
 */
final class BoundingBoxes {

    /** Anything smaller than this in either direction is treated as a degenerate box. */
    private static final double MIN_SPAN_DEGREES = 0.0001;

    private BoundingBoxes() { }

    /** True when {@code bbox} is a usable west,south,east,north box. */
    static boolean isUsable(String bbox) {
        return parse(bbox) != null;
    }

    /** Returns {west, south, east, north}, or {@code null} when the box is missing or unusable. */
    static double[] parse(String bbox) {
        if (bbox == null) {
            return null;
        }
        String[] parts = bbox.split(",");
        if (parts.length != 4) {
            return null;
        }
        double[] values = new double[4];
        for (int i = 0; i < 4; i++) {
            try {
                values[i] = Double.parseDouble(parts[i].trim());
            } catch (NumberFormatException e) {
                return null;
            }
            if (Double.isNaN(values[i]) || Double.isInfinite(values[i])) {
                return null;
            }
        }
        double west = values[0];
        double south = values[1];
        double east = values[2];
        double north = values[3];
        if (south < -90 || north > 90 || west < -180 || east > 180) {
            return null;
        }
        if (east - west < MIN_SPAN_DEGREES || north - south < MIN_SPAN_DEGREES) {
            return null;
        }
        return values;
    }

    /** Formats a box the way the feed expects it. */
    static String format(double west, double south, double east, double north) {
        return String.format(Locale.US, "%.4f,%.4f,%.4f,%.4f", west, south, east, north);
    }
}
