package org.bustimes.app;

import java.util.ArrayList;
import java.util.List;

/**
 * Joins the individual OSM way segments of a bus route relation into continuous lines so the
 * map can draw one polyline per direction instead of dozens of disconnected stubs.
 *
 * Ways are joined end-to-end (in either orientation) whenever their end points are within
 * {@link #JOIN_TOLERANCE_METERS}. Anything that does not line up stays a line of its own - the
 * joiner never invents geometry to bridge a gap.
 *
 * Pure Java (no Android types) so the joins can be unit tested.
 */
final class RouteShapeJoiner {

    /** Ways separated by less than this are treated as contiguous. */
    static final double JOIN_TOLERANCE_METERS = 45d;

    private RouteShapeJoiner() {
    }

    /**
     * @param ways each entry an array of {latitude, longitude} pairs
     * @return continuous lines, each an array of {latitude, longitude} pairs
     */
    static List<double[][]> join(List<double[][]> ways) {
        List<double[][]> remaining = new ArrayList<>();
        for (double[][] way : ways) {
            if (way != null && way.length >= 2) {
                remaining.add(way);
            }
        }
        List<double[][]> lines = new ArrayList<>();
        while (!remaining.isEmpty()) {
            double[][] seedWay = remaining.remove(0);
            List<double[]> points = new ArrayList<>();
            for (double[] point : seedWay) {
                points.add(point);
            }
            double[] head = points.get(0);
            double[] tail = points.get(points.size() - 1);
            boolean extended = true;
            while (extended && !remaining.isEmpty()) {
                extended = false;
                for (int i = 0; i < remaining.size(); i++) {
                    double[][] way = remaining.get(i);
                    double[] first = way[0];
                    double[] last = way[way.length - 1];
                    if (close(tail, first)) {
                        append(points, way, 1);
                        tail = points.get(points.size() - 1);
                    } else if (close(tail, last)) {
                        appendReversed(points, way, way.length - 2);
                        tail = points.get(points.size() - 1);
                    } else if (close(head, first)) {
                        prependReversed(points, way, 1);
                        head = points.get(0);
                    } else if (close(head, last)) {
                        prepend(points, way, way.length - 2);
                        head = points.get(0);
                    } else {
                        continue;
                    }
                    remaining.remove(i);
                    extended = true;
                    break;
                }
            }
            lines.add(points.toArray(new double[points.size()][]));
        }
        return lines;
    }

    private static void append(List<double[]> points, double[][] way, int fromIndex) {
        for (int i = fromIndex; i < way.length; i++) {
            points.add(way[i]);
        }
    }

    private static void appendReversed(List<double[]> points, double[][] way, int fromIndex) {
        for (int i = fromIndex; i >= 0; i--) {
            points.add(way[i]);
        }
    }

    private static void prepend(List<double[]> points, double[][] way, int fromIndex) {
        for (int i = fromIndex; i >= 0; i--) {
            points.add(0, way[i]);
        }
    }

    private static void prependReversed(List<double[]> points, double[][] way, int fromIndex) {
        for (int i = fromIndex; i < way.length; i++) {
            points.add(0, way[i]);
        }
    }

    private static boolean close(double[] a, double[] b) {
        return ArrivalAlertRules.distanceMeters(a[0], a[1], b[0], b[1]) <= JOIN_TOLERANCE_METERS;
    }

    /** Removes consecutive duplicate points and lines that are too short to be worth drawing. */
    static List<double[][]> tidy(List<double[][]> lines) {
        List<double[][]> tidy = new ArrayList<>();
        for (double[][] line : lines) {
            List<double[]> points = new ArrayList<>();
            for (double[] point : line) {
                if (points.isEmpty() || !samePoint(points.get(points.size() - 1), point)) {
                    points.add(point);
                }
            }
            if (points.size() >= 2) {
                tidy.add(points.toArray(new double[points.size()][]));
            }
        }
        return tidy;
    }

    private static boolean samePoint(double[] a, double[] b) {
        return Math.abs(a[0] - b[0]) < 1e-7 && Math.abs(a[1] - b[1]) < 1e-7;
    }
}
