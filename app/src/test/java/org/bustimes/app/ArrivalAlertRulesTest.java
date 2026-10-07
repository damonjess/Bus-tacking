package org.bustimes.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Rules that decide when an armed alert raises a notification. */
public class ArrivalAlertRulesTest {

    @Test
    public void liveEtaWithinThresholdNotifies() {
        assertTrue(ArrivalAlertRules.shouldNotify(4, 5_000, 800, 5));
        assertTrue(ArrivalAlertRules.shouldNotify(5, 9_000, 800, 5));
    }

    @Test
    public void liveEtaBeyondThresholdStillNotifiesOnceInsideRadius() {
        assertTrue(ArrivalAlertRules.shouldNotify(12, 120, 800, 5));
    }

    @Test
    public void farAwayVehicleWithSlowEtaDoesNotNotify() {
        assertFalse(ArrivalAlertRules.shouldNotify(12, 5_000, 800, 5));
    }

    @Test
    public void missingEtaFallsBackToRadius() {
        assertTrue(ArrivalAlertRules.shouldNotify(-1, 799, 800, 5));
        assertFalse(ArrivalAlertRules.shouldNotify(-1, 801, 800, 5));
    }

    @Test
    public void alreadyPassedEtaDoesNotNotify() {
        // -1 is how ArrivalAlerts reports an arrival time that is stale or absent
        assertFalse(ArrivalAlertRules.shouldNotify(-1, 4_000, 800, 5));
    }

    @Test
    public void etaTriggerFlagMatchesTheReason() {
        assertTrue(ArrivalAlertRules.etaTriggered(3, 5));
        assertFalse(ArrivalAlertRules.etaTriggered(-1, 5));
        assertFalse(ArrivalAlertRules.etaTriggered(9, 5));
    }

    @Test
    public void minutePresetsMapToTheirRadius() {
        assertEquals(400, ArrivalAlertRules.radiusForMinutes(2));
        assertEquals(800, ArrivalAlertRules.radiusForMinutes(5));
        assertEquals(1600, ArrivalAlertRules.radiusForMinutes(10));
        assertEquals(2400, ArrivalAlertRules.radiusForMinutes(15));
    }

    @Test
    public void unknownMinuteValueFallsBackToTheNearestPreset() {
        assertEquals(5, ArrivalAlertRules.normaliseMinutes(6));
        assertEquals(10, ArrivalAlertRules.normaliseMinutes(9));
        assertEquals(2, ArrivalAlertRules.normaliseMinutes(1));
        assertEquals(800, ArrivalAlertRules.radiusForMinutes(6));
    }

    @Test
    public void distanceIsGreatCircleMetres() {
        assertEquals(0, ArrivalAlertRules.distanceMeters(53.5786, -0.6548, 53.5786, -0.6548), 0.001);
        // one hundredth of a degree of latitude is close to 1.11 km
        assertEquals(1111.9, ArrivalAlertRules.distanceMeters(53.0, -0.65, 53.01, -0.65), 2.0);
    }

    @Test
    public void alertIdIsStableAndDistinguishesPlaces() {
        String a = ArrivalAlertRules.alertId("350", 53.5786, -0.6548);
        String b = ArrivalAlertRules.alertId("350", 53.5786, -0.6548);
        String c = ArrivalAlertRules.alertId("350", 53.6000, -0.6548);
        String d = ArrivalAlertRules.alertId("351", 53.5786, -0.6548);
        assertEquals(a, b);
        assertFalse(a.equals(c));
        assertFalse(a.equals(d));
    }
}
