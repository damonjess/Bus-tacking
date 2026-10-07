package org.bustimes.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import org.junit.Test;

/** Turning SIRI-VM ExpectedArrivalTime values into the minutes the alert rules use. */
public class ArrivalAlertsTest {

    private static String stamp(long millis) {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.UK).format(new Date(millis));
    }

    @Test
    public void upcomingArrivalBecomesMinutes() {
        assertEquals(10, ArrivalAlerts.etaMinutesFrom(stamp(System.currentTimeMillis() + 10 * 60_000L + 30_000L)));
    }

    @Test
    public void arrivalJustGoneBecomesDueNow() {
        assertEquals(0, ArrivalAlerts.etaMinutesFrom(stamp(System.currentTimeMillis() - 20_000L)));
    }

    @Test
    public void staleArrivalIsReportedAsUnknown() {
        assertEquals(-1, ArrivalAlerts.etaMinutesFrom(stamp(System.currentTimeMillis() - 5 * 60_000L)));
    }

    @Test
    public void missingOrUnreadableArrivalIsUnknown() {
        assertEquals(-1, ArrivalAlerts.etaMinutesFrom(null));
        assertEquals(-1, ArrivalAlerts.etaMinutesFrom(""));
        assertEquals(-1, ArrivalAlerts.etaMinutesFrom("not a timestamp"));
        assertEquals(0, ArrivalAlerts.parseIso("not a timestamp"));
    }

    @Test
    public void fractionalSecondsAndOffsetsAreHandled() {
        long expected = ArrivalAlerts.parseIso("2027-01-05T08:00:00+00:00");
        assertTrue(expected > 0);
        assertEquals(expected, ArrivalAlerts.parseIso("2027-01-05T08:00:00.123+00:00"));
    }
}
