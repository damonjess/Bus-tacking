package org.bustimes.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The speed rule, driven by the feed's own fix timestamps.
 *
 * Latitude offsets are converted at roughly 111.19 m per thousandth of a degree.
 */
public class VehicleSpeedTest {

    private static final double STOP_LAT = 53.5786;
    private static final double STOP_LON = -0.6548;
    private static final long NOW = 1_800_000_000_000L;

    private static double latSouthOf(int metres) {
        return STOP_LAT - metres / 111194.9;
    }

    @Test
    public void speedUsesTheFeedIntervalNotOurPollInterval() {
        // 250 m between two fixes the feed stamped 30 seconds apart is 30 km/h; dividing by a
        // 15-second poll interval would wrongly say 60 km/h
        float kph = VehicleSpeed.estimate(
                STOP_LAT, STOP_LON, NOW - 30_000L,
                latSouthOf(250), STOP_LON, NOW, Float.NaN, NOW);

        assertEquals(30f, kph, 0.3f);
    }

    @Test
    public void stoppedVehicleReportsZeroRatherThanUnknown() {
        // the same place, but the feed did publish a newer timestamp
        float kph = VehicleSpeed.estimate(
                STOP_LAT, STOP_LON, NOW - 20_000L,
                STOP_LAT, STOP_LON, NOW, Float.NaN, NOW);

        assertEquals(0f, kph, 0.001f);
    }

    @Test
    public void repeatedFeedFixKeepsTheLastKnownSpeed() {
        // half the fleet repeats its previous fix between our polls: that is not evidence of stopping
        float kph = VehicleSpeed.estimate(
                STOP_LAT, STOP_LON, NOW - 15_000L,
                STOP_LAT, STOP_LON, NOW - 15_000L, 32f, NOW);

        assertEquals(32f, kph, 0.001f);
    }

    @Test
    public void missingTimestampClaimsNoSpeed() {
        float kph = VehicleSpeed.estimate(
                STOP_LAT, STOP_LON, 0L, latSouthOf(250), STOP_LON, 0L, 32f, NOW);

        assertTrue(Float.isNaN(kph));
    }

    @Test
    public void staleFixClaimsNoSpeed() {
        // this vehicle's newest fix is minutes old, so nothing can be said about it now
        float kph = VehicleSpeed.estimate(
                STOP_LAT, STOP_LON, NOW - 300_000L,
                latSouthOf(250), STOP_LON, NOW - 260_000L, 32f, NOW);

        assertTrue(Float.isNaN(kph));
    }

    @Test
    public void impossibleJumpIsRejected() {
        // 10 km in one second is a GPS glitch, not a bus
        float kph = VehicleSpeed.estimate(
                STOP_LAT, STOP_LON, NOW - 1_000L,
                latSouthOf(10_000), STOP_LON, NOW, Float.NaN, NOW);

        assertTrue(Float.isNaN(kph));
    }

    @Test
    public void firstFixForAVehicleHasNoSpeedYet() {
        float kph = VehicleSpeed.estimate(
                STOP_LAT, STOP_LON, 0L, latSouthOf(250), STOP_LON, NOW, Float.NaN, NOW);

        assertTrue(Float.isNaN(kph));
    }

    @Test
    public void smallClockSkewAheadOfTheFeedIsTolerated() {
        // the phone's clock a few seconds ahead of the feed must not look like a stale fix
        long now = NOW + 5_000L;
        float kph = VehicleSpeed.estimate(
                STOP_LAT, STOP_LON, NOW - 30_000L,
                latSouthOf(250), STOP_LON, NOW, Float.NaN, now);

        assertEquals(30f, kph, 0.3f);
    }
}
