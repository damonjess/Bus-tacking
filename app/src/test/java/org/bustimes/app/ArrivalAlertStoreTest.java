package org.bustimes.app;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/** Arming one alert per route, and removing one wherever it was armed from. */
public class ArrivalAlertStoreTest {

    private static ArrivalAlert alert(String route, double lat, double lon, int minutes) {
        return new ArrivalAlert(route, "a place", lat, lon, minutes, 1_000L);
    }

    @Test
    public void rearmingTheSameRouteReplacesTheOldAlert() {
        List<ArrivalAlert> existing = new ArrayList<>(Arrays.asList(
                alert("350", 53.5786, -0.6548, 5),
                alert("12", 53.5800, -0.6500, 10)));

        List<ArrivalAlert> kept = ArrivalAlertStore.replacing(existing, "350|53579|-655", "350");

        assertEquals(1, kept.size());
        assertEquals("12", kept.get(0).route);
    }

    @Test
    public void armingTheSameRouteAtANewPlaceStillReplacesIt() {
        // the user moved, so the new alert has a different id but the same route
        List<ArrivalAlert> existing = new ArrayList<>(Arrays.asList(
                alert("350", 53.5786, -0.6548, 5)));

        List<ArrivalAlert> kept = ArrivalAlertStore.replacing(existing, "350|53600|-655", "350");

        assertEquals(0, kept.size());
    }

    @Test
    public void routeMatchingIsCaseInsensitive() {
        List<ArrivalAlert> existing = new ArrayList<>(Arrays.asList(alert("X1", 53.0, -0.6, 5)));
        assertEquals(0, ArrivalAlertStore.replacing(existing, null, "x1").size());
    }

    @Test
    public void removingByRouteLeavesOtherRoutesAlone() {
        List<ArrivalAlert> existing = new ArrayList<>(Arrays.asList(
                alert("350", 53.0, -0.6, 5),
                alert("351", 53.0, -0.6, 5)));

        List<ArrivalAlert> kept = ArrivalAlertStore.replacing(existing, null, "350");

        assertEquals(1, kept.size());
        assertEquals("351", kept.get(0).route);
    }

    @Test
    public void alertSummaryNamesTheRouteMinuteThresholdAndRadius() {
        ArrivalAlert armed = alert("350", 53.5786, -0.6548, 5);
        assertEquals(800, armed.radiusMeters);
        assertEquals("350 \u00B7 5 min / 800 m of a place", armed.summary());
    }

    @Test
    public void triggerTextSaysWhatActuallyFired() {
        ArrivalAlert armed = alert("350", 53.5786, -0.6548, 5);
        assertEquals("About 4 min away from a place", armed.triggerText(true, 4, 1200));
        assertEquals("Within 300 m of a place", armed.triggerText(false, -1, 300));
    }
}
