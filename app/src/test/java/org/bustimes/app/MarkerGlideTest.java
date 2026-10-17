package org.bustimes.app;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class MarkerGlideTest {

    @Test
    public void glideLastsAsLongAsTheVehicleTakes() {
        // 125 m at 30 km/h is 15 s: the marker moves the whole time between two feed fixes.
        assertEquals(15_000L, MarkerGlide.durationMs(125, 30f, 0));
        // 25 m at 30 km/h is 3 s.
        assertEquals(3_000L, MarkerGlide.durationMs(25, 30f, 0));
    }

    @Test
    public void slowVehicleIsNotSlowerThanTheFeedInterval() {
        // 10 m at walking pace would be 12 s, which is still inside the bound.
        assertEquals(6_000L, MarkerGlide.durationMs(10, 6f, 0));
        // Half a kilometre at walking pace is not a bus; the bound keeps it to one feed interval.
        assertEquals(MarkerGlide.MAX_DURATION_MS, MarkerGlide.durationMs(500, 6f, 0));
    }

    @Test
    public void tinyGapsDoNotLookLikeJumps() {
        assertEquals(MarkerGlide.MIN_DURATION_MS, MarkerGlide.durationMs(1, 60f, 0));
    }

    @Test
    public void noSpeedFallsBackToTheFeedInterval() {
        assertEquals(15_000L, MarkerGlide.durationMs(200, Float.NaN, 15_000));
        assertEquals(5_000L, MarkerGlide.durationMs(200, Float.NaN, 5_000));
    }

    @Test
    public void noSpeedAndNoIntervalUsesTheDefault() {
        assertEquals(MarkerGlide.FALLBACK_DURATION_MS, MarkerGlide.durationMs(200, Float.NaN, 0));
    }

    @Test
    public void stationaryOrImplausibleSpeedsAreNotUsed() {
        assertEquals(MarkerGlide.FALLBACK_DURATION_MS, MarkerGlide.durationMs(200, 0f, 0));
        assertEquals(MarkerGlide.FALLBACK_DURATION_MS, MarkerGlide.durationMs(200, 300f, 0));
    }
}
