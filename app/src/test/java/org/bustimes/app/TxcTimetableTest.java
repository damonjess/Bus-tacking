package org.bustimes.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public class TxcTimetableTest {

    private static final String STOP_A = "227000010001";
    private static final String STOP_B = "227000010002";
    private static final String STOP_C = "227000010003";

    /**
     * A document in the shape BODS publishes: stop points, journey pattern sections, services with
     * lines and journey patterns, then the vehicle journeys.
     */
    private static final String DOCUMENT =
            "<TransXChange xmlns=\"http://www.transxchange.org.uk/\">"
            + "<StopPoints>"
            + "<AnnotatedStopPointRef><StopPointRef>" + STOP_A + "</StopPointRef>"
            + "<CommonName>Depot</CommonName></AnnotatedStopPointRef>"
            + "<AnnotatedStopPointRef><StopPointRef>" + STOP_B + "</StopPointRef>"
            + "<CommonName>Cary Lane</CommonName></AnnotatedStopPointRef>"
            + "</StopPoints>"
            + "<JourneyPatternSections>"
            + "<JourneyPatternSection id=\"JPS1\">"
            + "<JourneyPatternTimingLink id=\"JPTL1\"><From><StopPointRef>" + STOP_A
            + "</StopPointRef></From><To><StopPointRef>" + STOP_B + "</StopPointRef></To>"
            + "<RunTime>PT5M</RunTime></JourneyPatternTimingLink>"
            + "<JourneyPatternTimingLink id=\"JPTL2\"><From><StopPointRef>" + STOP_B
            + "</StopPointRef></From><To><StopPointRef>" + STOP_C + "</StopPointRef></To>"
            + "<RunTime>PT10M</RunTime><WaitTime>PT1M</WaitTime></JourneyPatternTimingLink>"
            + "</JourneyPatternSection>"
            + "</JourneyPatternSections>"
            + "<Services><Service>"
            + "<ServiceCode>PB1:1</ServiceCode>"
            + "<Lines><Line id=\"OP:PB1:1:350\"><LineName>350</LineName></Line></Lines>"
            + "<OperatingPeriod><StartDate>2026-01-01</StartDate><EndDate>2026-12-31</EndDate>"
            + "</OperatingPeriod>"
            + "<StandardService><Origin>Scunthorpe</Origin><Destination>Brigg</Destination>"
            + "<JourneyPattern id=\"JP1\">"
            + "<DestinationDisplay>Brigg</DestinationDisplay>"
            + "<JourneyPatternSectionRefs>JPS1</JourneyPatternSectionRefs>"
            + "</JourneyPattern>"
            + "</StandardService>"
            + "</Service>"
            + "<Service>"
            + "<ServiceCode>PB2:2</ServiceCode>"
            + "<Lines><Line id=\"OP:PB2:2:6\"><LineName>6</LineName></Line></Lines>"
            + "<OperatingProfile><RegularDayType><DaysOfWeek><MondayToSaturday/>"
            + "</DaysOfWeek></RegularDayType></OperatingProfile>"
            + "<StandardService>"
            + "<JourneyPattern id=\"JP2\">"
            + "<JourneyPatternSectionRefs>JPS1</JourneyPatternSectionRefs>"
            + "</JourneyPattern>"
            + "</StandardService>"
            + "</Service>"
            + "</Services>"
            + "<VehicleJourneys>"
            + "<VehicleJourney>"
            + "<ServiceRef>PB1:1</ServiceRef><LineRef>OP:PB1:1:350</LineRef>"
            + "<JourneyPatternRef>JP1</JourneyPatternRef>"
            + "<OperatingProfile><RegularDayType><DaysOfWeek><MondayToFriday/></DaysOfWeek>"
            + "</RegularDayType></OperatingProfile>"
            + "<DepartureTime>08:00:00</DepartureTime>"
            + "</VehicleJourney>"
            + "<VehicleJourney>"
            + "<ServiceRef>PB1:1</ServiceRef><LineRef>OP:PB1:1:350</LineRef>"
            + "<JourneyPatternRef>JP1</JourneyPatternRef>"
            + "<OperatingProfile><RegularDayType><DaysOfWeek><Sunday/></DaysOfWeek>"
            + "</RegularDayType></OperatingProfile>"
            + "<DepartureTime>09:00:00</DepartureTime>"
            + "</VehicleJourney>"
            + "<VehicleJourney>"
            + "<ServiceRef>PB1:1</ServiceRef><LineRef>OP:PB1:1:350</LineRef>"
            + "<JourneyPatternRef>JP1</JourneyPatternRef>"
            + "<OperatingProfile><RegularDayType><DaysOfWeek><MondayToFriday/></DaysOfWeek>"
            + "</RegularDayType></OperatingProfile>"
            + "<DepartureTime>10:00:00</DepartureTime>"
            + "<VehicleJourneyTimingLink><JourneyPatternTimingLinkRef>JPTL1"
            + "</JourneyPatternTimingLinkRef><RunTime>PT2M</RunTime></VehicleJourneyTimingLink>"
            + "</VehicleJourney>"
            + "<VehicleJourney>"
            + "<ServiceRef>PB2:2</ServiceRef><LineRef>OP:PB2:2:6</LineRef>"
            + "<JourneyPatternRef>JP2</JourneyPatternRef>"
            + "<DepartureTime>11:00:00</DepartureTime>"
            + "</VehicleJourney>"
            + "</VehicleJourneys>"
            + "</TransXChange>";

    private static List<TxcTimetable.Departure> parseFor(String atcoCode) throws Exception {
        return TxcTimetable.parse(
                new ByteArrayInputStream(DOCUMENT.getBytes(StandardCharsets.UTF_8)), atcoCode);
    }

    @Test
    public void takesTheScheduledTimeFromTheJourneyPattern() throws Exception {
        List<TxcTimetable.Departure> departures = parseFor(STOP_B);
        assertEquals(4, departures.size());
        List<String> clocks = new ArrayList<>();
        for (TxcTimetable.Departure departure : departures) {
            clocks.add(departure.line + " " + departure.clock() + " to " + departure.destination);
        }
        // 08:00 + 5 min, 09:00 + 5 min, 10:00 + the journey's own 2 min, 11:00 + 5 min.
        assertTrue(clocks.contains("350 08:05 to Brigg"));
        assertTrue(clocks.contains("350 09:05 to Brigg"));
        assertTrue(clocks.contains("350 10:02 to Brigg"));
        assertTrue(clocks.contains("6 11:05 to "));
    }

    @Test
    public void journeyTimingOverridesBeatThePattern() throws Exception {
        for (TxcTimetable.Departure departure : parseFor(STOP_B)) {
            if (departure.secondsOfDay == 10 * 3600 + 2 * 60) {
                return;
            }
        }
        throw new AssertionError("the overriding journey was not timed with its own runtime");
    }

    private static int daysFor(List<TxcTimetable.Departure> departures, int secondsOfDay) {
        for (TxcTimetable.Departure departure : departures) {
            if (departure.secondsOfDay == secondsOfDay) {
                return departure.daysMask;
            }
        }
        throw new AssertionError("no departure at " + TxcTimetable.formatClock(secondsOfDay));
    }

    @Test
    public void dayRulesComeFromTheVehicleJourneyAndFallBackToTheService() throws Exception {
        List<TxcTimetable.Departure> departures = parseFor(STOP_B);
        int weekdays = TxcTimetable.MONDAY | TxcTimetable.TUESDAY | TxcTimetable.WEDNESDAY
                | TxcTimetable.THURSDAY | TxcTimetable.FRIDAY;
        assertEquals(weekdays, daysFor(departures, 8 * 3600 + 5 * 60));
        assertEquals(weekdays, daysFor(departures, 10 * 3600 + 2 * 60));
        assertEquals(TxcTimetable.SUNDAY, daysFor(departures, 9 * 3600 + 5 * 60));
        // no journey-level day rule: the service's own operating profile applies
        assertEquals(weekdays | TxcTimetable.SATURDAY, daysFor(departures, 11 * 3600 + 5 * 60));
    }

    @Test
    public void theLastStopOfAPatternIsAnArrivalNotADeparture() throws Exception {
        assertTrue(parseFor(STOP_C).isEmpty());
    }

    @Test
    public void aStopNoPatternCallsAtHasNoTimes() throws Exception {
        assertTrue(parseFor("227000019999").isEmpty());
    }

    @Test
    public void upcomingKeepsTodaysRemainingTimesInOrder() throws Exception {
        List<TxcTimetable.Departure> all = parseFor(STOP_B);
        int monday = TxcTimetable.MONDAY;

        List<TxcTimetable.Departure> afterEight =
                TxcTimetable.upcoming(all, monday, "2026-10-19", 8 * 3600, 10);
        assertEquals(3, afterEight.size());
        assertEquals(8 * 3600 + 5 * 60, afterEight.get(0).secondsOfDay);
        assertEquals(10 * 3600 + 2 * 60, afterEight.get(1).secondsOfDay);
        assertEquals(11 * 3600 + 5 * 60, afterEight.get(2).secondsOfDay);

        assertEquals(1, TxcTimetable.upcoming(all, monday, "2026-10-19", 0, 1).size());

        // Sundays: only the Sunday journey, and the weekday ones stay out.
        List<TxcTimetable.Departure> sunday =
                TxcTimetable.upcoming(all, TxcTimetable.SUNDAY, "2026-10-18", 0, 10);
        assertEquals(1, sunday.size());
        assertEquals(9 * 3600 + 5 * 60, sunday.get(0).secondsOfDay);
    }

    @Test
    public void timesOutsideTheOperatingPeriodAreLeftOut() throws Exception {
        List<TxcTimetable.Departure> all = parseFor(STOP_B);
        List<TxcTimetable.Departure> nextYear =
                TxcTimetable.upcoming(all, TxcTimetable.MONDAY, "2027-02-01", 0, 10);
        // the 350's service ends on 2026-12-31; the one with no operating period keeps running
        assertEquals(1, nextYear.size());
        assertEquals("6", nextYear.get(0).line);
        assertFalse(TxcTimetable.upcoming(all, TxcTimetable.MONDAY, "2026-10-19", 0, 10).isEmpty());
    }

    @Test
    public void clockAndDurationFormatsAreUnderstood() {
        assertEquals(8 * 3600, TxcTimetable.parseClock("08:00:00"));
        // a time past midnight wraps into the day, as the feed's own board does
        assertEquals(600, TxcTimetable.parseClock("24:10:00"));
        assertEquals(-1, TxcTimetable.parseClock("not a time"));
        assertEquals("13:52", TxcTimetable.formatClock(13 * 3600 + 52 * 60));
        assertEquals("00:05", TxcTimetable.formatClock(5 * 60));
        assertEquals(300, TxcTimetable.parseDuration("PT5M"));
        assertEquals(3720, TxcTimetable.parseDuration("PT1H2M"));
        assertEquals(90000, TxcTimetable.parseDuration("P1DT1H"));
        assertEquals(0, TxcTimetable.parseDuration("PT0S"));
        assertEquals(0, TxcTimetable.parseDuration(null));
        assertEquals(TxcTimetable.SATURDAY | TxcTimetable.SUNDAY,
                TxcTimetable.maskForDayName("Weekend"));
        assertEquals(0, TxcTimetable.maskForDayName("HolidaysOnly"));
        assertEquals(TxcTimetable.MONDAY,
                TxcTimetable.dayMaskForCalendarDay(java.util.Calendar.MONDAY));
        assertEquals(TxcTimetable.SUNDAY,
                TxcTimetable.dayMaskForCalendarDay(java.util.Calendar.SUNDAY));
    }
}
