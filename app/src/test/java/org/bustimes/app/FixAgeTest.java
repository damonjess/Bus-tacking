package org.bustimes.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FixAgeTest {

    private static final long NOW = FixAge.parse("2026-10-07T13:30:34+00:00");

    @Test
    public void recentReportIsLive() {
        assertTrue(FixAge.isLive("2026-10-07T13:30:04+00:00", NOW));
        assertTrue(FixAge.isLive("2026-10-07T13:21:00Z", NOW));
    }

    @Test
    public void oldReportIsNotLive() {
        // the real record seen in the feed: reported the previous evening
        assertFalse(FixAge.isLive("2026-10-06T17:29:22+00:00", NOW));
        assertFalse(FixAge.isLive("2026-10-07T13:00:00+00:00", NOW));
    }

    @Test
    public void unreadableTimeIsGivenTheBenefitOfTheDoubt() {
        assertTrue(FixAge.isLive("", NOW));
        assertTrue(FixAge.isLive(null, NOW));
        assertTrue(FixAge.isLive("not a time", NOW));
    }

    @Test
    public void fractionalSecondsAreAccepted() {
        assertEquals(30, FixAge.ageSeconds("2026-10-07T13:30:04.123+00:00", NOW, -1));
    }

    @Test
    public void ageFallsBackWhenTimeMissing() {
        assertEquals(42, FixAge.ageSeconds("", NOW, 42));
    }

    @Test
    public void futureReportsAreNotNegative() {
        assertEquals(0, FixAge.ageSeconds("2026-10-07T13:35:00+00:00", NOW, 5));
    }

    @Test
    public void describeUsesSensibleUnits() {
        assertEquals("12s ago", FixAge.describe(12));
        assertEquals("5m ago", FixAge.describe(300));
        assertEquals("3h ago", FixAge.describe(3 * 3600 + 20));
        assertEquals("6d ago", FixAge.describe(6 * 86400 + 5));
    }
}
