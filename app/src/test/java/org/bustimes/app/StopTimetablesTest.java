package org.bustimes.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

import org.junit.Test;

public class StopTimetablesTest {

    /** Monday 19 October 2026, 08:00 local time. */
    private static long mondayMorning() {
        Calendar calendar = Calendar.getInstance(TimeZone.getDefault());
        calendar.clear();
        calendar.set(2026, Calendar.OCTOBER, 19, 8, 0, 0);
        return calendar.getTimeInMillis();
    }

    @Test
    public void theAreaIsTheFirstThreeDigitsOfTheStopReference() {
        assertEquals("227", StopTimetables.areaCode("227000010340"));
        assertEquals("", StopTimetables.areaCode(""));
        assertEquals("", StopTimetables.areaCode(null));
        assertEquals("", StopTimetables.areaCode("N227000010340"));
    }

    @Test
    public void todayKeepsOnlyTimesThatRunTodayAndAreStillToCome() {
        List<TxcTimetable.Departure> all = new ArrayList<>();
        all.add(new TxcTimetable.Departure("350", "Brigg", 9 * 3600, TxcTimetable.ALL_DAYS, "", ""));
        all.add(new TxcTimetable.Departure("350", "Brigg", 7 * 3600, TxcTimetable.ALL_DAYS, "", ""));
        all.add(new TxcTimetable.Departure("6", "Scunthorpe", 9 * 3600, TxcTimetable.SUNDAY, "", ""));

        List<TxcTimetable.Departure> today = StopTimetables.today(all, mondayMorning());
        assertEquals(1, today.size());
        assertEquals("350", today.get(0).line);
        assertEquals(9 * 3600, today.get(0).secondsOfDay);
    }

    @Test
    public void atMostTheRowLimitComesBack() {
        List<TxcTimetable.Departure> all = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            all.add(new TxcTimetable.Departure("350", "Brigg", (9 + i) * 3600,
                    TxcTimetable.ALL_DAYS, "", ""));
        }
        assertEquals(StopTimetables.MAX_ROWS, StopTimetables.today(all, mondayMorning()).size());
    }

    @Test
    public void aClosedOperatingPeriodLeavesNothingForToday() {
        List<TxcTimetable.Departure> all = new ArrayList<>();
        all.add(new TxcTimetable.Departure("350", "Brigg", 9 * 3600, TxcTimetable.ALL_DAYS,
                "2019-01-01", "2019-12-31"));
        assertTrue(StopTimetables.today(all, mondayMorning()).isEmpty());
    }
}
