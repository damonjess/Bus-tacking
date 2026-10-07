package org.bustimes.app;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/** Joining OSM member ways into drawable route lines. */
public class RouteShapeJoinerTest {

    private static double[][] way(double... latLonPairs) {
        double[][] points = new double[latLonPairs.length / 2][2];
        for (int i = 0; i < points.length; i++) {
            points[i][0] = latLonPairs[i * 2];
            points[i][1] = latLonPairs[i * 2 + 1];
        }
        return points;
    }

    private static List<double[][]> ways(double[][]... entries) {
        return new ArrayList<>(Arrays.asList(entries));
    }

    @Test
    public void contiguousWaysBecomeOneLine() {
        List<double[][]> joined = RouteShapeJoiner.join(ways(
                way(53.000, -0.650, 53.000, -0.640),
                way(53.000, -0.630, 53.000, -0.640)));
        assertEquals(1, joined.size());
        assertEquals(3, joined.get(0).length);
        assertEquals(-0.650, joined.get(0)[0][1], 1e-9);
        assertEquals(-0.640, joined.get(0)[1][1], 1e-9);
        assertEquals(-0.630, joined.get(0)[2][1], 1e-9);
    }

    @Test
    public void waysAreJoinedAtEitherEnd() {
        List<double[][]> joined = RouteShapeJoiner.join(ways(
                way(53.000, -0.640, 53.000, -0.650),
                way(53.000, -0.620, 53.000, -0.630),
                way(53.000, -0.630, 53.000, -0.640)));
        assertEquals(1, joined.size());
        double[][] line = joined.get(0);
        assertEquals(4, line.length);
        // the line may run in either direction, but it must be one connected chain through
        // -0.650, -0.640, -0.630 and -0.620
        double[] lons = new double[4];
        for (int i = 0; i < 4; i++) {
            lons[i] = line[i][1];
            assertEquals(53.000, line[i][0], 1e-9);
        }
        double[] sorted = lons.clone();
        Arrays.sort(sorted);
        assertArrayEquals(new double[] {-0.650, -0.640, -0.630, -0.620}, sorted, 1e-9);
        for (int i = 0; i < 3; i++) {
            assertEquals(0.01, Math.abs(lons[i + 1] - lons[i]), 1e-9);
        }
    }

    @Test
    public void disjointWaysStaySeparate() {
        List<double[][]> joined = RouteShapeJoiner.join(ways(
                way(53.000, -0.650, 53.000, -0.640),
                way(53.500, -0.650, 53.500, -0.640)));
        assertEquals(2, joined.size());
    }

    @Test
    public void singlePointWaysAreIgnored() {
        List<double[][]> joined = RouteShapeJoiner.join(ways(
                way(53.000, -0.650),
                way(53.000, -0.650, 53.000, -0.640)));
        assertEquals(1, joined.size());
        assertEquals(2, joined.get(0).length);
    }

    @Test
    public void tidyDropsDuplicatesAndStubs() {
        List<double[][]> tidied = RouteShapeJoiner.tidy(Arrays.asList(
                way(53.000, -0.650, 53.000, -0.650, 53.000, -0.640),
                way(53.000, -0.650)));
        assertEquals(1, tidied.size());
        assertEquals(2, tidied.get(0).length);
    }
}
