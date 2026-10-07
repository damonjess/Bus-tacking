package org.bustimes.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;

import org.junit.Test;
import org.osmdroid.util.GeoPoint;

/** Parsing Overpass route geometry into drawable lines. */
public class RouteShapesTest {

    /** A trimmed payload captured from a live Overpass response for route 350. */
    private static String fixture() throws Exception {
        try (InputStream in = RouteShapesTest.class.getResourceAsStream("/overpass_route_350.json")) {
            assertNotNull("fixture must be on the test classpath", in);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toString("UTF-8");
        }
    }

    @Test
    public void realOverpassPayloadBecomesOneContiguousRouteLine() throws Exception {
        List<List<GeoPoint>> lines = RouteShapes.parse(fixture());

        assertEquals("the four member ways of this route connect end to end", 1, lines.size());
        List<GeoPoint> line = lines.get(0);
        assertEquals(44, line.size());

        for (GeoPoint point : line) {
            assertTrue("latitude inside the captured route", point.getLatitude() > 53.7445
                    && point.getLatitude() < 53.7455);
            assertTrue("longitude inside the captured route", point.getLongitude() > -0.3518
                    && point.getLongitude() < -0.3453);
        }

        // the joined line runs between these two mapped ends, in either direction
        GeoPoint first = line.get(0);
        GeoPoint last = line.get(line.size() - 1);
        boolean forward = near(first, 53.7453155, -0.3516986) && near(last, 53.7454636, -0.3454106);
        boolean backward = near(first, 53.7454636, -0.3454106) && near(last, 53.7453155, -0.3516986);
        assertTrue("line spans the captured geometry", forward || backward);
    }

    @Test
    public void stopMembersAreNotDrawnAsRoad() throws Exception {
        String json = "{\"elements\":[{\"type\":\"relation\",\"members\":["
                + "{\"type\":\"node\",\"role\":\"stop_entry_only\",\"lat\":53.0,\"lon\":-0.6},"
                + "{\"type\":\"way\",\"role\":\"\",\"geometry\":[{\"lat\":53.0,\"lon\":-0.60},{\"lat\":53.0,\"lon\":-0.61}]}"
                + "]}]}";
        List<List<GeoPoint>> lines = RouteShapes.parse(json);
        assertEquals(1, lines.size());
        assertEquals(2, lines.get(0).size());
    }

    @Test
    public void bareWayElementsAreAlsoAccepted() throws Exception {
        String json = "{\"elements\":[{\"type\":\"way\",\"geometry\":["
                + "{\"lat\":53.0,\"lon\":-0.60},{\"lat\":53.0,\"lon\":-0.61}]}]}";
        List<List<GeoPoint>> lines = RouteShapes.parse(json);
        assertEquals(1, lines.size());
        assertEquals(2, lines.get(0).size());
    }

    @Test
    public void emptyResponseProducesNoLine() throws Exception {
        assertTrue(RouteShapes.parse("{\"elements\":[]}").isEmpty());
    }

    @Test
    public void routeLabelCannotBeInjectedIntoTheQuery() {
        String hostile = "350\" ][out:json];node(1);out;//";
        String safe = RouteShapes.sanitiseRef(hostile);
        assertFalse(safe.contains("\""));
        assertFalse(safe.contains("["));
        assertFalse(safe.contains("]"));
        assertFalse(safe.contains(";"));
        assertFalse(safe.contains("\\"));
        assertEquals("350 outjsonnode1out//", safe);
    }

    @Test
    public void regexMetacharactersInARouteLabelAreEscaped() {
        assertEquals("X1\\+", RouteShapes.regexEscape(RouteShapes.sanitiseRef("X1+")));
        assertTrue(RouteShapes.buildQuery("X1+", 53.0, -0.6).contains("[ref~\"^X1\\+$\",i]"));
        // anchors are added by buildQuery, so a plain label is left alone here
        assertEquals("350", RouteShapes.regexEscape(RouteShapes.sanitiseRef("350")));
    }

    @Test
    public void queryTargetsBusRouteRelationsAndAsksForGeometry() {
        String query = RouteShapes.buildQuery("350", 53.5786, -0.6548);
        assertTrue(query.contains("[route=bus]"));
        assertTrue(query.contains("[type=route]"));
        assertTrue(query.contains("[ref~\"^350$\",i]"));
        assertTrue(query.contains("around:12000"));
        assertTrue(query.contains("out geom"));
        assertTrue(query.contains("53.5786"));
    }

    private static boolean near(GeoPoint point, double lat, double lon) {
        return Math.abs(point.getLatitude() - lat) < 1e-7 && Math.abs(point.getLongitude() - lon) < 1e-7;
    }
}
